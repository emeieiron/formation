# Seeker present badge

A Seeker Genesis Token shows who owns a Seeker, not that the phone in the room is one. Android key attestation can show that: the phone's secure hardware signs a statement about a key it holds and the phone it is in, and the statement chains to Google's attestation roots. Formation keeps an attestation verifier for a planned **Seeker present** badge on the host in Nearby and the lobby.

The badge never gates hosting; the linked wallet does (see [Hosting, joining and rewards](hosting.md)). A sponsor may later require it for a particular reward.

## Status

- The verifier, the Android attestation and the Seeker policy are built and tested.
- They aren't part of the session yet: hosts don't send an attestation and guests show no badge.
- The next step is to confirm it on a real Seeker. Install a `dev` build on the Seeker and run Profile → Developer → **Seeker hardware attestation** → **Check**. It should say "Seeker hardware attested". If the Seeker can't attest its device properties (`CANNOT_ATTEST_IDS`), or attests different strings from its build constants, the badge can't work as designed.
- Once confirmed, the host attaches an attestation whose challenge commits to its session key, and the beacon carries it so Nearby can show the badge.

## Why the Genesis Token isn't enough

Solana Mobile documents two ways to recognise Seeker users ([Detecting Seeker users](https://docs.solanamobile.com/recipes/general/detecting-seeker-users), [Seeker Genesis Token](https://docs.solanamobile.com/marketing/engaging-seeker-users)):

- **Build constants** (model `Seeker`, manufacturer `Solana Mobile Inc.`, brand `solanamobile`). The docs warn these can be spoofed. Formation only uses them to choose the onboarding path.
- **Genesis Token checks.** The wallet holds a token in Solana Mobile's group, signs a message to prove it holds its key, and the token's mint is tracked because the token can move between wallets. Formation's linking does all three.

Neither proves the phone in hand is a Seeker: a Seed Vault recovery phrase, or a token moved to another wallet, works from any phone.

## What the check proves

The phone makes a P-256 key in Android Keystore with an attestation challenge and `setDevicePropertiesAttestationIncluded(true)`, so the secure hardware writes its brand, manufacturer and model into the attestation. Formation keeps the certificate chain and deletes the key.

The chain rules follow Google's [reference verifier](https://github.com/android/keyattestation) and its [key attestation guide](https://developer.android.com/privacy-and-security/security-key-attestation):

| Check | Rule |
| --- | --- |
| Root | Trust comes from Google's two pinned root keys, never from the root the device sends. The RSA root `serialNumber=f92009e853b6b045` was reissued in 2016, 2019, 2021 and 2022 with one key. The EC root `Key Attestation CA1` is used from February 2026. |
| Path | Each certificate is issued by, and signed with, the one above it. Only the leaf may carry an attestation record. |
| Dates | Intermediates must be within their validity. Factory-provisioned chains keep expired intermediates, as Google allows. |
| Revocation | No certificate is on Google's [revocation list](https://android.googleapis.com/attestation/status), fetched by each phone, refreshed daily and kept offline. |
| Hardware | The key was generated in a TEE or StrongBox, and a StrongBox claim must match the chain. |
| Challenge | The record carries the challenge the verifier expects. |
| Boot | The bootloader is locked and verified boot reports `Verified`. |
| Seeker | The hardware-attested brand, manufacturer and model are `solanamobile`, `Solana Mobile Inc.` and `Seeker`. |
| App | The key was made by Formation's package, signed like the checking phone's copy. |

The code is in `app/core/crypto` (`Der`, `Certificates`, `Signatures`, `KeyAttestation`), `SeekerPresence` in `app/core/session`, `SeekerHardware` and `AttestationRevocations` in `app/shared`, and `AndroidAttestation` in `app/androidApp`.

## Verification

- The chain verifier passes against Google's published test chains, on the JVM and the iOS simulator. They come from a Pixel 9 Pro, a Pixel 8a, a Pixel StrongBox key and a Sony Xperia 10 III. They cover both roots, remote and factory provisioning, an unlocked bootloader, expiry, revocation, tampering, reordering and a malformed record.
- On an Android 16 emulator the check reports `CANNOT_ATTEST_IDS`, as emulators can't attest device properties.

## Limits

- Not yet run on a real Seeker.
- A Seeker could be reached through a relay from another room; attestation shows a Seeker signed, not where it is.
- Leaked attestation keys are stopped only once Google revokes them.
- iPhones can't check the signing certificate until the release certificate's SHA-256 is pinned for them.
