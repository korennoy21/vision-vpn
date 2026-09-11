# VISION VPN Suite 1.0.0 + VISION Control 1.1.0

VISION VPN Suite — self-hosted VPN ecosystem with three applications and one web control panel, built on the approved VISION interface without changing the visual concept.

## Components

- `android-client/` — **VISION VPN for Android**: one-button connect/disconnect, encrypted Unified Profiles, QR/file/link/text import, VISION Secure, embedded WireGuard and AmneziaWG, per-app split tunneling, IPv4/CIDR/domain rules for VISION, DNS/MTU controls, Wi-Fi↔LTE reconnect and telemetry.
- `ios-client/` — **VISION VPN for iOS**: SwiftUI client, QR/file/link/text import, VISION Secure Packet Tunnel, route/DNS settings within iOS platform constraints and Wi-Fi↔LTE recovery.
- `android-admin/` — **VISION Control for Android 1.1.0**: TLS-only administration app for the web panel, including authenticated backup downloads.
- `control/` — **VISION Control Web 1.1.0**: clients, nodes, protocol policies, routing, DNS filters, backups, roles, API tokens, audit and client telemetry.
- `sever/`, `ops/` — VISION Secure server/data-plane and deployment files.

## Protocol status in 1.0.0

| Platform | VISION Secure | WireGuard | AmneziaWG | OpenVPN | VLESS/VMess/Trojan/SS |
|---|---|---|---|---|---|
| Android | built-in | built-in | built-in | profile import / Engine Pack slot | profile import / Engine Pack slot |
| iOS | built-in | Engine Pack required | Engine Pack required | Engine Pack required | Engine Pack required |

`Engine Pack required` is intentional wording: this source archive does **not** pretend that absent native binaries are bundled. The profile layer preserves these configurations so a compatible signed/native engine can be connected without changing the approved UI.

## Release verification

Run:

```bash
./verify_release.sh
```

The script checks server/control tests, Python bytecode compilation, Control JavaScript syntax, Android/iOS project metadata, XML parsing, shell syntax, version consistency and archive-sensitive files.

Android APKs are built in GitHub Actions (`VISION build and verify`). iOS requires an Apple Developer Team, provisioning profiles and Network Extension entitlement for a device-installable build.

## Upgrade

Do **not** overwrite the live VPS blindly. Preserve `data/`, `panel.env`, `node.env`, `node.json` and Caddy data. Read `docs/UPGRADE_FROM_SEVER_0.4.md` and `RELEASE_NOTES.md` before deployment.

## Security / scope

VISION Secure currently uses WSS/TLS on the server path. The performance patch includes DATA no-padding, bounded queues and WebSocket frame batching. A separate QUIC/UDP FastPath is **not bundled** in 1.0.0; do not label WSS as QUIC. WireGuard/AmneziaWG are the high-throughput engines currently embedded on Android.
