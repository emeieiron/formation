# Ricochet

Ricochet turns two phones into one cooperative arena. Each player controls an outer paddle, keeps a shared pulse moving between screens, and aims returns at six breakable targets. The game is isolated in `app/challenges/ricochet`; Formation handles the surrounding session and reward flow.

## Implemented behaviour

| Property | Behaviour |
| --- | --- |
| Identity | `ricochet`, vault code `7`, format version `3` |
| Players | Exactly two distinct players; join order assigns left and right |
| Objective | Clear all six targets before the difficulty's deadline |
| Shared lives | Three missed returns end the attempt |
| Controls | Tap or drag vertically anywhere in your arena to position the paddle |
| Release | The paddle retains its last position |
| Aiming | A central contact returns the pulse almost horizontally; an edge contact angles it up or down |
| Momentum | Alternating catches multiply speed by 1.12, capped at 1.9 times the serve speed |
| Charge | Two alternating exchanges arm a pulse that passes through one target without reflecting |
| Introduction | An interactive aiming field in the existing briefing; moving its paddle changes the predicted return |
| Miss recovery | Preserve cleared targets and paddle positions, then serve again after 1.2 seconds |
| Sensors | None required |
| Result | The existing completion, sealing, persistence, and settlement flow |

Three targets occupy each half. The seed determines their row ordering, small vertical offsets, and initial serve direction. The first paddle catch establishes the returning player. Each following catch by the other player builds momentum. A catch by the same player after a target rebound retains speed without increasing it; walls and targets also preserve speed. Six alternating exchanges reach the 1.9× cap. A miss resets momentum and charge while retaining cleared targets.

Charging gives a rally a distinct peak. After two alternating exchanges, the pulse gains a red ring and a shared rising cue. Its next target contact clears that target without changing direction, then consumes the charge. Later target contacts reflect normally. Two more exchanges can charge another return. The collision envelope remains unchanged; the brighter rendering does not enlarge the pulse.

## Difficulty

The arena is 2 units wide and 1.7 units high. Each phone draws one unit of its width. The settings below use logical units, so different screen sizes do not change collisions or reaction windows.

| Difficulty | Serve speed, units/second | Maximum speed | Paddle height | Deadline |
| --- | --- | --- | --- | --- |
| Easy | 0.72 | 1.368 | 0.34 | 60 seconds |
| Normal | 0.96 | 1.824 | 0.28 | 50 seconds |
| Hard | 1.18 | 2.242 | 0.23 | 45 seconds |
| Extreme | 1.40 | 2.660 | 0.19 | 40 seconds |

Easy is the recommended emulator preset. A paddle stays in place after release, allowing one operator to alternate between two emulator windows. Higher presets need faster corrections and are better evaluated with two people. Automated completion is a reachability check, not evidence that these settings are enjoyable.

The briefing's practice field uses the same paddle reflection and target-contact rules. Dragging its paddle changes a dotted trajectory; the target brightens when that return would hit it. The preview is local and cannot change scoring or submit game inputs. It occupies the existing scrollable briefing, with the ready action retained. Other games keep their normal instructions unless they supply an introduction.

## Authority and rendering

The host advances the arena in 10 ms steps using its supplied session time. Swept collision checks find contacts along the pulse's path rather than testing only its next position. A delayed tick therefore cannot make the pulse skip a target or paddle. Targets use an expanded rectangular contact envelope around their visual tile; this provides a small allowance at corners.

Paddle commands contain the rally, a monotonically increasing sequence, and an absolute vertical position. The host advances to the receipt time before accepting a position, clamps it within the arena, and ignores unknown players, non-finite coordinates, old sequences, and previous rallies. Commands do not carry a client-selected scoring timestamp. A client cannot move a paddle retroactively to repair a missed contact.

The stage responds to a touch immediately. It keeps the unacknowledged local position until the host catches up and limits ongoing drag submissions to roughly 30 per second, with immediate down and release submissions. A new rally reconciles against the host's current paddle. An accessibility range action can also set its position.

Pulse rendering predicts at most 100 ms beyond the latest frame using the published arena, momentum, and charge. Acceleration is applied at paddle contact within the shared swept simulation, so the remaining part of a step uses the new speed. Prediction stops when the frame is too old and never changes shared scoring. Both displays use the same global position and clip it to their assigned half; crossing the centre is ordinary movement, with no separate handoff message.

The stage uniformly scales its logical half to the available space. Short displays leave horizontal margins instead of changing the arena's proportions. Edge markers signal an approaching crossing. Physical bezels and different physical screen sizes still interrupt the visual line; the game does not infer device placement or require a calibration step.

## Feedback

Host impact events drive target fragments, paddle compression, rings, and shared miss flashes. A catch in the outer 15% of a paddle's half-height, including the pulse's contact allowance beyond its edge, is marked as a close call. It receives stronger compression and a sharper cue. A compact multiplier and six angled marks show momentum; the pulse trail lengthens as speed rises. The pulse centre and target positions remain stable during feedback.

Own-paddle catches, close calls, charging, piercing, target breaks, and misses use distinct short original sound cues and existing haptics. At 1.35× speed or above, ordinary catches use a higher cue. Both phones hear charging, piercing, target breaks, and misses; only the catching phone announces a return. Coalesced events select one cue, prioritizing misses, piercing, charging, then ordinary target breaks.

