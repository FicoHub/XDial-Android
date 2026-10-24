// Isolated test listeners only; never creates a TUN or changes host networking.
package main

import (
	"context"
	"fmt"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	ctx := include.Context(context.Background())
	raw := `{"log":{"level":"info","timestamp":true},"inbounds":[{"type":"shadowsocks","tag":"controlled-ss","listen":"127.0.0.1","listen_port":18388,"method":"aes-128-gcm","password":"xdial-test-only-not-a-real-secret"}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"ip_cidr":["10.0.2.2/32"],"port":[18080,18081],"action":"route","outbound":"direct","override_address":"127.0.0.1"},{"domain":["www.gstatic.com"],"port":[443],"action":"route","outbound":"direct"},{"action":"reject"}]}}`
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(raw))
	if err != nil {
		panic(err)
	}
	b, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		panic(err)
	}
	if err = b.Start(); err != nil {
		panic(err)
	}
	defer b.Close()
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fmt.Println("HTTP_PROBE", r.URL.Path)
		fmt.Fprintln(w, "XDIAL_CONTROLLED_ENDPOINT_OK", r.URL.Path)
	})
	server := &http.Server{Addr: "127.0.0.1:18080"}
	go server.ListenAndServe()
	defer server.Close()
	udp, err := net.ListenPacket("udp", "127.0.0.1:18081")
	if err != nil {
		panic(err)
	}
	defer udp.Close()
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, peer, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			udp.WriteTo(buffer[:n], peer)
			fmt.Println("UDP_PROBE", n)
		}
	}()
	fmt.Println("TEST_ENDPOINT_READY 127.0.0.1:18080 + 127.0.0.1:18388")
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
}
