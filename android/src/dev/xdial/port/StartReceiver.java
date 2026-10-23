package dev.xdial.port;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a reboot or an app update, restore the connection the user left on. Android's own
 * always-on restart can race with the old connection going down, so we do not rely on it alone.
 */
public final class StartReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    String a = intent == null ? null : intent.getAction();
    if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
    if (!XDialVpnService.wantOn(c)) return;
    KeepAliveJob.schedule(c);
    KeepAliveJob.revive(c);
  }
}
