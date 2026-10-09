# Android 移植验证报告

验证日期：2026-10-09。环境：独立 AOSP Android 16 / API 36 ARM64 模拟器 `XDial_Port_Test`（serial `emulator-5580`）。无真机接入；未读取私人 VPN 账号、订阅或 Tailscale 身份。

## APK

- 包名 `dev.xdial.port`；版本 `0.9.1-port.1` / `90101`。
- minSdk 26 / Android 8.0，targetSdk 35，compileSdk 36。
- ABI：`arm64-v8a`、`armeabi-v7a`。ARMv7 已编译打包，未在 ARMv7 设备执行。
- 专用私有测试证书，RSA 3072 位，APK v2/v3 签名校验通过；不属于上游官方签名。
- ARM64 ELF LOAD 段对齐 `0x4000`（16 KB）；APK zipalign 检查通过。
- 覆盖安装与全新安装均成功，原生 Go 库加载成功，中文 Activity 启动成功。
- APK SHA-256 以交付的 `.apk.sha256` 文件为准；对应源码与依赖源码另行归档。

## 已通过的真实 Android 测试

| 测试 | 证据 / 结果 |
|---|---|
| 直连 TCP | 独立测试 App 经 VPN 访问受控 HTTP 端点，HTTP 200 |
| 直连 UDP | 经 TUN 收到 `XDIAL_UDP_ECHO` 回包 |
| DNS | VPN 内解析 example.com，返回 IPv4 和 IPv6 记录 |
| Shadowsocks TCP | 服务端记录成功解密、目标地址及 HTTP 200 |
| Shadowsocks UDP | 服务端记录 UDP 转发，测试 App 收到回包 |
| 错误密码 | 服务端认证失败，测试 App 请求失败；受控 HTTP 端点未收到该请求，无 Direct 回落 |
| 场景规则 | 默认线路使用错误密码代理，但显式 CIDR 规则绑定 Direct，指定目标成功返回 HTTP 200 |
| 断开 / 重连 | 核心 TUN fd 关闭，Android 撤销 VPN 后普通联网恢复；重连后再次通过 TUN |
| 后台 | 离开 XDial 界面后，前台 VPN 服务仍转发，独立测试 App 成功访问 |
| 网络切换 | 模拟器 Wi-Fi → 蜂窝 → Wi-Fi，三次均成功访问受控端点 |
| 配置导入 | 真实 UI 的配置编辑 / 保存调用原 Go 导入器后成功连接 |
| 拒绝授权 | 新安装后点系统 CANCEL，不建立 VPN |
| 允许授权 | 随后通过系统 VPN 与通知权限界面，成功联网 |
| 系统侧断开 | Android VPN 设置中断开，应用显示授权已撤销并释放服务 |
| AnyConnect 失败回滚 | 假凭据连接本地不可达端点，明确失败且未留下 VPN 入口 |

测试 App 的 UID 不在 XDial 自身的 VPN 排除集合中。HTTP/UDP 服务只监听宿主回环；没有通过修改 Mac VPN、代理、DNS 或默认路由来测试。

Android 16 模拟器在应用关闭全部 TUN fd 后，约 10 秒才撤销系统的 VPN 网络记录。验收等待真实系统状态移除后才检查恢复，未把“UI 显示断开”等同于“系统立即恢复”。

## 支持与未验证边界

| 功能 | 本次状态 |
|---|---|
| Direct / Shadowsocks / 场景 CIDR 规则 | Android 实测通过 |
| AnyConnect | 原 sslcon + Go bridge 保留并编译，失败回滚已测；无真实企业账号会话验证 |
| Tailscale / Exit Node / MagicDNS | 原核心及 Android 登录桥已接入；无真实 tailnet / 节点会话验证 |
| Trojan / VMess / AnyTLS | 原核心保留并编译；未对真实服务端做端到端验证 |
| selector / urltest / 远程规则 | 使用原编译器和运行时；未对所有导入格式与规则组合实测 |
| IPv6 | 双栈 TUN 捕获和 AAAA 查询已验证；IPv6 端到端服务连通性未验证 |
| 真机 / Android 8–15 / 厂商后台策略 | 未验证，minSdk 声明不是设备兼容性验收 |
| macOS 应用规则、无中断场景切换、常驻 VPN / 开机自启 | 尚未移植，界面或启动阶段明确限制 |

不能据此宣称“全部 macOS 功能已移植”或“所有协议已经实测成功”。移动端沿用原 iOS/tvOS 的 DNS 路径，具体差异见安装说明。

## 源码验证

- `go test -tags with_gvisor,with_utls ./core/config ./core/libbox` 通过。配置测试使用 Makefile 对应标签构建的 patched sing-box validator。
- 新增平台接口快照测试、socket 保护失败传播测试和运行能力 / 候选继承测试。
- 原仓库公开内容检查通过；Git diff 格式检查通过。
- 构建和验证脚本位于 `scripts/android/`；隔离测试客户端 / 服务端源码位于 `test/android/`。
- 原始过程证据保存在工作区 `build/android-evidence/`，交付报告仅列通过的最终验收与明确边界。
