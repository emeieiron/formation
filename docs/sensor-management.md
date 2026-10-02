# Sensor management

The `:core:sensors` module owns discovery, acquisition, and input processing. Availability describes device support; acquisition describes a running request. Neither is an attestation of device identity or gameplay.

Implementation phases:

1. Typed channels, availability, acquisition, and input requirements.
2. Android inventory and event metadata, with explicit registration failures.
3. Shared acquisition, lifecycle suspension, bounded buffering, and preparation.
4. Timestamp-based motion processing, rotation and light channels, and isolated simulation.
5. Capability-aware sessions, readiness, interruption handling, and concise UI feedback.

Each phase is committed separately and verified with focused tests. Physical-device verification remains necessary for timing, sensor quality, and platform behavior.
