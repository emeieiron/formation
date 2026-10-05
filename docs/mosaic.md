# Mosaic

Mosaic turns 6 or 9 phones, and later 18, into one Solana logomark. Each phone shows one piece at true physical size. Players lay the phones on a table in three rows until the bars line up, then seal every seam with a pinch across it. The game is isolated in `app/challenges/mosaic`; generic screen measurement, discrete group sizes and full-screen stages live in the session framework and the game API.

![Six emulators showing their Mosaic pieces after the first seam sealed](images/mosaic-pieces.png)

## Implemented behaviour

| Property | Behaviour |
| --- | --- |
| Identity | `mosaic`, vault code `8`, format version `1` |
| Players | Rewards can fund 6 or 9 phones: 3 × 2 or 3 × 3 phones lying in landscape, one row per bar. The 18-phone layout, 3 × 6 phones standing in portrait, is implemented and tested, and opens after an 18-phone playtest by adding 18 to `Layouts.groupSizes` |
| Objective | Seal every seam before the deadline: 7 seams for 6 phones, 12 for 9, 27 for 18 |
| Deadline | 20 seconds plus 9, 7, 5 or 4 seconds per seam, from Easy to Extreme |
| Wrong pairs | A pinch between phones that aren't neighbours on those edges costs a shared miss on every difficulty; the third ends the attempt |
| Pinch | One finger on each phone slides toward the shared edge and lifts at it; both releases must fall within 200 ms |
| Alignment | Neighbours whose lift points differ by more than 8 mm (6 on Hard, 5 on Extreme) see "Line up the bars" and lose nothing |
| Hints | Easy labels each piece; Easy and Normal mark unsealed seams; Hard and Extreme show only sealed seams |
| Screens | Every phone reports a measured screen with at least 90 × 45 mm of usable area before the round starts |
| Display | The stage runs full screen with the system bars hidden and the window at a fixed 80% brightness |
| Result | The existing completion, sealing, persistence and settlement flow |

