# XVPN Android v1.0.0 Mihomo Native Migration

## Goal
Migrate from legacy core integration to Mihomo Native Edition.

## Architecture
Panel v1.0.0 API -> xvpn.node.v1 -> Mihomo Config Builder -> Mihomo Core -> VPN Service

## Preserve
- Current UI
- Theme
- Animations
- Login flow design
- Node drawer design
- Latency testing UX

## Replace
- Legacy core adapter
- Old node model
- Old configuration builder
- Connection state handling
