package dev.xdial.probe;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Base64;

public final class FixtureRunner extends Instrumentation {
  private Bundle args;

  public void onCreate(Bundle b) {
    args = b;
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      Class<?> cls = getTargetContext().getClassLoader().loadClass("dev.xdial.port.ProfileStore");
      java.lang.reflect.Method save = cls.getDeclaredMethod("save", Context.class, String.class);
      save.setAccessible(true);
      String profile =
          new String(Base64.decode(args.getString("profile_b64"), Base64.DEFAULT), "UTF-8");
      save.invoke(null, getTargetContext(), profile);
      result.putString("stream", "FIXTURE_SAVED\n");
      finish(-1, result);
    } catch (Exception e) {
      result.putString("stream", e.toString());
      finish(0, result);
    }
  }
}
