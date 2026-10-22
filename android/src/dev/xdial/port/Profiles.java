package dev.xdial.port;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only helpers over the core's profile JSON; edits keep every unknown field intact. */
final class Profiles {
  private Profiles() {}

  static final String[] TYPES = {"vpn", "shadowsocks", "trojan", "vmess", "anytls", "tailscale"};

  static String typeLabel(String type) {
    switch (type) {
      case "direct":
        return "直连";
      case "vpn":
        return "AnyConnect";
      case "shadowsocks":
        return "Shadowsocks";
      case "trojan":
        return "Trojan";
      case "vmess":
        return "VMess";
      case "anytls":
        return "AnyTLS";
      case "tailscale":
        return "Tailscale";
      case "selector":
        return "手动选择组";
      case "urltest":
        return "自动测速组";
      default:
        return type;
    }
  }

  static String badge(String type) {
    switch (type) {
      case "direct":
        return "→";
      case "vpn":
        return "AC";
      case "shadowsocks":
        return "SS";
      case "trojan":
        return "TJ";
      case "vmess":
        return "VM";
      case "anytls":
        return "AT";
      case "tailscale":
        return "TS";
      default:
        return "组";
    }
  }

  static JSONObject activeScenario(JSONObject p) {
    JSONArray a = p.optJSONArray("scenarios");
    if (a == null || a.length() == 0) return null;
    String id = p.optString("active_scenario_id");
    for (int i = 0; i < a.length(); i++) {
      JSONObject s = a.optJSONObject(i);
      if (s != null && id.equals(s.optString("id"))) return s;
    }
    return a.optJSONObject(0);
  }

  static JSONObject line(JSONObject p, String id) {
    JSONArray a = p.optJSONArray("lines");
    if (a == null || id == null) return null;
    for (int i = 0; i < a.length(); i++) {
      JSONObject l = a.optJSONObject(i);
      if (l != null && id.equals(l.optString("id"))) return l;
    }
    return null;
  }

  static String lineName(JSONObject p, String id) {
    if (id == null || id.isEmpty() || id.equals("direct")) {
      JSONObject d = line(p, "direct");
      return d == null ? "直连" : d.optString("name", "直连");
    }
    JSONObject l = line(p, id);
    return l == null ? id : l.optString("name", id);
  }

  /** Name of what unmatched traffic uses in the active scenario. */
  static String exitName(JSONObject p) {
    JSONObject s = activeScenario(p);
    if (s == null) return "直连";
    String sub = s.optString("default_subscription_id");
    if (!sub.isEmpty()) {
      JSONArray subs = p.optJSONArray("subscriptions");
      if (subs != null)
        for (int i = 0; i < subs.length(); i++) {
          JSONObject o = subs.optJSONObject(i);
          if (o != null && sub.equals(o.optString("id"))) return o.optString("name", "订阅");
        }
      return "订阅";
    }
    return lineName(p, s.optString("default_line_id"));
  }

  static String exitName(String profile) {
    try {
      return exitName(new JSONObject(profile));
    } catch (Exception e) {
      return "";
    }
  }

  /** One-line description of where a line connects. Never includes secrets. */
  static String summary(JSONObject l) {
    String t = l.optString("type");
    String host = "";
    int port = 0;
    switch (t) {
      case "vpn":
        host = l.optString("vpn_server");
        break;
      case "shadowsocks":
        host = l.optString("ss_server");
        port = l.optInt("ss_port");
        break;
      case "trojan":
        host = l.optString("trojan_server");
        port = l.optInt("trojan_port");
        break;
      case "vmess":
        host = l.optString("vmess_server");
        port = l.optInt("vmess_port");
        break;
      case "anytls":
        host = l.optString("anytls_server");
        port = l.optInt("anytls_port");
        break;
      case "tailscale":
        String exit = l.optString("tailscale_exit_node");
        return "Tailscale" + (exit.isEmpty() ? "" : " · 出口 " + exit);
      case "selector":
      case "urltest":
        JSONArray m = l.optJSONArray("group_members");
        return typeLabel(t) + " · " + (m == null ? 0 : m.length()) + " 条线路";
      default:
        return typeLabel(t);
    }
    host = host.replaceFirst("^https?://", "");
    String where = host.isEmpty() ? "未填写服务器" : (port > 0 ? host + ":" + port : host);
    return typeLabel(t) + " · " + where;
  }

  /** Bindings and groups that point at a line; such lines cannot be removed on their own. */
  static int references(JSONObject p, String id) {
    int n = 0;
    JSONArray scenarios = p.optJSONArray("scenarios");
    if (scenarios != null)
      for (int i = 0; i < scenarios.length(); i++) {
        JSONArray b = scenarios.optJSONObject(i).optJSONArray("bindings");
        if (b != null)
          for (int j = 0; j < b.length(); j++)
            if (id.equals(b.optJSONObject(j).optString("line_id"))) n++;
      }
    JSONArray lines = p.optJSONArray("lines");
    if (lines != null)
      for (int i = 0; i < lines.length(); i++) {
        JSONObject l = lines.optJSONObject(i);
        JSONArray m = l.optJSONArray("group_members");
        if (m != null)
          for (int j = 0; j < m.length(); j++) if (id.equals(m.optString(j))) n++;
        if (id.equals(l.optString("group_default"))) n++;
      }
    JSONArray rules = p.optJSONArray("rule_sets");
    if (rules != null)
      for (int i = 0; i < rules.length(); i++)
        if (id.equals(rules.optJSONObject(i).optString("fetch_line_id"))) n++;
    return n;
  }

  static int bindingCount(JSONObject scenario) {
    JSONArray b = scenario == null ? null : scenario.optJSONArray("bindings");
    return b == null ? 0 : b.length();
  }

  static String newId(JSONObject p, String base) {
    String id = base;
    for (int i = 2; line(p, id) != null; i++) id = base + "-" + i;
    return id;
  }
}
