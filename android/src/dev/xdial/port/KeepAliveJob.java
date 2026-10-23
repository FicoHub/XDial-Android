package dev.xdial.port;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/**
 * Safety net for "stay connected": if the connection the user left on is gone (for example the
 * process crashed and Android did not restart it), bring it back. Never starts a connection the
 * user turned off, and never asks for VPN consent from the background.
 */
public final class KeepAliveJob extends JobService {
  private static final int ID = 7101;

  static void schedule(Context c) {
    JobScheduler js = c.getSystemService(JobScheduler.class);
    if (js == null || js.getPendingJob(ID) != null) return;
    js.schedule(
        new JobInfo.Builder(ID, new ComponentName(c, KeepAliveJob.class))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build());
  }

  static void cancel(Context c) {
    JobScheduler js = c.getSystemService(JobScheduler.class);
    if (js != null) js.cancel(ID);
  }

  /** Restart the connection if the user wants it on and it is not running. */
  static boolean revive(Context c) {
    if (!XDialVpnService.wantOn(c) || XDialVpnService.active) return false;
    if (VpnService.prepare(c) != null) return false;
    try {
      c.startForegroundService(new Intent(c, XDialVpnService.class).setAction("START"));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    if (!XDialVpnService.wantOn(this)) cancel(this);
    else revive(this);
    return false;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false;
  }
}
