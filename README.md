# Android Terminal

Android vehicle-terminal client workspace.

Scope:
- JT808 signaling client
- JT1078 media upload and talkback
- GPS, camera, mic, and foreground-service lifecycle
- DMS, ADAS, and BSD in the same app

Initial structure:
- `app/protocol` for JT808/JT1078 transport
- `app/media` for camera, audio, and upload
- `app/dms` for driver monitoring
- `app/adas` for active safety alerts
- `app/bsd` for blind-spot detection
- `app/ui` for operator screens
