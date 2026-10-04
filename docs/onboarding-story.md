# Onboarding: One Seeker unlocks a Formation

## Principle

Make the central rule explicit: **no game can be played without a Seeker. One Seeker unlocks play with any other phone.** Ordinary participants do not each need their own Seeker. Explain that relationship through one continuous scene: a Seeker sends the red unlock signal, other phones join, the catalogue opens, and the group plays and finishes together. Keep game names, screenshots, fixed player counts, and specific control schemes out of the story so it remains useful as the catalogue changes.

The app now replaces the original 1.1-second Join, Play, and Claim introduction with a 24-second native story. It is skippable throughout and holds at the final chapter. The Blender terminal layer retains its original 1024 × 1536 resolution; motion, captions, and controls run natively in Compose.

## Storyboard

| Time | Caption | Action and transition | Purpose |
| --- | --- | --- | --- |
| 0–3.5 s | **A Seeker unlocks play.** One Seeker makes the Formation possible. | An isolated terminal, visibly labeled Seeker, rises into light. The red unlock signal wakes inside its screen. Hold before moving. | Establish the required Seeker as the source of play. |
| 3.5–7.5 s | **Bring any other phone.** Join the Seeker nearby, by QR, or with a code. | The same signal leaves the Seeker and reaches arriving ordinary phones. Their entrances and responses follow the same beat. The camera widens to make room. | Explain that other phones can participate without owning a Seeker. |
| 7.5–11.5 s | **Choose your challenge.** Every game needs a Seeker to unlock play. | A fan of anonymous game portals appears locked. A signal visibly travels from the Seeker to the portals; they illuminate and one opens into a shared space. | Establish the unlock requirement for the whole changing catalogue. |
| 11.5–17.5 s | **Find your rhythm.** Different roles. One shared goal. | Contributions travel from different screens toward the shared space. Each exchange changes the central composition; a common rhythm makes the relationship visible. | Show cooperation through cause and effect. Illustrate contributions rather than a game mechanic. |
| 17.5–21.5 s | **Finish together.** Every contribution becomes part of the result. | Separate pieces align into Formation's three-bar mark. A single completion wave travels outward, then the scene settles. | Make the shared result the payoff. Do not promise a payout from every game. |
| 21.5–24 s, then hold | **Find your Formation.** Host with a Seeker, or join someone who has one. | The camera settles on the assembled mark. Get started hands off to name/colour selection and the appropriate host or joining path. | Give Seeker owners and ordinary-phone participants distinct useful actions. |

## Direction

- Preserve the app's black, red, and white palette, condensed headings, and restrained interface. Use high-resolution, beveled Blender objects and a largely empty stage to direct attention.
- Carry one red signal through the entire story. Objects move and transform between beats; they do not restart as separate looping slides.
- Change captions after the new action becomes legible. Keep one headline and one supporting sentence visible at a time.
- Let the camera follow the event: close on the first player, wider for the group, nearer the selected challenge, and still for the final action.
- Use quiet optional sound: a wake tone, paired arrivals, short exchanges on a 720 ms beat, and one resolving completion chord. Default the preview to silent. Production should use the app's sound preference and support a visible mute control.
- Keep this introduction free of repeated haptics. Optional audio uses the existing saved sound preference, platform volume/silent-mode behavior, and a visible mute control.

## Interaction

Play the story once on first use. Keep Pause, Next, and Skip introduction available; hold at the final action. Chapter markers allow reviewing earlier material. Avoid requiring someone to wait for animation before proceeding.

With reduced motion, show six composed still states with the same captions and manual Next. Screen readers receive the chapter and caption, not every frame. Backgrounding or hiding the story freezes it at its current time. Return resumes from that moment; it does not jump ahead or start over.

The app retains name/colour setup and adds two paths: **I have a Seeker** leads to linking the Seeker so it can unlock a Formation; **I'm joining a Seeker** leads to joining a Formation hosted by one. Ordinary phones do not receive a wallet-linking requirement merely to join. The joining path saves the profile without asking for a wallet, then returns to Home and resumes any pending join link. The host path waits for verified Seeker identity; someone who cannot link can explicitly choose Join a Seeker instead.

Skipping the introduction skips the story, not the Seeker requirement. Browsing the catalogue may remain available, but playing any game requires a Seeker-backed Formation. Practice must obey the same rule.

## Implementation approach

