package com.mazzei.cleanframe;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
/** Secrets never cross the WebView bridge or preference backup boundary. */
final class VacuumSecrets {
    static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("eufy-storage")){
            KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("eufy-storage",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)store.getKey("eufy-storage",null);
    }
    static synchronized void save(Context c,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        if(!c.getSharedPreferences("eufy-secret",0).edit().putString("iv",Base64.encodeToString(cipher.getIV(),2))
            .putString("data",Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")),2)).commit())throw new Exception();
    }
    static synchronized String load(Context c) throws Exception {
        android.content.SharedPreferences p=c.getSharedPreferences("eufy-secret",0);
        if(!p.contains("data"))return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),2)));
        return new String(cipher.doFinal(Base64.decode(p.getString("data",""),2)),"UTF-8");
    }
}
