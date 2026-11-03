package com.mazzei.cleanframe;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;
/** Shell-only one-time encrypted provisioning; private account values never appear in ADB args. */
public final class EufyProvision extends BroadcastReceiver {
    static java.lang.ref.WeakReference<MainActivity> owner=new java.lang.ref.WeakReference<>(null);
    static KeyStore keys()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("eufy-setup")){KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");generator.initialize(new KeyGenParameterSpec.Builder("eufy-setup",KeyProperties.PURPOSE_DECRYPT).setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1).build());generator.generateKeyPair();store.load(null);}return store;
    }
    public void onReceive(Context c,Intent intent){try{
        if("com.mazzei.cleanframe.EUFY_KEY".equals(intent.getAction())){setResultData(Base64.encodeToString(keys().getCertificate("eufy-setup").getPublicKey().getEncoded(),2));setResultCode(1);return;}
        if(!"com.mazzei.cleanframe.EUFY_SETUP".equals(intent.getAction()))return;
        String raw=intent.getStringExtra("envelope");if(raw==null||raw.length()>100000)return;JSONObject data=new JSONObject(raw);
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,keys().getKey("eufy-setup",null),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
        byte[] secret=rsa.doFinal(Base64.decode(data.getString("key"),2));Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,Base64.decode(data.getString("iv"),2)));
        final String value=new String(aes.doFinal(Base64.decode(data.getString("data"),2)),"UTF-8");JSONObject config=new JSONObject(value);
        if(config.optString("email").isEmpty()||config.optString("password").isEmpty()||!config.optString("clientId").matches("[a-f0-9]{32}"))return;
        MainActivity activity=owner.get();if(activity==null||!activity.hasWindowFocus())return;activity.provisionEufy(value);setResultCode(1);
    }catch(Exception ignored){setResultCode(0);}}
}
