//go:build android

package vpnc

import (
	"net"

	"sslcon/base"
	"sslcon/session"
)

// VPNAddress 与桌面版保持同名,供包内路由函数引用(Android 下为死代码)。
var VPNAddress string

// Android 上 XDial 恒为 base.Cfg.NoTUN == true:TUN、路由和 DNS 由 VpnService 与
// sing-box 负责,sslcon 只做 AnyConnect 认证与 CSTP/DTLS 数据通道。
//
// Android 10 起普通应用不能 bind netlink_route socket(SELinux 拒绝),因此不能
// 复用 vpnc_linux.go 的 netlink 默认路由查询,也不能依赖 net.Interfaces()。
// 下面的路由/DNS 函数保持空实现;GetLocalInterface 只取客户端标识所需的 IPv4。

// ConfigInterface 在 Android(NoTUN)下为空操作。
func ConfigInterface(cSess *session.ConnSession) error {
	return nil
}

// SetRoutes 在 Android(NoTUN)下为空操作。
func SetRoutes(cSess *session.ConnSession) error {
	return nil
}

// ResetRoutes 在 Android(NoTUN)下为空操作。
func ResetRoutes(cSess *session.ConnSession) {
}

// DynamicAddIncludeRoutes 在 Android(NoTUN)下为空操作。
func DynamicAddIncludeRoutes(ips []string) {
}

// DynamicAddExcludeRoutes 在 Android(NoTUN)下为空操作。
func DynamicAddExcludeRoutes(ips []string) {
}

// GetLocalInterface 通过一次不发包的 UDP connect 让内核按默认路由选出源 IPv4,
// 不需要 netlink。AnyConnect 握手只把 Ip4/Mac 当作客户端标识;取不到时留空,
// 不阻断连接。Android 不向应用暴露真实 MAC,Mac 恒为空。
func GetLocalInterface() error {
	base.LocalInterface.Name = "android"
	base.LocalInterface.Ip4 = ""
	base.LocalInterface.Gateway = ""
	base.LocalInterface.Mac = ""
	conn, err := net.Dial("udp4", "8.8.8.8:53")
	if err != nil {
		base.Info("GetLocalInterface: no IPv4 default route:", err)
		return nil
	}
	defer conn.Close()
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok && addr.IP.To4() != nil {
		base.LocalInterface.Ip4 = addr.IP.String()
	}
	return nil
}
