# Apex red UI implementation

## Objective

Apply the approved black, red, and white direction to Formation's app shell. Keep challenge implementations replaceable and preserve the existing onboarding, discovery, hosting, joining, readiness, sealing, and claiming flows.

## Phase 1 — shared foundation

- Pitch-black background, opaque graphite surfaces, red actions, white reward figures, and independent warning/success colors.
- Bundled Barlow Condensed headings and figures; retain Inter for readable body text and Space Grotesk for the wordmark.
- Six-dp control/card corners, twelve-dp sheet corners, consistent borders and twelve-dp action gaps.
- Custom controls with restrained press travel, finite transitions, visible selection, and existing accessibility semantics.
- Import the approved Blender assets with their editable source and attribution.
- Verify compilation, then commit the foundation separately.

## Phase 2 — discovery and rewards

- Establish the shared livery rule, large greeting, and clear section hierarchy.
- Nearby formations emphasize the host, joined count, reward, and Join action. Challenge information stays secondary and factual.
- Rewards use large figures and pass-like cards with actual ticket state, payout details, and existing claim actions.
- Retain empty, loading, error, offline, expired, and simulated-ledger states.
- Verify compilation and commit the screens separately.

## Phase 3 — onboarding and profile

- Apply the new identity, headings, square player plates, and aligned selection treatment.
- Retain the two guest onboarding steps and optional Seeker linking step, name validation, Light IDs, draft editing, and saved profile behavior.
- Preserve developer controls and wallet/Seeker information.
- Verify compilation and commit separately.

## Phase 4 — lobby and session shell

- Replace the lobby progress ring with a roster of player plates and connections. Animate real arrivals once; support every supported roster size and disconnection state.
- Preserve host QR/code/share/network/Begin actions, guest waiting state, profile editing, and wallet options.
- Carry the shared design into briefing and outcomes without changing challenge play mechanics or readiness/sealing/unlock behavior.
- Use finite, directional navigation and sheet transitions that honor Compose's platform motion scale.
- Verify compilation and commit separately.

## Acceptance and verification

- Build the Android debug app and run the existing host unit tests.
- Use the Android CLI skill for emulator installation, layout inspection, screenshots, and interaction.
- Inspect onboarding, Home, profile, Rewards, claim sheet, host/guest lobby, briefing, and outcome. Exercise the existing two-device simulated-ledger end-to-end journey.
- Check compact width, long names, larger system text, keyboard/sheet constraints, and disabled animations. Correct observed layout issues in a separate verification commit if needed.
- Keep generated screenshots and test output out of the application bundle. Record results and any unverified platform coverage here.

## Flow boundaries

No new navigation destinations, readiness steps, account requirements, wallet requirements, challenge rules, network protocol, ledger behavior, or real financial transactions are introduced by this refresh. All displayed rewards, participants, and statuses come from current app state.

## Implementation and verification results — 1 October 2026

All four implementation phases are committed separately on `codex/apex-red`. A final polish commit addresses issues found during emulator inspection: launcher consistency, long unbroken names, redundant card spacing, and empty-slot border/connection alignment.

- Android debug compilation passes. The existing Android host tests pass: 103 tests across 26 suites, with no failures, errors, or skipped tests.
- The existing two-device simulated-ledger journey passes on `emulator-5556` and `emulator-5554`: guest onboarding, nearby discovery, joining, readiness, autoplay, sealing, unlocking, and the guest's recorded share.
- Separate UI checks cover Rewards, ticket state, the claim sheet's four actions, simulated claim completion, profile editing, host setup, QR enlargement, and joining by code. No real reward transaction was submitted.
- At 320-dp width and 130% system text scale, the Light picker stays aligned, the full 20-character unbroken name fits the Home greeting, join controls adapt, and claim-sheet actions remain accessible while the body scrolls.
- With the platform animation scale set to zero, guest removal and the end-Formation sheet settle into their final state. With animations enabled, the lobby recording shows the guest arrival and connection activation. Motion is finite and driven by actual state changes.
- Emulator display and animation settings were restored, the guest's pre-check preferences were restored, and the final build was installed on both emulators.

Coverage limits: participant arrival was exercised with two devices. The roster implementation accommodates additional rows, but three-or-more-participant layouts and retained disconnected-player snapshots have not been exercised on devices. iOS, physical Seeker/Seed Vault integration, external wallets, and real-chain claims remain unverified by this UI pass. Challenge rules and stage implementations remain outside the redesign scope.

The editable Blender source and font license are in `assets/apex`; the app bundles only the two PNG renders and the required font files. Captures and test logs remain outside the app bundle.

## Discovery refinement — 1 October 2026

Replaced the nearby and code-search circular indicators with a shared custom `DiscoverySignal`: three staggered tracks carrying red pulses with white angled tips. The pulses fade at the track ends for a continuous reset. This expresses active discovery without displaying completion progress; scanning and joining behavior are unchanged.

The animation uses Compose's frame clock and platform motion scale. Animation values are read during drawing rather than recomposing the status text. The decorative signal adds no duplicate accessibility announcement; the adjacent search message remains readable with motion disabled.

Android debug compilation passes. Home and code search were visually checked at normal size and at 320-dp width with 130% text scale. Two captures with animations disabled confirmed the same static signal. Display and animation settings were restored after verification.
