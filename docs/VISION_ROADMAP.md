# VISION roadmap

## Product surfaces

1. VISION VPN Android client.
2. VISION VPN iOS client.
3. VISION Control Android administrator app.
4. VISION Control responsive web panel.

## Client capabilities target

- One-tap connect/disconnect with deterministic service state.
- Profile catalog, favourites, last-used and auto-select.
- QR / URL / clipboard / file import.
- Engine adapters: VISION Secure, WireGuard/AmneziaWG, OpenVPN, Xray-family, Trojan, Shadowsocks where licensing/platform constraints allow.
- Per-app split tunneling on Android.
- IP/CIDR include/exclude rules.
- Domain rules through the routing/DNS layer when safely implementable.
- LAN bypass, IPv6 policy, MTU, DNS policy, kill-switch guidance, Always-on integration.
- Network handover Wi-Fi ↔ cellular, exponential reconnect, last-known-good cached profile.
- Latency probes, endpoint health, auto-select and failover.
- Traffic counters, session history and diagnostics with secret redaction.
- Import validation and explicit unsupported-engine state: never report a false connection.

## Control plane target

- Nodes / regions / health / versions / rolling upgrade state.
- Clients / devices / groups / expiry / quotas / speed limits / concurrent sessions.
- Protocol profiles and engine capabilities per node.
- Routing policy sets and assignment to devices/groups.
- DNS policies and allow/deny lists.
- RBAC, admin sessions, 2FA, audit, API tokens and webhooks.
- Backups, restore verification and configuration export.
- Metrics: CPU, RAM, disk, tunnel sessions, bytes, errors and latency.
- Alerting and maintenance windows.
- Versioned configuration model suitable for future node agents.

## Compatibility principle

The product-facing name is VISION. Internal `sever1` protocol identifiers remain temporarily for wire compatibility with the currently deployed server. Renaming wire identifiers is a separate protocol migration and must not be mixed with UI rebranding.
