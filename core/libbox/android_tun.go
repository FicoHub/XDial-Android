//go:build android

package libbox

import (
	"fmt"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"syscall"
)

// AndroidTunHost owns the Android VPN consent and original descriptor. It is
// called only when the core opens its ingress, after protocol preparation.
type AndroidTunHost interface{ OpenTun() (int, error) }

func (l *Libbox) SetAndroidSocketProtector(host SocketProtector) {
	l.platform.mu.Lock()
	l.platform.socketProtector = host
	l.platform.mu.Unlock()
}

func (l *Libbox) SetAndroidTunHost(host AndroidTunHost) {
	l.SetTunOpener(func(options *tun.Options, _ option.TunPlatformOptions) (tun.Tun, error) {
		fd, err := host.OpenTun()
		if err != nil {
			return nil, err
		}
		if fd < 0 {
			return nil, fmt.Errorf("Android VPN permission unavailable")
		}
		dup, err := syscall.Dup(fd)
		if err != nil {
			return nil, err
		}
		// Go's poller must be able to interrupt a pending read on disconnect.
		// A blocking TUN read can otherwise retain the VPN after Close returns.
		if err = syscall.SetNonblock(dup, true); err != nil {
			syscall.Close(dup)
			return nil, err
		}
		options.FileDescriptor = dup
		options.AutoRoute = false
		options.MTU = 1500
		device, err := tun.New(*options)
		if err != nil {
			syscall.Close(dup)
			return nil, err
		}
		name, err := device.Name()
		if err != nil {
			device.Close()
			return nil, err
		}
		if options.InterfaceMonitor != nil {
			options.InterfaceMonitor.RegisterMyInterface(name)
		}
		return wrapTunWithDiagnostics(device, l.platform), nil
	})
}
