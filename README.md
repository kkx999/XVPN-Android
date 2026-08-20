# XVPN Android

XVPN 私人 VPN Android 客户端。

## 当前版本

`v1.0.0-rc5`（`versionCode 10022`）

1.0.0-rc5 基于 RC3 真机反馈修复特定出口 DNS、已连接入口测速和窄屏显示比例，并修复 RC4 的 Java 编译阻断；Application ID、固定签名链和 sing-box/libbox 1.13.19 数据面保持不变。发布 APK 前仍须完成 `QA_CHECKLIST.md` 的真机协议验收。

变更摘要见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，逐项验收见 [QA_CHECKLIST.md](QA_CHECKLIST.md)。

## 1.0.0-rc5 主要变化

- 智能分流内置版本固定、带 SHA-256 校验的 SagerNet `geosite-geolocation-cn` 与 `geoip-cn` 二进制规则；私网、本地域名与中国域名/IP 直连，其余流量走当前节点。全局代理仍保留局域网直连，所有公网流量走节点。
- 不照搬 Quantumult X 专有 rewrite 或广告规则；采用“本地优先、国内直连、最终代理”的可维护分流顺序，并只拒绝网页 UDP/443，使不支持 QUIC 的节点能立即回落 TCP/TLS，其他 UDP 流量不受影响。
- TUN 使用 IPv4 安全路径、MTU 1400、`mixed` 栈、`strict_route` 与 DNS 劫持。国内域名和节点域名由直连 `223.5.5.5` UDP 负责，其他域名使用经加密代理隧道传输的 `8.8.8.8 TCP DNS`，不再依赖台湾等特定出口对 Cloudflare DoH 的可达性。
- 支持 VLESS、Trojan、VMess、Shadowsocks、Hysteria2、TUIC 与 AnyTLS；VLESS / Trojan / VMess 同时处理常见 TLS、REALITY、uTLS 与 V2Ray transport 分享参数。Panel 仍可保存其他协议，但 App 会明确提示当前不支持。
- 已连接时可以切换智能分流 / 全局代理、打开节点列表、手动换节点或自动优选。首次连接与热重载不仅执行 libbox `checkConfig`，还会通过当前 TUN 发起真实 HTTPS 健康检查；新配置无法解析 DNS 或访问代理出口时自动回滚原节点与原模式，不会发布虚假“已连接”。
- 节点列表与首页统一显示节点入口 TCP 握手延迟，计时不再包含 DNS 查询，恢复更直观的低延迟数值；已连接时优先从物理网络测试，受厂商系统限制时才通过现有隧道回退，避免把其他正常节点误报“不可达”。
- 已连接自动优选不会逐个断开重连，而是在保持当前连接时并发比较入口延迟，只对最终最快节点执行一次热切换和真实联网验证；首次联网检测仅在第一次失败后自动重试一次。
- 前台通知使用系统原生、克制的两层卡片：折叠显示节点与分流模式，展开显示实时上下行、连接时长和“安全断开”。Android 13+ 首次连接请求通知权限，“我的”页面提供通知设置入口。
- 连接球、自动优选、节点结果、底部卡片与主题切换统一为短促的分层动效；浅色和深色分别调整冰晶高光、轮廓与对比度，并遵循系统“移除动画”设置。
- 右上角刷新改为随主题变化的圆形冰晶按钮与单环刷新箭头，刷新时使用沿圆弧移动的克制高光。
- 页面尺寸会根据手机逻辑宽度和高度缩放，窄屏、系统显示放大及大字体手机不再把固定卡片和连接球整体撑得过大。
- “我的 → 连接诊断”可复测当前 DNS、分流、节点协议与代理出口，显示脱敏后的检测站点及响应时间。
- 默认 Panel 使用 `https://xvpn.666101.xyz`。RC2 以此作为新的内置地址基线；后续更换内置地址时，仍使用内置地址的用户会自动迁移，手动保存的自定义 Panel 地址保持不变。
- 当前版本明确关闭 Android 系统 Always-on 能力，避免系统在没有安全持久化节点凭据时伪启动；普通前台 VPN、后台保持和系统通知不受影响。
- Panel 更新策略优先；Panel 临时超时、DNS/TLS 或 5xx 故障时回退 GitHub Release，Panel 明确暂停更新时仍遵守服务端策略。

## 当前测试边界

- CPU：1.0.0-rc5 仅包含 `arm64-v8a`，用于当前 arm64 Android 16 真机及同架构设备。
- 系统：Android 8.0+（minSdk 26），target / compileSdk 36。
- 流量：`TrafficStats` 是 App/Core UID 的代理侧统计，适合产品展示与 Panel 汇总，不是防篡改计费依据。
- 仍需真机验证各节点协议、Wi-Fi / 蜂窝切换、长时间锁屏、分流覆盖、DNS 检测以及各厂商通知栏样式。测试版本号为 1.0.0-rc5 不等于 APK 已通过这些设备项目，正式 Release 应保存完整验收记录。

## GitHub Actions 编译

工作流：`.github/workflows/build-apk.yml`

1. 按 [SIGNING_SETUP.md](SIGNING_SETUP.md) 配置四个固定 Release 签名 Secrets。
2. 将本目录内容上传到 GitHub 仓库根目录。
3. 打开 `Actions` 运行 `Build XVPN Android`，或 push 到 `main`。
4. 下载 Artifact `XVPN-v1.0.0-rc5`，其中包含 APK 与 `SHA256SUMS.txt`。

工作流会核对应用 ID、版本、libbox AAR、内置分流规则校验值、固定签名证书、APK 包名与 `arm64-v8a` ABI。后续 APK 必须继续使用当前固定签名并递增 `versionCode`。

构建基线：

- Release Application ID：`com.xvpn.android`
- Debug Application ID：`com.xvpn.android.debug`
- Java：17
- Android Gradle Plugin：8.13.2
- Gradle：8.13
- Build Tools：35.0.0

## Panel API

客户端按 Panel v1.2.1 使用 `/api/v1` Bearer Token API，包括注册、登录、bootstrap、节点、用户状态、流量上报、更新检查、修改密码与退出。登录 Token 使用 Android Keystore AES/GCM 加密保存；节点配置只交给本机 Core 生成器，不在 UI 中展示或提供复制入口。

## 第三方内核、规则与许可证

1.0.0 内置 sing-box/libbox 1.13.19 arm64 组件，以及固定版本的 SagerNet sing-geosite / sing-geoip 规则文件，受 GPL-3.0-or-later 与对应上游声明约束。来源、提交与 SHA-256 见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，许可证文本位于 `licenses/`。
