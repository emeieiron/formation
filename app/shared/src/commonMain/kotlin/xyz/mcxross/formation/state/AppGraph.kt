package xyz.mcxross.formation.state

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import xyz.mcxross.formation.sensors.MotionSimulator
import xyz.mcxross.formation.sensors.SensorHub
import xyz.mcxross.formation.sensors.capabilities.Assessment
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationHost
import xyz.mcxross.formation.session.FormationInfo
import xyz.mcxross.formation.session.HostChecks
import xyz.mcxross.formation.session.HostVerifier
import xyz.mcxross.formation.session.MonotonicClock
import xyz.mcxross.formation.session.NearbyScanner
import xyz.mcxross.formation.session.ScreenRequirement
import xyz.mcxross.formation.solana.FormationVault
import xyz.mcxross.formation.solana.SgtFinder
import xyz.mcxross.formation.solana.VaultConfig
import xyz.mcxross.formation.solana.SolanaRpc
import xyz.mcxross.formation.session.DiagnosticCode
import xyz.mcxross.formation.session.DiagnosticEvent
import xyz.mcxross.formation.state.diagnostics.LocalDiagnostics
import xyz.mcxross.formation.state.diagnostics.TraceSource
import xyz.mcxross.formation.ui.nav.Navigator
import xyz.mcxross.formation.ui.nav.Screen

