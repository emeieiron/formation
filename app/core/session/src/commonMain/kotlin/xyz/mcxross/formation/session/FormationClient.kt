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
import xyz.mcxross.formation.crypto.Ed25519KeyPair
import xyz.mcxross.formation.link.LinkChannel
import xyz.mcxross.formation.model.PlayerId

class PlayerIdentity(
  val device: String,
  val name: String,
  val light: Int,
  val key: Ed25519KeyPair,
  val wallet: String? = null,
) {
  val claimKey: String = Base58.encode(key.publicKey)
}

class FormationClient(
  private val identity: PlayerIdentity,
  private val connect: suspend () -> LinkChannel,
  private val scope: CoroutineScope,
  private val clock: Clock = MonotonicClock,
  private val json: Json = FormationJson,
) {
  sealed interface Status {
    data object Connecting : Status

    data object Joined : Status

    data class Reconnecting(val attempt: Int) : Status

    data class Rejected(val reason: Rejection) : Status

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
  @Volatile private var finished = false
  private var job: Job? = null

  fun start() {
    if (job == null) job = scope.launch { run() }
  }

  fun ready(ready: Boolean) = send(ToHost.Ready(ready))

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
    if (_status.value !is Status.Rejected) _status.value = Status.Ended(reason)
  }

  private suspend fun run() {
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
        delay(backoff(failures))
        continue
      }
      failures = 0
      session(channel)
      if (_me.value != null) joinedOnce = true
      if (!finished) {
        _status.value = Status.Reconnecting(1)
        delay(RECONNECT_PAUSE_MS)
      }
    }
  }

  private suspend fun session(channel: LinkChannel) = coroutineScope {
    // Moves made while the link was down are stale; drop them.
    while (outbox.tryReceive().isSuccess) {}
    sealedMessage = null
    channel.send(
      encode(
        ToHost.Hello(
          PROTOCOL_VERSION,
          identity.device,
          identity.name,
          identity.light,
          identity.claimKey,
          wallet,
        )
      )
    )
    val writer = launch { for (message in outbox) if (!channel.send(encode(message))) break }
    val pinger = launch {
      repeat(BURST) {
        channel.send(encode(ToHost.Ping(clock.now(), sync.rttMs?.toInt())))
        delay(BURST_GAP_MS)
      }
      while (true) {
        delay(PING_EVERY_MS)
        channel.send(encode(ToHost.Ping(clock.now(), sync.rttMs?.toInt())))
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

  private fun handle(message: ToPlayer) {
    when (message) {
      is ToPlayer.Welcome -> {
        _me.value = message.you
        _status.value = Status.Joined
      }
      is ToPlayer.Pong -> sync.onPong(message.sent, message.host)
      is ToPlayer.Session -> {
        val snapshot = message.snapshot
        if (snapshot.round != _snapshot.value?.round) _frame.value = null
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
    if (expected.message != won.seal.message || mine.claimKey != identity.claimKey) return
    if (!mine.seeker && mine.wallet != wallet) return
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
