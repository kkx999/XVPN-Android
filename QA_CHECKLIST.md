# XVPN Android v1.0.0 验收清单

## 自动检查

- [x] Application ID 保持 `com.xvpn.android`
- [x] versionName / versionCode 为 `1.0.0 / 10000`
- [x] minSdk 26、target/compileSdk 36、Java 17、arm64-v8a
- [x] Panel API 固定使用 `/api/v1`
- [x] 只接受 `xvpn.nodes.v1` 和 `xvpn.node.v1`
- [x] Mihomo AAR 与两份 MRS 规则在构建时执行 SHA-256 校验
- [x] APK 必须含 `libclash.so`、`libmihomo-jni.so` 和两份 MRS
- [x] APK 不得含 libbox、sing-box Java 绑定或旧 SRS 规则
- [x] VLESS、VMess、Trojan、Shadowsocks、Hysteria2、TUIC、AnyTLS
  标准节点均有配置生成单元测试
- [x] 智能分流与全局代理规则差异有单元测试
- [x] Release APK 使用既有固定签名证书

## 真机必测

- [ ] 登录、邀请码注册、修改密码、退出登录
- [ ] Panel v1.0.0 bootstrap、节点刷新、失效节点自动断开
- [ ] 七类协议至少各连接一个真实节点
- [ ] 智能分流：中国网站直连、其他网站代理、IP 检测不泄漏真实公网 IP
- [ ] 全局代理：所有公网流量代理，局域网仍可访问
- [ ] IPv4/IPv6、DNS 泄漏与 QUIC/TCP 回落
- [ ] 连接中切换模式、手动换节点、自动优选和失败回滚
- [ ] Wi-Fi/蜂窝切换、锁屏、进程重启、Always-on
- [ ] 今日/月/累计流量与 Panel 上报时间
- [ ] 通知权限、实时速率、连接时长和安全断开
- [ ] 最低版本强制更新、APK 下载、SHA-256/包名/签名校验
- [ ] 浅色/深色、动画关闭、大字体、窄屏和系统手势区
