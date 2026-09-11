# VISION VPN Suite 1.0.0 — release status

This is the packaged **source release** of the VISION project state that can be verified in this environment.

## Included and implemented

### VISION VPN Android
- approved VISION design and one connect/disconnect button;
- encrypted AES-GCM multi-profile vault with migration of the old VISION profile;
- QR, file, URL and pasted-text import;
- deterministic detection of VISION, WireGuard, AmneziaWG, OpenVPN, VLESS, VMess, Trojan and Shadowsocks profiles;
- working source adapters for VISION/WSS, embedded WireGuard and embedded AmneziaWG;
- Android per-app VPN/bypass selection;
- IPv4/CIDR/domain route policies for the VISION engine;
- DNS/MTU/local-network controls where the selected engine supports them;
- physical-network tracking and accelerated Wi-Fi↔LTE reconnect;
- manual STOP path uses non-sticky service semantics;
- managed VISION telemetry: RX/TX, RTT, reconnects, endpoint and network type;
- WSS performance patch: DATA without random padding, batching/coalescing and bounded backpressure.

### VISION VPN iOS
- approved SwiftUI interface and one-button flow;
- VISION Secure Packet Tunnel;
- file/link/text import and camera QR scanner;
- route/DNS/local-network controls within Network Extension constraints;
- iOS cannot offer arbitrary per-app selection for normal unmanaged devices in the same way as Android.

### VISION Control 1.1.0
- web dashboard, clients, nodes, events/audit, policies and telemetry;
- protocol policy matrix for VISION/WireGuard/AmneziaWG/OpenVPN/Xray;
- central routing rules for app/domain/IP-CIDR with vpn/bypass/block actions;
- DNS resolver policy and allow/block/vpn/bypass domain rules;
- versioned policy bundle embedded into refreshed VISION profiles;
- consistent SQLite + master.key backup creation/download/delete workflow;
- owner/admin/viewer roles and one-time Bearer API tokens with read/write scopes;
- Android administrative app uses HTTPS-only WebView, never bypasses TLS errors and supports authenticated backup downloads;
- Control plane does not return client tunnel secrets in state/telemetry snapshots.

### Server
- batched VISION frames with backwards-compatible single-frame support;
- stale same-client session replacement for handover;
- invalid private/link-local client destinations are dropped rather than terminating the full session;
- existing WSS/TLS server and deployment layout retained for safe migration.

## Deliberately not misrepresented as bundled

- OpenVPN native engine is not embedded in this archive; its profiles can be imported/stored and require an Engine Pack/provider integration.
- Xray native core for VLESS/VMess/Trojan/Shadowsocks is not embedded.
- iOS WireGuard/AmneziaWG/OpenVPN/Xray native frameworks are not embedded.
- VISION QUIC/UDP FastPath is not present in 1.0.0; VISION Secure is the optimized WSS transport.

These are hard binary/runtime dependencies, not UI flags. The release does not show an unavailable engine as connected.

## Verification boundary

The local release verifier can run Python/server/control tests and static project checks. Android APK compilation requires Android SDK/Maven artifacts; the included GitHub Actions workflow runs `assembleDebug` and `lintDebug` for both Android applications. iOS device deployment additionally requires Apple signing and Network Extension entitlement.
