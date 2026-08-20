# XVPN Android 1.0.0-rc

首个正式版候选源码，基于已确认可连接的 RC1 做增量收口。

## 网络与稳定性

- 智能分流按私网 / 本地、中国域名、中国 IP、最终代理的顺序匹配；全局模式仅保留局域网直连。
- 国内域名使用直拨 AliDNS DoH，其他域名使用经代理的 Google DoH；DNS 仅返回 IPv4，TUN MTU 固定为 1400。
- 拒绝网页 UDP/443，使 UDP 不可用节点上的 QUIC 请求立即回落 TCP/TLS；不会拦截 Core 自身的 Hysteria2 / TUIC 出口。
- 首次连接在发布“已连接”前执行一次真实 HTTPS 健康检查；热切换保留双次验证与失败自动回滚，避免假连接。
- 支持 VLESS、Trojan、VMess、Shadowsocks、Hysteria2、TUIC、AnyTLS 分享配置及常见 TLS / REALITY / transport 参数。

## 交互与系统集成

- 已连接时可直接切换智能 / 全局、打开节点列表、手动切换或自动优选。
- 未连接时自动优选并发测试节点入口；已连接时逐个真实切换候选节点并验证 VPN 隧道，失败自动回退，最终保留实际延迟最低节点。
- 连接球在已连接状态使用独立薄荷绿冰晶边缘；底部卡片、测速反馈、主题切换与深色模式统一短动效。
- 前台通知显示节点、模式、连接时长、实时速率、健康状态与安全断开操作。
- 默认 Panel 使用 `https://xvpn.666101.xyz`；只自动迁移旧内置地址，不改写自定义地址。
- Panel 更新接口故障时回退 GitHub Latest Release；服务端明确暂停更新时不绕过策略。

## 发布要求

GitHub Actions 会执行 14 份协议/分流配置生成测试、lint、签名构建、zipalign、APK v2 签名、包名/版本/ABI、单 DEX、规则资产和固定证书检查。创建 `v1.0.0` 正式 Release 前，仍须按 `QA_CHECKLIST.md` 在实际节点与 Android 真机上完成协议握手、分流、DNS、通知和长时间运行验收。
