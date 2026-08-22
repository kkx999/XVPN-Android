# XVPN Android v1.0.0

XVPN 私人 VPN Android 客户端。此版本以新的 XVPN Panel v1.0.0 和
Mihomo/Clash Meta 为唯一运行架构。

## 架构

```text
Panel /api/v1
  -> xvpn.nodes.v1 / xvpn.node.v1
  -> Android Mihomo profile builder
  -> Mihomo v1.19.28
  -> Android VpnService / TUN
```

Panel 只负责登录、用户、节点标准化、流量汇总和 App 更新策略。Android
不会接收或兼容旧 sing-box 配置，也不会解析原始分享链接；节点凭据只在本机
转换成 Mihomo YAML，并使用 Android Keystore 加密保存 Always-on 所需的
最近一次已验证配置。

## 保留的产品体验

- 现有 XVPN Logo、启动图和全部图片资源
- 登录与邀请码注册页面
- 首页布局、连接球、浅色/深色主题和过渡动画
- 国家分组节点抽屉、延迟测试、首次自动优选和连接中热切换
- 我的页面、连接诊断、通知卡片和 App 内安全更新
- 智能分流与全局代理切换

## 新数据面

- Mihomo core：`v1.19.28`
- Android wrapper：`libmihomo-android v0.3.1`
- 支持协议：VLESS、VMess、Trojan、Shadowsocks、Hysteria2、TUIC、AnyTLS
- 节点格式：`xvpn.node.v1`
- API Base：`/api/v1`
- 默认 Panel：`https://xvpn.666101.xyz`
- CPU：`arm64-v8a`
- 系统：Android 8.0+（minSdk 26，target/compileSdk 36）

智能分流使用固定提交和 SHA-256 的 MetaCubeX 中国域名/IP MRS 规则，保留
私网与本地域名直连，中国域名/IP 直连，其余公网流量代理。全局代理仍保留
局域网直连。TUN 同时接管 IPv4/IPv6 默认路由，并使用双栈 DNS。

## GitHub Actions 编译

工作流：`.github/workflows/build-apk.yml`

1. 按 `SIGNING_SETUP.md` 配置固定 Release 签名 Secrets。
2. 将源码推送到 `main`，或手动运行 **Build XVPN Android**。
3. 下载 Artifact `XVPN-v1.0.0`，其中包含 APK 与 `SHA256SUMS.txt`。

工作流会运行单元测试和 Android Lint，并核对应用 ID、版本、固定签名、
arm64 ABI、Mihomo 原生库、MRS 规则校验值，以及 APK 中不存在 libbox 和
旧 SRS 规则。

详细真机验收项见 [QA_CHECKLIST.md](QA_CHECKLIST.md)，第三方来源与许可证
见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