Use one monotonic playhead for scene positions, camera transforms, caption changes, and event cues. Production advances one saved playhead from Compose frame timestamps. Caption changes, all geometry, progress markers, and short sound-cue crossings read that same playhead. Pause, chapter selection, leaving the story, and backgrounding stop the current cue. Resume crosses only future cue times; a delayed frame drops stale cues instead of playing a burst. Reduced motion uses six explicitly chosen settled poses with manual Next. Motion duration scaling is respected.

The study reuses an original high-resolution terminal model, rendered at 1024 × 1536. A transparent WebP preserves that resolution for the preview. The game portals, trails, and final mark are drawn at the screen's resolution. The isolated model, materials, camera, and source are in [assets/onboarding](../assets/onboarding/README.md).

WelcomeScreen owns the saved playhead outside its step transitions, so going back from profile setup restores the story position. Native captions and chapter buttons are localized; captions announce chapter changes through a polite live region, while decorative frame updates remain hidden from accessibility. The stage height adapts to available space and text scaling, with a scroll fallback for unusually small windows.

AppGraph.host now calls SeekerState.requireHostIdentity before ending an existing session, assessing game inputs, or starting a server. Selecting the host path or skipping onboarding does not grant this identity. The same identity gates standalone interactive practice in GameActionSheet; guests can still read static game instructions and use a Seeker-hosted session without linking their own wallet. Existing verification remains responsible for the Genesis Token, including removing identity when the token is definitively absent. Debug simulated Seekers remain confined to debug builds.

The layered format adds a 36,752-byte transparent WebP and five short, original PCM sound cues (76,958 bytes total). One decoded RGBA terminal costs approximately 6 MiB and is reused for all three illustrated phones. There is no frame atlas, video player, or WebView in the onboarding. The sound generator is in assets/onboarding/sounds.py; existing Android/iOS sound players retain their foreground and silent-mode gates.

Physical-device frame pacing, decoded-memory measurement, and audio latency still require device testing; emulator playback and iOS compilation do not establish those measurements.

## App verification

Verified on 2026-10-05:

- **42 tests passed:** 39 shared tests and 3 challenge API tests. New coverage checks cue crossings, dropping stale cues, paused/reduced-motion playback, completion holds, host identity requirements through token loss, and cancellation/retry during Seeker linking.
- Android debug assembly and release lint passed. Shared iOS simulator compilation passed. Existing SessionScreen warnings remain unrelated to this change.
- Normal Android playback, chapter selection, Pause/Resume, saved sound toggle, Next, Skip, final hold, and Replay passed. An eight-second background interval preserved the opening chapter and playhead rather than advancing to later chapters.
- All six reduced-motion chapters held static poses and advanced manually. At 360 × 640 dp and 130% text size, headlines, supporting captions, Skip, and Next/Get started remained visible after adapting the stage height.
- Empty-name validation, selected light/name persistence, and returning from profile setup to the saved final chapter passed. The guest handoff saved Maya/Jade and reached Home without creating Seeker identity or requiring a wallet.
- An unlinked host remained on the linking step when no wallet answered, with Try again and Join a Seeker instead available. An already-linked host fixture reached Home as a debug test Seeker. Real wallet approval still requires a physical Seeker.
- A guest opening Ricochet received static instructions and the Seeker requirement; the interactive standalone practice control was absent. Host entry calls the identity guard before server/session side effects.
- All five story cues decoded successfully through Android SoundPool. Foreground cue requests followed the shared timeline, including the 720 ms exchange beat. These logs validate requests, not physical audio latency.

The final checks used a temporary isolated emulator. Original profiles/preferences and display settings on the existing devices were restored. A few instrumentation screenshots omitted unchanged header pixels; native framebuffer captures confirmed the complete layout.

The [silent device recording](images/onboarding-story.mp4) shows the six native story chapters at 1080 × 2400. The [compact still](images/onboarding-compact.png) records the larger-text reduced-motion layout. The video is a preview artifact, not an app playback dependency.

## Preview checks

The browser study passed playback/pause, chapter selection, optional sound activation, Next, skip, both local Seeker-owner and joining handoffs, and replay. Layouts at 320, 360, and 736 pixels had no horizontal overflow. Reduced motion held a composed still and manual Next advanced the chapter. No JavaScript runtime errors were observed. The Seeker introduction, locked portals, and compact layout were visually inspected.

The embedded study is under 1 MB. The transparent terminal layer preserves the original 1024 × 1536 render resolution. These checks validate the design study, not Android/iOS game-entry enforcement or physical audio timing.