class AppGraph(
  val platform: PlatformServices,
  seekerCheck: SeekerCheck? = null,
  ledger: RewardLedger? = null,
) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val navigator = Navigator()
  val diagnostics = LocalDiagnostics(platform.store, ::now)
  val simulator = if (platform.config.developer && platform.device.emulator) MotionSimulator(scope) else null
  val sensors = SensorHub(simulator ?: platform.sensorBackend, scope, MonotonicClock::now)
  val motion = MotionSense(sensors, scope, simulator)
  val identity = Identity(platform) {
    sensors.capabilities.value.supported(allowSimulated = simulator != null) +
      listOfNotNull(ScreenRequirement.CAPABILITY.takeIf { platform.screen.measurable })
  }
  val sounds = SoundEffects(platform.store, platform.sound, scope)
  internal val challengeAudio = ChallengeAudio(sounds)
  val seeker =
    SeekerState(
      platform.store,
      platform.secrets,
      platform.wallet,
      platform.device.seeker,
      platform.config.cluster,
      seekerCheck ?: sgtCheck(SolanaRpc(platform.network.http, platform.config.rpcUrl)),
      platform.config.faucetUrl?.let { url -> TestFaucet(platform.network.http, url, "Formation/${platform.config.version}")::fund },
    )
  val revocations = AttestationRevocations(platform.store, platform.network.http, ::now)
  val hardware = SeekerHardware(platform.attestation, revocations, ::now)
  // Every phone but the host's own checks who the host plays for, and that its reward is on chain for them.
  private val hostVerifier = HostVerifier { session, proof ->
    HostChecks.problem(proof, session, platform.config.cluster)
      ?: proof?.let { this.ledger.rewardProblem(it.opportunity, it.wallet) }
  }
  val role: StateFlow<PhoneRole> = seeker.identity.map { phoneRole(platform.device.seeker, it) }
    .stateIn(scope, SharingStarted.Eagerly, phoneRole(platform.device.seeker, seeker.identity.value))

  val ledger: RewardLedger =
    ledger
      ?: SolanaLedger(
        SolanaRpc(platform.network.http, platform.config.rpcUrl),
        platform.wallet,
        { identity.claimKey },
        { seeker.testWallet() },
        platform.config.cluster,
        platform.store,
        observe = diagnostics.sink(TraceSource.SETTLEMENT),
      )

  val pending = PendingUnlocks(platform.store)
  val completed = CompletedSessions(platform.store)
  val recovery = xyz.mcxross.formation.state.recovery.RecoveryService(identity.claims, this.ledger,
    platform.store, platform.secrets, platform.config.cluster, replacementAllowed = {
      _session.value == null && this.ledger.tickets.value.isEmpty() && completed.entries.value.isEmpty() && pending.pending.value.isEmpty()
    })
  val recoveryProblem = MutableStateFlow<String?>(null)
  private val unlocking = Mutex()
  private val settlementCompletion = SettlementCompletion(completed, this.ledger)

  suspend fun unlockWin(win: PendingUnlock): Result<UnlockReceipt> = unlocking.withLock {
    val identity =
      seeker.identity.value
        ?: return Result.failure(IllegalStateException("This phone is not a Seeker"))
    diagnostics.sink(TraceSource.SETTLEMENT)(DiagnosticEvent(DiagnosticCode.UNLOCK_START))
    ledger.unlock(identity, win.opportunity, win.seal).mapCatching { receipt ->
      settlementCompletion.record(win, receipt, identity, now())
      if (receipt.settled) pending.remove(win.opportunity.id) else pending.paidPartially(win.opportunity.id)
      _session.value
        ?.takeIf { it.host?.snapshot?.value?.formation?.opportunity?.id == win.opportunity.id }
        ?.unlockedElsewhere(receipt)
      receipt
    }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
  }

  fun updateProfile(profile: Profile) {
    identity.save(profile)
    _session.value?.client?.setProfile(profile.name, profile.light)
  }

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

  val nearby = NearbyScanner(platform.network.finder, BeaconProbe(platform.network.http)::fetch)

  private val _session = MutableStateFlow<ActiveSession?>(null)
  val session: StateFlow<ActiveSession?> = _session.asStateFlow()

  val autoplay = MutableStateFlow(false)

  suspend fun host(opportunity: Opportunity, recovery: xyz.mcxross.formation.session.SessionSnapshot? = null): Result<ActiveSession> = runCatching {
    seeker.requireHostIdentity()
    check(identity.claims.status.value is xyz.mcxross.formation.state.recovery.ClaimKeyState.Ready) { "Restore this phone's claim key first" }
    val info =
      recovery?.formation ?: FormationInfo(newUuid(), FormationHost.newCode(), identity.player().name, opportunity)
    val credentials = seeker.credentials(info.session, opportunity)
    endSession()
    val challenge =
      ChallengeCatalog[opportunity.challenge] ?: error("This app doesn't know that challenge yet")
    check(opportunity.players in challenge.info.groupSizes) { "This game does not support this group size." }
    if (recovery == null) {
      val required = challenge.requiredSensors(opportunity.players).map {
        it.copy(allowSimulated = it.allowSimulated && simulator != null)
      }
      check(sensors.assess(required) == Assessment.Ready) {
        "This phone cannot provide the inputs required by this Formation."
      }
      check(challenge.screenRequirement(opportunity.players) == null || platform.screen.measurable) {
        "This phone can't measure its screen for this game."
      }
    }
    val server = platform.network.server ?: error("This phone can't host Formations")
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tag = diagnostics.nextSession()
    val host = FormationHost(info, challenge, sessionScope, recovery = recovery,
      checkpoint = { completed.remember(it) }, observe = diagnostics.sink(TraceSource.HOST, tag), host = credentials)
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
        observe = diagnostics.sink(TraceSource.CLIENT, tag),
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
        sensors,
        simulator != null,
        platform.screen,
      )
      .also { _session.value = it }
  }

  fun join(address: HostAddress, recovery: xyz.mcxross.formation.session.SessionSnapshot? = null): ActiveSession {
    check(identity.claims.status.value is xyz.mcxross.formation.state.recovery.ClaimKeyState.Ready) { "Restore this phone's claim key first" }
    endSession()
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connector = WebSocketConnector(platform.network.http)
    val tag = diagnostics.nextSession()
    val client =
      FormationClient(identity.player(), connect = { connector.connect(address) }, sessionScope,
        observe = diagnostics.sink(TraceSource.CLIENT, tag), recovery = recovery, verifier = hostVerifier)
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
        sensors,
        simulator != null,
        platform.screen,
      )
      .also { _session.value = it }
  }

  suspend fun resumeCompletion(record: CompletedSession): Result<Unit> = runCatching {
    val snapshot = record.snapshot
    if (snapshot.seeker?.claimKey == identity.claimAddress) {
      host(snapshot.formation.opportunity, snapshot).getOrThrow()
    } else {
      join(HostAddress.parse(record.address ?: error("Scan the Seeker's QR code to reconnect"))
        ?: error("Scan the Seeker's QR code to reconnect"), snapshot)
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
    scope.launch {
      platform.network.finder.status.collect { state ->
        diagnostics.sink(TraceSource.DISCOVERY)(DiagnosticEvent(
          if (state is xyz.mcxross.formation.link.DiscoveryStatus.Failed) DiagnosticCode.DISCOVERY_FAILED else DiagnosticCode.DISCOVERY_SEARCHING,
          (state as? xyz.mcxross.formation.link.DiscoveryStatus.Failed)?.reason?.ordinal?.toLong()))
      }
    }
    platform.hotspot?.let { hotspot -> scope.launch {
      hotspot.active.collect { if (it == null) _session.value?.onSeekerNetworkStopped() }
    } }
    scope.launch { this@AppGraph.ledger.sync() }
    // A test Seeker signs with its own key, so it can retry quietly; a real one waits for a tap.
    scope.launch {
      while (true) {
        if (seeker.identity.value?.test == true)
          pending.pending.value.forEach { unlockWin(it) }
        delay(30_000)
      }
    }
  }

  private companion object {
  }
}

// The vault names the token group it accepts on this network, so the app checks the same one.
private fun sgtCheck(rpc: SolanaRpc) = SeekerCheck { wallet ->
  runCatching {
    val config = rpc.account(FormationVault().config())?.data ?: error("The Formation vault isn't set up on this network")
    SgtFinder(rpc, VaultConfig.decode(config).sgtGroup).find(SolanaPublicKey.from(wallet))?.mint?.base58()
  }
}

const val NO_WALLET = "No Solana wallet on this phone"
