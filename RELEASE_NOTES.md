# VISION VPN Suite 1.0.0 — release notes

This package contains the source for all four deliverables agreed for VISION:

1. VISION VPN Android client.
2. VISION VPN iOS client.
3. VISION Control Android administrator app.
4. VISION Control Web + VISION server components.

The approved visual design has been retained. Internal compatibility identifiers such as `sever1` remain in protocol/storage code so existing deployed profiles and servers are not broken; they are not product branding.

## First deployment target

The safest first production candidate is Android VISION VPN using either:
- VISION Secure/WSS for compatibility on TCP 443;
- WireGuard or AmneziaWG for higher throughput where the network allows it.

Before replacing a live server, make a backup of Control data/key material and existing environment files. Run the included verifier and GitHub Actions. Test connect/disconnect, DNS, IPv4 leak behavior, Wi-Fi↔LTE handover, routing and revocation on a staging client.

## Native Engine Packs

OpenVPN/Xray on Android and WG/AWG/OpenVPN/Xray on iOS are not silently faked. Their profile formats are preserved by the Unified Profile layer, but a final device build needs the corresponding native/provider engine integration. This boundary is visible in `BUILD_STATUS.md`.
