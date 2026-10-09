# Android 源码移植构建

这是 XDial v0.9.1 的独立 Android 测试移植，包名 `dev.xdial.port`，不是上游官方发行。
源码基线为标签 `v0.9.1` 对应的提交 `9672ecc26cca5db6dd9f77a06561e62788c1993b`。
2026-10-09 核验时，远程 main 与该提交相同。标签是 annotated tag，标签对象 SHA
`2daa00870f3059dfe2500f3b4c400229c342aacb` 与提交 SHA 不同。

上游发布 ZIP 是 macOS 的 `XDial.app`，其 SHA-256 为
`30655048ec3757d46e749b7a27d7d8c06a539df13b21acaebdda74aa5e0cbf20`，已与 release 的校验文件比对。
未执行归档中的任何 macOS 二进制。

## 架构

- Java 原生 Activity、Android Keystore AES-GCM 配置存储、前台 VpnService。
- SagerNet gomobile 将原仓库 `core/libbox` 绑定为 AAR，保留 `with_gvisor,with_utls`、AnyConnect、Tailscale 和原代理协议。
- 原 `GenerateNEConfig` / `BuildConnectionPlan` / `Libbox` 执行移动端配置生成、场景路由、DNS 与协议生命周期。Android 不另写逐流路由器。
- Android 12+ 以 `registerBestMatchingNetworkCallback` 监听系统选出的基础网络，Android 8–11 使用通用 Internet NetworkRequest；不固定 Wi-Fi / 蜂窝优先级。
- Android 提供完整接口快照（名称、索引、MTU、标志、地址）和系统选定的默认 Network；不依赖被 Android 限制的 Go netlink 查询。
- VpnService 在核心打开 TUN 时建立双栈入口，捕获 `0.0.0.0/0` 和 `::/0`。核心以非阻塞、独立 dup 的 fd 运行。失败回收入口；断开等待实际核心清理完成。
- 系统 `protect` 与 `Network.bindSocket` 保护核心 socket。本应用 UID 排除在自己创建的 VPN 外，覆盖 sslcon 等直接创建的连接，避免递归接管。
- Android 自身每用户仅支持一个活动 VPN；不提供 macOS 的多 VPN 叠加承诺。
- 修改配置、切换场景需先断开。尚未实现 Android 的候选场景无中断提交、自动启动或 always-on 支持；不假称复现了 macOS 的完整事务模型。

## 工具链

本次实际使用：Go 1.26.5、Temurin JDK 17.0.20.1+1、Android SDK platform 36、build-tools 36.0.0、NDK 27.1.12297006、SagerNet gomobile/gobind v0.1.13。
工具只放在任务的 `build/android-tools/`；SDK/NDK复用已有安装。不需要 Gradle，不修改宿主全局 Java/Go 配置。

已备好这些依赖的本机，可在仓库根目录执行：

```sh
bash scripts/android/build-apk.sh
```

也可显式传入工具路径：

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.1.12297006"
export JAVA_HOME="/path/to/jdk-17/Contents/Home"
export GOPATH="$PWD/build/android-tools/gopath"
export GOBIN="$PWD/build/android-tools/bin"
export PATH="/path/to/go1.26.5/bin:$GOBIN:$JAVA_HOME/bin:$PATH"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
bash scripts/android/build-apk.sh
```

脚本先调用仓库原有 `scripts/prepare-patched-go-mod.sh` 应用 sing-box、Tailscale、sslcon 的本地补丁，随后编译 ARM64/ARMv7 原生库，使用 SDK 的 aapt2、javac、d8 打包并签名。
源码归档包含本次 Go 模块源码缓存（`build/android-tools/gopath/pkg/mod`）和构建/测试脚本；Go 编译器、JDK、Android SDK/NDK 仍需安装。
编译可复现同一功能和版本；签名私钥、ZIP 时间戳和绝对路径会影响 APK 字节哈希，不承诺跨机器逐字节相同。

产物：`deliverables/XDial-Android-0.9.1-port.apk`。

## 私有测试签名

首次构建在 `build/android-signing/` 生成专用 RSA 3072 位测试密钥与随机密码，后续构建复用。
该目录不提交 Git、不进入源码归档、不公开。要继续覆盖升级，请私下备份这一目录；换密钥后 Android 不能直接覆盖安装已有包。
本次证书 SHA-256：`54de45e7cfd4726cc4aa62489e9320db259548379a5e76fc08c2a4521b2c469e`。

## 验证入口

```sh
# 原配置与移动核心的定向回归，需要符合 Makefile 的 validator 标签
export GOWORK="$PWD/build/patched-go/xdial.work"
go build -tags with_gvisor,with_utls,with_clash_api,with_tailscale \
  -o build/android-tools/bin/sing-box github.com/sagernet/sing-box/cmd/sing-box
PATH="$PWD/build/android-tools/bin:$PATH" \
  go test -tags with_gvisor,with_utls ./core/config ./core/libbox

# 只在独立 Android 测试模拟器运行，不操作真机或宿主网络
bash scripts/android/build-probe.sh
go run -tags with_gvisor,with_utls ./test/android/endpoint
# 另一终端：先将 APK 和 build/android-probe/xdial-probe.apk 安装至 emulator-5580
python3 scripts/android/verify-emulator.py
```

测试端点仅监听 Mac 的 `127.0.0.1:18080`（HTTP）、`127.0.0.1:18081`（UDP）、`127.0.0.1:18388`（Shadowsocks），不会创建宿主 TUN、改代理或改 DNS/路由。
Shadowsocks 服务端仅把模拟器的 `10.0.2.2:18080/18081` 映射到这些回环端点，拒绝其他目的地。
测试 App 的 UID 与 XDial 不同；因此 HTTP、UDP、DNS 测试真实经过 VPN，不是从被排除的 XDial 自身进程发起。
FixtureRunner 是独立、同签名的测试 APK，仅用于注入公开的假凭据。交付 APK 不包含它。

## 许可

XDial 及本次新增代码保留 MIT。链接的 sing-box 等依赖有 GPLv3 等各自许可，因此 APK 不能被描述为“全部 MIT”。
对应源码随交付提供；完整依赖声明及许可证见 `android/assets/THIRD_PARTY_NOTICES.txt`、`android/assets/GPL-3.0.txt` 和各模块的 LICENSE 文件。
未上传公开 Release、应用商店或上游 PR。

接口依据：[Android VPN 指南](https://developer.android.com/develop/connectivity/vpn)、[ConnectivityManager 官方 API](https://developer.android.com/reference/android/net/ConnectivityManager)。
