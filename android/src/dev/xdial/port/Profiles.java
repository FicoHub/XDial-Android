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

  static JSONObject scenario(JSONObject p, String id) {
    JSONArray a = p.optJSONArray("scenarios");
    if (a == null || id == null) return null;
    for (int i = 0; i < a.length(); i++) {
      JSONObject s = a.optJSONObject(i);
      if (s != null && id.equals(s.optString("id"))) return s;
    }
    return null;
  }

  /** Name of what unmatched traffic uses in the active scenario. */
  static String exitName(JSONObject p) {
    return exitName(p, activeScenario(p));
  }

  /** Name of what unmatched traffic uses in the given scenario. */
  static String exitName(JSONObject p, JSONObject s) {
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

  /**
   * What a person is "connected to": {title, protocol, split}. When unmatched traffic goes direct
   * but rules send some traffic through other lines (e.g. company intranet over the company VPN),
   * those lines are the headline instead of "直连". split is "1" in that case.
   */
  static String[] headline(JSONObject p) {
    return headline(p, activeScenario(p));
  }

  static String[] headline(JSONObject p, JSONObject s) {
    if (s != null && !s.optString("default_subscription_id").isEmpty())
      return new String[] {exitName(p, s), "订阅", ""};
    String exitId = s == null ? "direct" : s.optString("default_line_id", "direct");
    if (!exitId.isEmpty() && !exitId.equals("direct")) {
      JSONObject l = line(p, exitId);
      return new String[] {exitName(p, s), l == null ? "" : typeLabel(l.optString("type")), ""};
    }
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
    JSONArray b = s == null ? null : s.optJSONArray("bindings");
    if (b != null)
      for (int i = 0; i < b.length(); i++) {
        JSONObject binding = b.optJSONObject(i);
        if (binding == null) continue;
        String id = binding.optString("line_id");
        if (!binding.optString("subscription_id").isEmpty()) {
          names.add("订阅");
          types.add("订阅");
        } else if (!id.isEmpty() && !id.equals("direct")) {
          JSONObject l = line(p, id);
          names.add(l == null ? id : l.optString("name", id));
          if (l != null) types.add(typeLabel(l.optString("type")));
        }
      }
    if (names.isEmpty()) return new String[] {exitName(p, s), "直连", ""};
    return new String[] {String.join("、", names), String.join(" / ", types), "1"};
  }

  static String headlineTitle(String profile) {
    try {
      return headline(new JSONObject(profile))[0];
    } catch (Exception e) {
      return "";
    }
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

  /** Scenario to run: one bound to the current Wi-Fi ({@code match_ssids}), else the user's pick. */
  static String effectiveScenarioId(JSONObject p, String ssid) {
    JSONObject chosen = activeScenario(p);
    String base = chosen == null ? p.optString("active_scenario_id") : chosen.optString("id");
    if (ssid == null || ssid.isEmpty()) return base;
    JSONArray a = p.optJSONArray("scenarios");
    if (a != null)
      for (int i = 0; i < a.length(); i++) {
        JSONObject s = a.optJSONObject(i);
        JSONArray names = s == null ? null : s.optJSONArray("match_ssids");
        if (names != null)
          for (int j = 0; j < names.length(); j++)
            if (ssid.equals(names.optString(j))) return s.optString("id");
      }
    return base;
  }

  /** "XINDONG → 公司" lines for every Wi-Fi-bound scenario; empty when the profile has none. */
  static java.util.List<String> ssidRules(JSONObject p) {
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    JSONArray a = p.optJSONArray("scenarios");
    if (a != null)
      for (int i = 0; i < a.length(); i++) {
        JSONObject s = a.optJSONObject(i);
        JSONArray names = s == null ? null : s.optJSONArray("match_ssids");
        if (names != null)
          for (int j = 0; j < names.length(); j++)
            if (!names.optString(j).isEmpty())
              out.add("「" + names.optString(j) + "」时切到「" + s.optString("name") + "」");
      }
    return out;
  }

  static String cleanSsid(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
    if (s.isEmpty() || s.equals("<unknown ssid>") || s.equals("0x")) return null;
    return s;
  }

  static boolean directExit(JSONObject s) {
    if (s == null) return true;
    if (!s.optString("default_subscription_id").isEmpty()) return false;
    String id = s.optString("default_line_id", "direct");
    return id.isEmpty() || id.equals("direct");
  }

  static JSONObject ruleSet(JSONObject p, String id) {
    JSONArray a = p.optJSONArray("rule_sets");
    if (a == null || id == null) return null;
    for (int i = 0; i < a.length(); i++) {
      JSONObject r = a.optJSONObject(i);
      if (r != null && id.equals(r.optString("id"))) return r;
    }
    return null;
  }

  static String ruleName(JSONObject p, String id) {
    JSONObject r = ruleSet(p, id);
    String name = r == null ? "" : r.optString("name");
    return name.isEmpty() ? (id == null || id.isEmpty() ? "规则" : id) : name;
  }

  /** "12 个域名 · 2 个 IP 段" or "远程规则"; never lists the entries themselves. */
  static String ruleSummary(JSONObject r) {
    if (r == null) return "";
    java.util.ArrayList<String> parts = new java.util.ArrayList<>();
    JSONArray d = r.optJSONArray("domains"), c = r.optJSONArray("cidrs");
    if (d != null && d.length() > 0) parts.add(d.length() + " 个域名");
    if (c != null && c.length() > 0) parts.add(c.length() + " 个 IP 段");
    if (!r.optString("url").isEmpty()) parts.add("远程规则");
    JSONArray apps = r.optJSONArray("applications"), procs = r.optJSONArray("processes");
    if ((apps != null && apps.length() > 0) || (procs != null && procs.length() > 0)) parts.add("应用规则");
    if (r.optBoolean("invert")) parts.add("取反");
    return String.join(" · ", parts);
  }

  /** Names of the rules in a scenario that send traffic through a line. */
  static java.util.List<String> rulesUsing(JSONObject p, JSONObject s, String lineId) {
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
    JSONArray b = s == null ? null : s.optJSONArray("bindings");
    if (b != null)
      for (int i = 0; i < b.length(); i++) {
        JSONObject binding = b.optJSONObject(i);
        if (binding != null && lineId.equals(binding.optString("line_id")))
          out.add(ruleName(p, binding.optString("rule_set_id")));
      }
    return new java.util.ArrayList<>(out);
  }

  /** One sentence on where traffic goes, e.g. "公司内网走公司 VPN，其余直连". */
  static String routeText(JSONObject p, JSONObject s) {
    if (s == null) return "全部直连";
    boolean exitDirect = directExit(s);
    String exit = exitName(p, s);
    java.util.LinkedHashSet<String> proxied = new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> direct = new java.util.LinkedHashSet<>();
    JSONArray b = s.optJSONArray("bindings");
    if (b != null)
      for (int i = 0; i < b.length(); i++) {
        JSONObject binding = b.optJSONObject(i);
        if (binding == null) continue;
        String rule = ruleName(p, binding.optString("rule_set_id"));
        String lineId = binding.optString("line_id");
        if (!binding.optString("subscription_id").isEmpty()) {
          proxied.add(rule);
          targets.add("订阅");
        } else if (lineId.isEmpty() || lineId.equals("direct")) {
          direct.add(rule);
        } else {
          proxied.add(rule);
          targets.add(lineName(p, lineId));
        }
      }
    String rest = exitDirect ? "其余直连" : "其余走" + exit;
    if (!exitDirect && (targets.isEmpty() || (targets.size() == 1 && targets.contains(exit)))) {
      // Rules that send traffic to the exit anyway change nothing; only direct ones are worth naming.
      if (direct.isEmpty()) return "全部走" + exit;
      String what = direct.size() == 1 ? direct.iterator().next() : direct.size() + " 条规则";
      return what + "直连，" + rest;
    }
    if (!proxied.isEmpty()) {
      String what = proxied.size() == 1 ? proxied.iterator().next() : proxied.size() + " 条规则";
      return what + "走" + String.join("、", targets) + "，" + rest;
    }
    if (!direct.isEmpty() && !exitDirect) {
      String what = direct.size() == 1 ? direct.iterator().next() : direct.size() + " 条规则";
      return what + "直连，" + rest;
    }
    return exitDirect ? "全部直连" : "全部走" + exit;
  }

  /** A VPN line some scenario uses whose username or password is still empty; null when none. */
  static JSONObject missingAccount(JSONObject p) {
    java.util.HashSet<String> used = new java.util.HashSet<>();
    JSONArray scenarios = p.optJSONArray("scenarios");
    for (int i = 0; scenarios != null && i < scenarios.length(); i++) {
      JSONObject s = scenarios.optJSONObject(i);
      if (s == null) continue;
      used.add(s.optString("default_line_id"));
      JSONArray b = s.optJSONArray("bindings");
      for (int j = 0; b != null && j < b.length(); j++) {
        JSONObject binding = b.optJSONObject(j);
        if (binding != null) used.add(binding.optString("line_id"));
      }
    }
    JSONArray lines = p.optJSONArray("lines");
    for (int i = 0; lines != null && i < lines.length(); i++) {
      JSONObject l = lines.optJSONObject(i);
      if (l == null || !l.optString("type").equals("vpn") || !used.contains(l.optString("id"))) continue;
      if (l.optString("vpn_username").trim().isEmpty() || l.optString("vpn_password").isEmpty()) return l;
    }
    return null;
  }

  static int lineCount(JSONObject p) {
    JSONArray a = p.optJSONArray("lines");
    int n = 0;
    if (a != null)
      for (int i = 0; i < a.length(); i++) {
        JSONObject l = a.optJSONObject(i);
        if (l != null && !l.optString("type").equals("direct")) n++;
      }
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
