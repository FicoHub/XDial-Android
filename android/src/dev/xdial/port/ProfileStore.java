package dev.xdial.port;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ProfileStore {
  static final String DEFAULT =
      "{\"profile_id\":\"android-local\",\"lines\":[{\"id\":\"direct\",\"name\":\"直连\",\"type\":\"direct\",\"enabled\":true}],\"rule_sets\":[],\"scenarios\":[{\"id\":\"default\",\"name\":\"默认场景\",\"bindings\":[],\"default_line_id\":\"direct\"}],\"active_scenario_id\":\"default\"}";

  static SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("xdial.profile.v1")) {
      KeyGenerator gen =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      gen.init(
          new KeyGenParameterSpec.Builder(
                  "xdial.profile.v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      gen.generateKey();
    }
    return (SecretKey) ks.getKey("xdial.profile.v1", null);
  }

  static AtomicFile file(Context c) {
    return new AtomicFile(new File(c.getFilesDir(), "profile.aesgcm"));
  }

  static synchronized String read(Context c) throws Exception {
    if (!file(c).getBaseFile().exists()) return DEFAULT;
    byte[] b = file(c).readFully();
    if (b.length < 28) throw new Exception("配置损坏；请重新导入");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(b, 0, 12)));
    return new String(cipher.doFinal(b, 12, b.length - 12), StandardCharsets.UTF_8);
  }

  static synchronized void save(Context c, String s) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    AtomicFile f = file(c);
    FileOutputStream stream = f.startWrite();
    try {
      stream.write(cipher.getIV());
      stream.write(cipher.doFinal(s.getBytes(StandardCharsets.UTF_8)));
      f.finishWrite(stream);
    } catch (Exception e) {
      f.failWrite(stream);
      throw e;
    }
  }
}