The last life adds a two-beat sound every 2.4 seconds during live play, a single light haptic, and matching border/life pulses. Recent impacts suppress an overlapping heartbeat. Stale frames, serve preparation, completion, and leaving stop this repeating feedback. Restored or repeated impact events stay quiet. All audio uses the existing Profile sound setting and native foreground/volume policies; no extra setting is required. Sound is secondary to visible state and never determines collision timing.

## Module boundaries

All paths below are relative to `app/challenges/ricochet/src/commonMain/kotlin/xyz/mcxross/formation/ricochet`.

| File | Responsibility |
| --- | --- |
| `Ricochet.kt` | Metadata, serializers, stage binding, roles, and optional debug assistance |
| `RicochetState.kt` | Serializable arena, impacts, and paddle commands |
| `Arena.kt` | Dimensions, seeded target placement, and difficulty settings |
| `Momentum.kt` | Serializable exchange history and capped speed multiplier |
| `Charge.kt` | Cooperative charging and one-target piercing state |
| `Collision.kt` | Swept target contact |
| `Physics.kt` | Travel, swept contact, reflection, acceleration, close calls, and escapes |
| `RicochetGame.kt` | Host timing, command admission, lives, serves, and terminal outcomes |
| `ReturnGuide.kt` | Public-state trajectory guidance for debug assistance |
| `ui/PaddleControl.kt` | Local touch prediction, throttling, and acknowledgement reconciliation |
| `ui/Presentation.kt` | Viewport mapping and bounded pulse prediction |
| `ui/RicochetArena.kt` | Canvas, touch gestures, and accessibility input |
| `ui/ArenaDrawing.kt` | Arena, target, and paddle geometry |
| `ui/PulseDrawing.kt` | Momentum trail and charge indicator |
| `ui/ImpactDrawing.kt` | Contact rings, fragments, and piercing streak |
| `ui/AimingModel.kt` | Local preview trajectory using shared reflection and collision |
| `ui/AimingIntroduction.kt` | Interactive briefing preview and accessibility input |
| `ui/RicochetHud.kt` | Shared targets, life marks, and countdown |
| `ui/MomentumReadout.kt` | Numeric speed multiplier and angled momentum marks |
| `ui/FeedbackEvents.kt` | Fresh event selection, deduplication, and danger cadence |
| `ui/RicochetFeedback.kt` | Stage-bound audio and haptic dispatch |
| `ui/RicochetStage.kt` | Stage layout, shared clock, and touch reconciliation |

`StageAudio` in the challenge API exposes semantic cues with a silent default. The app's `ChallengeAudio` adapter maps them to packaged resources and `SoundEffects`; a game does not own native players or preferences.

Registration uses the standard settings, dependency, and catalog entries. No Ricochet branch was added to session admission, discovery, sealing, wallets, or settlement. Future incompatible rule or wire-format changes must increment `formatVersion`; the ID and vault code remain stable.

## Demo on two emulators

Start two Android emulators. From the repository root, run:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/ricochet.json" \
  python3 scripts/e2e.py --title Ricochet --code 7 --chain testnet \
  --layout android
```

The journey builds and installs the debug app, connects the emulator session ports, joins the two phones, and enables their debug assistance. Testnet is the default chain. If no suitable reward is open, it provisions the explicit 120-token fixture through `scripts/testnet.py`, using the existing test authority and vault configuration. That authority needs testnet SOL and mint funding; see the [testnet setup](../README.md#testnet). Test tokens do not represent mainnet SKR value. The stage visibly marks assisted play as **AUTOPLAY**. Assistance reads public state and sends ordinary paddle commands; collisions and completion still run on the host.

To operate both paddles yourself, append `--driver manual`. The journey handles setup and waits for your game result before continuing through sealing and chain unlock. Add `--wallet ADDRESS` to bind the guest payout, or `--wallet connect` to connect an installed wallet. With neither, the helper share remains reserved for a later claim. Use `--no-build` after building the current APK to shorten repeated runs. The layout reader needs the Android CLI and a JDK, as described in the [project README](../README.md#verification).

The journey verifies the configured mint, unlocked vault state, committed roster, retained confirmed signatures, and exact token changes for paid shares. A verification failure is reported as a failure; it never substitutes a simulated receipt. Public verification records are saved under `program/target/e2e/settlement-<id>.json`. Signing material stays outside the repository and logs.

Use `--chain simulated` explicitly for an isolated run without chain transactions. Simulation is limited to debug selection; release builds ignore a saved simulated-ledger preference. The journey restores prior ledger, wallet, and fixture preferences afterward. Its test completion and earned-reward records remain in local history.

See the [current format 3 checks and screenshots](ricochet-escalation-verification.md), the [format 2 verification](ricochet-pressure-verification.md), the [initial implementation verification](ricochet-verification.md), and the [implementation phases and roadmap](ricochet-plan.md).

## Roadmap

The [plan's checklist](ricochet-plan.md#roadmap) is the current implementation roadmap. Rules, presentation, registration, and the repeatable emulator journey are complete. The next work is human play on physical phones: assess aiming, reaction time, screen continuity, and Wi-Fi latency before adjusting difficulty.

The aiming introduction, stronger difficulty curve, charged pulse, and testnet verification are implemented. Armoured targets or one predictable moving target are separate difficulty experiments. Optional tilt belongs behind the sensor module's availability checks with touch retained as a fallback. Additional players, multiple pulses, and competitive rewards need separate designs; they are outside this implementation.
