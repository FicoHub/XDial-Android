// Minimal AnyConnect (CSTP over TLS, no DTLS) test server for the Android
// emulator. Accepts the fixed test account, hands out 10.99.0.2 and NATs the
// tunnel through a userspace gVisor stack to the host network. Test-only:
// never creates a TUN or changes host networking.
//
//	go run ./test/android/fakeanyconnect -listen 127.0.0.1:18443
package main

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	testUser     = "plane"
	testPassword = "plane-test-only-not-a-secret"
	clientAddr   = "10.99.0.2"
	dnsAddr      = "10.99.0.1"
	upstreamDNS  = "223.5.5.5:53"
)

var cstpHeader = []byte{'S', 'T', 'F', 0x01, 0, 0, 0, 0}

func main() {
	listen := flag.String("listen", "127.0.0.1:18443", "TLS listen address")
	flag.Parse()
	ln, err := tls.Listen("tcp", *listen, &tls.Config{Certificates: []tls.Certificate{selfSigned()}})
	if err != nil {
		log.Fatal(err)
	}
	// Tunnel-only HTTP target, reachable as http://10.99.0.1:18090/ through the VPN.
	go http.ListenAndServe("127.0.0.1:18090", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/big" {
			w.Write([]byte(strings.Repeat("x", 512<<10)))
			return
		}
		fmt.Fprintln(w, "XDIAL_PLANE_OK", r.URL.Path)
	}))
	log.Printf("FAKE_ANYCONNECT_READY %s user=%s", *listen, testUser)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go serve(c)
	}
}

