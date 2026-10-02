# Sensor management

The `:core:sensors` module owns discovery, acquisition, and input processing. Availability describes device support; acquisition describes a running request. Neither is an attestation of device identity or gameplay.

Implementation phases:

1. Typed channels, availability, acquisition, and input requirements.
2. Android inventory and event metadata, with explicit registration failures.
3. Shared acquisition, lifecycle suspension, bounded buffering, and preparation.
4. Timestamp-based motion processing, rotation and light channels, and isolated simulation.
5. Capability-aware sessions, readiness, interruption handling, and concise UI feedback.

Each phase is committed separately and verified with focused tests.

## Ownership

| Layer | Responsibility |
| --- | --- |
| `api` | Typed samples, availability, acquisition states, and backend contracts |
| Android backend | Hardware discovery, listener registration, and event metadata |
| `runtime` | Shared listeners, sampling requests, foreground lifecycle, buffering, and readiness leases |
| `capabilities` | Input requirements and accepted sources |
| `MotionSense` | Pose, tilt, and gesture processing |
| `SessionSensors` | Session preparation, readiness reports, and cleanup |

Challenges declare the inputs they need. They do not register Android listeners or decide whether hardware is present.

## Availability and readiness

Availability describes whether a source exists and whether it is permitted. Acquisition describes what happens when a consumer requests it. A present accelerometer can still fail to register, stop delivering events, or be suspended when the app leaves the foreground.

`SensorHub.prepare()` holds a readiness lease. It checks source policy and subscribes to the requested inputs. Close the lease when the screen or session no longer needs them:

```kotlin
val preparation = sensors.prepare(listOf(SensorRequirement(InputCapability.TILT)))
try {
  preparation.readiness.collect { state -> updateInputStatus(state) }
} finally {
  preparation.close()
}
```

The states distinguish checking, ready, blocked support, and interrupted acquisition. An unreliable sample cannot satisfy readiness. Retry restarts acquisition and refreshes restrictions; it cannot add missing hardware or bypass an operating-system restriction.

Estimated gravity and linear acceleration are allowed by default when their native sensors are absent. A requirement can reject estimation with `allowEstimated = false`. Simulation requires explicit acceptance and is enabled only for debug emulator builds in the app.

## Acquisition and measurements

The runtime shares one registration per physical channel. Concurrent consumers use the fastest requested period; removing the last consumer unregisters the listener. Game sampling requests 20 ms and UI sampling requests 60 ms. Android treats these periods as requests, so processing uses measurement timestamps rather than assuming a fixed callback rate.

Samples retain their source, quality, and timestamp in nanoseconds. Android timestamps use the device's monotonic sensor clock; they are not wall time or synchronized time across phones.

| Channel | Value |
| --- | --- |
| Acceleration, gravity, linear acceleration | Three axes in m/s² |
| Angular velocity | Three axes in rad/s |
| Game rotation | Relative-orientation quaternion, without magnetic heading |
| Proximity | Covered / uncovered |
| Light | Illuminance in lux |

The Android activity forwards foreground changes to the hub. Suspension releases listeners, clears cached readings, and resets gesture history. Resume requires fresh readings. Raw and consumer buffers are bounded; overflow produces a gap so processors discard incomplete gesture history.

A continuous sensor that stops delivering readings fails after two seconds. Proximity and light use change-driven reporting: after their first reading, silence alone does not imply failure.

## Session behavior

Required inputs must be ready before play starts. The host freezes each phone's available inputs into the challenge setup. Optional Sync moves are selected from that phone's supported inputs; a phone without proximity is not assigned a cover move.

Readiness reports are scoped to the current round and intersected with the capabilities negotiated during joining. Losing an input required by the active task interrupts the attempt without producing a win. A new briefing requires another readiness report.

The wire protocol is version 4 and the Sync format is version 2. Older builds must update before joining these sessions.

## Verification and limits

Focused tests cover shared registration, sampling changes, suspension and resume, acquisition failure and retry, missing hardware, source acceptance, gesture-history resets, capability-aware task selection, start gating, and stale readiness reports.

The Android build, common tests on the Android host, and iOS simulator compilation pass. The existing two-emulator Sync journey passes through joining, play, and simulated settlement. iOS currently reports unsupported sensors rather than providing placeholder measurements.

Physical-device verification remains necessary for timing, sensor quality, and operating-system behavior. Check a Seeker and a phone without proximity, then background an active session and interrupt sensor access. Emulator simulation does not establish hardware support or attest gameplay. Camera, microphone, location, NFC, and Bluetooth remain separate integrations with their own permission and privacy requirements.
