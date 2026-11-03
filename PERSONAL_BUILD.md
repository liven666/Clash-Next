# FlClash Personal

This branch is a small, maintainable self-use fork of FlClash. It keeps the
upstream Flutter UI, Android service layer, and Mihomo core intact while making
the Android package easier to distinguish and safer to build locally.

## Baseline

- Upstream repository: `chen08209/FlClash`
- Upstream commit: `7c831855efedceb1a72bd0b4c18da026593d0853`
- App version: `0.8.94+2026071302`

## Android changes

- Uses the independent application ID `com.follow.clash.personal`.
- Uses the launcher label `FlClash Personal`.
- Removes the Google Services, Firebase Crashlytics, and Firebase Analytics
  plugins and dependencies.
- Removes the Android crash-reporting prompt and setting.
- Disables automatic upstream update checks by default. Manual update checks
  remain available.
- Adds a responsive Android home-screen proxy switch for 1x2, 1x4, 2x2, and
  2x4 launcher cell sizes. Its provider metadata and OEM fallback behavior
  follow the vivo-tested ChargeMonitor implementation: a 2x2 default, broad
  resize bounds, home-screen and keyguard categories, a standard widget
  background ID, and legacy portrait/landscape views when exact size data is
  unavailable. It toggles the existing VPN service and reflects stopped,
  running, and pending states.
- Declares both coarse and fine location permissions required by the Wi-Fi SSID
  helper on Android 12 and later.
- Builds Android Go cores sequentially to prevent concurrent native compilers
  from exhausting local memory.

The current prototype artifacts use the fallback debug signing certificate, so
their installed application ID is `com.follow.clash.personal.dev`. A dedicated
personal release key should be created before treating this as the permanent
install.

## Privacy boundary

Crashlytics and Analytics are not included. QR-code scanning still uses Google
ML Kit through `mobile_scanner`; ML Kit brings generic Firebase encoder and
Google data-transport libraries transitively. Replacing this scanner with a
fully local implementation is a separate change because QR profile import must
keep working.

## Build and verification

```bash
flutter analyze --no-fatal-infos
flutter test
dart setup.dart android --env stable
```

The Android packaging command creates separate APKs for `arm64-v8a`,
`armeabi-v7a`, and `x86_64` under `dist/`.

## Next Android milestones

1. Create and back up a dedicated release key, then produce a stable
   `com.follow.clash.personal` package.
2. Install on a physical Android device and test VPN permission, profile
   import, DNS, quick settings, all four widget sizes, boot recovery, and
   background operation.
3. Profile battery usage before changing polling or service behavior.
4. Decide whether to replace Google ML Kit with an offline-only QR scanner.
5. Add a personal icon and final app name once the branding is chosen.

macOS and Windows can reuse the Dart-side defaults. Platform-specific signing,
packaging, and service behavior should be handled after the Android build is
stable on a real device.
