# Ricochet implementation plan

Ricochet is a cooperative game for two phones. Each phone renders one half of a shared arena. Players move the outer paddles vertically, keep one pulse in play, and clear six targets before time expires. The host decides collisions, misses, and completion; the stage renders that state and sends paddle positions.

## Scope

- Register `ricochet`, vault code `7`, format version `1`, for exactly two players. Keep Overdrive and retired game identities unchanged.
- Use one fixed logical arena with equal halves. Join order assigns left and right. Physical screen dimensions do not change the rules or require calibration.
- Use touch input only. Tap or drag anywhere in the arena to position the paddle; it stays there after release. Local movement responds immediately and reconciles with host acknowledgements.
- Clear three stationary targets in each half within 60 seconds. Three escaped pulses end the attempt. Cleared targets survive a miss; a short reset prepares the next serve.
- Difficulty changes paddle size and pulse speed. Easy is the initial emulator demo preset. All presets use the same six-target objective and shared result flow.
- Seed target placement and serve direction. Advance physics in fixed steps with swept collision checks, including after delayed ticks. Reject non-finite positions, unknown players, duplicate commands, and commands from earlier rallies.
- Use the shared clock for presentation, with bounded pulse prediction between host frames. Presentation cannot award hits or wins.
- Build focused custom HUD, arena drawing, touch controls, and impact feedback. Use the app's black, red, and white tokens and existing haptics. The existing session sound and reward flows handle completion.
- Provide a debug autopilot using public state, explicit development reward fixtures, and repeatable emulator instructions. Assistance uses ordinary paddle commands and never bypasses scoring.

## Commit phases

1. **Plan.** Record scope, verification criteria, and the roadmap before implementation.
2. **Rules.** Add the standalone module, serialized state and commands, geometry, swept physics, host lifecycle, and focused tests for actual failure boundaries.
3. **Stage and integration.** Add custom drawing, immediate touch response, bounded pulse prediction, feedback, metadata, and catalog registration. Build Android and compile the shared iOS target.
4. **Demo and verification.** Add debug assistance and explicit reward fixtures. Exercise two emulator instances through completion, sealing, and simulated settlement. Inspect both screen halves and a compact layout.
5. **Documentation.** Document the shipped rules, module boundaries, executed checks, limitations, and follow-up work. Refresh the integration guide and project overview where needed.

## Acceptance criteria

- A pulse crosses between the two logical halves at the same height. Paddle and target collisions cannot tunnel on delayed ticks.
- Both phones receive the same cleared-target count, lives, and deadline, while each stage draws its own half.
- One miss consumes one life, schedules a new serve, and leaves cleared targets cleared. The third miss or deadline ends the attempt. Clearing the final target wins once.
- Invalid, stale, and duplicate paddle commands cannot change a settled result or a new rally.
- A controlled seeded round completes through ordinary inputs on every difficulty; tests also cover losses and replay consistency.
- The game uses the existing briefing, readiness, clock, disconnect, sealing, and reward machinery without game-specific branches in those systems.
- Verification records distinguish rule checks, emulator execution, and untested physical-device behaviour.

## Roadmap

- [x] Host-authoritative rules and focused tests.
- [x] Two-screen stage and registration.
- [x] Repeatable two-emulator demo and documented verification.
- [ ] Physical-device playtest: controls, reaction windows, bezel continuity, and differently sized screens.
- [ ] Measure Wi-Fi latency and interruption behaviour; tune prediction and contact tolerances from observations.
- [ ] Tune target layouts and difficulty from human play rather than autopilot success alone.
- [ ] Explore moving targets or a single moving barrier after the basic exchange feels reliable.
- [ ] Consider optional tilt controls with a touch fallback through the sensor module.

Additional players, multiple pulses, and competitive modes are outside the first implementation. They change attention, arena layout, or reward semantics and need separate design work.

## Progress

All five implementation phases are complete as of 4 October 2026. The module uses a 2 × 1.7 logical arena, 10 ms simulation steps, swept contacts, and sequenced absolute paddle positions. The custom stage and registration compile for Android and the shared iOS simulator target.

Thirteen focused tests cover rules, presentation boundaries, 32 seeded completions across all difficulties, and deadline enforcement. Three two-emulator journeys verified the explicit fixture, completion, sealing, and simulated settlement. The final journey also checked tap/drag input with assistance briefly disabled and a compact guest display.

The [implementation guide](ricochet.md) records shipped behaviour and module boundaries. The [verification record](ricochet-verification.md) distinguishes executed checks from the physical-device work still on the roadmap.
