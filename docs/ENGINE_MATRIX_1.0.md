# Engine matrix — VISION VPN 1.0.0

| Engine | Android runtime in archive | iOS runtime in archive | Import/store | Notes |
|---|---:|---:|---:|---|
| VISION Secure (WSS) | yes | yes | yes | Existing managed VISION protocol; WSS/TLS, batched DATA frames |
| WireGuard | yes | no | yes | Android uses embeddable WireGuard tunnel backend |
| AmneziaWG | yes | no | yes | Android uses AmneziaWG Android backend |
| OpenVPN | no | no | yes | Native/provider Engine Pack required |
| VLESS | no | no | yes | Xray Engine Pack required |
| VMess | no | no | yes | Xray Engine Pack required |
| Trojan | no | no | yes | Xray Engine Pack required |
| Shadowsocks | no | no | yes | Xray Engine Pack required |

An unavailable runtime must fail closed with a clear error instead of reporting a false connected state.
