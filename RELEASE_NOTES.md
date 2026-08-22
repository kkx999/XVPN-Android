# XVPN Android v1.0.0

全新 Mihomo 架构基线，配套 XVPN Panel v1.0.0。

- 保留现有 Logo、图片、登录注册、首页、主题、动画、节点抽屉、延迟测试、
  自动优选、设置页和 App 更新界面。
- 删除 sing-box/libbox、旧 SRS 规则、旧配置生成器和旧节点配置兼容路径。
- 只接受 Panel 的 `xvpn.nodes.v1` / `xvpn.node.v1` 标准节点。
- 使用 Mihomo v1.19.28 + libmihomo-android v0.3.1。
- 支持 VLESS、VMess、Trojan、Shadowsocks、Hysteria2、TUIC、AnyTLS。
- 智能分流使用校验固定的 MetaCubeX 中国域名/IP MRS 规则；全局模式保留
  私网直连，其他公网流量全部代理。
- IPv4/IPv6 TUN、DNS、真实联网验证、失败回滚、连接中切换、Always-on、
  流量上报和通知卡片均接入新的 Mihomo 服务。
- App 更新策略直接读取 Panel v1.0.0 `/api/v1/app/update`。
