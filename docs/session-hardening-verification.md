# Session hardening verification

Verified on 2026-10-01. The onboarding, host/join sequence, challenge rules and reward split remain intact. The wire protocol is now version 3; both participating apps need this version.

## Automated checks

- 129 host tests across 35 suites passed, including the existing challenge, crypto, vault contract and UI sound tests.
- Added failure-boundary checks for lost submission responses, uncertain confirmation, block-height expiry, confirmation at the expiry boundary, observed but unconfirmed submissions, independently tracked payout batches, partial seals across restart, corrupted acknowledgements, storage failure, signed admission replay/tampering, format/capability rejection, stale clock samples, bounded beacon probing, claim expiry, encrypted recovery tampering and interrupted restore, monotonic completion records, and bounded diagnostics.
- Android debug assembly and iOS simulator compilation passed. The Kotlin incremental compiler cache failed during two intermediate runs; verification succeeded with incremental compilation disabled, followed by successful normal builds.

Commands used:

```sh
cd app
./gradlew testAndroidHostTest :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64
```

## Emulator checks

- Two emulators completed the simulated Sync journey: discovery, signed admission, briefing, synchronized play, win, verified sealing, unlock and reward display.
- Reopened a stored partial completion on the host, then reconnected the guest. Both phones saved all acknowledgements while retaining the exact original roster and commitment. This used a reversible partial-checkpoint injection into an existing signed simulated win; it does not claim to reproduce the timing of a physical radio interruption at victory.
- Injected an unreadable encrypted key record. The Android app displayed recovery, retained the public identity and did not replace the damaged secret. The recovery screen was inspected visually.
- Inspected the saved local diagnostic traces. The schema accepts only fixed event codes and bounded numeric measurements, with a 256-event limit and explicit export/clear controls.
- Used Android CLI for launch, UI inspection and capture. The existing journey's UI Automator dump could not reliably inspect the animated Home screen; a temporary Android CLI layout adapter completed the same journey. No harness workaround changes were added to the application.
- Restored the emulators' original app preferences and encrypted secret records after testing. No real funds were submitted.

## Remaining device checks

Physical phones are required to verify hotspot opening/cancellation, permission denial, ordinary Wi-Fi versus local-only hotspot discovery, changes between networks, sensor availability and recovery after real process/radio interruptions. A local-only hotspot has no internet; settlement requires a later internet connection. Clock readiness currently requires a sample no older than 10 seconds and a round trip of at most 500 ms; those limits should be calibrated with physical-device traces.

Live wallet/chain recovery after a dropped submission response remains a device integration check. Its state transitions and retry semantics are covered with fake transports; the emulator journey uses simulated rewards.

Encrypted export/import is implemented for Android with PBKDF2-HMAC-SHA256 and AES-256-GCM. The existing iOS shell lacks a secure recovery provider and exposes recovery as unavailable. iOS compilation is compatibility evidence, not proof of feature parity or readiness for real rewards.
