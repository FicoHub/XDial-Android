package engine

import (
	"fmt"
	"sync"

	"sslcon/auth"
	"sslcon/base"
	"sslcon/rpc"
	"sslcon/session"
)

const forcedAnyConnectDPDSeconds = 5

// maxAnyConnectTunnelMTU caps the inner packet size handed to the tunnel.
// The server advertises the CSTP (TCP) MTU, but the same packets also ride
// DTLS, where encapsulation adds ~60 bytes on top. In-flight and other
// tunnelled Wi-Fi often has a path MTU below 1500 and drops ICMP, so oversized
// DTLS datagrams vanish while small DPD probes still pass: the line looks
// connected yet pages hang. 1280 (the IPv6 minimum) fits such paths.
const maxAnyConnectTunnelMTU = 1280

func anyConnectTunnelMTU(advertised int) int {
	if advertised <= 0 || advertised > maxAnyConnectTunnelMTU {
		return maxAnyConnectTunnelMTU
	}
	return advertised
}

type anyConnectDPDConfigurator interface {
	SetXDialForceDPD(seconds int)
}

type anyConnectSessionDiagnostics interface {
	XDialDiagnosticsJSON() string
}

type VPNConfig struct {
	Server        string
	DialAddress   string // optional numeric socket target; Server remains TLS SNI / HTTP identity
	Username      string
	Password      string
	AllowInsecure bool // true 时跳过服务器证书验证（自签场景 opt-in）
}

type VPNInfo struct {
	VPNAddress string
	VPNMask    string
	DNS        []string
	MTU        int
	ServerAddr string
}

type VPNClient struct {
	mu   sync.Mutex
	info *VPNInfo
}

func NewVPNClient() *VPNClient {
	return &VPNClient{}
}

func (c *VPNClient) Connect(cfg VPNConfig) (*VPNInfo, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	base.Cfg.NoTUN = true
	base.Cfg.InsecureSkipVerify = cfg.AllowInsecure // 默认 false=验证证书，自签需显式 opt-in
	base.Cfg.CiscoCompat = true
	base.Cfg.LogLevel = "Info"
	dpdConfig, ok := any(base.Cfg).(anyConnectDPDConfigurator)
	if !ok {
		return nil, fmt.Errorf(
			"AnyConnect runtime does not support the required DPD policy",
		)
	}
	dpdConfig.SetXDialForceDPD(forcedAnyConnectDPDSeconds)
	base.InitLog()

	auth.Prof.Host = cfg.Server
	auth.Prof.DialAddress = cfg.DialAddress
	auth.Prof.Username = cfg.Username
	auth.Prof.Password = cfg.Password

	if err := rpc.Connect(); err != nil {
		return nil, fmt.Errorf("AnyConnect connect: %w", err)
	}

	cSess := session.Sess.CSess
	if cSess == nil {
		return nil, fmt.Errorf("no session after connect")
	}

	c.info = &VPNInfo{
		VPNAddress: cSess.VPNAddress,
		VPNMask:    cSess.VPNMask,
		DNS:        cSess.DNS,
		MTU:        anyConnectTunnelMTU(cSess.MTU),
		ServerAddr: cSess.ServerAddress,
	}

	return c.info, nil
}

func (c *VPNClient) Session() *session.ConnSession {
	return session.Sess.CSess
}

func (c *VPNClient) Disconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()
	session.Sess.ActiveClose = true
	if cSess := session.Sess.CSess; cSess != nil {
		cSess.Close()
	}
	c.info = nil
}

// AnyConnectSessionDiagnostics returns a credential-free, structured snapshot
// from the patched sslcon I/O boundary. Keeping the capability behind a narrow
// interface lets ordinary source inspection still compile against the pristine
// vendored module, while production Make targets require the staged patch.
func AnyConnectSessionDiagnostics(cSess *session.ConnSession) string {
	if cSess == nil {
		return ""
	}
	diagnostics, ok := any(cSess).(anyConnectSessionDiagnostics)
	if !ok {
		return ""
	}
	return diagnostics.XDialDiagnosticsJSON()
}