The mark is the official `solanaLogoMark.svg` from [solana.com/branding](https://solana.com/branding), drawn from its vector paths with its six-stop gradient. Mosaic does not follow the full Solana brand guidelines. The artwork lives in `Artwork.kt`, so another image can replace it.

## Pieces across phones

The official mark is 101 × 88 units with three bars 21.79 units tall and two 11.31-unit gaps. Three rows put both row seams in those gaps, so no seam runs along a bar.

- **Shared piece size.** The host takes the per-axis minimum of every phone's usable area in the layout's orientation: the narrowest width and the shortest height. The usable area excludes display cutouts and a clearance for rounded corners, r(1 − 1/√2) on each axis.
- **Physical scale.** Each phone converts millimetres with its own measured pixels per millimetre, so every piece has the same physical size.
- **Placement.** A piece sits against the screen edges that face its neighbours. On an axis with neighbours on both sides it is centred, and the deal gives those positions to the phones with the least spare screen. Landscape phones lie with their top edge to the left; the activity stays portrait-locked and the stage lays its content out sideways.
- **Bezels.** The mark is laid out on one canvas with a nominal 6 mm gap between long phone edges and 10 mm between short ones. Content behind a bezel is hidden rather than shifted, so bars keep their length.

The shipped layout code gives these results for usable screen shapes from 1.6:1 to 2.4:1. Fill is the share of a piece covered by the mark; mark sizes assume a usable area of 66.5 × 147.6 mm.

| Phones | Smallest piece fill | Mark size |
| --- | --- | --- |
| 6 | 47–63% | About 24 × 21 cm |
| 9 | 6–28%; the outer phones carry the slanted bar ends | About 24 × 21 cm |
| 18 | 22–37% | About 43 × 37 cm |

`LayoutTest` repeats this sweep, checks that no bar crosses a row seam, and checks that every piece's empty strip leaves at least 4 mm for the HUD.

## Screen measurement

Generic framework pieces support any game drawn at true size:

- `ScreenProfile` describes the full-screen window in portrait: size and safe insets in millimetres and pixels per millimetre. `ScreenRequirement` sets the smallest usable area a game accepts.
- A game opts in through `screenRequirement(players)`. That adds the `display.physical.v1` admission capability, which turns away phones that can't measure their screen, and readiness waits for an accepted profile from every phone. `ChallengeSetup.screens` freezes the profiles at start, so the opening frame already holds the deal.
- Phones send their profile with the readiness report. The field is optional and older phones never reach Mosaic because of its format version, so the protocol version stays 4.
- Android measures the maximum window metrics, `DisplayCutout` safe insets and `Display.getRoundedCorner` radii, with a 6 mm corner assumption before API 31. Density comes from `xdpi` and `ydpi`. A reading is trusted when pixels are square within 5%, the density is within 35% of the density bucket and the diagonal is 3.5–14 inches.
- An untrusted or too-small reading offers a one-time calibration in the briefing: drag an on-screen outline to the 53.98 mm edge of any bank, transit or ID card. The phone stores the result. Split-screen and multi-window modes report the screen as unavailable.
- iOS reports no physical screen, so iOS phones can't join Mosaic.
- `StageScope.screen` gives the stage its own profile for drawing. `Challenge.fullScreen` asks the session shell to hide the system bars during play. The Android port counts full-screen requests, because a phase transition briefly composes two copies of the stage.

## Sealing

Each phone reports half a pinch: the edge, the lift point along that edge in millimetres from the piece's corner, and the touch-down and release times in host time. A release stamp is trusted only within 300 ms of arrival, and repeated pinch IDs are ignored.

1. A half pairs at once with a half from its real neighbour on the facing edge whose release is within 200 ms. The seam seals if the lift points agree within the tolerance. Otherwise both phones see "Line up the bars".
2. A half with no neighbouring partner waits 350 ms. It then pairs with any facing half within 200 ms as a wrong pair, which costs a miss.
3. A lone half expires after 700 ms without effect, and a slide toward the mosaic's outer edge is never sent.

When two pinches happen at once, each half finds its own neighbour first. A seal proves the two phones were adjacent and aligned at that moment; Mosaic doesn't detect a phone lifted afterwards.

## Presentation and feedback

- The stage draws the piece, a red glow on sealed seams, faint marks on unsealed seams where hints allow, and a ring where this phone's last half landed.
- The HUD shows the time, sealed seams and remaining wrong pairs in the empty strip beside the bar. It never covers the mark and doubles as an orientation cue.
- A wrong pair flashes every phone's border red. When the last seam seals, a light band crosses the whole mark on the shared clock within the session's 1.2-second final hold, travelling from phone to phone.
- Sound reuses the existing cues. Every phone hears a wrong pair (`Miss`), the two phones involved hear a seal (`Target`) or an alignment hint (`CloseCall`), and the last 10 seconds bring the `Danger` heartbeat.
- The stage redraws per frame only while something animates, and the HUD recomposes once a second.
- Screen readers announce each piece ("Mosaic piece: Top bar · left half") and each seam strip with its state ("Right seam, sealed").
- The briefing and the Seeker's action sheet include a practice field: two pretend phones share the top bar, and sliding one finger on each toward the seam seals it locally.
- The Home card uses a Blender cover and motion sheet; see [the cover artwork](../assets/covers/README.md).

## Module boundaries

All paths below are relative to `app/challenges/mosaic/src/commonMain/kotlin/xyz/mcxross/formation/mosaic`.

| File | Responsibility |
| --- | --- |
| `Mosaic.kt` | Metadata, group sizes, screen requirement, full screen, serializers and stage binding |
| `Artwork.kt` | Official logomark contours, gradient stops and view box |
| `PathData.kt` | SVG path parsing and flattening for the contours |
| `Layout.kt` | Grids, edges, seams, the shared piece size, canvas windows, placement and the HUD strip |
| `Deal.kt` | Seeded, size-aware assignment of phones to positions |
| `MosaicState.kt` | Serializable state, seam events and pinch halves |
| `MosaicGame.kt` | Pacing, host lifecycle, pairing, sealing, wrong pairs and the deadline |
| `Assist.kt` | Debug assistance from public state on a shared seam schedule |
| `ui/Mark.kt` | Mark path and gradient, piece geometry in pixels, sideways layout and piece labels |
| `ui/PinchInput.kt` | Per-finger tracking and reading a slide as half of a pinch |
| `ui/MosaicStage.kt` | Piece drawing, seam glows, the sweep, the HUD and seam strips |
| `ui/MosaicFeedback.kt` | Stage-bound sound and haptics |
| `ui/PinchIntroduction.kt` | Briefing practice |
| `ui/MosaicCover.kt` | The Home card's still and motion cover |

## Demo on emulators

Start six emulators. Instances of one AVD can run together with `-read-only`. Give two of them smaller displays so the shared piece size matters, for example `adb -s SERIAL shell wm size 1080x2400`, and reset with `wm size reset` afterwards. From the repository root:

```sh
FORMATION_REWARDS="$PWD/scripts/fixtures/mosaic.json" \
  python3 scripts/e2e.py --title Mosaic --code 8 --players 6 --chain simulated --driver mosaic
```

`--players` selects the group size and uses the first that many running emulators, or name them with `--seeker` and repeated `--guest`. The default autoplay driver turns on each phone's debug assistance, marked **AUTOPLAY**. `--driver mosaic` reads each phone's piece and seam strips from its accessibility tree, then swipes both phones of every seam toward it at the same moment. That exercises real touch input and pairing, but emulators can't be laid on a table, so it doesn't test physical placement. `--driver manual` waits for you to play.

See [the verification record](mosaic-verification.md) and [the plan](mosaic-plan.md).

## Limits

- No physical phones have played Mosaic yet. The nominal seam gaps, alignment tolerances, pinch thresholds and deadlines are starting values for a playtest.
- Some Android devices report the wrong density; calibration covers them, but each new device family needs a ruler check.
- Neighbouring screens differ in colour. The fixed window brightness helps but can't override Night Light or Extra dim.
- Android's system gesture exclusion isn't used: pinches start inside the screen and move outward, which doesn't trigger the back or home gestures. Android shows its one-time "Viewing full screen" notice the first time a phone hides its bars.
- Cutout insets reserve the full safe inset on that edge even when the camera hole is small, which can shrink the shared piece slightly.
