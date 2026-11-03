package com.mazzei.cleanframe;
import org.json.JSONObject;
import javax.net.ssl.*;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import android.util.Base64;
/** Minimal MQTT 3.1.1 over certificate-verified TLS; QoS0 commands, no offline queue/replay. */
final class EufyMqtt {
    interface Listener { void data(JSONObject value);void ready(boolean value); }
    private SSLSocket socket;private OutputStream output;private volatile boolean connected;private String id,sessionId,topic;private int sequence;private JSONObject credentials;private String device;
    static byte[] der(int type,byte[] value)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(type);int n=value.length;if(n<128)out.write(n);else{int count=n>65535?3:n>255?2:1;out.write(128|count);for(int i=count-1;i>=0;i--)out.write(n>>>(8*i));}out.write(value);return out.toByteArray();}
    static byte[] pkcs8(byte[] value)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{2,1,0,48,13,6,9,42,(byte)134,72,(byte)134,(byte)247,13,1,1,1,5,0});out.write(der(4,value));return der(48,out.toByteArray());}
    static byte[] bytes(String value)throws Exception{byte[] b=value.getBytes("UTF-8");ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(b.length>>>8);out.write(b.length);out.write(b);return out.toByteArray();}
    synchronized void packet(int type,byte[] data)throws Exception{if(output==null)throw new IOException();output.write(type);int n=data.length;do{int b=n%128;n/=128;output.write(b|(n>0?128:0));}while(n>0);output.write(data);output.flush();}
    void connect(JSONObject c,String deviceId,String clientId,Listener listener)throws Exception{
        credentials=c;device=deviceId;
        String host=c.getString("endpoint_addr");if(!host.matches("[A-Za-z0-9.-]+"))throw new IOException();
        byte[] der=Base64.decode(c.getString("private_key").replaceAll("-----[^-]+-----|\\s", ""),0);
        if(c.getString("private_key").contains("RSA PRIVATE"))der=pkcs8(der);
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        CertificateFactory factory=CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert=factory.generateCertificate(new ByteArrayInputStream(c.getString("certificate_pem").getBytes("UTF-8")));
        KeyStore keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null);keys.setKeyEntry("client",key,new char[0],new java.security.cert.Certificate[]{cert});
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,new char[0]);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init((KeyStore)null);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        socket=(SSLSocket)ssl.getSocketFactory().createSocket();socket.connect(new java.net.InetSocketAddress(host,8883),10000);
        SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);socket.setSoTimeout(12000);socket.startHandshake();output=socket.getOutputStream();
        sessionId="android-"+c.getString("app_name")+"-eufy_android_"+clientId+"_"+c.getString("user_id");id=sessionId+"-"+System.currentTimeMillis();topic="cmd/eufy_home/T2280/"+deviceId;
        ByteArrayOutputStream connect=new ByteArrayOutputStream();connect.write(bytes("MQTT"));connect.write(new byte[]{4,(byte)130,0,30});connect.write(bytes(id));connect.write(bytes(c.getString("thing_name")));packet(16,connect.toByteArray());
        InputStream in=socket.getInputStream();byte[] ack=read(in,32);if(ack.length!=2||ack[1]!=0)throw new IOException();
        ByteArrayOutputStream sub=new ByteArrayOutputStream();sub.write(new byte[]{0,1});sub.write(bytes(topic+"/res"));sub.write(0);sub.write(bytes("smart/mb/in/"+device));sub.write(0);packet(130,sub.toByteArray());
        byte[] accepted=read(in,144);if(accepted.length<3||accepted[2]==(byte)128)throw new IOException();connected=true;listener.ready(true);socket.setSoTimeout(0);
        try{while(connected){int type=in.read();if(type<0)break;byte[] body=body(in);if((type&240)==48){int length=((body[0]&255)<<8)|(body[1]&255);int start=2+length+(((type>>1)&3)>0?2:0);if(start>body.length)continue;
            JSONObject message=new JSONObject(new String(body,start,body.length-start,"UTF-8"));Object p=message.opt("payload");JSONObject payload=p instanceof JSONObject?(JSONObject)p:p instanceof String?new JSONObject((String)p):null;
            if(payload!=null&&payload.optJSONObject("data")!=null)listener.data(payload.getJSONObject("data"));}
        }}finally{connected=false;listener.ready(false);close();}
    }
    static byte[] body(InputStream in)throws Exception{int n=0,m=1;for(int i=0;i<4;i++){int b=in.read();if(b<0)throw new IOException();n+=(b&127)*m;if((b&128)==0){if(n>262144)throw new IOException();byte[] data=new byte[n];new DataInputStream(in).readFully(data);return data;}m*=128;}throw new IOException();}
    static byte[] read(InputStream in,int expected)throws Exception{if(in.read()!=expected)throw new IOException();return body(in);}
    synchronized void ping(){try{if(connected)packet(192,new byte[0]);}catch(Exception e){close();}}
    synchronized void send(String action,int passes)throws Exception{
        sendData(new JSONObject().put("152",Base64.encodeToString(EufyProto.command(action,passes),2)));
    }
    synchronized void sendData(JSONObject data)throws Exception{
        if(!connected)throw new IOException("Vacuum connection is offline.");long now=System.currentTimeMillis();
        JSONObject head=new JSONObject().put("client_id",sessionId).put("cmd",65537).put("cmd_status",EufyProto.REQUEST_STATUS).put("msg_seq",++sequence).put("seed","").put("sess_id",sessionId).put("sign_code",0).put("timestamp",now).put("version","1.0.0.1");
        JSONObject payload=new JSONObject().put("account_id",credentials.getString("user_id")).put("data",data).put("device_sn",device).put("protocol",2).put("t",now);
        byte[] value=new JSONObject().put("head",head).put("payload",payload.toString()).toString().getBytes("UTF-8");ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(bytes(topic+"/req"));out.write(value);packet(48,out.toByteArray());
    }
    synchronized void close(){connected=false;try{if(socket!=null)socket.close();}catch(Exception ignored){}output=null;}
}
