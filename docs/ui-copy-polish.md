# App copy and visual hierarchy polish

Reduce repeated explanations in the general app. Show occupancy and reward state once, retain explicit action labels, and preserve essential wallet, permission, recovery, and error information. Challenge rules and implementations remain outside this pass.

## Phases

1. Establish shared Compose string resources for the reduced labels, accessibility names, and plural forms. Brand names and user data stay separate from translated sentences.
2. Simplify Home and reward cards: names instead of greetings, concise search state, numeric occupancy, and one reward status. Preserve every hosting, joining, and claim action.
3. Simplify onboarding and the lobby: finite visual introduction, concise profile fields, roster occupancy, and a single waiting state. Preserve editing, network setup, readiness, and challenge instructions.

Commit each phase separately after compilation. Check the final Android app with the Android CLI skill, including compact width and larger system text. Run the existing host tests and two-device simulated journey. Record verification results here.

## Localization scope

This pass creates the default English resource catalog for the touched surfaces; it does not add translated languages or claim complete app localization. Wallet/error text and challenge content outside the touched surfaces still need a later resource migration. Actions with different effects keep distinct names, and reward readiness is separate from group readiness so translators can handle context correctly.

Numbers, dates, font coverage, and RTL layout require their own locale verification. Removing a visible label never removes its accessibility meaning.
