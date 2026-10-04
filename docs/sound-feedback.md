# Sound feedback

Short original cues reinforce major moments in the general app. They are optional feedback, never a timing source or a requirement to understand the UI.

| Moment | Cue | Trigger |
| --- | --- | --- |
| Group assembled | Two mechanical contacts | First full, connected lobby roster |
| Begin | Low impact and rising resolve | New briefing after the host begins or retries |
| Formation complete | Fuller harmonic impact | Confirmed successful round |
| Reward unlocked or claimed | Latch and brighter resolve | Confirmed unlock snapshot or successful claim result |

Discovery, waiting, routine navigation, and readiness toggles remain quiet. Ricochet also uses short gameplay cues through the optional `StageAudio` interface: own returns, close calls, faster returns, charging, piercing, target breaks, misses, and a restrained last-life heartbeat. Its stage deduplicates event IDs and drops stale events. A persistent Sound effects control lives in Profile. Muting stops the current cue and prevents queued requests from starting. Foreground checks drop background events; sound loading never queues stale cues or delays the app.

## Build phases

1. Add the shared sound contract, persisted preference, native playback, and original packaged assets. Compile Android and iOS and test the preference gate; commit the foundation.
2. Connect confirmed session milestones and claim results, deduplicate repeated snapshots, and expose the existing custom toggle. Test reconnection, mute, failed transactions, and retries; commit integration.
3. Run the Android emulator journey and playback checks, restore emulator data, and record verification in a separate commit.

## Assets and playback

`assets/sound/generate.py` authors four deterministic mono 48 kHz, 16-bit PCM WAVs using the Python standard library. Cues last 0.32–0.90 seconds, with shaped attacks, fading endpoints, and headroom. There are no recorded voices, music loops, downloaded samples, or challenge-specific dependencies.

`assets/sound/gameplay.py` reuses those PCM helpers to author eight shorter gameplay cues, lasting 0.10–0.38 seconds. They use quieter mechanical contacts and a low two-beat danger cue. Charging adds a 0.24-second rising cue; piercing adds a brighter 0.20-second impact. All cues share the same preference and native playback policy. See the [Ricochet pressure verification](ricochet-pressure-verification.md) for the first six cues and the [format 3 verification](ricochet-escalation-verification.md) for charge and pierce playback.

Android uses preloaded [SoundPool](https://developer.android.com/reference/android/media/SoundPool) samples, at most one playing stream, media volume, and a ringer/communication-mode gate. Playback stops on Activity pause. iOS uses prepared AVAudioPlayer instances and the [ambient audio category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/ambient), which mixes with other audio and respects the Silent switch. It stops on resignation of active state. Native playback objects belong to the application and retain no screen or Activity.

## Verification — 2026-10-01

- Android debug assembly and iOS simulator Kotlin compilation passed. iOS playback has not been tested on a device or simulator.
- All 111 Android host tests passed across 28 suites. New tests cover mute persistence, a request muted before dispatch, unavailable playback, connected occupancy, repeated snapshots, connection/profile changes, briefing retries, failed unlocks, and coalesced win/unlock snapshots.
- The two-device simulated Sync journey passed. Debug-only native diagnostics confirmed successful decoding of all four assets and exactly one accepted playback of ASSEMBLED, BEGIN, COMPLETE, and REWARD on each phone.
- The custom Profile toggle saved mute, retained it across a cold launch, and re-enabled sound. Its normal and 320 dp / 130% text layouts were visually checked. A separate successful simulated claim produced one additional REWARD playback.
- WAV headers, silent endpoints, DC offset, and clipping headroom were checked. Packaged sound data totals 222,896 bytes. The preview uses the same cues in the table's order, at the native playback gain, with brief silence between them.
- Both emulators' original preference entries were restored exactly. Display size and font scale were restored, and media volumes were verified unchanged. Test claims used the simulated ledger; no real wallet transaction was performed.

Device speaker timbre, Bluetooth latency, and physical-device interruptions still require hardware verification. Sound remains secondary to the existing visible states and controls.
