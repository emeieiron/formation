# Game discovery

Home exposes installed games independently of reward funding. Players can learn the controls before a Seeker hosts a Formation, including when the phone has no wallet, no rewards, or no internet connection.

## Implementation

- Populate the Games section from `ChallengeCatalog.all`; new registered modules appear automatically.
- Use a horizontal card deck with a focused card, neighboring-card preview, and a compressed stack behind the selection. Swipes snap to a game; arrow controls provide the same selection without dragging. Tapping a neighboring card selects it; tapping the focused card opens its preview.
- Keep metadata, supported player counts, instructions and optional interactive introductions available to guests. Games own their optional cover artwork; Home owns layout and navigation.
- Label every card: Playable for a linked host with a valid reward, No reward for a linked host without one, and Join to play for guests. Use the same reward filter for labels and preview options; refresh availability at reward expiry while Home remains open.
- Show the selected game's existing instruction steps beneath the deck. Selection updates the panel with a brief fade; longer instructions remain vertically scrollable with Home.
- Show matching funded rewards in the preview only for a linked host. Reject expired, settled, unsupported, and unrelated rewards; recheck the current selection before opening the existing reward sheet.
- Keep the current reward sheet, session setup, signing, and settlement flow. Browsing does not create a reward or start a game.
- Keep funded reward selection inside the game's preview. Home has no separate funding shelf. The debug identity is labelled Test host beside Games so it is distinct from simulated reward funding.

## Verification plan

Build Android, run the focused reward-selection test, compile the shared iOS target, and run release lint. On emulators, inspect guest and empty-host Home, both previews, a compact display with larger text, and a matching funded reward opening the existing hosting sheet. Restore temporary preferences and display overrides afterward. Record executed checks below.

## Initial catalogue checks

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

## Card deck checks

Verified on 4 October 2026 with the updated debug APK installed on both Android emulators.

From `app`:

```sh
./gradlew :shared:testAndroidHostTest :challenges:api:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:lintRelease \
  :shared:compileKotlinIosSimulatorArm64
```

All 35 shared and two challenge API host tests passed. Android assembly, release lint and shared iOS compilation passed. The APK contains both module-owned cover resources. The original 1024 × 600 artwork and its editable Blender source are documented in [Cover artwork](../assets/covers/README.md).

| Scenario | Observed result |
| --- | --- |
| Guest without a wallet, Seeker identity or rewards | Home presents both registered games through the deck. |
| Swipe and arrow selection | Both gestures select and snap to the next game; the position indicator updates. |
| Card selection and preview | A neighboring card selects the game. The focused card opens its existing preview; closing it preserves the selection. |
| 360 × 640 dp display, 130% text size | Titles, artwork, player counts and arrow controls fit. Nearby and its join controls remain reachable by vertical scrolling. |
| Matching funded reward | Ricochet's preview offers its 120 SKR fixture and excludes Overdrive's 180 SKR fixture. The existing reward sheet reaches Ricochet's lobby. |
| Cleanup | Temporary preferences, reward history, display overrides and font scale were restored and checked. |

The tested emulators use `skiagl`. Partial frame updates caused unchanged Home regions to disappear after animation while the accessibility tree remained intact. Disabling `debug.hwui.use_partial_updates` and relaunching restored complete frames. The emulator installer applies this development setting when that renderer is active; the APK does not change device rendering settings.

Hosting checks used simulated fixtures and stopped at the lobby. No settlement was performed during this visual pass.

| Overdrive | Ricochet | Compact display |
| --- | --- | --- |
| ![Overdrive selected in the Home card deck](images/game-deck-overdrive.png) | ![Ricochet selected with Overdrive stacked behind it](images/game-deck-ricochet.png) | ![Card deck at 360 by 640 dp with 130 percent text size](images/game-deck-compact.png) |

## Availability and instruction panel checks

Verified on 4 October 2026 with the updated debug APK installed on both Android emulators.

From `app`:

```sh
./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug \
  :androidApp:lintRelease :shared:compileKotlinIosSimulatorArm64
```

All 35 shared host tests passed, including reward selection at expiry, unsupported player counts and settled rewards. Android assembly, release lint and shared iOS compilation passed. The four existing Python driver and chain-verification unit tests passed. The updated journey driver selects the game through the deck and chooses a matching duo reward in its preview.

| Scenario | Observed result |
| --- | --- |
| Linked test host without rewards | Every card shows No reward. Home has no funded shelf or empty-funding section. |
| Valid Ricochet reward and expired Overdrive reward | Ricochet shows Playable; Overdrive shows No reward. |
| Reward expires while Home remains open | Ricochet changes from Playable to No reward without relaunching or manually refreshing. |
| Guest without a wallet or Seeker identity | Cards show Join to play; instructions remain available. |
| Selection changes | The instruction panel switches between the games' existing steps beneath the deck. |
| 360 × 640 dp display, 130% text size | Cards and labels fit. All instruction rows wrap and remain readable; Scan QR and Code are reachable by vertical scrolling. |
| Updated hosting driver | Ricochet's selected card opens its preview, its funded duo reward opens the existing reward sheet, and Start Formation reaches the lobby. |
| Cleanup | Temporary preferences, reward history, display overrides and font scale were restored and checked. |

The expanded motion check reproduced missing unchanged regions with the emulator's host GPU backend under both OpenGL and Vulkan UI rendering. Disabling partial updates alone was insufficient. Cold-starting with `-gpu swiftshader -no-snapshot-load` preserved the app's full layout through repeated deck and instruction transitions. The APK does not set rendering properties; the emulator installer retains its existing partial-update setting.

Hosting used explicit simulated reward fixtures and stopped at the lobby. This pass did not perform settlement or verify physical-device rendering.

| No funded reward | Playable game | Compact instructions |
| --- | --- | --- |
| ![Unfunded host with No reward labels and selected-game instructions](images/game-availability-empty.png) | ![Ricochet marked Playable with instructions beneath the deck](images/game-availability-playable.png) | ![All three Ricochet instruction rows readable at 130 percent text size](images/game-availability-compact.png) |

## Follow-up

Reward-free practice rounds, playable Overdrive tutorials, and funding-management UI are separate features. The catalogue explains installed games; a shared attempt still needs an existing funded reward and the normal Formation flow.
