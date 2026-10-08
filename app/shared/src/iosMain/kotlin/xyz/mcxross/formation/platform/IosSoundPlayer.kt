package xyz.mcxross.formation.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosSoundPlayer : SoundPlayer {
  private val players = mutableMapOf<SoundCue, AVAudioPlayer>()
  private var current: AVAudioPlayer? = null
  private val session = AVAudioSession.sharedInstance()
  private val inactiveObserver =
    NSNotificationCenter.defaultCenter.addObserverForName(
      UIApplicationWillResignActiveNotification,
      null,
      NSOperationQueue.mainQueue,
    ) {
      stop()
    }

  override suspend fun load(cue: SoundCue, bytes: ByteArray) {
    if (bytes.isEmpty() || !session.setCategory(AVAudioSessionCategoryAmbient, error = null)) return
    val data = bytes.usePinned {
      NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
    }
    val player = AVAudioPlayer(data = data, error = null)
    player.volume = 0.65f
    if (player.prepareToPlay()) players[cue] = player
  }

  override fun play(cue: SoundCue) {
    if (
      UIApplication.sharedApplication.applicationState !=
        UIApplicationState.UIApplicationStateActive
    )
      return
    val player = players[cue] ?: return
    stop()
    player.currentTime = 0.0
    if (player.play()) current = player
  }

  override fun stop() {
    current?.stop()
    current = null
  }
}
