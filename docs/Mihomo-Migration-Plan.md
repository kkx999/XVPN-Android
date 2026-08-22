# XVPN Android v1.0.0 Mihomo Native Architecture

## Status
Implemented. The legacy data plane is no longer part of the application build.

## Architecture
Panel v1.0.0 API -> xvpn.node.v1 -> Mihomo Profile Builder -> Mihomo Core -> Android VPN Service

## Preserve
- Current UI
- Theme
- Animations
- Login flow design
- Node drawer design
- Latency testing UX

## Removed
- sing-box/libbox runtime and bindings
- Legacy raw node/config compatibility
- Old configuration builder and SRS rules
- Legacy core service implementation
