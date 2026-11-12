<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

# FCM Guard · Push diagnostics

[Download APK](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest/download/FCMGuard-HyperOS.apk)

Version 3 refocuses this HyperOS tool on the apps whose notifications matter to you. The dashboard, system-list reader, component scan, app settings links and Quick Settings entry work without Shizuku authorization. No whitelist writes, persistent notification, foreground service, periodic polling, wake lock, or scheduled wakeup is used.

## Workflow

1. Open Google Play services' FCM diagnostics using the fixed home-screen button. Record Connected or Disconnected as a dated **manual observation**. Network changes invalidate that observation. This is not a continuously verified connection status.
2. Choose your notification apps from launchable apps, including those without detected FCM/GCM declarations. Components establish capability, not a working token, actual message route or guaranteed delivery.
3. Inspect Autostart, read-only `MILLET_NO_RESTRICT_APP` membership, Android notification permission, stopped flag and AOSP Doze exemption. Unknown is distinct from enabled. Notification master switches/channels, in-app mute settings and recents locking require review where a supported query is unavailable. System-list membership and the HyperOS UI's complete battery policy are not treated as interchangeable.
4. Open the exact selected app's notification, battery or app-details settings. Record manual checks separately, including a real message received after locking the phone. App updates invalidate app-specific confirmation records. Retest after settings/network changes.
5. Add the Quick Settings tile. Visibility starts checks; tapping attempts a rate-limited heartbeat request and opens GMS diagnostics. A network probe or broadcast does not certify reconnection. The tile never lights up as proof of a continuous FCM connection.

## Version 3.0.1

Authorization, helper readiness and socket-query results are displayed independently. An authorized helper with restricted or unsupported socket access is not called unauthorized. Checks refresh immediately after binding and independently of the network probe; replies from a disabled or replaced helper are discarded. The socket query uses kernel UID metadata without process-name scanning.

## Optional Shizuku

Off by default, even after upgrading an older authorized installation. Enabling it explicitly allows fixed, read-only helper operations: read the system projection and look for GMS-UID-attributed established sockets on standard push ports. An observed socket is transport evidence, not Diagnostics Connected. Absence is inconclusive because ports/visibility vary. No arbitrary command or settings-write interface is exposed.

## Background limits

While Android lets the app process run, system-list and network callbacks refresh checks. Process freezing/reclamation can interrupt them until the tile or app is opened. There is no claim of continuous offline detection, permanent Connected state, or guaranteed delivery for Twitter/X, Telegram, Slack or other apps. Network/VPN conditions, Google services, platform delivery, notification channels and app-specific policies all matter. Telegram variants can use their own connection.

## Build and validation

AGP 9.5.1, SHA-256-verified Gradle 9.9.1, JDK 21 / Java 17, compile SDK 37.2, target API 37, minSdk 24. Official Material Components 1.14.0 and Material 3 Views; language follows the system. ABI-neutral DEX supports 64-bit devices without dummy native libraries. Only Android 17 images are used for device CI. See [BUILDING](docs/BUILDING.md).

Releases attach one signed `FCMGuard-HyperOS.apk`; previews/test reports stay in CI and checksums are included in release notes. The signing key is unchanged from v2.x.

Based on [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS). System-policy research: [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix). MIT, see [LICENSE](LICENSE).
