# Ricochet pressure pass

Pressure should grow from successful cooperation while the pulse, targets, and controls remain readable. This pass adds rally acceleration and impact/danger feedback. Charged pulses, armour, and moving targets remain separate experiments after human playtesting.

## Rules

- The first catch establishes the last returning player. Each subsequent catch by the other player multiplies speed by 1.08, up to 1.5 times the difficulty's serve speed. A same-player catch after a target rebound retains momentum without increasing it.
- Walls and targets preserve the current speed. A miss resets momentum, keeps cleared targets, and uses the existing 1.2-second serve delay.
- Apply acceleration at the collision inside the swept simulation. Host updates and the bounded presentation prediction use the same rule.
- Publish momentum and close-call contact events in format version 2. Older clients must fail the existing compatibility check before play.

## Feedback

- Lengthen the pulse trail with momentum. Show a compact numeric speed multiplier with six segments; add no controls or gameplay instructions to the stage.
- Give own-paddle catches, grazing catches, target breaks, and misses distinct short sound cues. Raise the catch cue at high momentum. Use the existing mute and foreground policies.
- On the last life, add a restrained two-beat cue and matching border/life indicator every 2.4 seconds, only during live play. Stop feedback on stale frames, serve preparation, leaving, and completion. Avoid replaying restored impacts.
- Expose a small optional audio interface through the challenge API. Challenge modules select semantic cues; the application owns resources, playback, and settings.

## Logical commits

1. Record this plan and verification boundaries.
2. Add deterministic momentum and close-call rules, versioning, and focused rule tests.
3. Add the challenge audio contract, resources, feedback event gating, and custom visual feedback.
4. Verify Android/iOS builds, targeted tests, and a two-emulator journey; update the game guide and roadmap with executed results.

## Acceptance

- Alternating catches accelerate; repeated catches by one player cannot farm speed. The cap holds on every difficulty.
- A miss resets speed without restoring targets. Delayed ticks replay the same rules, and presentation prediction accelerates at the same contact as the host.
- Restored/stale events stay silent; fresh events play once. Last-life feedback has a bounded cadence and stops outside live play.
- Two emulators can still complete through ordinary inputs, sealing, and simulated settlement. Emulator execution does not establish enjoyable human pacing or physical haptic/audio feel.
