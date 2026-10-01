# Session and reward hardening

Implementation is complete for the Android application. See [verification results](session-hardening-verification.md) for executed checks and the remaining physical-device work.

Keep the existing onboarding, hosting, joining, and claiming flow. Harden the boundaries where local sessions, durable storage, and chain settlement meet. Challenge rules remain replaceable.

## 1. Settlement recovery

- Separate transaction packing, durable submission tracking, and chain reconciliation from the ledger.
- Save signed transaction IDs and blockhash validity before broadcasting. Resume confirmation after relaunch; distinguish an uncertain transaction from a failed or expired one.
- Track every payout batch independently. Retry unpaid bound shares without repeating the unlock, and reconcile receipts against the committed roster.
- Use durable writes for financial records. Test interrupted submission and partial settlement with a fake RPC, without real funds.

## 2. Interrupted completion

- Save the frozen result, roster, and verified seal acknowledgements as soon as a round wins.
- Restore the same completed session and continue sealing after host relaunch. Persist guest tickets before presenting a saved entitlement.
- Use a shared disconnect grace policy: briefing waits for connected players, an interrupted round ends explicitly after grace, and a completed round remains recoverable.
- Test a partial seal across restart and disconnects at the completion boundary.

## 3. Discovery and hotspot lifecycle

- Expose discovery failure separately from an empty search. Bound resolution and allow a fresh scan.
- Keep direct QR joining available; distinguish local connectivity from internet-dependent settlement.
- Release hotspot reservations on cancellation and shutdown, including late callbacks.
- Verify failure-state presentation and existing joining on emulators. Physical hotspot behavior requires devices supporting that API.

## 4. Admission and readiness

- Authenticate each connection with a signed, fresh host challenge. Bind reconnecting seats to their original claim keys.
- Negotiate format versions and required sensor capabilities before admission. Version the shared wire protocol.
- Require fresh clock synchronization and connected participants before starting; expose the reason when readiness is blocked.
- Test forged reconnects, incompatible formats, missing capabilities, and stale synchronization.

## 5. Deferred claim recovery

- Reconcile claim deadlines and terminal states from chain data; retain useful receipts.
- Distinguish a first installation from a lost or unreadable key on an existing installation.
- Add an explicit encrypted recovery export/import for the claim key and reward proofs, preserving wallet-free play. Validate before replacing identity or merging records.
- Test expiry, rejected recovery data, and successful restoration of an entitlement.

## 6. Local diagnostics and integrated verification

- Keep bounded local traces for transitions, joining, reconnects, synchronization, sealing, and settlement. Exclude secrets, names, and raw wallet addresses.
- Add explicit export and clear controls. No remote analytics dependency.
- Run appropriate host tests, Android assembly, iOS compilation, and the existing two-device journey. Add deliberate interruption checks where the environment permits them.
- Record actual verification and remaining physical-device checks; do not treat emulator autoplay as proof of human play quality.

Each phase is committed separately after its focused checks pass. New files own one responsibility; AppGraph remains wiring. Tests cover failure/recovery semantics rather than component appearance or implementation details. Sponsor campaign tooling and changes to challenge rules are outside this work.
