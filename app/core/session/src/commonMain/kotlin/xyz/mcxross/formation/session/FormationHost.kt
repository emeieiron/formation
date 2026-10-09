package xyz.mcxross.formation.session

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.mcxross.formation.crypto.Base58
import xyz.mcxross.formation.link.LinkChannel
import xyz.mcxross.formation.model.PlayerId

class HostTiming(
  val briefingMs: Long = 30_000,
  val countdownMs: Long = 3_600,
  val tickMs: Long = 33,
  val latencyPublishMs: Long = 3_000,
  val disconnectGraceMs: Long = 10_000,
)

// All state lives on one coroutine that handles events in order, so nothing here needs locks.
class FormationHost(
  formation: FormationInfo,
  private val rules: ChallengeRules<*, *>,
  private val scope: CoroutineScope,
  private val clock: Clock = MonotonicClock,
  private val random: Random = Random.Default,
  private val timing: HostTiming = HostTiming(),
  private val json: Json = FormationJson,
  recovery: SessionSnapshot? = null,
  private val checkpoint: (SessionSnapshot) -> Unit = {},
  private val observe: (DiagnosticEvent) -> Unit = {},
  private val host: HostCredentials? = null,
) {
  private val info = formation
  private val screenRequirement = rules.screenRequirement(info.opportunity.players)
  private val seats =
    recovery
      ?.players
      ?.map { p ->
        Seat(p.id, p.device ?: p.claimKey, p.name, p.light, p.seeker, p.claimKey, p.wallet, null)
      }
      ?.toMutableList() ?: mutableListOf()
  private var stage: Stage =
    recovery?.let { saved ->
      require(saved.formation == info) { "The saved session belongs to another Formation" }
      validatedCompletion(saved).let {
        if (it.unlock is Unlock.Unlocking) it.copy(unlock = Unlock.Waiting) else it
      }
    } ?: Stage.Lobby
  private var round = recovery?.round ?: 0
  private var nextSeat =
    (seats.maxOfOrNull { it.id.value.removePrefix("p").toIntOrNull() ?: 0 } ?: 0) + 1
  private var game: HostedGame<*, *>? = null
  private var frameSeq = 0L
  private var lastState: String? = null
  private val lastFrames = mutableMapOf<PlayerId, String>()
  private var latencyDirty = false
  private var latencyPublishedAt = 0L
  private var reportedStage: DiagnosticCode? = null
  private var reportedSeals = -1
  private var sealingAt = 0L

  private val _snapshot =
    MutableStateFlow(SessionSnapshot(info, seats.map { it.toPlayer() }, stage, round))
  val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

  private val events = Channel<Event>(Channel.UNLIMITED)
  private val loop: Job = scope.launch {
    launch {
      while (true) {
        delay(timing.tickMs)
        events.send(Event.Tick)
      }
    }
    for (event in events) handle(event)
  }

  // Only the Seeker's own in-process link ([local]) can take the Seeker's seat.
  suspend fun serve(channel: LinkChannel, local: Boolean = false) {
    val conn = HostConnection(channel, local, admissionChallenge(info, rules), host?.proof, json)
    conn.run(
      received = { events.send(Event.Received(conn, it)) },
      closed = { events.trySend(Event.Closed(conn)) },
      timedOut = { command { if (seatOf(conn) == null) reject(conn, Rejection.IDENTITY) } },
    )
  }

  // Safe to call from any thread.
  fun beacon(): String {
    val s = _snapshot.value
    val opp = s.formation.opportunity
    val helpers = opp.helpers
    return json.encodeToString(
      Beacon.serializer(),
      Beacon(
        protocol = PROTOCOL_VERSION,
        session = s.formation.session,
        code = s.formation.code,
        host = s.formation.host,
        challenge = opp.challenge,
        reward = opp.reward,
        players = opp.players,
        joined = s.players.size,
        open = s.stage == Stage.Lobby && !s.full,
        helperShare =
          if (opp.hasReward) opp.split(helpers).helper else xyz.mcxross.formation.model.Skr.ZERO,
        hasReward = opp.hasReward,
        tier = opp.tier.label,
      ),
    )
  }

  fun begin() = command {
    if (
      stage == Stage.Lobby &&
        seats.size >= info.opportunity.players &&
        seats.all { it.conn != null }
    )
      toBriefing()
  }

  fun observe(round: Int, observation: GameObservation) = command {
    val playing = stage as? Stage.Playing ?: return@command
    val running = game ?: return@command
    if (this.round != round || clock.now() < playing.goAt) return@command
    running.observe(observation, clock.now())
    settle(running)
  }

  fun startNow() = command { if (stage is Stage.Briefing) startRound() }

  fun runItBack() = command { if (stage is Stage.Lost || stage is Stage.Finished) toBriefing() }

  fun backToLobby() = command {
    if (stage is Stage.Lost || stage is Stage.Finished || stage is Stage.Briefing) {
      seats.removeAll { it.conn == null && !it.seeker }
      seats.forEach { it.ready = false }
      stage = Stage.Lobby
      publish()
    }
  }

  fun remove(player: PlayerId) = command {
    if (stage != Stage.Lobby) return@command
    val seat = seats.firstOrNull { it.id == player && !it.seeker } ?: return@command
    seats.remove(seat)
    seat.conn?.let { reject(it, Rejection.CLOSED) }
    publish()
  }

  fun unlocking() = updateUnlock { Unlock.Unlocking }

  fun unlocked(
    receipt: String,
    explorerUrl: String?,
    paid: List<PlayerId> = emptyList(),
    settled: Boolean = true,
  ) = updateUnlock {
    Unlock.Unlocked(receipt, clock.now(), explorerUrl, paid, settled)
  }

  fun unlockFailed(message: String) = updateUnlock { Unlock.Failed(message) }

  fun close(reason: String = "The Seeker ended this Formation.") = command {
    stage = Stage.Closed(reason)
    game = null
    publish()
    seats.forEach { it.conn?.outbox?.close() }
    scope.launch {
      delay(1_000)
      loop.cancel()
    }
  }

  private sealed interface Event {
    class Received(val conn: HostConnection, val message: ToHost) : Event

    class Closed(val conn: HostConnection) : Event

    class Command(val run: () -> Unit) : Event

    data object Tick : Event
  }

  private class Seat(
    val id: PlayerId,
    val device: String,
    var name: String,
    var light: Int,
    val seeker: Boolean,
    val claimKey: String,
    var wallet: String?,
    var conn: HostConnection?,
    var ready: Boolean = false,
    var latencyMs: Int? = null,
    var disconnectedAt: Long? = null,
    var clockReady: Boolean = false,
    var syncReportedAt: Long = 0,
    var capabilities: Set<String> = emptySet(),
    var availableInputs: Set<String> = emptySet(),
    var sensorReady: Boolean = true,
    var screen: ScreenProfile? = null,
  ) {
    fun toPlayer() =
      Player(
        id,
        name,
        light,
        seeker,
        claimKey,
        wallet,
        conn != null,
        ready,
        latencyMs,
        device,
        clockReady,
        availableInputs,
        sensorReady,
      )
  }

  private fun command(run: () -> Unit) {
    events.trySend(Event.Command(run))
  }

  private fun handle(event: Event) {
    when (event) {
      is Event.Received -> receive(event.conn, event.message)
      is Event.Closed -> disconnected(event.conn)
      is Event.Command -> event.run()
      Event.Tick -> tick()
    }
  }

  private fun seatOf(conn: HostConnection) = seats.firstOrNull { it.conn === conn }

  private fun receive(conn: HostConnection, message: ToHost) {
    if (message is ToHost.Hello) return hello(conn, message)
    if (message is ToHost.Ping) {
      conn.send(encode(ToPlayer.Pong(message.sent, clock.now())))
      val seat = seatOf(conn) ?: return
      val synced =
        message.synced && message.rtt != null && message.rtt in 0..ClockSync.MAX_RTT_MS.toInt()
      if (seat.clockReady != synced) latencyDirty = true
      seat.clockReady = synced
      if (synced) seat.syncReportedAt = clock.now()
      if (stage is Stage.Briefing && seats.all { it.ready } && readyToPlay()) startRound()
      if (message.rtt != null && message.rtt != seat.latencyMs) {
        seat.latencyMs = message.rtt
        latencyDirty = true
      }
      return
    }
    val seat = seatOf(conn) ?: return
    when (message) {
      is ToHost.Ready -> ready(seat, message.ready)
      is ToHost.Sensors -> sensors(seat, message)
      is ToHost.Play -> play(seat, message)
      is ToHost.SealIt -> sealed(seat, message)
      is ToHost.Wallet -> wallet(seat, message.address)
      is ToHost.Profile -> profile(seat, message)
      ToHost.Leave -> left(seat)
      is ToHost.Hello,
      is ToHost.Ping -> Unit
    }
  }

  private fun hello(conn: HostConnection, hello: ToHost.Hello) {
    if (stage is Stage.Closed) return reject(conn, Rejection.CLOSED)
    admissionRejection(conn.challenge, hello)?.let {
      return reject(conn, it)
    }
    if (seatOf(conn) != null) return reject(conn, Rejection.DUPLICATE)
    conn.presence = hello.presence

    val returning = seats.firstOrNull { it.device == hello.device || it.claimKey == hello.claimKey }
    if (returning != null) {
      if (returning.seeker != conn.local || returning.claimKey != hello.claimKey)
        return reject(conn, Rejection.DUPLICATE)
      if (returning.conn != null && returning.conn !== conn)
        return reject(conn, Rejection.DUPLICATE)
      returning.capabilities = hello.capabilities
      returning.availableInputs = emptySet()
      returning.screen = null
      returning.sensorReady = rules.requiredCapabilities(info.opportunity.players).isEmpty()
      returning.conn = conn
      val disconnectedAt = returning.disconnectedAt
      val interruptedPlay = stage is Stage.Playing && disconnectedAt != null
      val canResume =
        rules.resumesAfterReconnect &&
          disconnectedAt != null &&
          clock.now() - disconnectedAt < (rules.reconnectGraceMs ?: timing.disconnectGraceMs)
      returning.disconnectedAt = null
      returning.clockReady = false
      if (interruptedPlay && !canResume)
        finish(
          RoundResult("The connection was interrupted. Try again.", null, emptyList(), clock.now()),
          won = false,
        )
      if (stage !is Stage.Won) returning.wallet = validWallet(hello.wallet)
      welcome(conn, returning)
      return
    }

    if (stage != Stage.Lobby) return reject(conn, Rejection.STARTED)
    if (conn.local && seats.any { it.seeker }) return reject(conn, Rejection.DUPLICATE)
    if (seats.any { it.claimKey == hello.claimKey }) return reject(conn, Rejection.DUPLICATE)
    // One seat always stays free for the Seeker itself.
    val reserved = if (!conn.local && seats.none { it.seeker }) 1 else 0
    if (seats.size + reserved >= info.opportunity.players) return reject(conn, Rejection.FULL)

    val seat =
      Seat(
        id = PlayerId("p${nextSeat++}"),
        device = hello.device,
        name = hello.name.trim().take(MAX_NAME).ifEmpty { "Player" },
        light = hello.light.mod(LIGHTS),
        seeker = conn.local,
        claimKey = hello.claimKey,
        wallet = validWallet(hello.wallet),
        conn = conn,
        capabilities = hello.capabilities,
        sensorReady = rules.requiredCapabilities(info.opportunity.players).isEmpty(),
      )
    if (seat.seeker) seats.add(0, seat) else seats.add(seat)
    welcome(conn, seat)
  }

  // Only while gathering: once play starts every phone is showing these names.
  private fun profile(seat: Seat, profile: ToHost.Profile) {
    if (stage != Stage.Lobby) return
    seat.name = profile.name.trim().take(MAX_NAME).ifEmpty { seat.name }
    seat.light = profile.light.mod(LIGHTS)
    publish()
  }

  // Frozen once the seal is built: it commits to each helper's wallet.
  private fun wallet(seat: Seat, address: String?) {
    if (stage is Stage.Won || stage is Stage.Closed) return
    seat.wallet = validWallet(address)
    publish()
  }

  private fun validWallet(address: String?): String? = address?.takeIf {
    runCatching { Base58.decode(it).size }.getOrNull() == 32
  }

  private fun welcome(conn: HostConnection, seat: Seat) {
    conn.send(signed(ToPlayer.Welcome(seat.id, conn.presence)))
    publish()
    lastFrames[seat.id]?.takeIf { stage is Stage.Playing }?.let(conn::send)
  }

  private fun reject(conn: HostConnection, reason: Rejection) {
    observe(DiagnosticEvent(DiagnosticCode.CONNECT_REJECTED, reason.ordinal.toLong()))
    conn.send(encode(ToPlayer.Rejected(reason)))
    conn.outbox.close()
  }

  private fun disconnected(conn: HostConnection) {
    val seat = seatOf(conn) ?: return
    seat.conn = null
    seat.disconnectedAt = clock.now()
    seat.clockReady = false
    if (stage == Stage.Lobby && !seat.seeker) seats.remove(seat)
    publish()
  }

  private fun left(seat: Seat) {
    val conn = seat.conn
    seat.conn = null
    seat.disconnectedAt = clock.now()
    seat.clockReady = false
    if (stage == Stage.Lobby && !seat.seeker) seats.remove(seat)
    conn?.outbox?.close()
    publish()
  }

  private fun sensors(seat: Seat, message: ToHost.Sensors) {
    if (
      message.round != round || stage is Stage.Won || stage is Stage.Lost || stage is Stage.Closed
    )
      return
    // A phone that can measure its screen still has to report a measurement this game accepts.
    val screen = message.screen?.takeIf { screenRequirement?.accepts(it) == true }
    val available =
      message.available.intersect(seat.capabilities).filterTo(mutableSetOf()) {
        it != ScreenRequirement.CAPABILITY || screen != null
      }
    val sensorReady = available.containsAll(rules.requiredCapabilities(info.opportunity.players))
    if (stage !is Stage.Playing) seat.screen = screen
    if (seat.availableInputs == available && seat.sensorReady == sensorReady) return
    seat.availableInputs = available
    seat.sensorReady = sensorReady
    if (!sensorReady) seat.ready = false
    if (
      stage is Stage.Playing &&
        game?.activeCapabilities(seat.id, seats.size)?.all { it in available } == false
    ) {
      finish(
        RoundResult(
          "A phone's input stopped. Restore it and try again.",
          null,
          emptyList(),
          clock.now(),
        ),
        won = false,
      )
    } else publish()
  }

  private fun ready(seat: Seat, ready: Boolean) {
    if (stage !is Stage.Briefing) return
    seat.ready = ready && seat.sensorReady
    publish()
    if (seats.all { it.ready && it.conn != null }) startRound()
  }

  private fun toBriefing() {
    seats.forEach {
      it.ready = false
      it.availableInputs = emptySet()
      it.screen = null
      it.sensorReady = rules.requiredCapabilities(info.opportunity.players).isEmpty()
    }
    stage = Stage.Briefing(clock.now() + timing.briefingMs)
    publish()
  }

  private fun readyToPlay() =
    seats.size == info.opportunity.players &&
      seats.all {
        it.conn != null &&
          it.sensorReady &&
          it.clockReady &&
          clock.now() - it.syncReportedAt <= ClockSync.MAX_AGE_MS
      }

  private fun startRound() {
    if (!readyToPlay()) return
    val goAt = clock.now() + timing.countdownMs
    val roster = seats.map { it.id }
    round += 1
    val setup =
      ChallengeSetup(
        players = roster,
        seeker = seats.first { it.seeker }.id,
        difficulty = info.opportunity.difficulty,
        seed = random.nextLong(),
        startAt = goAt,
        capabilities = seats.associate { it.id to it.availableInputs.toSet() },
        screens =
          if (screenRequirement == null) emptyMap()
          else seats.mapNotNull { seat -> seat.screen?.let { seat.id to it } }.toMap(),
      )
    game = HostedGame.start(rules, setup, json)
    frameSeq = 0
    lastState = null
    lastFrames.clear()
    stage = Stage.Playing(goAt, roster)
    publish()
    pushFrame()
  }

  private fun play(seat: Seat, message: ToHost.Play) {
    val playing = stage as? Stage.Playing ?: return
    val running = game ?: return
    if (message.round != round || seats.any { it.conn == null }) return
    if (!seat.availableInputs.containsAll(running.activeCapabilities(seat.id, seats.size))) return
    val now = clock.now()
    if (now < playing.goAt - EARLY_GRACE_MS) return
    running.input(seat.id, message.input, now)
    settle(running)
  }

  private fun tick() {
    val now = clock.now()
    seats
      .filter { it.clockReady && now - it.syncReportedAt > ClockSync.MAX_AGE_MS }
      .forEach {
        it.clockReady = false
        observe(DiagnosticEvent(DiagnosticCode.CLOCK_STALE))
        latencyDirty = true
      }
    when (val s = stage) {
      is Stage.Briefing -> if (now >= s.until && readyToPlay()) startRound()
      is Stage.Playing -> {
        val stopped = seats.any { seat ->
          game?.activeCapabilities(seat.id, seats.size)?.all { it in seat.availableInputs } == false
        }
        if (stopped) {
          finish(
            RoundResult(
              "A phone's input stopped. Restore it and try again.",
              null,
              emptyList(),
              now,
            ),
            won = false,
          )
          return
        }
        val missing = seats.firstOrNull {
          it.conn == null &&
            now - (it.disconnectedAt ?: now) >= (rules.reconnectGraceMs ?: timing.disconnectGraceMs)
        }
        if (missing != null) {
          finish(
            RoundResult(
              "The connection was interrupted. Reconnect and try again.",
              null,
              emptyList(),
              now,
            ),
            won = false,
          )
          return
        }
        // Freeze inputs and ticking while a participant is reconnecting. Restart if grace expires.
        val running = game
        if (running != null && now >= s.goAt && seats.all { it.conn != null }) {
          running.tick(now)
          settle(running)
        }
      }
      else -> {}
    }
    if (latencyDirty && now - latencyPublishedAt >= timing.latencyPublishMs) {
      latencyDirty = false
      latencyPublishedAt = now
      publish()
    }
  }

  private fun settle(running: HostedGame<*, *>) {
    pushFrame()
    when (val status = running.status) {
      GameStatus.Running -> {}
      is GameStatus.Won ->
        finish(RoundResult(status.headline, null, status.stats, clock.now()), won = true)
      is GameStatus.Lost ->
        finish(RoundResult(status.reason, status.culprit, status.stats, clock.now()), won = false)
    }
  }

  private fun finish(result: RoundResult, won: Boolean) {
    game = null
    seats.forEach { it.ready = false }
    stage =
      if (won && info.opportunity.hasReward) {
        val seal = Sealing.seal(info.opportunity, info.session, seats.map { it.toPlayer() }, result)
        Stage.Won(result, seal, Unlock.Waiting)
      } else if (won) Stage.Finished(result) else Stage.Lost(result)
    publish()
  }

  private fun sealed(seat: Seat, message: ToHost.SealIt) {
    val won = stage as? Stage.Won ?: return
    if (message.round != round || seat.id in won.seal.signed) return
    if (!Sealing.verify(won.seal, seat.toPlayer(), message.signature)) return
    stage =
      won.copy(
        seal =
          won.seal.copy(
            signed = won.seal.signed + seat.id,
            signatures = won.seal.signatures + (seat.id to message.signature),
          )
      )
    publish()
  }

  private fun updateUnlock(next: () -> Unlock) = command {
    val won = stage as? Stage.Won ?: return@command
    stage = won.copy(unlock = next())
    publish()
  }

  private fun pushFrame() {
    val running = game ?: return
    val state = running.state()
    val text = state.toString()
    if (text == lastState) return
    lastState = text
    val sequence = ++frameSeq
    seats.forEach { seat ->
      val frame = encode(ToPlayer.Frame(round, sequence, running.stateFor(seat.id)))
      lastFrames[seat.id] = frame
      seat.conn?.send(frame)
    }
  }

  private fun publish() {
    var snapshot = SessionSnapshot(info, seats.map { it.toPlayer() }, stage, round)
    if (snapshot.stage is Stage.Won) {
      val won = snapshot.stage
      val problem = runCatching {
        checkpoint(snapshot.copy(stage = won.copy(storageProblem = null)))
      }
        .exceptionOrNull()
        ?.let { "This win could not be saved. Keep this session open and free some storage." }
      if (problem != null) observe(DiagnosticEvent(DiagnosticCode.STORAGE_FAILED))
      stage = won.copy(storageProblem = problem)
      snapshot = snapshot.copy(stage = stage)
    }
    val code = snapshot.stage.diagnosticCode
    if (code != reportedStage) {
      observe(DiagnosticEvent(code, round.toLong()))
      if (code == DiagnosticCode.SESSION_WON) sealingAt = clock.now()
      reportedStage = code
    }
    val count = (snapshot.stage as? Stage.Won)?.seal?.signed?.size
    if (count != null && count != reportedSeals) {
      observe(DiagnosticEvent(DiagnosticCode.SEAL_PROGRESS, count.toLong()))
      if ((snapshot.stage as? Stage.Won)?.seal?.complete == true)
        observe(DiagnosticEvent(DiagnosticCode.SEAL_DURATION, clock.now() - sealingAt))
      reportedSeals = count
    }
    _snapshot.value = snapshot
    broadcast(signed(ToPlayer.Session(snapshot)))
  }

  private fun broadcast(frame: String) = seats.forEach { it.conn?.send(frame) }

  private fun encode(message: ToPlayer) = json.encodeToString(ToPlayer.serializer(), message)

  private fun signed(message: ToPlayer): String {
    val key = host?.sessionKey ?: return encode(message)
    val text = encode(message)
    return encode(
      ToPlayer.Signed(text, Base58.encode(key.sign(HostChecks.update(info.session, text))))
    )
  }

  companion object {
    // Size of the design system's palette of player lights.
    const val LIGHTS = 8
    const val MAX_NAME = 20
    /** Inputs this long before "go" still count; a phone's clock estimate can be a little off. */
    private const val EARLY_GRACE_MS = 200L

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    // No look-alike characters, so it can be read aloud.
    fun newCode(random: Random = Random.Default): String =
      (1..4).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
  }
}
