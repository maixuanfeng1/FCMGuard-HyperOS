<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

# FCM Guard · Push diagnostics

[Download APK](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest/download/FCMGuard-HyperOS.apk)

Version 3 refocuses this HyperOS tool on the apps whose notifications matter to you. The dashboard, system-list reader, component scan, app settings links and Quick Settings entry work without Shizuku authorization. No whitelist writes, persistent notification, foreground service, background periodic polling, wake lock, or scheduled wakeup is used.

## Workflow

1. View the recent automatically read GMS connection state. Open Google Play services’ FCM diagnostics using the fixed home-screen button when needed; returning resumes automatic checks without a recording or confirmation dialog.
2. Choose your notification apps from launchable apps, including those without detected FCM/GCM declarations. Components establish capability, not a working token, actual message route or guaranteed delivery.
3. Inspect Autostart, read-only `MILLET_NO_RESTRICT_APP` membership, Android notification permission, stopped flag and AOSP Doze exemption. Unknown is distinct from enabled. Notification master switches/channels, in-app mute settings and recents locking require review where a supported query is unavailable. System-list membership and the HyperOS UI's complete battery policy are not treated as interchangeable.
4. Open the exact selected app’s notification, battery or app-details settings. Unsupported checks retain explanatory text and settings links without checkboxes or result recording.
5. Add the Quick Settings tile. Visibility starts checks; tapping attempts a rate-limited heartbeat request and opens GMS diagnostics. A network probe or broadcast does not certify reconnection. With optional Shizuku, the tile reports current GMS service state: lit/Connected, grey disconnected icon/Disconnected, or a question mark/Unknown. Unknown stays clickable for diagnostics and never means off.

## Version 3.1.1: automatic checks without manual records

Remove diagnostic recording, the confirmation dialog on return, historical user-reported connection state and per-app confirmation checklists. Upgrades clear old records and pending-dialog flags while retaining selected apps, Shizuku preferences, themes and request rate limits. Connection status comes only from automatic reads; unreadable state stays unknown.

## Version 3.1: connection tile

Only a successful read of the correct profile and current GMS connection fields supplies Connected/Disconnected. Historical events, reachability, sockets and authorization cannot light the tile. The dashboard and tile share the same snapshot.

Reads refresh every five seconds only while the dashboard or quick-settings panel is visible. Hidden surfaces stop their refresh callbacks. Verdicts expire after 15 monotonic seconds and invalidate immediately on network, authorization or helper changes. Hiding the panel clears its lit state so SystemUI cannot cache a stale connection claim. Tapping still checks, attempts a heartbeat and opens diagnostics; it does not turn off GMS push.

The private GMS dump format can change. Denied, unavailable, unsupported or timed-out reads remain Unknown. Permission-free mode retains the diagnostic shortcut; automatic connection indication needs Shizuku.

## Version 3.0.1

Authorization, helper readiness and socket-query results are displayed independently. An authorized helper with restricted or unsupported socket access is not called unauthorized. Checks refresh immediately after binding and independently of the network probe; replies from a disabled or replaced helper are discarded. The socket query uses kernel UID metadata without process-name scanning.

## Optional Shizuku

Off by default, even after upgrading an older authorized installation. Enabling it explicitly allows fixed, read-only helper operations: read the system projection and current GMS push-service state for the correct user. Connection indication no longer depends on restricted socket UID enumeration. No arbitrary command or settings-write interface is exposed.

## Background limits

While Android lets the app process run, system-list and network callbacks refresh checks. Process freezing/reclamation can interrupt them until the tile or app is opened. There is no claim of continuous offline detection, permanent Connected state, or guaranteed delivery for Twitter/X, Telegram, Slack or other apps. Network/VPN conditions, Google services, platform delivery, notification channels and app-specific policies all matter. Telegram variants can use their own connection.

## Build and validation

AGP 9.5.1, SHA-256-verified Gradle 9.9.1, JDK 21 / Java 17, compile SDK 37.2, target API 37, minSdk 24. Official Material Components 1.14.0 and Material 3 Views; language follows the system. ABI-neutral DEX supports 64-bit devices without dummy native libraries. Only Android 17 images are used for device CI. See [BUILDING](docs/BUILDING.md).

Releases attach one signed `FCMGuard-HyperOS.apk`; previews/test reports stay in CI and checksums are included in release notes. The signing key is unchanged from v2.x.

Based on [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS). System-policy research: [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix). MIT, see [LICENSE](LICENSE).
