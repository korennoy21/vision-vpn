# Changelog

## VISION Control 1.1.0

- Completed Protocols, Routing, DNS/Filters, Backups and Roles/API sections; removed roadmap placeholders from the control UI.
- Added protocol enable/priority/mode policy for VISION, WireGuard, AmneziaWG, OpenVPN and Xray families.
- Added app/domain/CIDR routing rules with VPN, bypass and block actions.
- Added DNS rule management and policy-bundle delivery in VISION subscription profiles.
- Added owner/admin/viewer roles and scoped API tokens.
- Added consistent encrypted-secret-aware backups containing both SQLite and master.key.
- Added telemetry/audit retention settings, preferred engine and Kill Switch policy.
- Added authenticated backup download support in the VISION Control Android wrapper.
- Expanded control regression suite from 45 to 48 tests.


## 1.0.0 — 2026-09-11

### Android VISION VPN
- Unified encrypted profiles and migration from the original single VISION profile.
- QR/file/URL/text import with deterministic protocol detection.
- Embedded WireGuard and AmneziaWG engines alongside VISION Secure/WSS.
- One-button engine controller and corrected manual disconnect semantics.
- Per-app split tunneling plus VISION IPv4/CIDR/domain routing.
- DNS/MTU/local-network settings and physical network awareness.
- Wi-Fi↔LTE reconnect, cached managed profile on transient control-plane outage, explicit revocation remains authoritative.
- Managed client telemetry.
- VISION WSS batching, DATA no-padding and bounded queues.

### VISION Control / server
- Client telemetry storage/API/UI.
- Same-client stale-session replacement for handover.
- Batched frame support.
- Safer handling of invalid/private packet destinations.
- Versioned 1.0.0 source release and release verification script.

### iOS
- VISION-branded SwiftUI client, QR scanner and VISION Secure Packet Tunnel retained as the built-in engine.
- Release metadata updated to 1.0.0.
- Platform limitation and Engine Pack boundaries documented explicitly.

### Not bundled
- QUIC/UDP VISION FastPath.
- OpenVPN/Xray native binaries.
- iOS WG/AWG/OpenVPN/Xray native frameworks.
