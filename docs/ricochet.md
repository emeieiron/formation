# Ricochet

Ricochet turns two phones into one cooperative arena. Each player controls an outer paddle, keeps a shared pulse moving between screens, and aims returns at six breakable targets. The game is isolated in `app/challenges/ricochet`; Formation handles the surrounding session and reward flow.

## Implemented behaviour

| Property | Behaviour |
| --- | --- |
| Identity | `ricochet`, vault code `7`, format version `1` |
| Players | Exactly two distinct players; join order assigns left and right |
| Objective | Clear all six targets before the 60-second deadline |
| Shared lives | Three missed returns end the attempt |
| Controls | Tap or drag vertically anywhere in your arena to position the paddle |
| Release | The paddle retains its last position |
| Aiming | A central contact returns the pulse almost horizontally; an edge contact angles it up or down |
| Miss recovery | Preserve cleared targets and paddle positions, then serve again after 1.2 seconds |
| Sensors | None required |
| Result | The existing completion, sealing, persistence, and settlement flow |

Three targets occupy each half. The seed determines their row ordering, small vertical offsets, and initial serve direction. The pulse speed stays constant during a round; contacts change direction rather than adding acceleration. This keeps the first version's timing predictable while players learn aiming.

## Difficulty

The arena is 2 units wide and 1.7 units high. Each phone draws one unit of its width. The settings below use logical units, so different screen sizes do not change collisions or reaction windows.

| Difficulty | Pulse speed, units/second | Paddle height |
| --- | --- | --- |
| Easy | 0.62 | 0.34 |
| Normal | 0.82 | 0.28 |
| Hard | 1.04 | 0.23 |
| Extreme | 1.20 | 0.19 |

Easy is the recommended emulator preset. A paddle stays in place after release, allowing one operator to alternate between two emulator windows. Higher presets need faster corrections and are better evaluated with two people. Automated completion is a reachability check, not evidence that these settings are enjoyable.

## Authority and rendering

The host advances the arena in 10 ms steps using its supplied session time. Swept collision checks find contacts along the pulse's path rather than testing only its next position. A delayed tick therefore cannot make the pulse skip a target or paddle. Targets use an expanded rectangular contact envelope around their visual tile; this provides a small allowance at corners.

Paddle commands contain the rally, a monotonically increasing sequence, and an absolute vertical position. The host advances to the receipt time before accepting a position, clamps it within the arena, and ignores unknown players, non-finite coordinates, old sequences, and previous rallies. Commands do not carry a client-selected scoring timestamp. A client cannot move a paddle retroactively to repair a missed contact.

The stage responds to a touch immediately. It keeps the unacknowledged local position until the host catches up and limits ongoing drag submissions to roughly 30 per second, with immediate down and release submissions. A new rally reconciles against the host's current paddle. An accessibility range action can also set its position.

Pulse rendering predicts at most 100 ms beyond the latest frame using the published arena. Prediction stops when the frame is too old and never changes shared scoring. Both displays use the same global position and clip it to their assigned half; crossing the centre is ordinary movement, with no separate handoff message.

The stage uniformly scales its logical half to the available space. Short displays leave horizontal margins instead of changing the arena's proportions. Edge markers signal an approaching crossing. Physical bezels and different physical screen sizes still interrupt the visual line; the game does not infer device placement or require a calibration step.

Host impact events drive target fragments, paddle compression, rings, and shared miss flashes. Local catches, target clears, and misses use existing haptics. Formation's existing completion sound plays through the session flow; this version adds no per-contact audio or new sound setting.

## Module boundaries

All paths below are relative to `app/challenges/ricochet/src/commonMain/kotlin/xyz/mcxross/formation/ricochet`.

| File | Responsibility |
| --- | --- |
| `Ricochet.kt` | Metadata, serializers, stage binding, roles, and optional debug assistance |
| `RicochetState.kt` | Serializable arena, impacts, and paddle commands |
| `Arena.kt` | Dimensions, seeded target placement, and difficulty settings |
| `Collision.kt` | Swept target contact |
| `Physics.kt` | Travel, earliest contact, reflection, and escape detection |
| `RicochetGame.kt` | Host timing, command admission, lives, serves, and terminal outcomes |
| `ReturnGuide.kt` | Public-state trajectory guidance for debug assistance |
| `ui/PaddleControl.kt` | Local touch prediction, throttling, and acknowledgement reconciliation |
| `ui/Presentation.kt` | Viewport mapping and bounded pulse prediction |
| `ui/RicochetArena.kt` | Canvas, touch gestures, and accessibility input |
| `ui/ArenaDrawing.kt` | Arena geometry and impact animation |
| `ui/RicochetHud.kt` | Shared targets, life marks, and countdown |
| `ui/RicochetStage.kt` | Stage layout, shared clock, and haptic dispatch |

Registration uses the standard settings, dependency, and catalog entries. No Ricochet branch was added to session admission, discovery, sealing, wallets, or settlement. Future incompatible rule or wire-format changes must increment `formatVersion`; the ID and vault code remain stable.

## Demo on two emulators

Start two Android emulators. From the repository root, run:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/ricochet.json" \
  python3 scripts/e2e.py --title Ricochet --code 7 --chain simulated \
  --layout android
```

The journey builds and installs the debug app, connects the emulator session ports, supplies an explicit 120 SKR simulated fixture, joins the two phones, and enables their debug assistance. The stage visibly marks assisted play as **AUTOPLAY**. Assistance reads public state and sends ordinary paddle commands; collisions and completion still run on the host.

To operate both paddles yourself, append `--driver manual`. The journey handles setup and waits for your game result before continuing through sealing and simulated unlock. Use `--no-build` after building the current APK to shorten repeated runs. The layout reader needs the Android CLI and a JDK, as described in the [project README](../README.md#verification).

The journey restores prior ledger, wallet, and fixture preferences afterward. Its test completion and earned-reward records remain in local history. It does not seed production rewards or transfer real tokens.

See the [executed checks and screenshots](ricochet-verification.md), and the [implementation phases and roadmap](ricochet-plan.md).

## Roadmap

The [plan's checklist](ricochet-plan.md#roadmap) is the current implementation roadmap. Rules, presentation, registration, and the repeatable emulator journey are complete. The next work is human play on physical phones: assess aiming, reaction time, screen continuity, and Wi-Fi latency before adjusting difficulty.

After that validation, consider one moving target or barrier. Optional tilt belongs behind the sensor module's availability checks with touch retained as a fallback. Additional players, multiple pulses, and competitive rewards need separate designs; they are outside this implementation.
