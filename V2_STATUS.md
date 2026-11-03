# Clash Next Android V2 status

## 0.1.0 alpha 1

Implemented in the first native Android milestone:

- Independent `com.follow.clash.next` application using the existing full
  Mihomo core without Flutter.
- Local YAML and HTTPS profile import, URL refresh, camera QR scan, and gallery
  QR recognition.
- VPN start/stop, live traffic, Rule/Global/Direct modes, proxy groups, node
  selection, and latency tests.
- Active connections and recent request details.
- On-demand Geo resource download; no Geo database is packaged in the APK.
- Quick Settings tile and responsive 1x2, 1x4, 2x2, and 2x4 home-screen
  widgets, including the vivo exact-size and legacy launcher paths.
- No analytics, crash reporting, Firebase, ML Kit, advertising, or automatic
  update request.

Still scheduled before the stable Release 1:

- Subscription usage and expiry display.
- Per-app allow/reject routing editor.
- Searchable logs, connection close actions, and accumulated traffic display.
- Complete local backup/restore and import from the earlier personal build.
- Local Geo resource import and interrupted-download recovery.
- Device testing on vivo X300 Pro and Android 12-16.

The alpha APK is arm64-only and uses an independent development signature so
it can coexist with the earlier FlClash build during testing.

## 0.1.1 alpha 2

- Changed profile downloads to an established Clash.Meta Android user agent so
  subscription panels do not mistake the new app version for an obsolete core.
- Added a compatible `global-ua` fallback for remote proxy and rule providers,
  while preserving a profile's explicitly configured value.

## 0.1.2 alpha 3

- Tries multiple established Clash client identities and rejects subscription
  responses that contain an obsolete-client warning instead of overwriting the
  current configuration.
- Uses the successful identity for nested proxy and rule provider downloads.
- Applies Android system-bar and display-cutout insets to the whole screen.
- Shows the installed application version at the bottom of Settings.

## 0.1.3 alpha 4

- Expands all four vivo-compatible widget sizes with the selected/active node,
  inferred node-country flag, live connection duration, exit IP and its country
  flag, plus throttled upload/download speed on wide layouts.
- Keeps the duration clock running without waking the app every second; node
  and IP data are cached and refreshed after connection or node changes.
- Opens the app when the widget card is tapped and reserves the power button
  for start/stop, reducing accidental disconnects.

## 0.1.4 alpha 5

- Shows the currently selected/active node on both the Home and Nodes pages,
  and refreshes it when traffic, proxy state, or request-chain data changes.
- Explicitly stops the widget Chronometer and clears its saved run time when
  the VPN stops, including launchers that reuse the previous RemoteViews.
- Reworks every widget size into a larger left-side power control and a
  right-side information area with increased left inset and touch target.

## 0.1.5 alpha 6

- Persists every proxy-group selection and restores the complete
  `selected-map` when Mihomo starts instead of passing an empty map.
- Separates the user's saved selection from the currently active request-chain
  node, so the app and widget still show the saved node while disconnected.
- Migrates the last group/node pair saved by 0.1.4 into the new selection map.

## 0.1.6 alpha 7

- Packages GEOIP.metadb, ASN.mmdb, GEOIP.dat, and GEOSITE.dat directly in the
  APK instead of downloading them after installation.
- Extracts and size-checks bundled Geo resources before Mihomo starts, with a
  Settings action to restore damaged or missing local copies.

## 0.1.7 alpha 8

- Collects app lifecycle, VPN state, profile, proxy-selection, service errors,
  and live Mihomo core logs in a local rolling 2 MB diagnostic file.
- Adds recent-log preview, clear, and Storage Access Framework export actions
  in Settings, with device/runtime/Geo metadata in the exported report.
- Redacts subscription URLs and common credential fields before writing logs,
  and forces the runtime core log level to `info` for connection diagnosis.

## 0.1.8 alpha 9

- Shows the active Rule, Global, or Direct mode with a persistent current-mode
  summary, check mark, and high-contrast selected button state.
- Applies the saved mode to the local profile so the UI and Mihomo keep the
  same mode after stopping or restarting the VPN.
- Lets users browse inline subscription nodes and preselect a node while the
  VPN is stopped; the saved selection is applied on the next start.
- Keeps a names-only cache of provider-backed groups after a live load, so
  previously loaded remote provider nodes remain visible while disconnected.
- Recreates Mihomo's implicit `GLOBAL` selector offline and refreshes provider
  snapshots during startup, including profiles without explicit proxy groups.
- Refreshes the Nodes page when a widget or Quick Settings tile changes the
  VPN state, keeping Select/Preselect and latency actions in sync.
- Prevents profile replacement while the VPN is active, rejects stale proxy
  callbacks, and clears old node state when switching to another subscription.
- Writes mode/profile changes atomically and restores the previous core mode
  if local persistence fails.

## 0.1.9 alpha 10

- Restyles all four native pages with consistent typography, spacing, vector
  icons, tonal surfaces, ripple feedback, and icon-and-label navigation.
- Makes the Home connection panel show the selected node, live duration,
  separate upload/download rates, and a prominent connect/disconnect action.
- Adds node search and group filters, with full-row selection and compact
  latency actions instead of two large buttons per node.
- Groups import, subscription, offline resources, diagnostics, and system
  actions into descriptive Settings rows; keeps the version visible.
- Follows system dark mode, including native dialogs and system-bar icons,
  and aligns widget colors with the app without changing widget sizing.
