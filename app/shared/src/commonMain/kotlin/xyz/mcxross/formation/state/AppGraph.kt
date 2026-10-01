package xyz.mcxross.formation.state

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.mcxross.formation.link.BeaconProbe
import xyz.mcxross.formation.link.HostAddress
import xyz.mcxross.formation.link.LinkDefaults
import xyz.mcxross.formation.link.WebSocketConnector
import xyz.mcxross.formation.link.memoryLink
import xyz.mcxross.formation.model.Opportunity
import xyz.mcxross.formation.platform.HotspotInfo
import xyz.mcxross.formation.platform.PlatformServices
import xyz.mcxross.formation.platform.WalletResult
import xyz.mcxross.formation.sensors.MotionSense
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationHost
import xyz.mcxross.formation.session.FormationInfo
import xyz.mcxross.formation.session.MonotonicClock
import xyz.mcxross.formation.session.NearbyScanner
import xyz.mcxross.formation.solana.SgtFinder
import xyz.mcxross.formation.solana.SolanaRpc
import xyz.mcxross.formation.ui.nav.Navigator
import xyz.mcxross.formation.ui.nav.Screen

class AppGraph(
  val platform: PlatformServices,
  seekerCheck: SeekerCheck? = null,
  ledger: RewardLedger? = null,
) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val navigator = Navigator()
  val identity = Identity(platform)
  val sounds = SoundEffects(platform.store, platform.sound, scope)
  val seeker =
    SeekerState(
      platform.store,
      platform.wallet,
      platform.device.seeker,
      platform.config.debug,
      seekerCheck ?: sgtCheck(SolanaRpc(platform.network.http, platform.config.sgtRpcUrl)),
    )

  val ledgerChoice: LedgerMode =
    LedgerMode.entries.firstOrNull { it.name == platform.store.get(KEY_LEDGER) }
      ?: LedgerMode.SOLANA
  val ledger: RewardLedger =
    ledger
      ?: when (ledgerChoice) {
        LedgerMode.SIMULATED -> SimulatedLedger(platform.store)
        LedgerMode.SOLANA ->
          SolanaLedger(
            SolanaRpc(platform.network.http, platform.config.rpcUrl),
            platform.wallet,
            { identity.claimKey },
            platform.config.cluster,
            platform.store,
          )
      }

  val pending = PendingUnlocks(platform.store)
  val completed = CompletedSessions(platform.store)
  val recovery = xyz.mcxross.formation.state.recovery.RecoveryService(identity.claims, this.ledger,
    platform.store, platform.secrets, platform.config.cluster, replacementAllowed = {
      _session.value == null && this.ledger.tickets.value.isEmpty() && completed.entries.value.isEmpty() && pending.pending.value.isEmpty()
    })
  val recoveryProblem = MutableStateFlow<String?>(null)
  private val unlocking = Mutex()

  suspend fun unlockWin(win: PendingUnlock): Result<UnlockReceipt> = unlocking.withLock {
    val identity =
      seeker.identity.value
        ?: return Result.failure(IllegalStateException("This phone is not a Seeker"))
    val result = ledger.unlock(identity, win.opportunity, win.seal)
    result.onSuccess { receipt ->
      if (receipt.settled) pending.remove(win.opportunity.id)
      _session.value
        ?.takeIf { it.host?.snapshot?.value?.formation?.opportunity?.id == win.opportunity.id }
        ?.unlockedElsewhere(receipt)
    }
    result
  }

  fun updateProfile(profile: Profile) {
    identity.save(profile)
    _session.value?.client?.setProfile(profile.name, profile.light)
  }

  fun chooseLedger(mode: LedgerMode) = platform.store.put(KEY_LEDGER, mode.name)

  suspend fun connectWallet(): String? =
    when (val connected = platform.wallet.connect()) {
      WalletResult.NoWallet -> NO_WALLET
      is WalletResult.Failed -> connected.message
      is WalletResult.Ok -> {
        identity.saveWallet(connected.value.address)
        _session.value?.client?.setWallet(connected.value.address)
        null
      }
    }

  val motion = MotionSense(platform.motion, scope, MonotonicClock::now)
  val nearby = NearbyScanner(platform.network.finder, BeaconProbe(platform.network.http)::fetch)

  private val _session = MutableStateFlow<ActiveSession?>(null)
  val session: StateFlow<ActiveSession?> = _session.asStateFlow()

  val autoplay = MutableStateFlow(false)

  suspend fun host(opportunity: Opportunity, recovery: xyz.mcxross.formation.session.SessionSnapshot? = null): Result<ActiveSession> = runCatching {
    check(identity.claims.status.value is xyz.mcxross.formation.state.recovery.ClaimKeyState.Ready) { "Restore this phone's claim key first" }
    endSession()
    val challenge =
      ChallengeCatalog[opportunity.challenge] ?: error("This app doesn't know that challenge yet")
    val server = platform.network.server ?: error("This phone can't host Formations")
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val info =
      recovery?.formation ?: FormationInfo(newUuid(), FormationHost.newCode(), identity.player().name, opportunity)
    val host = FormationHost(info, challenge, sessionScope, recovery = recovery, checkpoint = { completed.remember(it) })
    val port = server.start(LinkDefaults.PORTS, host::beacon) { channel -> host.serve(channel) }
    platform.network.advertiser?.advertise("Formation ${info.code}", port)
    val client =
      FormationClient(
        identity.player(),
        connect = {
          val (mine, seekerSide) = memoryLink("seeker", "self")
          sessionScope.launch { host.serve(seekerSide, local = true) }
          mine
        },
        sessionScope,
      )
    client.start()
    val address = platform.network.addresses().firstOrNull()?.let { HostAddress(it, port) }
    ActiveSession(
        client,
        host,
        address,
        port,
        server,
        platform.network.advertiser,
        this.ledger,
        { seeker.identity.value },
        ::unlockWin,
        pending::add,
        sounds::play,
        sessionScope,
        { completed.remember(it) },
      )
      .also { _session.value = it }
  }

  fun join(address: HostAddress): ActiveSession {
    check(identity.claims.status.value is xyz.mcxross.formation.state.recovery.ClaimKeyState.Ready) { "Restore this phone's claim key first" }
    endSession()
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connector = WebSocketConnector(platform.network.http)
    val client =
      FormationClient(identity.player(), connect = { connector.connect(address) }, sessionScope)
    client.start()
    return ActiveSession(
        client,
        null,
        null,
        null,
        null,
        null,
        ledger,
        { seeker.identity.value },
        ::unlockWin,
        {},
        sounds::play,
        sessionScope,
        { completed.remember(it, address.toString()) },
      )
      .also { _session.value = it }
  }

  suspend fun resumeCompletion(record: CompletedSession): Result<Unit> = runCatching {
    val snapshot = record.snapshot
    if (snapshot.seeker?.claimKey == identity.claimAddress) {
      host(snapshot.formation.opportunity, snapshot).getOrThrow()
    } else {
      join(HostAddress.parse(record.address ?: error("Scan the Seeker's QR code to reconnect"))
        ?: error("Scan the Seeker's QR code to reconnect"))
    }
    navigator.push(Screen.Session)
  }

  private var pendingJoin: HostAddress? = null

  fun open(link: String): Boolean {
    val address = Links.parse(link)?.address ?: return false
    if (identity.profile.value == null || identity.claims.status.value !is xyz.mcxross.formation.state.recovery.ClaimKeyState.Ready) {
      pendingJoin = address
    } else {
      join(address)
      navigator.push(Screen.Session)
    }
    return true
  }

  fun resumePendingJoin() {
    val address = pendingJoin ?: return
    pendingJoin = null
    join(address)
    navigator.push(Screen.Session)
  }

  suspend fun openSeekerNetwork(): Result<HotspotInfo> {
    val hotspot =
      platform.hotspot
        ?: return Result.failure(IllegalStateException("This phone can't open a network"))
    val before = platform.network.addresses().toSet()
    return hotspot.start().onSuccess { info ->
      val ip =
        withTimeoutOrNull(5_000) {
          var found: String? = null
          while (found == null) {
            found = platform.network.addresses().firstOrNull { it !in before }
            if (found == null) delay(250)
          }
          found
        }
      _session.value?.onSeekerNetwork(info, ip)
    }
  }

  fun endSession() {
    platform.hotspot?.stop()
    _session.value?.end()
    _session.value = null
    platform.external.keepScreenOn(false)
  }

  init {
    runCatching { recovery.resumePending() }.onFailure { recoveryProblem.value = it.message ?: "Recovery could not finish" }
    scope.launch { sounds.prepare() }
    platform.hotspot?.let { hotspot -> scope.launch {
      hotspot.active.collect { if (it == null) _session.value?.onSeekerNetworkStopped() }
    } }
    scope.launch { this@AppGraph.ledger.sync() }
    // A pretend Seeker signs with its own key, so it can retry quietly; a real one waits for a tap.
    scope.launch {
      while (true) {
        if (seeker.identity.value?.simulated == true)
          pending.pending.value.forEach { unlockWin(it) }
        delay(30_000)
      }
    }
  }

  private companion object {
    const val KEY_LEDGER = "ledger"
  }
}

private fun sgtCheck(rpc: SolanaRpc) = SeekerCheck { wallet ->
  runCatching { SgtFinder(rpc).find(SolanaPublicKey.from(wallet))?.mint?.base58() }
}

const val NO_WALLET = "No Solana wallet on this phone"
