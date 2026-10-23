package dev.xdial.port;

import java.util.Locale;

/** Turns core and platform errors into one short sentence a person can act on. */
final class Errors {
  private Errors() {}

  static String friendly(String raw) {
    if (raw == null || raw.trim().isEmpty()) return "连接失败，请检查线路设置后重试。";
    String s = raw.toLowerCase(Locale.ROOT);
    if (has(s, "login failed", "401 ", "status 401", "authentication failed", "auth failed", "invalid credentials", "密码错误"))
      return "用户名或密码错误。";
    if (has(s, "available user groups"))
      return "服务器要求选择用户组，当前版本暂不支持。";
    if (has(s, "x509", "certificate"))
      return "服务器证书无法验证。若服务器使用自签证书，可在线路的「高级」里开启「跳过证书验证」。";
    if (has(s, "no such host", "nxdomain"))
      return "找不到服务器地址，请检查域名是否填写正确。";
    if (has(s, "connection refused"))
      return "服务器拒绝连接，请检查端口是否正确。";
    if (has(s, "i/o timeout", "deadline exceeded", "timed out", "timeout"))
      return "连接服务器超时，请检查地址、端口和当前网络。";
    if (has(s, "lookup "))
      return "解析服务器地址失败，请检查域名和当前网络。";
    if (has(s, "no route to host", "network is unreachable", "没有可用网络", "基础网络"))
      return "当前没有可用网络。";
    if (has(s, "eof", "connection reset", "broken pipe"))
      return "服务器中断了连接。请确认地址和端口对应的是该协议的服务器。";
    if (has(s, "其他 android vpn", "another vpn"))
      return "请先关闭手机上的其他 VPN，Android 同一时间只能开一个。";
    if (has(s, "vpn 授权", "vpn permission", "需要重新允许 vpn"))
      return "没有 VPN 权限。请重新点「连接」并在系统弹窗里允许。";
    if (has(s, "permission denied", "operation not permitted"))
      return "系统拒绝了这次网络操作，详细原因见「连接诊断」。";
    if (has(s, "application rules", "应用进程规则", "import_warnings"))
      return "配置里有 Android 不支持的应用分流规则，请在电脑端去掉后再导入。";
    if (has(s, "tailscale") && has(s, "login", "needslogin", "auth"))
      return "Tailscale 需要登录，请在该线路里点「登录 Tailscale」。";
    if (raw.length() <= 60 && containsChinese(raw)) return raw.endsWith("。") ? raw : raw + "。";
    return "连接失败，详细原因可在「连接诊断」里查看。";
  }

  /**
   * Whether trying again later can help. Credentials, certificates and configuration errors are
   * not retried: repeating a rejected login can lock the account.
   */
  static boolean retryable(String raw) {
    if (raw == null) return true;
    String s = raw.toLowerCase(Locale.ROOT);
    if (has(s, "login failed", "401", "auth failed", "invalid credentials", "密码",
        "available user groups", "x509", "certificate", "授权", "需要重新允许", "permission", "application rules",
        "invalid", "missing", "required", "配置", "tailscale"))
      return false;
    return true;
  }

  /** The server rejected the account; retrying would only risk locking it. */
  static boolean isCredential(String raw) {
    if (raw == null) return false;
    String s = raw.toLowerCase(Locale.ROOT);
    return has(s, "login failed", "status 401", "401 ", "authentication failed", "auth failed", "invalid credentials", "密码错误");
  }

  private static boolean has(String s, String... keys) {
    for (String k : keys) if (s.contains(k)) return true;
    return false;
  }

  private static boolean containsChinese(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= 0x4e00 && c <= 0x9fff) return true;
    }
    return false;
  }
}
