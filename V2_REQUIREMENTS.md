# Clash Next — Android V2 requirements

Status: implementation baseline, 2026-07-14

Publication note, 2026-11-03: this document preserves the original requirements,
not a list of completed features. The later decision to bundle all four Geo
resources supersedes the on-demand download plan and 22–28 MB size target below.
The current 0.1.9-alpha10 arm64 APK is 33.92 MiB. See `V2_STATUS.md` for actual
implementation progress and `CLASH_NEXT_BUILD.md` for the native build.

## Product goal

Build a small, private, dependable Android Clash client for daily personal use.
Android ships first. Windows and macOS may later reuse the configuration model
and core contract, but Android package size and system integration take priority
over sharing one UI runtime.

## Release 1 — required

- Keep full Mihomo protocol and Clash YAML compatibility.
- Import profiles from URL, local file, camera QR code, and gallery QR image.
- Refresh URL profiles and show subscription traffic/expiry when provided.
- Start and stop the Android VPN, with clear stopped, starting, running, and
  error states.
- Show live upload/download speed and total traffic.
- Show proxy groups and nodes, change the selected node, and run latency tests.
- Support Rule, Global, and Direct modes.
- Support per-app allow and reject routing lists.
- Provide useful logs plus active connection and recent request details.
- Download GeoIP, GeoSite, MMDB, and ASN resources on demand instead of
  packaging them in the APK. Allow retry and local resource import.
- Provide local backup, restore, and an import path from the earlier personal
  FlClash build.
- Provide a Quick Settings tile and a home-screen switch widget supporting
  1x2, 1x4, 2x2, and 2x4 layouts, including vivo launcher fallbacks.
- Keep all profile and usage data local. Include no analytics, crash upload,
  Firebase, Google ML Kit, advertising, or automatic update check.

## Deferred until after Release 1

- JavaScript profile override scripts.
- WebDAV backup and restore.
- A graphical rule authoring system and full graphical advanced DNS editor.
- A customizable dashboard, decorative memory/IP cards, avatars, emoji font,
  Android TV support, and developer test tools.
- Windows and macOS UI work.

Raw YAML remains importable and exportable. A rich in-app YAML editor is not a
Release 1 requirement because it is easy to misuse and pulls the product away
from its daily-use focus.

## Size budget

- Target arm64 APK: 22–28 MB.
- Geo resources are downloaded after installation and do not count toward the
  APK budget.
- The complete Mihomo core remains the largest component and is not protocol-
  trimmed in Release 1.

## Acceptance baseline

- Installs independently from upstream FlClash.
- Starts a valid imported Clash YAML profile on Android 12–16.
- Survives the UI process being closed while the VPN service is running.
- Widget and Quick Settings state converge with the real VPN service state.
- No network request occurs before a user imports or refreshes a profile,
  downloads Geo resources, or explicitly checks for an update.
