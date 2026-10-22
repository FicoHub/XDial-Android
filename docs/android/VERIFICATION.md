# Android 移植验证报告

## 0.9.1-port.2（2026-10-22）

环境：独立 Android 16 / API 36 Google APIs ARM64 模拟器 `XDial_UI_Test`（Pixel 7 尺寸，serial `emulator-5580`）。工具链在 Mac Studio 上重新安装：Go 1.26.5（官方包，SHA-256 已核对）、Temurin JDK 17、Android SDK platform 36 / build-tools 36.0.0 / NDK 27.1.12297006。Go 核心从源码完整重编。

### 本版改动

- **界面重做**：参考手机「设置」的分组列表。首页为状态卡片 + 连接按钮，依次是「当前场景 / 线路 / 配置 / 更多」。每种线路只显示必要字段，证书、SNI 等收进「高级」。支持深色模式、系统手势栏与刘海屏留白、软键盘不遮挡输入框，并在平板/折叠屏展开时限制内容宽度。
- **AnyConnect 在 Android 上实际不可用的问题已修复**：sslcon 在连接前用 netlink 查询默认路由，Android 10+ 的 SELinux 禁止应用绑定 `netlink_route_socket`，连接在联系服务器前就以 `permission denied` 失败。新增 `vpnc_android.go`，与 iOS 一样把路由/DNS 交给系统 VPN，仅用一次不发包的 UDP connect 取得本机 IPv4 作为客户端标识。上一版只验证了「不可达服务器正确失败」，没有覆盖成功路径。
- 错误提示改为一句可操作的中文，原始错误放在「连接诊断」里，可复制。
- 修改配置或切换场景时不必先断开：保存后可一键重新连接。
- 新增 Shadowsocks / Trojan / VMess / AnyTLS 线路的「测试连接」（复用核心的独立测速会话，不建立 VPN）。
- 导出配置（核心脱敏，不含密码/密钥/UUID）。
- 状态卡片只在内容变化时刷新，界面空闲时不再每 0.5 秒重绘。

### 实测结果

| 测试 | 结果 |
|---|---|
| 真实企业 AnyConnect（ocserv）登录 | 通过真实界面填写账号后连接成功；服务器与账号不写入仓库 |
| 企业 AnyConnect 全局出口 | 独立测试 App 访问 ipinfo.io，出口 IP 从宿主网络的出口变为企业 VPN 出口 |
| 企业 AnyConnect + 分流规则 | 场景规则绑定 Direct 的目标在 VPN 连接期间直连成功 |
| Shadowsocks「测试连接」 | 回环测试服务端可用时显示「可用 · 115 ms」；不可达时显示失败原因 |
| 原功能回归 `verify-emulator.py` | 直连 TCP/UDP、DNS、断开恢复、重连、Shadowsocks TCP/UDP、错误密码不回落、场景规则：全部通过 |
| 生命周期回归 `verify-lifecycle.py` | 完整配置编辑保存、后台、Wi-Fi⇄蜂窝、拒绝授权、全新安装授权、系统设置断开、AnyConnect 不可达失败回滚：全部通过 |
| 从 0.9.1-port.1 覆盖升级 | 签名证书 SHA-256 `54de45e7…469e` 不变，覆盖安装后旧配置可正常解密并显示 |
| 深色模式 / 软键盘 | 截图检查通过 |
| sslcon 单元测试 | `auth`、`rpc`、`vpn` 通过；Android、Linux、iOS 交叉检查通过 |

### 仍未验证

- 真机（含三星折叠屏等厂商 ROM 的后台策略）；以上均为模拟器结果。
- AnyConnect 用户名或密码错误时的提示：映射了 ocserv 的 `Login failed`，但为避免触发企业账号锁定，没有对真实服务器做错误密码尝试。
- Tailscale、Trojan、VMess、AnyTLS 对真实服务端的端到端连接。
- AnyConnect 需要选择用户组的服务器：会提示暂不支持。

---

## 0.9.1-port.1（2026-10-09）

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
