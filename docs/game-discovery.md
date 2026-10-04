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

## Follow-up

Reward-free practice rounds, playable Overdrive tutorials, and funding-management UI are separate features. The catalogue explains installed games; a shared attempt still needs an existing funded reward and the normal Formation flow.
