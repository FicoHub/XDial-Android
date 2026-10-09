//go:build !windows

package libbox

import (
	"errors"
	"testing"
)

type testSocketProtector struct {
	fd  int
	err error
}

func (p *testSocketProtector) Protect(fd int) error { p.fd = fd; return p.err }

func TestPlatformSocketProtectorPropagatesFailure(t *testing.T) {
	denied := errors.New("permission revoked")
	host := &testSocketProtector{err: denied}
	p := &xdPlatformInterface{socketProtector: host}
	if !p.UsePlatformAutoDetectInterfaceControl() {
		t.Fatal("must use host protection")
	}
	if err := p.AutoDetectInterfaceControl(42); !errors.Is(err, denied) {
		t.Fatalf("must fail closed: %v", err)
	}
	if host.fd != 42 {
		t.Fatal("wrong socket protected")
	}
	capability := p.lineRuntimeCapability()
	if capability.socketProtector != host {
		t.Fatal("protocol runtime lost host socket protection")
	}
	candidate, err := p.switchCandidate(`[{"name":"test0","index":2,"type":"other"}]`, "test0", 2)
	if err != nil {
		t.Fatal(err)
	}
	if candidate.socketProtector != host {
		t.Fatal("switch lost host socket protection")
	}
}

func TestPlatformUsesFrameworkInterfaceFacts(t *testing.T) {
	p := &xdPlatformInterface{}
	err := p.setNetworkInterfaces([]platformNetworkInterfaceSnapshot{{
		Name: "android-test0", Index: 41, Type: "other", MTU: 1500,
		Flags: 33, Addresses: []string{"10.0.2.16/24", "fd00::2/64"},
	}})
	if err != nil {
		t.Fatal(err)
	}
	p.setDefaultInterface("android-test0", 41)
	if p.defaultInterface.MTU != 1500 || p.defaultInterface.Flags != 33 || len(p.defaultInterface.Addresses) != 2 {
		t.Fatalf("lost framework facts: %+v", p.defaultInterface)
	}
	err = p.setNetworkInterfaces([]platformNetworkInterfaceSnapshot{{Name: "bad", Index: 1, Type: "other", MTU: 1500, Addresses: []string{"invalid"}}})
	if err == nil {
		t.Fatal("accepted invalid interface address")
	}
}
