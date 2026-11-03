# Clash Next — Android

Clash Next is a personal-use Android client derived from
[FlClash](https://github.com/chen08209/FlClash), using its full Mihomo core and
Android service layer with a new native Kotlin interface.

Current test release: **0.1.9-alpha10** · Android arm64 · **33.92 MiB**.

- [Download the APK and view release notes](https://github.com/liven666/Clash-Next/releases/tag/android-0.1.9-alpha10)
- [Android user guide (Chinese)](ANDROID_USER_SOP.md)
- [Build the native Android version](CLASH_NEXT_BUILD.md)
- [Version history and implementation status](V2_STATUS.md)

## Implemented

- Import Clash profiles from HTTPS links, YAML files, camera QR scans, or QR images.
- Start/stop VPN, switch Rule/Global/Direct modes, and view live traffic and duration.
- Browse and search nodes while disconnected, save a selection, and run latency tests while connected.
- Home-screen widgets in 1x2, 1x4, 2x2, and 2x4 layouts, with node, flag, duration, and exit-IP information where space permits.
- Bundled GeoIP, GeoSite, MMDB, and ASN data; no separate Geo download after installation.
- Connection details and local rolling diagnostic logs with export and common-credential redaction.
- A consistent native light/dark interface and a visible version number in Settings.
- No analytics, crash uploads, Firebase, ML Kit, or advertising in the native APK.

This is an **alpha**, signed with a development certificate. Android 15 emulator
checks have passed; vivo X300 Pro launcher and background behavior still need
physical-device verification. Subscription usage/expiry, a per-app routing
editor, and complete backup/restore are not implemented yet. Windows, macOS,
and iOS are not part of this release.

The native app is `android/v2app`; `android/app` and `lib/` retain the earlier
Flutter implementation. The APK does not embed that Flutter UI. Profiles and
logs remain local; subscriptions/providers and exit-IP checks still make the
network requests required by those features.

## Source and license

The fork starts at FlClash commit
`7c831855efedceb1a72bd0b4c18da026593d0853`. The Mihomo submodule is pinned to
`80362fc1895dcf60b79b562896653046e0687413`. Original history, notices, and
[GPLv3 license](LICENSE) are preserved. This project is not an official FlClash
release. Public uploads exclude subscription data, signing keys, local SDKs,
emulator data, logs, and build caches.

## Original FlClash README

The upstream documentation below is retained for attribution and the legacy
Flutter client. Its downloads and platform claims do not describe Clash Next.

<div>

[**简体中文**](README_zh_CN.md)

</div>

## FlClash

[![Downloads](https://img.shields.io/github/downloads/chen08209/FlClash/total?style=flat-square&logo=github)](https://github.com/chen08209/FlClash/releases/)[![Last Version](https://img.shields.io/github/release/chen08209/FlClash/all.svg?style=flat-square)](https://github.com/chen08209/FlClash/releases/)[![License](https://img.shields.io/github/license/chen08209/FlClash?style=flat-square)](LICENSE)

[![Channel](https://img.shields.io/badge/Telegram-Channel-blue?style=flat-square&logo=telegram)](https://t.me/FlClash)

A multi-platform proxy client based on ClashMeta, simple and easy to use, open-source and ad-free.

on Desktop:
<p style="text-align: center;">
    <img alt="desktop" src="snapshots/desktop.gif">
</p>

on Mobile:
<p style="text-align: center;">
    <img alt="mobile" src="snapshots/mobile.gif">
</p>

## Features

✈️ Multi-platform: Android, Windows, macOS and Linux

💻 Adaptive multiple screen sizes, Multiple color themes available

💡 Based on Material You Design, [Surfboard](https://github.com/getsurfboard/surfboard)-like UI

☁️ Supports data sync via WebDAV

✨ Support subscription link, Dark mode

## Use

### Linux

⚠️ Make sure to install the following dependencies before using them

   ```bash
    sudo apt-get install libayatana-appindicator3-dev
    sudo apt-get install libkeybinder-3.0-dev
   ```

### Android

Support the following actions

   ```bash
    com.follow.clash.action.START
    
    com.follow.clash.action.STOP
    
    com.follow.clash.action.TOGGLE
   ```

## Download

<a href="https://chen08209.github.io/FlClash-fdroid-repo/repo?fingerprint=789D6D32668712EF7672F9E58DEEB15FBD6DCEEC5AE7A4371EA72F2AAE8A12FD"><img alt="Get it on F-Droid" src="snapshots/get-it-on-fdroid.svg" width="200px"/></a> <a href="https://github.com/chen08209/FlClash/releases"><img alt="Get it on GitHub" src="snapshots/get-it-on-github.svg" width="200px"/></a>

### Homebrew

```bash
brew tap chen08209/tap
brew install --cask flclash
```

## Build

1. Update submodules
   ```bash
   git submodule update --init --recursive
   ```

2. Install `Flutter` and `Golang` environment

3. Build Application

    - android

        1. Install `Android SDK`, `Android NDK`

        2. Set `ANDROID_NDK` environment variable

        3. Run build script

           ```bash
           dart setup.dart android
           ```

    - windows

        1. Requires a Windows client

        2. Install `GCC`, `Inno Setup`

        3. Run build script

           ```bash
           dart setup.dart windows
           ```

    - linux

        1. Requires a Linux client

        2. Dependencies are auto-installed by setup script, or manually:
           ```bash
           sudo apt-get install -y libayatana-appindicator3-dev libkeybinder-3.0-dev
           ```

        3. Run build script

           ```bash
           dart setup.dart linux
           ```

    - macOS

        1. Requires a macOS client

        2. Run build script

           ```bash
           dart setup.dart macos
           ```

## Star

The easiest way to support developers is to click on the star (⭐) at the top of the page.

<p style="text-align: center;">
    <a href="https://api.star-history.com/svg?repos=chen08209/FlClash&Date">
        <img alt="start" width=50% src="https://api.star-history.com/svg?repos=chen08209/FlClash&Date"/>
    </a>
</p>
