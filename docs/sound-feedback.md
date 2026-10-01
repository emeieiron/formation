# Sound feedback

Short original cues reinforce major moments in the general app. They are optional feedback, never a timing source or a requirement to understand the UI.

| Moment | Cue | Trigger |
| --- | --- | --- |
| Group assembled | Two mechanical contacts | First full, connected lobby roster |
| Begin | Low impact and rising resolve | New briefing after the host begins or retries |
| Formation complete | Fuller harmonic impact | Confirmed successful round |
| Reward unlocked or claimed | Latch and brighter resolve | Confirmed unlock snapshot or successful claim result |

Discovery, waiting, routine navigation, readiness toggles, and individual challenge actions remain quiet. A persistent Sound effects control lives in Profile. Muting stops the current cue and prevents queued requests from starting. Foreground checks drop background events; sound loading never queues stale cues or delays the app.

## Build phases

1. Add the shared sound contract, persisted preference, native playback, and original packaged assets. Compile Android and iOS and test the preference gate; commit the foundation.
2. Connect confirmed session milestones and claim results, deduplicate repeated snapshots, and expose the existing custom toggle. Test reconnection, mute, failed transactions, and retries; commit integration.
3. Run the Android emulator journey and playback checks, restore emulator data, and record verification in a separate commit.

## Assets and playback

`assets/sound/generate.py` authors four deterministic mono 48 kHz, 16-bit PCM WAVs using the Python standard library. Cues last 0.32–0.90 seconds, with shaped attacks, fading endpoints, and headroom. There are no recorded voices, music loops, downloaded samples, or challenge-specific dependencies.

Android uses preloaded [SoundPool](https://developer.android.com/reference/android/media/SoundPool) samples, at most one playing stream, media volume, and a ringer/communication-mode gate. Playback stops on Activity pause. iOS uses prepared AVAudioPlayer instances and the [ambient audio category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/ambient), which mixes with other audio and respects the Silent switch. It stops on resignation of active state. Native playback objects belong to the application and retain no screen or Activity.
