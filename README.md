# ESP Control

Android app that controls ESP8266 / ESP32 projects using the [esp-device-control](https://github.com/davidvancl/esp-device-control) library over the local WiFi network. The protocol, security model and device types are defined in the library's [DESIGN.md](../esp-device-control/DESIGN.md).

## Features (0.1)

- Discovery over mDNS (`_espctl._tcp`); remembers the last IP of every known device; devices can be added by IP address in Settings
- Pairing with a PIN shown on the device, or confirmed on the device; the token is stored per device id
- Screen for the `ikarus` control panel: displays, brightness, LEDs, key switch and button presses
- Generic screen for unknown device types: readable values and a JSON input for every writable value
- Connection only while the app is in the foreground, reconnect with backoff; slider changes are throttled to 100 ms

## Requirements

- Android 8.0+ (API 26); on Android 17+ the app asks for access to the local network
- Phone and device on the same WiFi network

## Build

```sh
./gradlew :app:assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`.

## Testing against a device

Device side see the library README (testing with `websocat`). In the app: open the device from the list, tap **Pair**, enter the PIN shown on the device. After pairing the device screen appears; on the next start the app connects with the stored token.
