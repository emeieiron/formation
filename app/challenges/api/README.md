# Game integration

Games supply rules, serializable state and inputs, metadata, and a Compose stage. The app owns discovery, admission, readiness, clocks, session lifecycle, sealing, and rewards. The catalog currently contains no games.

## Add a module

Create `app/challenges/<game>/build.gradle.kts`:

```kotlin
plugins { id("formation.challenge") }
```

The convention plugin provides the shared API, Compose, serialization, and multiplatform targets. Put production code in `src/commonMain/kotlin` and rule tests in `src/commonTest/kotlin`.

Add `include(":challenges:<game>")` to `app/settings.gradle.kts` and the corresponding dependency to `app/shared/build.gradle.kts`. Import the game's entry point in `app/shared/.../state/Catalog.kt` and add it to the registry's `challenges` list.

Registration supplies ID lookup, vault-code lookup, player-count checks, and advertised format versions. No game-specific switch is needed in the app.

## Implement the contracts

| Contract | Responsibility |
| --- | --- |
| `Challenge<S, I>` | Bind metadata, serializers, rules, and stage UI |
| `ChallengeInfo` | Stable ID and vault code, title, instructions, icon, accent, and supported player counts |
| `ChallengeGame<S, I>` | Evaluate inputs and time; expose authoritative state and status |
| `ChallengeSetup` | Frozen roster, Seeker, difficulty, random seed, start time, and each phone's available inputs |
| `StageScope<S, I>` | Render state, identify players, read sensors and the shared clock, provide haptics, and send typed inputs |

Implement `newGame(setup)`, `stateSerializer`, `inputSerializer`, `goal(players, difficulty)`, and `Stage(scope)` on the entry point. Role descriptions and debug `autopilot()` are optional.

Rules run sequentially on the host. Use `setup.seed` for randomness and the supplied `now` for timing. Keep sensor access, UI, networking, and wall-clock reads outside the rules. This makes outcomes reproducible in focused rule tests.

The stage reads `scope.state` and sends `I` through `scope.send()`. Use `scope.sensors` for typed channels, `scope.motion` for processed pose and gestures, and `scope.clock` for host time. `rememberHostNow`, `rememberPose`, `OnGesture`, and `onTouchDown` are focused helpers; stage presentation remains under the game's control.

Return `GameStatus.Won` or `GameStatus.Lost` to finish an attempt. The framework then handles the shared result, signatures, persistence, and settlement. A game does not construct reward transactions or manage claim keys.

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

After implementing duo support and an autopilot, run the existing journey with the game's visible title and code:

```sh
FORMATION_REWARDS=/absolute/path/rewards.json \
  scripts/e2e.py --title "My game" --code 6 --chain simulated
```

The journey explicitly seeds simulated rewards and restores prior emulator preferences afterward. Localnet uses the same fixture file. Testnet can use existing rewards or provision the configured fixtures. Human play, physical sensor quality, and network interruptions still need device testing.
