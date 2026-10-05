package xyz.mcxross.formation.session

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import xyz.mcxross.formation.crypto.Base64
import xyz.mcxross.formation.crypto.Ed25519
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.crypto.secureRandomBytes
import xyz.mcxross.formation.link.LinkChannel
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.model.PlayerId

class PlayerIdentity(
  val device: String,
  val name: String,
  val light: Int,
  val key: Ed25519KeyPair,
  val wallet: String? = null,
  val formats: Map<String, Int> = emptyMap(),
  val capabilities: Set<String> = emptySet(),
) {
  val claimKey: String = Base58.encode(key.publicKey)
}

class FormationClient(
  private val identity: PlayerIdentity,
  private val connect: suspend () -> LinkChannel,
  private val scope: CoroutineScope,
  private val clock: Clock = MonotonicClock,
  private val json: Json = FormationJson,
  private val observe: (DiagnosticEvent) -> Unit = {},
  private val recovery: SessionSnapshot? = null,
  // Every phone but the Seeker's own checks the host; without a verifier the client trusts its link.
  private val verifier: HostVerifier? = null,
) {
  sealed interface Status {
    data object Connecting : Status

    data object Joined : Status

    data class Reconnecting(val attempt: Int) : Status

    data class Rejected(val reason: Rejection) : Status

    // The host couldn't prove it's a Seeker, or its signed updates didn't check out.
    data class Untrusted(val reason: String) : Status

    data class Ended(val reason: String) : Status
  }

  private val _status = MutableStateFlow<Status>(Status.Connecting)
  val status: StateFlow<Status> = _status.asStateFlow()

  private val _me = MutableStateFlow<PlayerId?>(null)
  val me: StateFlow<PlayerId?> = _me.asStateFlow()

  private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
  val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()

  private val _frame = MutableStateFlow<ToPlayer.Frame?>(null)
  val frame: StateFlow<ToPlayer.Frame?> = _frame.asStateFlow()

  val sync = ClockSync(clock)

  private val outbox = Channel<ToHost>(Channel.UNLIMITED)
  @Volatile private var sealedMessage: String? = null
  @Volatile private var wallet: String? = identity.wallet
  @Volatile private var name: String = identity.name
  @Volatile private var light: Int = identity.light
  @Volatile private var finished = false
  private var job: Job? = null
  private var connectingAt = clock.now()
  private var reportedStage: DiagnosticCode? = null
  private var completionCheckpoint: (SessionSnapshot) -> Unit = {}
  private var hostSession: String? = null
  private var sessionKey: ByteArray? = null
  private var reward: Opportunity? = null
  private var presence = ""

  fun checkpointCompletions(save: (SessionSnapshot) -> Unit) { completionCheckpoint = save }

  fun start() {
    if (job == null) job = scope.launch { run() }
  }

  fun ready(ready: Boolean) {
    send(ToHost.Ping(clock.now(), sync.rttMs?.toInt(), sync.synced))
    send(ToHost.Ready(ready))
  }

  fun sensors(round: Int, available: Set<String>, screen: ScreenProfile? = null) { send(ToHost.Sensors(round, available, screen)) }

  fun setProfile(name: String, light: Int) {
    this.name = name
    this.light = light
    send(ToHost.Profile(name, light))
  }

  fun setWallet(address: String?) {
    wallet = address
    send(ToHost.Wallet(address))
  }

  fun play(input: JsonElement) {
    val round = _snapshot.value?.round ?: return
    send(ToHost.Play(round, input))
  }

  fun leave() {
    send(ToHost.Leave)
    end("You left the Formation.")
    scope.launch {
      delay(300)
      job?.cancel()
    }
  }

  private fun send(message: ToHost) {
    outbox.trySend(message)
  }

  private fun end(reason: String) {
    finished = true
    observe(DiagnosticEvent(DiagnosticCode.CONNECT_ENDED))
    if (_status.value !is Status.Rejected && _status.value !is Status.Untrusted) _status.value = Status.Ended(reason)
  }

  private suspend fun run() {
    observe(DiagnosticEvent(DiagnosticCode.CONNECT_START))
    var failures = 0
    var joinedOnce = false
    while (!finished) {
      val channel =
        try {
          connect()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      if (channel == null) {
        failures++
        when {
          !joinedOnce && failures >= CONNECT_TRIES -> return end("Couldn't reach the Seeker.")
          failures >= RECONNECT_TRIES -> return end("Lost the Seeker.")
        }
        _status.value = if (joinedOnce) Status.Reconnecting(failures) else Status.Connecting
        observe(DiagnosticEvent(DiagnosticCode.CONNECT_RETRY, failures.toLong()))
        delay(backoff(failures))
        continue
      }
      failures = 0
      try {
        session(channel)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // A link that fails rather than closes is just another drop; escaping would kill the client.
      }
      if (_me.value != null) joinedOnce = true
      if (!finished) {
        _status.value = Status.Reconnecting(1)
        observe(DiagnosticEvent(DiagnosticCode.CONNECT_RETRY, 1))
        connectingAt = clock.now()
        delay(RECONNECT_PAUSE_MS)
      }
    }
  }

  private suspend fun session(channel: LinkChannel) = coroutineScope {
    // Moves made while the link was down are stale; drop them.
    while (outbox.tryReceive().isSuccess) {}
    sealedMessage = null
    sessionKey = null
    reward = null
    hostSession = null
    sync.reset()
    val writer = launch { for (message in outbox) if (!channel.send(encode(message))) break }
    val pinger = launch {
      repeat(BURST) {
        channel.send(encode(ToHost.Ping(clock.now(), sync.rttMs?.toInt(), sync.synced)))
        delay(BURST_GAP_MS)
      }
      while (true) {
        delay(PING_EVERY_MS)
        channel.send(encode(ToHost.Ping(clock.now(), sync.rttMs?.toInt(), sync.synced)))
      }
    }
    try {
      channel.incoming.collect { text ->
        val message = decode(text) ?: return@collect
        handle(message)
        if (finished) channel.close()
      }
    } finally {
      writer.cancel()
      pinger.cancel()
      channel.close()
    }
  }

  private suspend fun handle(message: ToPlayer) {
    when (message) {
      is ToPlayer.Authenticate -> {
        val session = message.challenge.session
        if (verifier != null) verifier.problem(session, message.host)?.let { return untrusted(it) }
        sessionKey = message.host?.sessionKey?.let { runCatching { Base58.decode(it) }.getOrNull() }
        reward = message.host?.opportunity
        hostSession = session
        presence = Base58.encode(secureRandomBytes(32))
        val hello = ToHost.Hello(PROTOCOL_VERSION, identity.device, name, light, identity.claimKey, wallet,
          identity.formats, identity.capabilities, message.challenge.nonce, presence)
        send(hello.copy(signature = Base58.encode(identity.key.sign(admissionMessage(message.challenge, hello)))))
      }
      is ToPlayer.Signed -> {
        val key = sessionKey
        val session = hostSession
        if (verifier != null) {
          val valid = key != null && session != null && runCatching {
            Ed25519.verify(Base58.decode(message.signature), HostChecks.update(session, message.message), key)
          }.getOrDefault(false)
          if (!valid) return untrusted("The Seeker's updates couldn't be verified.")
        }
        val inner = decode(message.message)
        if (inner is ToPlayer.Welcome || inner is ToPlayer.Session) accept(inner)
      }
      // Only the Seeker's own link may send these unsigned.
      is ToPlayer.Welcome, is ToPlayer.Session -> if (verifier == null) accept(message)
      else -> accept(message)
    }
  }

  private fun untrusted(reason: String) {
    _status.value = Status.Untrusted(reason)
    observe(DiagnosticEvent(DiagnosticCode.CONNECT_UNTRUSTED))
    finished = true
  }

  private fun accept(message: ToPlayer) {
    when (message) {
      is ToPlayer.Authenticate, is ToPlayer.Signed -> {}
      is ToPlayer.Welcome -> {
        if (verifier != null && message.presence != presence) return untrusted("The Seeker's welcome wasn't meant for this phone.")
        _me.value = message.you
        _status.value = Status.Joined
        observe(DiagnosticEvent(DiagnosticCode.CONNECT_JOINED, clock.now() - connectingAt))
        send(ToHost.Ping(clock.now()))
      }
      is ToPlayer.Pong -> {
        sync.onPong(message.sent, message.host)
        observe(DiagnosticEvent(DiagnosticCode.CLOCK_RTT, sync.rttMs))
      }
      is ToPlayer.Session -> {
        if (verifier != null && message.snapshot.formation.opportunity != reward)
          return untrusted("The Seeker's updates don't match the reward it showed.")
        var snapshot = message.snapshot
        val completed = snapshot.stage as? Stage.Won
        if (completed != null) {
          val problem = runCatching { completionCheckpoint(snapshot) }.exceptionOrNull()
          if (problem != null) {
            observe(DiagnosticEvent(DiagnosticCode.STORAGE_FAILED))
            snapshot = snapshot.copy(stage = completed.copy(
            storageProblem = "This win could not be saved on this phone. Keep the session open and free some storage."))
          }
        }
        if (snapshot.round != _snapshot.value?.round) _frame.value = null
        if (snapshot.stage.diagnosticCode != reportedStage) {
          reportedStage = snapshot.stage.diagnosticCode
          observe(DiagnosticEvent(snapshot.stage.diagnosticCode, snapshot.round.toLong()))
        }
        _snapshot.value = snapshot
        when (val stage = snapshot.stage) {
          is Stage.Closed -> end(stage.reason)
          is Stage.Won -> sign(snapshot, stage)
          else -> {}
        }
      }
      is ToPlayer.Frame -> {
        val current = _frame.value
        if (current == null || message.round != current.round || message.seq > current.seq) {
          _frame.value = message
        }
      }
      is ToPlayer.Rejected -> {
        _status.value = Status.Rejected(message.reason)
        observe(DiagnosticEvent(DiagnosticCode.CONNECT_REJECTED, message.reason.ordinal.toLong()))
        finished = true
      }
    }
  }

  // Only signs a seal this phone would have built itself: its own claim key and wallet, the split
  // and the result.
  private fun sign(snapshot: SessionSnapshot, won: Stage.Won) {
    val me = _me.value ?: return
    if (me !in won.seal.required || me in won.seal.signed) return
    if (sealedMessage == won.seal.message) return
    val expected =
      Sealing.seal(
        snapshot.formation.opportunity,
        snapshot.formation.session,
        snapshot.players,
        won.result,
      )
    val mine = snapshot.player(me) ?: return
    if (expected.message != won.seal.message || expected.root != won.seal.root || expected.roster != won.seal.roster ||
      expected.ownerAmount != won.seal.ownerAmount || expected.required != won.seal.required || mine.claimKey != identity.claimKey) return
    val restored = recovery?.takeIf { it.formation.session == snapshot.formation.session }
    if (restored != null) {
      val savedWin = runCatching { validatedCompletion(restored) }.getOrNull() ?: return
      val original = restored.players.firstOrNull { it.claimKey == identity.claimKey } ?: return
      if (savedWin.seal.message != won.seal.message || original.wallet != mine.wallet) return
    } else if (!mine.seeker && mine.wallet != wallet) return
    sealedMessage = won.seal.message
    val signature = identity.key.sign(Base64.decode(won.seal.message))
    send(ToHost.SealIt(snapshot.round, Base58.encode(signature)))
  }

  private fun encode(message: ToHost) = json.encodeToString(ToHost.serializer(), message)

  private fun decode(text: String): ToPlayer? = runCatching {
    json.decodeFromString(ToPlayer.serializer(), text)
  }
    .getOrNull()

  private companion object {
    const val CONNECT_TRIES = 3
    const val RECONNECT_TRIES = 30
    const val RECONNECT_PAUSE_MS = 400L
    const val BURST = 6
    const val BURST_GAP_MS = 120L
    const val PING_EVERY_MS = 2_000L

    fun backoff(attempt: Int): Long = (300L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(4_000)
  }
}
