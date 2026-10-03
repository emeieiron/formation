# Overdrive

Overdrive is a cooperative reflex game for exactly two phones. Each player calls the symbol shown for their partner and rotates their own square to catch a neutral pulse. A wave succeeds only when both catches succeed.

## Rules and presentation

- Register `overdrive` with vault code `6` and format version `1`. Retired games remain retired.
- Complete 12 successful waves within the difficulty's limit: 46, 41, 36, or 31 seconds from Easy to Extreme. A clean run keeps about five seconds spare; two early failed waves run out the clock. Three failed waves end the attempt. A failed pair consumes one shared miss, even if both players miss.
- Each dial has triangle, circle, cross, and diamond edges. Colour reinforces each symbol rather than replacing it. Each phone's edge order is fixed for the attempt.
- The first four waves stagger arrivals. Later waves overlap arrivals; after eight waves, the partner's next clue is also previewed.
- Every correct shape catch increases that player's next pulse speed, including a catch whose partner missed. Eleven correct catches halve the opening fall in equal steps, so the relative speed-up grows toward the finish. The pause after a cleared wave shrinks from 650 to 350 ms. An incorrect shape and elapsed time do not increase speed. Both catches are still required to score a shared wave. Catch deadlines stay fixed during each fall, and the stage renders progress against those deadlines.
- The host owns seeded targets, catch deadlines, rotation acceptance, results, and the clock. Typed rotation commands carry a wave and sequence; stale, duplicate, early, late, and unknown-player inputs cannot alter a catch.
- A player receives their own dial and timing, but only the other player's target. Hidden answers are omitted from that player's transmitted state. This protects asymmetric information from ordinary client inspection; it does not attest gameplay or hardware.
- Prediction animates a local quarter-turn immediately. Host acknowledgement reconciles the dial. A failed wave has a short reset, and the shared outcome has no individual blame.
- Custom Compose drawing supplies the segmented square, symbols, neutral pulse, impact, and progress. The shell retains the app's black/red/white styling. Instructions are short; the playfield uses symbols, partner identity, progress, timer, and three miss marks.
- Touch is sufficient. No sensor requirement, wallet flow, settlement logic, or discovery change belongs to the game.

## Commit phases

1. **Plan and private views.** Add a default per-player game-state projection, serialize frames per recipient, and verify opening and updated frames with the existing session harness.
2. **Rules.** Add the Overdrive module, typed model, pacing, and host rules. Test paired success, shared misses, deadline boundaries, rejected inputs, target privacy, deterministic replay, and all difficulty presets.
3. **Stage and registration.** Build focused HUD, clue, dial, and feedback components; register the game through the existing catalog. Check Android assembly and iOS compilation. Preserve the existing result, sealing, and reward flow.
4. **Match acceleration.** Increase each phone's pulse speed after its correct shape catches. Verify faster arrivals for the matching phone, unchanged speed for a missed catch, and rendering against host deadlines; keep controls and shared scoring unchanged.
5. **Device verification.** Add explicit development reward fixtures and a two-phone driver that reads each phone's visible partner clue and operates the other dial. Exercise the actual session through completion and simulated settlement. Inspect standard and compact layouts and record executed checks.

## Verification boundaries

Unit tests cover rules and private-frame routing, not drawing details. Emulator automation supplies partner communication externally; it must not add a production answer reveal or a win bypass. Development rewards are explicit fixtures and never appear automatically in production. Human timing, communication, and physical Wi-Fi play need later device testing.