func serve(c net.Conn) {
	defer c.Close()
	r := bufio.NewReader(c)
	for {
		req, err := http.ReadRequest(r)
		if err != nil {
			return
		}
		body, _ := io.ReadAll(req.Body)
		req.Body.Close()
		switch {
		case req.Method == http.MethodConnect:
			if !strings.Contains(req.Header.Get("Cookie"), "webvpn=tok") {
				fmt.Fprint(c, "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
				return
			}
			log.Printf("CSTP_CONNECT from %s", c.RemoteAddr())
			fmt.Fprint(c, "HTTP/1.1 200 CONNECTED\r\n"+
				"X-CSTP-Version: 1\r\n"+
				"X-CSTP-Address: "+clientAddr+"\r\n"+
				"X-CSTP-Netmask: 255.255.255.0\r\n"+
				"X-CSTP-DNS: "+dnsAddr+"\r\n"+
				"X-CSTP-MTU: 1399\r\n"+
				"X-CSTP-DPD: 30\r\n"+
				"X-CSTP-Keepalive: 20\r\n"+
				"X-CSTP-Idle-Timeout: none\r\n"+
				"X-CSTP-Session-Timeout: none\r\n"+
				"\r\n")
			tunnel(c, r)
			return
		case strings.Contains(string(body), `type="init"`):
			reply(c, `<config-auth client="vpn" type="auth-request" aggregate-auth-version="2">`+
				`<auth id="main"><form method="post" action="/auth">`+
				`<input type="text" name="username" label="Username:"/>`+
				`<input type="password" name="password" label="Password:"/>`+
				`</form></auth></config-auth>`, "")
		case strings.Contains(string(body), "<username>"+testUser+"</username>") &&
			strings.Contains(string(body), "<password>"+testPassword+"</password>"):
			log.Printf("AUTH_OK from %s", c.RemoteAddr())
			reply(c, `<config-auth client="vpn" type="complete" aggregate-auth-version="2">`+
				`<session-token>tok</session-token></config-auth>`, "webvpn=tok; Secure")
		default:
			log.Printf("AUTH_FAILED from %s", c.RemoteAddr())
			reply(c, `<config-auth client="vpn" type="auth-request" aggregate-auth-version="2">`+
				`<auth id="main"><error id="88" param1="" param2="">Login failed</error>`+
				`<form method="post" action="/auth"></form></auth></config-auth>`, "")
		}
	}
}

func reply(c net.Conn, xml, cookie string) {
	body := `<?xml version="1.0" encoding="UTF-8"?>` + "\n" + xml
	h := "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: " + fmt.Sprint(len(body)) + "\r\n"
	if cookie != "" {
		h += "Set-Cookie: " + cookie + "\r\n"
	}
	fmt.Fprint(c, h+"\r\n"+body)
}

// tunnel speaks CSTP on the TLS stream and NATs data packets via gVisor.
func tunnel(c net.Conn, r *bufio.Reader) {
	var wmu sync.Mutex
	write := func(typ byte, data []byte) error {
		frame := make([]byte, len(cstpHeader)+len(data))
		copy(frame, cstpHeader)
		binary.BigEndian.PutUint16(frame[4:6], uint16(len(data)))
		frame[6] = typ
		copy(frame[len(cstpHeader):], data)
		wmu.Lock()
		defer wmu.Unlock()
		_, err := c.Write(frame)
		return err
	}
	ns, ep := newNAT()
	defer ns.Close()
	done := make(chan struct{})
	defer close(done)
	go func() {
		for {
			pkt := ep.ReadContext(nilCtx{done})
			if pkt.IsNil() {
				return
			}
			view := pkt.ToView()
			data := append([]byte(nil), view.AsSlice()...)
			pkt.DecRef()
			if write(0x00, data) != nil {
				return
			}
		}
	}()
	hdr := make([]byte, len(cstpHeader))
	for {
		if _, err := io.ReadFull(r, hdr); err != nil {
			log.Printf("CSTP_CLOSED %v", err)
			return
		}
		data := make([]byte, binary.BigEndian.Uint16(hdr[4:6]))
		if _, err := io.ReadFull(r, data); err != nil {
			log.Printf("CSTP_CLOSED %v", err)
			return
		}
		switch hdr[6] {
		case 0x00:
			if len(data) > 0 && data[0]>>4 == 4 {
				ep.InjectInbound(ipv4.ProtocolNumber, stack.NewPacketBuffer(stack.PacketBufferOptions{
					Payload: buffer.MakeWithData(data),
				}))
			}
		case 0x03:
			_ = write(0x04, nil)
		case 0x05:
			log.Printf("CSTP_DISCONNECT")
			return
		}
	}
}

type nilCtx struct{ done chan struct{} }

func (nilCtx) Deadline() (time.Time, bool) { return time.Time{}, false }
func (n nilCtx) Done() <-chan struct{}     { return n.done }
func (n nilCtx) Err() error                { return nil }
func (nilCtx) Value(any) any               { return nil }

func newNAT() (*stack.Stack, *channel.Endpoint) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep := channel.New(1024, 1399, "")
	_ = s.CreateNIC(1, ep)
	_ = s.SetPromiscuousMode(1, true)
	_ = s.SetSpoofing(1, true)
	s.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})

	tcpFwd := tcp.NewForwarder(s, 0, 1024, func(fr *tcp.ForwarderRequest) {
		id := fr.ID()
		target := net.JoinHostPort(id.LocalAddress.String(), fmt.Sprint(id.LocalPort))
		if id.LocalAddress.String() == dnsAddr {
			target = net.JoinHostPort("127.0.0.1", fmt.Sprint(id.LocalPort))
		}
		up, err := net.DialTimeout("tcp4", target, 10*time.Second)
		if err != nil {
			fr.Complete(true)
			return
		}
		var wq waiter.Queue
		tep, tcpErr := fr.CreateEndpoint(&wq)
		if tcpErr != nil {
			up.Close()
			fr.Complete(true)
			return
		}
		fr.Complete(false)
		down := gonet.NewTCPConn(&wq, tep)
		go pipe(down, up)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	udpFwd := udp.NewForwarder(s, func(fr *udp.ForwarderRequest) {
		id := fr.ID()
		target := net.JoinHostPort(id.LocalAddress.String(), fmt.Sprint(id.LocalPort))
		if id.LocalAddress.String() == dnsAddr && id.LocalPort == 53 {
			target = upstreamDNS
		}
		var wq waiter.Queue
		uep, tcpErr := fr.CreateEndpoint(&wq)
		if tcpErr != nil {
			return
		}
		down := gonet.NewUDPConn(s, &wq, uep)
		up, err := net.Dial("udp4", target)
		if err != nil {
			down.Close()
			return
		}
		go func() {
			defer down.Close()
			defer up.Close()
			go func() {
				b := make([]byte, 65535)
				for {
					_ = up.SetReadDeadline(time.Now().Add(60 * time.Second))
					n, err := up.Read(b)
					if err != nil {
						down.Close()
						return
					}
					if _, err := down.Write(b[:n]); err != nil {
						return
					}
				}
			}()
			b := make([]byte, 65535)
			for {
				_ = down.SetReadDeadline(time.Now().Add(60 * time.Second))
				n, err := down.Read(b)
				if err != nil {
					up.Close()
					return
				}
				if _, err := up.Write(b[:n]); err != nil {
					return
				}
			}
		}()
	})
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)
	return s, ep
}

func pipe(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	go func() { io.Copy(a, b); a.Close() }()
	io.Copy(b, a)
}

func selfSigned() tls.Certificate {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "xdial-fake-anyconnect"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("10.0.2.2"), net.ParseIP("127.0.0.1")},
	}
	der, _ := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}
