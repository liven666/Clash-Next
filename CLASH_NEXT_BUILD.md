# Clash Next Android build

This guide builds the native `android/v2app` application, not the earlier
Flutter `android/app`. Release version: `0.1.9-alpha10`, version code `10`,
application ID `com.follow.clash.next`, ABI `arm64-v8a`.

## Source and tools

The published APK was built on macOS Apple Silicon with:

- Flutter 3.44.4 / Dart 3.12.2. Flutter is still required by the shared Gradle
  project and build harness, but is not embedded in the native app.
- Go 1.26.4.
- Temurin JDK 21.0.11; Java/Kotlin output targets Java 17.
- Android SDK platform 36, NDK 28.2.13676358, and CMake 3.22.1.
- Gradle 9.2.1, via the checked-in wrapper.

Start from the release source and its pinned submodule:

```bash
git clone --recurse-submodules https://github.com/liven666/Clash-Next.git
cd Clash-Next
git checkout android-0.1.9-alpha10
git submodule update --init --recursive
flutter pub get
```

The upstream FlClash baseline is
`7c831855efedceb1a72bd0b4c18da026593d0853`. The corresponding Mihomo submodule
commit is `80362fc1895dcf60b79b562896653046e0687413`; do not replace it with a
moving upstream branch when reproducing this release.

Create your own untracked `android/local.properties`, setting both paths to
your installed tools:

```properties
sdk.dir=/absolute/path/to/android-sdk
flutter.sdk=/absolute/path/to/flutter
```

Set `JAVA_HOME`, add Flutter and Go to `PATH`, and set `ANDROID_HOME` to the
same SDK directory. Then build the Go core from the repository root:

```bash
export ANDROID_NDK="$ANDROID_HOME/ndk/28.2.13676358"
export ANDROID_ARCH=arm64
make core-android TARGET_PLATFORM=android-arm64
```

Before continuing, verify that the build generated all three native inputs:

```bash
test -s android/core/src/main/jniLibs/arm64-v8a/libclash.so
test -s android/core/src/main/cpp/includes/arm64-v8a/libclash.h
test -s android/core/src/main/cpp/includes/arm64-v8a/bride.h
```

Do not use `skipGoCore` with missing or outdated native inputs: the legacy
CMake fallback can produce a bridge without a working proxy core.

## Test and package

From the `android` directory:

```bash
./gradlew -PskipGoCore=true :v2app:testDebugUnitTest :v2app:lintRelease :v2app:assembleRelease
```

The APK is written to `build/v2app/outputs/apk/release/v2app-release.apk`
relative to the repository root. All four tracked resources under
`assets/data/` are packaged in the APK; do not delete them as build caches.

The 2026-10-22 verification recorded 33 passing native unit tests and release
lint with no errors. Android 15 ARM64 emulator checks covered import, node
search/preselection, mode feedback, connect/disconnect, duration, light/dark
mode, and narrow/large-text layouts. Physical vivo launcher behavior still
requires device testing. The release APK has not been rebuilt for this GitHub
publication; only documentation, source-fetch URL, ignore rules, and inactive
workflow packaging were adjusted.

## Signing and publication

This alpha uses the project's existing development signing configuration.
Private signing material is not included in Git. A local build normally has a
different certificate and cannot necessarily update an installed release APK.
Do not uninstall an existing app without backing up its configuration.

The original Flutter multi-platform release workflow is archived at
`.github/legacy-workflows/build.yaml.example`: it builds the legacy app and
expects the upstream author's signing/distribution secrets. It is deliberately
not enabled for Clash Next. The native alpha is manually published as the
GitHub prerelease `android-0.1.9-alpha10`, with the APK and SHA-256 file as
release assets rather than large binary files in Git.

The source remains GPLv3, including the original history and notices. Downloaded
SDKs, dependency/build caches, emulator data, subscriptions, runtime logs, and
signing keys are intentionally excluded.
