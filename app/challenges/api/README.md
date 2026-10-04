# Game integration

Games supply rules, serializable state and inputs, metadata, and a Compose stage. The app owns discovery, admission, readiness, clocks, session lifecycle, sealing, and rewards. Overdrive and Ricochet are registered games; their rules and stages live in `challenges/overdrive` and `challenges/ricochet`.

## Add a module

Create `app/challenges/<game>/build.gradle.kts`:

```kotlin
plugins { id("formation.challenge") }
```

The convention plugin provides the shared API, Compose, serialization, and multiplatform targets. Put production code in `src/commonMain/kotlin` and rule tests in `src/commonTest/kotlin`.

Add `include(":challenges:<game>")` to `app/settings.gradle.kts` and the corresponding dependency to `app/shared/build.gradle.kts`. Import the game's entry point in `app/shared/.../state/Catalog.kt` and add it to the registry's `challenges` list.

Registration supplies ID lookup, vault-code lookup, player-count checks, and advertised format versions. It also adds the game to Home's Games section using its metadata and instructions, independently of reward funding. No game-specific switch or separate catalogue registration is needed in the app.

## Implement the contracts

| Contract | Responsibility |
| --- | --- |
| `Challenge<S, I>` | Bind metadata, serializers, rules, and stage UI |
| `ChallengeInfo` | Stable ID and vault code, title, instructions, icon, accent, and supported player counts |
| `ChallengeGame<S, I>` | Evaluate inputs and time; expose authoritative state and status |
| `ChallengeSetup` | Frozen roster, Seeker, difficulty, random seed, start time, and each phone's available inputs |
| `StageScope<S, I>` | Render state, identify players, read sensors and the shared clock, provide haptics/audio, and send typed inputs |

Implement `newGame(setup)`, `stateSerializer`, `inputSerializer`, `goal(players, difficulty)`, and `Stage(scope)` on the entry point. Role descriptions and debug `autopilot()` are optional.

Override `introduction: (@Composable () -> Unit)?` for a brief interactive explanation in the existing briefing and catalogue preview. Its default is `null`, which retains the metadata's normal instructions. Keep preview state local: it must not submit gameplay inputs, score a result, or add a readiness step. Ricochet uses this slot to demonstrate paddle aiming with its shared reflection rules.

Override `cover: (@Composable () -> Unit)?` to supply decorative artwork for the Home card. Fill the provided slot without adding controls, inputs or session work. The default uses the metadata icon, so artwork is optional. Overdrive and Ricochet keep their Blender renders in their own modules; the app contains no game-specific artwork selection. A module using Compose resources must enable Android resources and declare its resource dependency, as these two modules do.

Rules run sequentially on the host. Use `setup.seed` for randomness and the supplied `now` for timing. Keep sensor access, UI, networking, and wall-clock reads outside the rules. This makes outcomes reproducible in focused rule tests.

The stage reads `scope.state` and sends `I` through `scope.send()`. Use `scope.sensors` for typed channels, `scope.motion` for processed pose and gestures, and `scope.clock` for host time. `rememberHostNow`, `rememberPose`, `OnGesture`, and `onTouchDown` are focused helpers; stage presentation remains under the game's control.

For short gameplay sounds, call `scope.audio.play(GameCue.CloseCall)` or another semantic `GameCue`. The optional `StageAudio` contract defaults to silence. The app owns assets, native playback, the persisted mute setting, and foreground/volume gates. Deduplicate authoritative event IDs in the stage and discard stale events; bind repeating cues to the stage's lifetime. Audio is feedback, not a clock or an input requirement. Ricochet's `FeedbackEvents` demonstrates impact selection and a bounded last-life cadence.

Return `GameStatus.Won` or `GameStatus.Lost` to finish an attempt. The framework then handles the shared result, signatures, persistence, and settlement. A game does not construct reward transactions or manage claim keys.

For asymmetric information, override `ChallengeGame.stateFor(player)` to remove that player's hidden answers. The host evaluates the full `state` but serializes each player's projected view separately, including the opening frame. The default shares the full state for games with no private information. Do not publish random seeds or future answers that reconstruct a hidden target.

## Declare sensor requirements

`requiredSensors(players)` returns typed `SensorRequirement` values. The framework prepares them and gates play on usable readings. For example:

```kotlin
override fun requiredSensors(players: Int) = listOf(
  SensorRequirement(InputCapability.TILT, allowSimulated = true),
)
```

Simulation is accepted only when both the requirement and the debug emulator environment allow it. Set `allowEstimated = false` when the game cannot use estimated input. The hub retains source and quality metadata.

Use `optionalSensors(players)` for inputs with a supported fallback. Select optional tasks from `setup.capabilities[player]`, whose values are the stable IDs on `InputCapability`. If a task depends on an optional input, report it through `activeSensors(state, player, players)` so losing that input interrupts the attempt. The default reports all required inputs.

Channels share acquisition and release it when collection stops. Collect them in effects bound to the stage's lifetime; do not create platform listeners inside a game. Availability and readiness do not attest hardware or gameplay.

## Preserve compatibility

Use a new game ID and a previously unused positive vault code. Codes `1..5` and IDs `rally`, `circuit`, `sync`, `formation`, and `rush` are retired. The app registry rejects their reuse because old rewards and saved records retain those identities.

Supported player counts must stay within `2..32`. Vault codes fit `1..65535`. Increment `formatVersion` when rules, input/state serialization, or required capabilities change incompatibly; admission rejects mismatched formats before play.

## Configure development rewards

The app does not invent rewards. A test can inject explicit `Opportunity` fixtures into `SimulatedLedger(store, fixtures)` and pass that ledger to `AppGraph`. Fixtures initialize fresh simulated storage; settled rewards do not reappear after relaunch.

For the emulator journey or chain provisioning scripts, set `FORMATION_REWARDS` to a JSON file containing fixtures for registered games. An illustrative record is:

```json
[
  {
    "challenge": "my-game",
    "code": 6,
    "amount": 120,
    "players": 2,
    "owner_bps": 5000,
    "difficulty": 0,
    "days": 1,
    "title": ""
  }
]
```

`amount` is whole SKR; difficulty `0..3` corresponds to Easy, Normal, Hard, and Extreme. Use the ID and code assigned to the actual game. Local validator setup creates no rewards without a fixture file. Testnet reward creation requires one.

After implementing duo support and an optional autopilot, run the journey with the game's visible title and code:

```sh
FORMATION_REWARDS=/absolute/path/rewards.json \
  scripts/e2e.py --title "My game" --code 6 --chain simulated
```

For asymmetric games, use an external driver that coordinates visible clues across the phones. Overdrive provides `--driver overdrive --layout android` and a fixture in `scripts/fixtures/overdrive.json`. A local autopilot should not receive hidden answers just to make a journey pass.

Ricochet provides public-state debug assistance and `scripts/fixtures/ricochet.json`, registered with code `7`. Use `--driver manual` to prepare a duo and operate its controls yourself. The default autoplay driver closes the debug control panel before capturing the playfields; assisted play retains its visible tag.

The journey defaults to testnet; `--chain simulated` explicitly selects an isolated simulation. Localnet and simulation use the supplied fixture file. Testnet uses an open reward or provisions the configured fixtures through the existing test authority. Chain runs verify the configured mint, committed roster, confirmed signatures, and exact token changes for paid shares. Test tokens do not represent mainnet SKR value. The journey restores ledger, wallet, and reward-fixture preferences afterward. Human play, physical sensor quality, and network interruptions still need device testing.
