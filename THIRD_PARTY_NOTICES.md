# Third-party notices

## Mihomo / libmihomo-android

This Mihomo migration build uses `libmihomo-android` wrapper `v0.3.1`, which embeds Mihomo (the maintained Clash Meta successor) `v1.19.28` and exposes the Android JNI bridge used by XVPN.

- Mihomo upstream: https://github.com/MetaCubeX/mihomo
- Android JNI wrapper: https://github.com/oviron/libmihomo-android
- Wrapper release: `v0.3.1`
- Bundled Mihomo core: `v1.19.28`
- Bridge ABI: `3`
- XVPN build downloads the pinned AAR from the wrapper's GitHub Release and verifies it against the release `.sha256` file before compilation.
- XVPN currently packages only the `arm64-v8a` libraries from that AAR.

`libmihomo-android` and Mihomo are distributed under GNU GPL version 3. The full GPL-3.0 text remains in `licenses/GPL-3.0.txt`. XVPN uses its own name and does not imply endorsement by Mihomo, MetaCubeX, or the wrapper maintainer.

The previous sing-box/libbox AAR can remain in the source tree while this migration branch is tested, but it is not an Android runtime dependency of the Mihomo build and the CI verifies that `libbox.so` is absent from the generated Mihomo APK.

## SagerNet routing rule sets

The repository still contains the two previously pinned sing-box binary rule assets while the Mihomo migration is being validated:

- `app/src/main/assets/rules/geosite-geolocation-cn.srs`
  - Upstream: https://github.com/SagerNet/sing-geosite
  - Pinned revision: `b3e5c6a15dd82d367ba45cc8c03c81b6fc6b7792`
  - SHA-256: `9cccc08ff669d707d7662ed78cc0a3b2626c4f16a6d151a9167dadb44d3da7b8`
- `app/src/main/assets/rules/geoip-cn.srs`
  - Upstream: https://github.com/SagerNet/sing-geoip
  - Pinned revision: `b9c5e675b4d5359d4b47f4434fa7ae77e9991306`
  - SHA-256: `0acf5dad38fba9db2dade29ce5e4edc6902220944f30628ae46ed16cb0ec5edd`

These `.srs` files are not consumed by Mihomo in the first migration test build; they are retained temporarily so the existing sing-box baseline can be compared and restored without reconstructing source assets. Both rule repositories are distributed under GNU GPL version 3 or later.
