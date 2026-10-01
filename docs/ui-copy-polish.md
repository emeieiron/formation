# App copy and visual hierarchy polish

Reduce repeated explanations in the general app. Show occupancy and reward state once, retain explicit action labels, and preserve essential wallet, permission, recovery, and error information. Challenge rules and implementations remain outside this pass.

## Phases

1. Establish shared Compose string resources for the reduced labels, accessibility names, and plural forms. Brand names and user data stay separate from translated sentences.
2. Simplify Home and reward cards: names instead of greetings, concise search state, numeric occupancy, and one reward status. Preserve every hosting, joining, and claim action.
3. Simplify onboarding and the lobby: finite visual introduction, concise profile fields, roster occupancy, and a single waiting state. Preserve editing, network setup, readiness, and challenge instructions.

Commit each phase separately after compilation. Check the final Android app with the Android CLI skill, including compact width and larger system text. Run the existing host tests and two-device simulated journey. Record verification results here.

## Localization scope

This pass creates the default English resource catalog for the touched surfaces; it does not add translated languages or claim complete app localization. Wallet/error text and challenge content outside the touched surfaces still need a later resource migration. Actions with different effects keep distinct names, and reward readiness is separate from group readiness so translators can handle context correctly.

Numbers, dates, font coverage, and RTL layout require their own locale verification. Removing a visible label never removes its accessibility meaning.

## Implemented

- Home shows the player's name, a concise search state, numeric capacity, and short reward labels. Host names are displayed as data rather than inserted into possessive English sentences.
- Reward cards show one status header. Claim availability, payout amounts, destination information, and wallet acquisition actions retain their existing behavior.
- Onboarding illustrates Join → Play → Claim with a finite animation and the existing player plate and reward pass assets. Profile entry retains name validation and light selection.
- The lobby shows occupancy and the actual roster. Profile editing is available in the top bar; QR enlargement, sharing, network setup, readiness, and challenge instructions remain available.

## Verification — 2026-10-01

- Android debug compilation passed after all UI changes and resource cleanup.
- `testAndroidHostTest`: 103 tests in 26 suites passed, with no failures, errors, or skipped tests.
- `python3 scripts/e2e.py --chain simulated --seeker emulator-5556 --guest emulator-5554 --no-build` passed: fresh guest onboarding, nearby discovery, joining, host Begin, both readiness controls, Sync autoplay, unlocking, and the guest's saved share. This checks existing game flow without redesigning it.
- Visually inspected the introduction, Home search state, and host lobby at the emulator's normal width. Inspected introduction and profile entry at 320 dp with 130% system text and animations disabled. The profile content scrolls while its action remains reachable.
- Inspected the lobby, reward cards, and claim sheet at 320 dp with 130% text. Corrected a crowded reward total by giving the amount full width when the decorative pass would compete with it; the complete amount and SKR unit were then visually verified. All four no-wallet claim actions remained visible.
- Opened profile editing through the lobby's top bar, selected a different light, saved it, and verified the stored selection.
- The E2E reader now discards its previous UI dump before reading a new screen. This prevents a failed dump from silently returning an earlier screen; onboarding also verifies field focus before typing.
- Restored both emulators' original preference entries exactly, verified them against the backups, and restored display size, font scale, and animation settings. The final debug build is installed on both devices.

Verification is limited to Android emulators and simulated rewards. It does not cover iOS, physical-device networking, real wallet transactions, every challenge format, translated languages, or RTL layouts.
