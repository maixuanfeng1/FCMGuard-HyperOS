<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

# Vigil · HyperOS App Keep-Alive & Push Diagnostics

A lightweight, strictly read-only, zero-background diagnostic console for HyperOS app keep-alive and push notifications (`com.vigil.app`).

[Download Latest APK](https://github.com/maixuanfeng1/vigil/releases/latest/download/Vigil.apk)

---

## Why Vigil

On HyperOS, reliable app notifications depend on more than the FCM socket alone — they rely on the entire survival and delivery chain across **GMS live connection state, Autostart, battery/no-restrictions policy, Recents task locking, notification permissions, and Android stopped state**.

**Vigil** brings these checks together in a lightweight, transparent, strictly read-only diagnostic console:

- **Permission-free by default**: App discovery, Autostart and notification permission checks, read-only system-list checks, exact per-app settings links, Google FCM diagnostics, and the Quick Settings entry work without Shizuku.
- **Strictly read-only**: Never writes to system whitelists, never mutates battery policies, and exposes no arbitrary shell execution.
- **Zero-background architecture**: No foreground service, no persistent notification, no background periodic polling, no wake locks, and no scheduled wakeups.

---

## Four Core Diagnostic Pillars

| Pillar | What Vigil Inspects | Direct Actions & Boundaries |
| --- | --- | --- |
| **1. GMS / FCM Live Connection & Wakeup** | Reads current-user GMS service connection state, clearly distinguishing `Connected`, `Disconnected`, and `Unknown`; includes network reachability probing and GMS availability checks | Fixed button opens Google Play services’ FCM diagnostics; launcher long-press shortcut and rate-limited (1/min) heartbeat requests (network reachability never claims GMS Connected) |
| **2. App Autostart (`Autostart`)** | Reads HyperOS Autostart status (`Enabled`, `Disabled`, `Partially enabled`, or `Unknown`) | Disabled/partial apps sort first with clear error emphasis and feed the bottom-nav attention badge; opens the exact app’s settings page |
| **3. Battery Policy & No-Restrictions List (`MILLET_NO_RESTRICT_APP` & Doze)** | Read-only check of HyperOS `MILLET_NO_RESTRICT_APP` membership alongside AOSP Doze exemption | Explicitly separates the system reference list from HyperOS’s full battery policy; deep-links directly to that app’s battery page without falling back to a global app list |
| **4. Recents Task Lock (`locked_apps`)** | Read-only parser for HyperOS’s multi-user `locked_apps` setting, displaying `Locked`, `Not locked`, or `Unknown` with live updates on change | Locking keeps the task when clearing recent apps; standard FCM can still deliver with the lock off, so lock status stays neutral and never triggers false alarms. Direct shortcut opens HyperOS Security Center’s lock-task settings (falling back to guided instructions when unavailable) |

For every watched app, Vigil also checks **Android notification permission (`POST_NOTIFICATIONS`)** and the **force-stopped flag (`FLAG_STOPPED`)**, with direct links to that app’s own notification, battery, and details screens. Launchable apps without FCM/GCM component declarations can also be watched and inspected.

---

## Three-Tab Material 3 Interface

- **Connection**: Organized into three distinct outlined Material 3 cards — the live FCM status hero card with its Google diagnostics primary action, a tappable **Watched apps** summary card, and a **Network check** card for manual probing and heartbeat attempts.
- **Apps**: Material 3 **App checks** card (full-width **Choose apps** primary action, secondary **Refresh checks** / **Show all apps** buttons, and live summary) above a softly fading Material 3 card list; paired with a live-searchable app picker (`All` / `FCM/GCM` / `Watched`) and an attention-count badge on the bottom bar. Tapping any card opens a live-updating details sheet structured into **Push checks**, **Background checks**, and **System settings** cards.
- **Settings**: Separated into four focused cards — **Diagnostic access** (**Grant via Shizuku**), **Quick Settings** (**Add tile**), **Appearance** (`System` / `Light` / `Dark`), and **About** (opens the About Vigil screen with manual on-demand GitHub Releases update checking, direct links to the repository, releases, issues, and documentation, diagnostic environment copy, share action, system app settings shortcut, open-source licenses, and privacy/read-only boundaries). Manual ADB grant and revoke commands are documented in [One-Time Diagnostic Grants](docs/DIRECT_DIAGNOSTIC_ACCESS.md).

**Quick Settings Tile & Launcher Shortcut**:
- The Quick Settings tile refreshes every 5 seconds only while the panel is visible (lit/`Connected`, grey disconnected/`Disconnected`, or question mark/`Unknown`), stops polling when hidden, and clears cached lit state on collapse. Tapping attempts a rate-limited heartbeat and opens Google diagnostics.
- Long-press the **Vigil** launcher icon and select **FCM diagnostics** to jump straight to Google’s diagnostic screen with zero extra permissions.

---

## Zero-Background & One-Time Independent Grant

### Zero-Background Guarantees & Honest Limits

- Vigil declares no foreground service permission, shows no persistent notification, and runs no background timers or wake locks.
- System-list (`MILLET_NO_RESTRICT_APP`), Recents lock (`locked_apps`), and default-network observers are always on while Android keeps the process alive, with network reachability probes deferred until Vigil is in the foreground (or manually triggered); process freezing or reclamation pauses passive updates until you open Vigil or expand the Quick Settings tile.
- Automatic connection verdicts expire after 15 monotonic seconds and invalidate immediately on network or permission changes. Unreadable or unsupported outputs stay `Unknown`.
- Vigil is a diagnostic tool — it does not promise continuous offline monitoring or guaranteed message delivery (network/VPN routing, Google services, notification channels, and app-specific push policies all apply).

### One-Time Independent Diagnostic Access (No Running Shizuku Needed)

To enable automatic GMS connection status — and read-only fallback inspection of `locked_apps` when direct settings access is restricted — you can grant Vigil its own diagnostic permissions once:

1. **On-device one-time grant via Shizuku (no computer required)**: Start Shizuku via wireless debugging, open **Vigil → Settings → Diagnostic access**, and tap **Grant via Shizuku**. Vigil requests Shizuku permission if needed, binds its temporary helper, and opens the permission confirmation dialog automatically. The helper verifies the actual Binder caller UID and package name before granting only `DUMP`, `PACKAGE_USAGE_STATS`, and the `GET_USAGE_STATS` AppOp to Vigil itself, then verifies access and retires the helper.
2. **Manual one-time grant via ADB**: Run the exact ADB grant/revoke commands documented in [One-Time Diagnostic Grants](docs/DIRECT_DIAGNOSTIC_ACCESS.md) (`0` is the primary user; use `adb shell pm list users` to find secondary or work profile user IDs).

Once verified, Vigil executes its fixed read-only queries under **its own UID**, allowing you to **stop Shizuku and turn off USB debugging, wireless debugging, and Developer options completely**. See [grant/revoke instructions and validation scope](docs/DIRECT_DIAGNOSTIC_ACCESS.md).

---

## Build, Verification & License

- **Toolchain**: AGP 9.5.1, SHA-256-verified Gradle 9.9.1, JDK 21 / Java 17 source level, `compileSdk 37.2`, `targetSdk 37`, `minSdk 24`.
- **UI & Packaging**: Official Material Components 1.14.0 (Material 3 Views), system-following locale, R8 code/resource shrinking, ABI-neutral pure DEX (64-bit compatible without dummy native libraries). Releases publish a single signed `Vigil.apk` with SHA-256 checksums in release notes.
- **Documentation**:
  - [Building, Signing & Android 17 CI Validation](docs/BUILDING.md)
  - [One-Time Diagnostic Grants & Validation Scope](docs/DIRECT_DIAGNOSTIC_ACCESS.md)
  - [HyperOS 4 On-Device Verification Checklist](docs/HYPEROS_CHECKS.md)

Licensed under MIT — see [LICENSE](LICENSE). HyperOS system-policy research informed by [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix).
