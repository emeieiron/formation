# Apex red UI implementation

## Objective

Apply the approved black, red, and white direction to Formation's app shell. Keep challenge implementations replaceable and preserve the existing onboarding, discovery, hosting, joining, readiness, sealing, and claiming flows.

## Phase 1 — shared foundation

- Pitch-black background, opaque graphite surfaces, red actions, white reward figures, and independent warning/success colors.
- Bundled Barlow Condensed headings and figures; retain Inter for readable body text and Space Grotesk for the wordmark.
- Six-pixel control/card corners, twelve-pixel sheet corners, consistent borders and twelve-pixel action gaps.
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
