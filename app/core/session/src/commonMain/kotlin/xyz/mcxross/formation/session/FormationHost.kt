package xyz.mcxross.formation.session

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
) {
  private val info = formation
  private val seats = recovery?.players?.map { p ->
    Seat(p.id, p.device ?: p.claimKey, p.name, p.light, p.seeker, p.claimKey, p.wallet, null)
  }?.toMutableList() ?: mutableListOf()
  private var stage: Stage = recovery?.let { saved ->
    require(saved.formation == info) { "The saved session belongs to another Formation" }
    validatedCompletion(saved).let { if (it.unlock is Unlock.Unlocking) it.copy(unlock = Unlock.Waiting) else it }
  } ?: Stage.Lobby
  private var round = recovery?.round ?: 0
  private var nextSeat = (seats.maxOfOrNull { it.id.value.removePrefix("p").toIntOrNull() ?: 0 } ?: 0) + 1
  private var game: HostedGame<*, *>? = null
  private var frameSeq = 0L
  private var lastState: String? = null
  private var lastFrame: String? = null
  private var latencyDirty = false
  private var latencyPublishedAt = 0L

  private val _snapshot = MutableStateFlow(SessionSnapshot(info, seats.map { it.toPlayer() }, stage, round))
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
    val conn = Conn(channel, local, admissionChallenge(info, rules))
    conn.send(encode(ToPlayer.Authenticate(conn.challenge)))
    try {
      coroutineScope {
        val deadline = launch {
          delay(10_000)
          command { if (seatOf(conn) == null) reject(conn, Rejection.IDENTITY) }
        }
        val writer = launch {
          for (frame in conn.outbox) if (!channel.send(frame)) break
          // The host closed the outbox: hang up once everything queued is out.
          channel.close()
        }
        try {
          channel.incoming.collect { text ->
            decode(text)?.let { events.send(Event.Received(conn, it)) }
          }
        } finally {
          writer.cancel()
          deadline.cancel()
        }
      }
    } finally {
      events.trySend(Event.Closed(conn))
      channel.close()
    }
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
        helperShare = opp.split(helpers).helper,
        tier = opp.tier.label,
      ),
    )
  }

  fun begin() = command {
    if (stage == Stage.Lobby && seats.size >= info.opportunity.players && seats.all { it.conn != null }) toBriefing()
  }

  fun startNow() = command { if (stage is Stage.Briefing) startRound() }

  fun runItBack() = command { if (stage is Stage.Lost) toBriefing() }

  fun backToLobby() = command {
    if (stage is Stage.Lost || stage is Stage.Briefing) {
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

  fun unlocked(receipt: String, explorerUrl: String?, paid: List<PlayerId> = emptyList()) =
    updateUnlock {
      Unlock.Unlocked(receipt, clock.now(), explorerUrl, paid)
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
    class Received(val conn: Conn, val message: ToHost) : Event

    class Closed(val conn: Conn) : Event

    class Command(val run: () -> Unit) : Event

    data object Tick : Event
  }

  private class Conn(val channel: LinkChannel, val local: Boolean, val challenge: AdmissionChallenge) {
    // A slow phone loses old frames rather than holding up everyone; every message is a snapshot.
    val outbox = Channel<String>(512, BufferOverflow.DROP_OLDEST)

    fun send(frame: String) {
      outbox.trySend(frame)
    }
  }

  private class Seat(
    val id: PlayerId,
    val device: String,
    var name: String,
    var light: Int,
    val seeker: Boolean,
    val claimKey: String,
    var wallet: String?,
    var conn: Conn?,
    var ready: Boolean = false,
    var latencyMs: Int? = null,
    var disconnectedAt: Long? = null,
    var clockReady: Boolean = false,
    var syncReportedAt: Long = 0,
  ) {
    fun toPlayer() =
      Player(id, name, light, seeker, claimKey, wallet, conn != null, ready, latencyMs, device, clockReady)
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

  private fun seatOf(conn: Conn) = seats.firstOrNull { it.conn === conn }

  private fun receive(conn: Conn, message: ToHost) {
    if (message is ToHost.Hello) return hello(conn, message)
    if (message is ToHost.Ping) {
      conn.send(encode(ToPlayer.Pong(message.sent, clock.now())))
      val seat = seatOf(conn) ?: return
      val synced = message.synced && message.rtt != null && message.rtt in 0..ClockSync.MAX_RTT_MS.toInt()
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
      is ToHost.Play -> play(seat, message)
      is ToHost.SealIt -> sealed(seat, message)
      is ToHost.Wallet -> wallet(seat, message.address)
      is ToHost.Profile -> profile(seat, message)
      ToHost.Leave -> left(seat)
      else -> {}
    }
  }

  private fun hello(conn: Conn, hello: ToHost.Hello) {
    if (stage is Stage.Closed) return reject(conn, Rejection.CLOSED)
    admissionRejection(conn.challenge, hello)?.let { return reject(conn, it) }
    if (seatOf(conn) != null) return reject(conn, Rejection.DUPLICATE)

    val returning = seats.firstOrNull { it.device == hello.device || it.claimKey == hello.claimKey }
    if (returning != null) {
      if (returning.seeker != conn.local || returning.claimKey != hello.claimKey) return reject(conn, Rejection.DUPLICATE)
      if (returning.conn != null && returning.conn !== conn) return reject(conn, Rejection.DUPLICATE)
      returning.conn = conn
      val interruptedPlay = stage is Stage.Playing && returning.disconnectedAt != null
      returning.disconnectedAt = null
      returning.clockReady = false
      if (interruptedPlay) finish(RoundResult("The connection was interrupted. Try again.", null, emptyList(), clock.now()), won = false)
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

  private fun welcome(conn: Conn, seat: Seat) {
    conn.send(encode(ToPlayer.Welcome(seat.id)))
    publish()
    lastFrame?.takeIf { stage is Stage.Playing }?.let(conn::send)
  }

  private fun reject(conn: Conn, reason: Rejection) {
    conn.send(encode(ToPlayer.Rejected(reason)))
    conn.outbox.close()
  }

  private fun disconnected(conn: Conn) {
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

  private fun ready(seat: Seat, ready: Boolean) {
    if (stage !is Stage.Briefing) return
    seat.ready = ready
    publish()
    if (seats.all { it.ready && it.conn != null }) startRound()
  }

  private fun toBriefing() {
    seats.forEach { it.ready = false }
    stage = Stage.Briefing(clock.now() + timing.briefingMs)
    publish()
  }

  private fun readyToPlay() = seats.size == info.opportunity.players && seats.all {
    it.conn != null && it.clockReady && clock.now() - it.syncReportedAt <= ClockSync.MAX_AGE_MS
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
      )
    game = HostedGame.start(rules, setup, json)
    frameSeq = 0
    lastState = null
    lastFrame = null
    stage = Stage.Playing(goAt, roster)
    publish()
    pushFrame()
  }

  private fun play(seat: Seat, message: ToHost.Play) {
    val playing = stage as? Stage.Playing ?: return
    val running = game ?: return
    if (message.round != round || seats.any { it.conn == null }) return
    val now = clock.now()
    if (now < playing.goAt - EARLY_GRACE_MS) return
    running.input(seat.id, message.input, now)
    settle(running)
  }

  private fun tick() {
    val now = clock.now()
    seats.filter { it.clockReady && now - it.syncReportedAt > ClockSync.MAX_AGE_MS }.forEach {
      it.clockReady = false
      latencyDirty = true
    }
    when (val s = stage) {
      is Stage.Briefing -> if (now >= s.until && readyToPlay()) startRound()
      is Stage.Playing -> {
        val missing = seats.firstOrNull { it.conn == null && now - (it.disconnectedAt ?: now) >= timing.disconnectGraceMs }
        if (missing != null) {
          finish(RoundResult("The connection was interrupted. Reconnect and try again.", null, emptyList(), now), won = false)
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
      if (won) {
        val seal = Sealing.seal(info.opportunity, info.session, seats.map { it.toPlayer() }, result)
        Stage.Won(result, seal, Unlock.Waiting)
      } else Stage.Lost(result)
    publish()
  }

  private fun sealed(seat: Seat, message: ToHost.SealIt) {
    val won = stage as? Stage.Won ?: return
    if (message.round != round || seat.id in won.seal.signed) return
    if (!Sealing.verify(won.seal, seat.toPlayer(), message.signature)) return
    stage = won.copy(seal = won.seal.copy(signed = won.seal.signed + seat.id, signatures = won.seal.signatures + (seat.id to message.signature)))
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
    val frame = encode(ToPlayer.Frame(round, ++frameSeq, state))
    lastFrame = frame
    broadcast(frame)
  }

  private fun publish() {
    var snapshot = SessionSnapshot(info, seats.map { it.toPlayer() }, stage, round)
    if (snapshot.stage is Stage.Won) {
      val won = snapshot.stage as Stage.Won
      val problem = runCatching { checkpoint(snapshot.copy(stage = won.copy(storageProblem = null))) }
        .exceptionOrNull()?.let { "This win could not be saved. Keep this session open and free some storage." }
      stage = won.copy(storageProblem = problem)
      snapshot = snapshot.copy(stage = stage)
    }
    _snapshot.value = snapshot
    broadcast(encode(ToPlayer.Session(snapshot)))
  }

  private fun broadcast(frame: String) = seats.forEach { it.conn?.send(frame) }

  private fun encode(message: ToPlayer) = json.encodeToString(ToPlayer.serializer(), message)

  private fun decode(text: String): ToHost? = runCatching {
    json.decodeFromString(ToHost.serializer(), text)
  }
    .getOrNull()


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
