package xyz.mcxross.formation.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.mcxross.formation.challenge.Challenge
import xyz.mcxross.formation.crypto.toHex
import xyz.mcxross.formation.link.Advertiser
import xyz.mcxross.formation.link.HostAddress
import xyz.mcxross.formation.link.LinkServer
import xyz.mcxross.formation.model.PlayerId
import xyz.mcxross.formation.platform.HotspotInfo
import xyz.mcxross.formation.platform.SoundCue
import xyz.mcxross.formation.session.FormationClient
import xyz.mcxross.formation.session.FormationHost
import xyz.mcxross.formation.session.Sealing
import xyz.mcxross.formation.session.SessionSnapshot
import xyz.mcxross.formation.session.Stage
import xyz.mcxross.formation.session.Unlock

class ActiveSession
internal constructor(
  val client: FormationClient,
  val host: FormationHost?,
  address: HostAddress?,
  private val port: Int?,
  private val server: LinkServer?,
  private val advertiser: Advertiser?,
  private val ledger: RewardLedger,
  private val seeker: () -> SeekerIdentity?,
  private val unlockWin: suspend (PendingUnlock) -> Result<UnlockReceipt>,
  private val onSealed: (PendingUnlock) -> Unit,
  private val onSound: (SoundCue) -> Unit,
  private val scope: CoroutineScope,
  private val onCompletion: (SessionSnapshot) -> Unit = {},
) {
  val isHost: Boolean
    get() = host != null

  val snapshot: StateFlow<SessionSnapshot?> = client.snapshot
  val status: StateFlow<FormationClient.Status> = client.status
  val advertising = advertiser?.status ?: kotlinx.coroutines.flow.flowOf(xyz.mcxross.formation.link.DiscoveryStatus.Searching)

  val challenge: Challenge<*, *>?
    get() = snapshot.value?.formation?.opportunity?.challenge?.let { ChallengeCatalog[it] }

  private val initialAddress = address
  private val _address = MutableStateFlow(address)
  val address: StateFlow<HostAddress?> = _address.asStateFlow()

  private val _network = MutableStateFlow<HotspotInfo?>(null)
  val network: StateFlow<HotspotInfo?> = _network.asStateFlow()

  val joinLink: String?
    get() {
      val code = host?.snapshot?.value?.formation?.code ?: return null
      return _address.value?.let { Links.join(it, code) }
    }

  // The hotspot's address wasn't there when hosting began: the QR moves to it and Nearby hears
  // again.
  internal fun onSeekerNetwork(info: HotspotInfo, ip: String?) {
    _network.value = info
    if (ip != null && port != null) _address.value = HostAddress(ip, port)
    val code = host?.snapshot?.value?.formation?.code ?: return
    if (port != null) {
      advertiser?.stop()
      advertiser?.advertise("Formation $code", port)
    }
  }

  internal fun onSeekerNetworkStopped() {
    if (_network.value == null) return
    _network.value = null
    _address.value = initialAddress
  }

  init {
    client.checkpointCompletions { snapshot ->
      onCompletion(snapshot)
      keepShare(snapshot)
    }
    val soundEvents = SessionSoundEvents()
    scope.launch {
      client.snapshot.collect { s ->
        if (s == null) return@collect
        if (s.stage !is Stage.Won) keepShare(s)
        soundEvents.next(s)?.let(onSound)
        val won = s.stage as? Stage.Won
        if (host != null && won != null && won.seal.complete && won.unlock !is Unlock.Unlocked) {
          onSealed(PendingUnlock(s.formation.opportunity, won.seal, now()))
        }
      }
    }
  }

  internal fun unlockedElsewhere(receipt: UnlockReceipt) =
    host?.unlocked(receipt.signature, receipt.explorerUrl, receipt.paid)

  fun ready(on: Boolean) = client.ready(on)

  fun begin() = host?.begin()

  fun startNow() = host?.startNow()

  fun runItBack() = host?.runItBack()

  fun backToLobby() = host?.backToLobby()

  fun remove(player: PlayerId) = host?.remove(player)

  suspend fun unlock(): String? {
    val host = host ?: return "Only the Seeker can unlock"
    val snapshot = host.snapshot.value
    val won = snapshot.stage as? Stage.Won ?: return "Nothing to unlock yet"
    if (!won.seal.complete) return "Everyone must seal the Formation first"
    host.unlocking()
    return unlockWin(PendingUnlock(snapshot.formation.opportunity, won.seal, now()))
      .fold(
        onSuccess = { null },
        onFailure = {
          val message = it.message ?: "The unlock didn't go through"
          host.unlockFailed(message)
          message
        },
      )
  }

  private fun keepShare(snapshot: SessionSnapshot) {
    val won = snapshot.stage as? Stage.Won ?: return
    val unlocked = won.unlock as? Unlock.Unlocked
    val me = client.me.value ?: return
    val opportunity = snapshot.formation.opportunity
    val share = won.seal.roster.firstOrNull { it.player == me }
    val mine = snapshot.player(me)
    val ticket =
      when {
        share != null ->
          ClaimTicket(
            opportunity = opportunity.id,
            challenge = opportunity.challenge,
            host = snapshot.formation.host,
            amount = share.amount,
            index = share.index,
            root = won.seal.root,
            proof = Sealing.proof(won.seal, me).orEmpty().map { it.toHex() },
            earnedAt = now(),
            unlockReceipt = unlocked?.receipt ?: "",
            wallet = share.wallet,
            claimedTo = share.wallet?.takeIf { unlocked != null && me in unlocked.paid },
            claimReceipt = unlocked?.receipt?.takeIf { me in unlocked.paid },
            unlocked = unlocked != null,
          )
        mine?.seeker == true && unlocked != null ->
          ClaimTicket(
            opportunity = opportunity.id,
            challenge = opportunity.challenge,
            host = snapshot.formation.host,
            amount = won.seal.ownerAmount,
            index = -1,
            root = won.seal.root,
            proof = emptyList(),
            earnedAt = now(),
            unlockReceipt = unlocked.receipt,
            // The Seeker's share goes straight to its wallet when it unlocks.
            claimedTo = seeker()?.wallet,
            claimReceipt = unlocked.receipt,
          )
        else -> null
      }
    ticket?.let(ledger::keep)
  }

  internal fun end() {
    host?.close()
    if (host == null) client.leave()
    scope.launch {
      delay(600)
      advertiser?.stop()
      server?.stop()
      scope.cancel()
    }
  }
}
