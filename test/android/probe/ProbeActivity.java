package dev.xdial.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.*;
import java.net.*;

public final class ProbeActivity extends Activity {
  public void onCreate(Bundle b) {
    super.onCreate(b);
    TextView text = new TextView(this);
    text.setTextSize(20);
    setContentView(text);
    String url = getIntent().getStringExtra("url");
    String dns = getIntent().getStringExtra("dns");
    boolean udp = getIntent().getBooleanExtra("udp", false);
    new Thread(
            () -> {
              String result;
              try {
                if (udp) {
                  try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(6000);
                    byte[] payload = "XDIAL_UDP_ECHO".getBytes("UTF-8");
                    socket.send(
                        new DatagramPacket(
                            payload, payload.length, InetAddress.getByName("10.0.2.2"), 18081));
                    DatagramPacket reply = new DatagramPacket(new byte[256], 256);
                    socket.receive(reply);
                    result = "UDP_OK " + new String(reply.getData(), 0, reply.getLength(), "UTF-8");
                  }
                } else if (dns != null) {
                  result =
                      "DNS_OK "
                          + dns
                          + " "
                          + java.util.Arrays.toString(InetAddress.getAllByName(dns));
                } else {
                  HttpURLConnection c =
                      (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
                  c.setConnectTimeout(6000);
                  c.setReadTimeout(6000);
                  try (InputStream in = c.getInputStream()) {
                    byte[] data = new byte[1024];
                    int n = in.read(data);
                    result =
                        "HTTP_OK " + c.getResponseCode() + " " + new String(data, 0, n, "UTF-8");
                  }
                  c.disconnect();
                }
              } catch (Exception e) {
                Log.e("XDialProbe", "probe exception", e);
                result = "PROBE_FAILED " + e.getClass().getSimpleName() + " " + e.getMessage();
              }
              String r = result;
              Log.i("XDialProbe", r);
              runOnUiThread(() -> text.setText(r));
            })
        .start();
  }
}
