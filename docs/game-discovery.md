# Game discovery

Home exposes installed games independently of reward funding. Players can learn the controls before a Seeker hosts a Formation, including when the phone has no wallet, no rewards, or no internet connection.

## Implementation

- Populate the Games section from `ChallengeCatalog.all`; new registered modules appear automatically.
- Use compact custom cards with game metadata and supported player counts. Keep previews, instructions, and optional interactive introductions available to guests.
- Show matching funded rewards in the preview only for a linked host. Reject expired, settled, unsupported, and unrelated rewards; recheck the current selection before opening the existing reward sheet.
- Keep the current reward sheet, session setup, signing, and settlement flow. Browsing does not create a reward or start a game.
- Keep funding in its own Home section. The debug identity is labelled Test host so it is distinct from simulated reward funding.

## Verification plan

Build Android, run the focused reward-selection test, compile the shared iOS target, and run release lint. On emulators, inspect guest and empty-host Home, both previews, a compact display with larger text, and a matching funded reward opening the existing hosting sheet. Restore temporary preferences and display overrides afterward. Record executed checks in the verification commit.

## Executed checks

Verified on 4 October 2026 with the debug APK installed on two Android emulators.

From `app`:

```sh
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug \
  :androidApp:lintRelease :shared:compileKotlinIosSimulatorArm64
```

All 35 shared host tests passed, including the new reward-selection boundary test. Android assembly, release lint and shared iOS compilation passed. The four existing Python driver and chain-verification tests passed, and `scripts/e2e.py` compiled successfully.

| Scenario | Observed result |
| --- | --- |
| Guest without a wallet, Seeker identity or rewards | Both games appear on Home; previews expose instructions without a hosting action. |
| Linked test host without rewards | Both games remain visible; Funded rewards reports No locked rewards. |
| Offline guest with the Solana ledger selected | Home and Ricochet's aiming preview remain available with Wi-Fi and mobile data disabled. |
| 360 × 640 dp display, 130% text size | Both game cards fit the initial viewport. Both previews scroll; instructions and Done remain accessible. |
| Ricochet and Overdrive reward fixtures | Ricochet's preview offers its 120 SKR reward and excludes Overdrive's 180 SKR reward. Selection opens the existing reward sheet and reaches Ricochet's lobby. |
| Existing reward-shelf driver | The driver distinguishes funded reward cards from catalogue cards and reaches Overdrive's lobby through the horizontal shelf. |
| Cleanup | Selected preferences, reward history, display overrides and connectivity settings were restored; preference and font-scale restoration were checked. |

Hosting checks used explicit simulated fixtures and stopped at the lobby. This pass verifies discovery and routing; [Ricochet's settlement verification](ricochet-escalation-verification.md) records the separate testnet payout checks.

| Guest Home | Matching funded reward | Compact instructions |
| --- | --- | --- |
| ![Guest Home showing Overdrive and Ricochet in Games](images/game-catalog-home.png) | ![Ricochet preview offering its matching funded reward](images/game-catalog-funded.png) | ![Ricochet instructions and Done at 130 percent text size](images/game-catalog-compact.png) |

## Follow-up

Reward-free practice rounds, playable Overdrive tutorials, and funding-management UI are separate features. The catalogue explains installed games; a shared attempt still needs an existing funded reward and the normal Formation flow.
