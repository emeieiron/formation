# Overdrive verification

Executed on 3 October 2026. Overdrive is registered as `overdrive`, vault code `6`, format version `1`, for exactly two players.

## Rules and integration

- All 124 Android host tests passed across 31 suites. Nine focused Overdrive tests cover paired scoring, shared misses, rejected inputs, deadline boundaries, private targets, deterministic replay, all difficulty presets, and per-player shape-match acceleration.
- The session test checks both opening and updated frames through the real host/client harness. Each recipient receives its projected state; the other games' default shared-state behavior remains available.
- Each correct shape catch increments that phone's match count. Its next pulse becomes faster even if its partner missed. An incorrect catch leaves its speed unchanged. A catch cannot change another pulse's deadline while it is falling.
- A focused Python regression test passed for parsing the current clue when the next-shape preview is present. An earlier driver failure came from treating the preview as the current clue.

## Builds

Android assembly and shared iOS simulator compilation passed:

```sh
cd app
./gradlew :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64
```

Rule tests run with `:challenges:overdrive:testAndroidHostTest`; the complete host suite runs with `testAndroidHostTest`. The rule module was cleaned and rebuilt after a stale Kotlin incremental-compilation cache failed during the model change. No compiler configuration change was needed.

## Native two-phone journey

The final Android build passed the simulated journey on `emulator-5554` and `emulator-5556`:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/overdrive.json" \
  scripts/e2e.py --title Overdrive --code 6 --chain simulated \
  --layout android --driver overdrive --no-build
```

The driver reads each phone's visible partner clue and taps the other dial. It completed 12 shared waves with zero misses, collected the seals, and unlocked the explicit 120 SKR simulated fixture. The host received 60 SKR; the wallet-free helper retained a 60 SKR claim-later entitlement. No production autopilot, hidden-answer reveal, or win bypass was added.

The host used its normal 448 dp display. The helper used 320 × 640 dp with 130% text size. Play screenshots were visually inspected: the counters, partner clue, pulse, dial, and controls remained visible. Lobby and outcome content outside the viewport was reached by scrolling. The helper's original resolution and text size were restored afterward.

The Android layout adapter starts the CLI's instrumentation server and uses its bundled protocol and serializer from a persistent Java process. This avoids restarting the runtime for each reflex-game observation. Repeated local reads measured approximately 16–20 ms after initialization. The adapter requires an installed Android CLI and JDK; `ANDROID_CLI_JAR` can select its `main.jar` explicitly. It is test tooling and is not included in the app.

Screenshots are saved under the ignored `program/target/e2e` directory: `seeker-overdrive-play.png`, `guest-overdrive-play.png`, `seeker-won.png`, and `guest-won.png`. The journey restores the previous ledger, wallet, and reward-fixture preferences; it retains the test's completed history and claim records.

## Remaining checks

Human communication and difficulty tuning need playtesting with two people. Physical Wi-Fi reliability, interrupted Overdrive sessions, real Seeker wallet signing, on-chain code-6 rewards, and iOS device play were not verified in this run. The existing session-hardening tests and documentation cover the shared infrastructure; this journey uses simulated settlement.
