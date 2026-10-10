This is the Android app for controlling ESP devices (esp-device-control).
The protocol, device types and architecture are defined in
C:\Users\vancl\Documents\workspace\esp-device-control\DESIGN.md – read it before working on
networking, discovery, the protocol model or device screens. Do not change the protocol
on the app side only; protocol changes are made in DESIGN.md first.

## Build

Use the JDK bundled with Android Studio:

```sh
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The app version is `appVersion` in `app/build.gradle.kts`; changing it on `main` publishes a GitHub release (see README, Releases).

## Layout (package `com.davidvancl.espcontrol`)

| Package | Responsibility |
|---|---|
| `protocol` | protocol v1 messages (`Request`, `Message`), JSON encoding |
| `net` | `DeviceConnection` interface, `WsConnection` (OkHttp), mDNS `Discovery` (NsdManager) |
| `data` | `KnownDeviceStore` (DataStore), `DeviceRepository` (known + discovered devices) |
| `device` | `DeviceSession` – one connection: hello, requests by `seq`, events, pairing, reconnect |
| `ui.list` | device list, settings (manual IP, remove device) |
| `ui.device` | device detail, pairing, one screen per device type (`IkarusScreen`), `GenericDeviceScreen` fallback |

A new device type gets its screen in `ui.device` and a branch in `DeviceScreen`, after its table is added to DESIGN.md.
