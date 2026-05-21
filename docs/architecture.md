# Android Terminal Architecture

The app is organized around one mounted vehicle terminal client with shared
feature modules:

- `protocol` handles JT808 signaling and JT1078 transport.
- `media` handles camera, audio, upload, and talkback.
- `dms` handles driver monitoring.
- `adas` handles active safety alerts.
- `bsd` handles blind-spot detection.
- `feature` coordinates per-frame analysis and emits a combined snapshot.
- `ui` exposes operator and diagnostic screens.

This layout keeps the app useful for real vehicles while still allowing the
terminal to be simulated in a controlled way.
