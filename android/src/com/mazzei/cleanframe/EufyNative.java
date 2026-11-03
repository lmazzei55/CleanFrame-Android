package com.mazzei.cleanframe;
import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Foreground-only native Eufy client; no account material is returned to JavaScript. */
final class EufyNative {
    private final Activity activity;private final ScheduledExecutorService io=Executors.newScheduledThreadPool(3);
    private volatile int generation;private volatile boolean visible,ready;private volatile EufyMqtt mqtt;
    private JSONObject config,session,user,mqttCredentials,dps=new JSONObject();private String device,state="unknown",phase="",action="",before="";
    private int suction=-1;private long suctionAt;
    private byte[] settings;private long settingsAt;private String pendingSetting="";private int pendingValue;
    private String task="";
    private int battery=-1,error;private boolean tankOnly;private long observed,commandAt;private ScheduledFuture<?> tick;
    EufyNative(Activity activity){this.activity=activity;}
    synchronized JSONObject status()throws Exception{
        boolean fresh=System.currentTimeMillis()-observed<=90000;
        return new JSONObject().put("configured",config!=null).put("state",fresh?(error>0?"error":state):"unknown").put("battery",fresh&&battery>=0?battery:JSONObject.NULL)
            .put("receivedAt",fresh?observed:0).put("errorCode",error>0?error:JSONObject.NULL).put("controlsReady",ready&&visible)
            .put("task",fresh?task:"").put("settings",settingsSummary()).put("canResume",state.matches("paused|idle")&&error==0&&fresh)
            .put("command",new JSONObject().put("phase",phase));
    }
    void visible(boolean show){if(show==visible)return;visible=show;if(show)start();else stop();}
    private synchronized void stop(){generation++;ready=false;if(mqtt!=null)mqtt.close();mqtt=null;if(tick!=null)tick.cancel(false);}
    void destroy(){visible=false;stop();io.shutdownNow();}
    private void start(){phase="";final int token=++generation;io.execute(new Runnable(){public void run(){try{
        String raw=VacuumSecrets.load(activity);if(raw==null)return;config=new JSONObject(raw);
        JSONObject cache=config.optJSONObject("cache");
        if(cache!=null&&System.currentTimeMillis()-cache.optLong("savedAt")<3600000){session=cache.getJSONObject("session");user=cache.getJSONObject("userInfo");mqttCredentials=cache.getJSONObject("mqttCredentials");}
        else login();
        JSONObject response=http("https://aiot-clean-api-pr.eufylife.com/app/devicerelation/get_device_list",new JSONObject().put("attribute",3),null);
        JSONObject data=response.optJSONObject("data");if(data==null)data=response;JSONArray devices=data.optJSONArray("devices");int matches=0;
        if(devices!=null)for(int i=0;i<devices.length();i++){JSONObject entry=devices.getJSONObject(i).optJSONObject("device");if(entry!=null&&entry.optString("device_model").startsWith("T2280")){matches++;device=entry.getString("device_sn");dps=entry.optJSONObject("dps");}}
        if(matches!=1||device==null||!device.matches("[A-Za-z0-9._-]{1,160}"))throw new IOException();if(dps==null)dps=new JSONObject();decode(dps);
        if(token!=generation||!visible)return;
        final EufyMqtt connection=new EufyMqtt();mqtt=connection;
        final int[] ticks={0};
        tick=io.scheduleAtFixedRate(new Runnable(){public void run(){if(token==generation&&visible){connection.ping();if(++ticks[0]%2==0)refresh();synchronized(EufyNative.this){if(!phase.isEmpty()&&phase.matches("sent|sending")&&System.currentTimeMillis()-commandAt>35000)phase="unconfirmed";}}}},15,15,TimeUnit.SECONDS);
        connection.connect(mqttCredentials,device,config.getString("clientId"),new EufyMqtt.Listener(){
            public void ready(boolean value){if(token==generation)ready=value;else connection.close();}
            public void data(JSONObject value){if(token==generation)decode(value);}
        });
    }catch(Exception ignored){if(token==generation){ready=false;phase="connection_failed";}}}});}
    synchronized void decode(JSONObject value){try{
        Iterator<String> keys=value.keys();while(keys.hasNext()){String key=keys.next();dps.put(key,value.get(key));}
        boolean valid=false;
        if(value.has("153"))try{Map<Integer,Object> work=EufyProto.read(Base64.decode(value.getString("153"),0),true);
            state=EufyProto.workState(work);task="";
            if(work.get(1) instanceof byte[]){int mode=EufyProto.enumValue(EufyProto.nested(work,1),1);String[] tasks={"Whole-home cleaning","Room cleaning","Zone cleaning","Spot cleaning","Mapping","Cruising"};if(mode<tasks.length)task=tasks[mode];}
            valid=!"unknown".equals(state);
        }catch(Exception ignored){state="unknown";}
        if(value.has("163"))try{Object raw=value.get("163");Object level=raw instanceof Number?raw:EufyProto.read(Base64.decode((String)raw,0),true).get(1);int n=level instanceof Number?((Number)level).intValue():-1;battery=n>=0&&n<=100?n:-1;valid|=battery>=0;}catch(Exception ignored){battery=-1;}
        if(value.has("177"))try{error=0;tankOnly=true;Map<Integer,Object> errors=EufyProto.read(Base64.decode(value.getString("177"),0),true);for(int field:new int[]{2,3}){Object code=errors.get(field);if(code instanceof byte[]){ByteArrayInputStream packed=new ByteArrayInputStream((byte[])code);while(packed.available()>0){long n=EufyProto.varint(packed);if(n>0&&n<=Integer.MAX_VALUE){if(error==0)error=(int)n;if(n!=6011)tankOnly=false;}}}else if(code instanceof Long&&((Long)code)>0&&((Long)code)<=Integer.MAX_VALUE){int n=((Long)code).intValue();if(error==0)error=n;if(n!=6011)tankOnly=false;}}}catch(Exception ignored){}
        if(value.has("158"))try{int n=Integer.parseInt(value.get("158").toString());suction=n>=0&&n<=3?n:-1;suctionAt=System.currentTimeMillis();if("suction".equals(pendingSetting)&&phase.matches("sent|sending")&&suction==pendingValue){phase="confirmed";pendingSetting="";}}catch(Exception ignored){suction=-1;}
        if(value.has("154"))try{byte[] next=Base64.decode(value.getString("154"),0);EufyProto.nested(EufyProto.read(next,true),1);settings=next;settingsAt=System.currentTimeMillis();
            if(!pendingSetting.isEmpty()&&!"suction".equals(pendingSetting)&&phase.matches("sent|sending")&&EufyProto.setting(next,pendingSetting)==pendingValue){phase="confirmed";pendingSetting="";}
        }catch(Exception ignored){settings=null;settingsAt=0;}
        if(valid)observed=System.currentTimeMillis();
        if(value.has("153")&&phase.matches("sent|sending")&&!state.equals(before)&&((action.matches("start|resume")&&state.matches("cleaning|mapping|returning|cruising"))||("pause".equals(action)&&state.matches("idle|paused"))||("dock".equals(action)&&state.matches("returning|charging|docked"))))phase="confirmed";
    }catch(Exception ignored){}}
    private void refresh(){try{JSONObject response=http("https://aiot-clean-api-pr.eufylife.com/app/devicerelation/get_device_list",new JSONObject().put("attribute",3),null);JSONObject data=response.optJSONObject("data");if(data==null)data=response;JSONArray list=data.optJSONArray("devices");if(list!=null)for(int i=0;i<list.length();i++){JSONObject item=list.getJSONObject(i).optJSONObject("device");if(item!=null&&device.equals(item.optString("device_sn"))&&item.optJSONObject("dps")!=null)decode(item.getJSONObject("dps"));}}catch(Exception ignored){}}
    synchronized void send(final String command)throws Exception{
        if(!command.matches("start|resume|pause|dock"))throw new IllegalArgumentException("Unsupported vacuum command.");
        if(!ready||!visible||mqtt==null||System.currentTimeMillis()-observed>90000)throw new IllegalArgumentException("Wait for the vacuum connection and a recent status.");
        if(phase.matches("sent|sending"))throw new IllegalArgumentException("A command is already waiting for confirmation.");
        if(!EufyProto.controlAllowed(command,state,error,tankOnly))throw new IllegalArgumentException(error==6011?"Refill the dock’s clean-water tank before starting cleaning.":"Check the vacuum in the Eufy app first.");
        if("pause".equals(command)&&error==0&&!state.matches("cleaning|returning|mapping|cruising|remote"))throw new IllegalArgumentException("The vacuum has no active task to pause.");
        if("dock".equals(command)&&state.matches("docked|charging"))throw new IllegalArgumentException("The vacuum is already at its dock.");
        if("start".equals(command)&&state.equals("cleaning"))throw new IllegalArgumentException("The vacuum is already cleaning.");
        if("resume".equals(command)&&!state.matches("paused|idle"))throw new IllegalArgumentException("There is no paused task to resume.");
        final int passes="start".equals(command)&&settings!=null&&System.currentTimeMillis()-settingsAt<=90000?EufyProto.setting(settings,"passes"):1;
        pendingSetting="";action=command;before=state;phase="sending";commandAt=System.currentTimeMillis();final EufyMqtt connection=mqtt;
        io.execute(new Runnable(){public void run(){try{connection.send(command,passes);synchronized(EufyNative.this){if("sending".equals(phase))phase="sent";}}catch(Exception ignored){synchronized(EufyNative.this){phase="unconfirmed";}}}});
    }
    void confirm(final String command)throws Exception{
        if("options".equals(command)){showOptions();return;}
        if(!command.matches("start|resume")){send(command);return;}
        if(!ready)throw new IllegalArgumentException("The vacuum connection is not ready.");
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("resume".equals(command)?"Resume task?":"Start cleaning?").setMessage("resume".equals(command)?"Continue the paused task using its existing Eufy settings.":"The vacuum will leave its dock and clean using its current Eufy settings.")
            .setNegativeButton("Cancel",null).setPositiveButton("resume".equals(command)?"Resume":"Start cleaning",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){try{send(command);}catch(Exception e){new AlertDialog.Builder(activity).setMessage(e.getMessage()).setPositiveButton("OK",null).show();}}}).show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    private static final String[] OPTION_KEYS={"mode","suction","water","passes"};
    private static final String[] OPTION_TITLES={"Cleaning mode","Suction","Water level","Cleaning passes"};
    private static final String[][] OPTION_LABELS={{"Vacuum only","Mop only","Vacuum and mop"},{"Quiet","Standard","Turbo","Max"},{"Low","Medium","High"},{"1 pass","2 passes"}};
    private int settingValue(String name)throws Exception{return "suction".equals(name)&&suction>=0&&System.currentTimeMillis()-suctionAt<=90000?suction:EufyProto.setting(settings,name);}
    private synchronized JSONObject settingsSummary()throws Exception{
        JSONObject result=new JSONObject();if(settings==null||System.currentTimeMillis()-settingsAt>90000)return result;
        for(int i=0;i<OPTION_KEYS.length;i++)try{int n=settingValue(OPTION_KEYS[i]);result.put(OPTION_KEYS[i],OPTION_LABELS[i][n-(i==3?1:0)]);}catch(Exception ignored){}
        return result;
    }
    private void showOptions()throws Exception{
        final JSONObject summary=settingsSummary();String[] rows=new String[4];for(int i=0;i<4;i++)rows[i]=OPTION_TITLES[i]+": "+summary.optString(OPTION_KEYS[i],"Unavailable");
        new AlertDialog.Builder(activity).setTitle("Cleaning options").setItems(rows,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){try{chooseOption(which);}catch(Exception e){showError(e);}}}).setNegativeButton("Back",null).show();
    }
    private void chooseOption(final int index)throws Exception{
        if(settings==null||System.currentTimeMillis()-settingsAt>90000)throw new IllegalArgumentException("Wait for recent cleaning settings.");
        final byte[] baseline=settings.clone();int current=settingValue(OPTION_KEYS[index])-(index==3?1:0);final int[] choice={current};
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(OPTION_TITLES[index]).setSingleChoiceItems(OPTION_LABELS[index],current,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){choice[0]=which;}})
            .setNegativeButton("Cancel",null).setPositiveButton("Apply",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){try{applyOption(OPTION_KEYS[index],choice[0]+(index==3?1:0),baseline);}catch(Exception e){showError(e);}}}).show();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    private void showError(Exception e){new AlertDialog.Builder(activity).setMessage(e instanceof IllegalArgumentException?e.getMessage():"This option is not available from the vacuum yet.").setPositiveButton("OK",null).show();}
    private synchronized void applyOption(final String name,final int value,byte[] baseline)throws Exception{
        if(!visible||!ready||mqtt==null||System.currentTimeMillis()-settingsAt>90000||System.currentTimeMillis()-observed>90000)throw new IllegalArgumentException("Wait for a recent vacuum connection and settings.");
        if(error>0||!state.matches("docked|charging|idle|paused"))throw new IllegalArgumentException("Pause the vacuum or dock it before changing cleaning options.");
        if(phase.matches("sent|sending"))throw new IllegalArgumentException("Wait for the previous command result.");
        if(!Arrays.equals(baseline,settings))throw new IllegalArgumentException("Settings changed. Reopen Cleaning options and try again.");
        if(settingValue(name)==value)return;
        byte[] baselineForWrite=settings;
        if(!"suction".equals(name)&&suction>=0&&System.currentTimeMillis()-suctionAt<=90000)baselineForWrite=EufyProto.settingRequest(settings,"suction",suction);
        final byte[] request="suction".equals(name)?null:EufyProto.settingRequest(baselineForWrite,name,value);final EufyMqtt connection=mqtt;final int token=generation;
        pendingSetting=name;pendingValue=value;action="setting";phase="sending";commandAt=System.currentTimeMillis();
        io.execute(new Runnable(){public void run(){try{connection.sendData("suction".equals(name)?new JSONObject().put("158",value):new JSONObject().put("154",Base64.encodeToString(request,2)));synchronized(EufyNative.this){if(token==generation&&"sending".equals(phase))phase="sent";}}catch(Exception ignored){synchronized(EufyNative.this){if(token==generation)phase="unconfirmed";}}}});
    }
    void provision(final String value){new AlertDialog.Builder(activity).setTitle("Connect Eufy on this TV?").setMessage("Save your Eufy account encrypted on this TV. Your Mac will no longer be needed for vacuum status and controls.").setNegativeButton("Cancel",null)
        .setPositiveButton("Connect",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){try{VacuumSecrets.save(activity,value);stop();if(visible)start();}catch(Exception ignored){new AlertDialog.Builder(activity).setMessage("Could not save the Eufy connection.").setPositiveButton("OK",null).show();}}}).show();}
    private void login()throws Exception{
        long until=activity.getSharedPreferences("eufy-backoff",0).getLong("until",0);if(System.currentTimeMillis()<until)throw new IOException();
        JSONObject body=new JSONObject().put("email",config.getString("email")).put("password",config.getString("password"));
        session=null;
        String[] clients={"eufy-app","eufyhome-app"},secrets={"8FHf22gaTKu7MZXqz5zytw","GQCpr9dSp3uQpsOMgJ4xQ"};
        for(int i=0;i<2;i++){body.put("client_id",clients[i]).put("client_secret",secrets[i]);JSONObject result=http("https://home-api.eufylife.com/v1/user/"+(i==0?"v2/":"")+"email/login",body,i==0?"Health":"Home");if(result.has("access_token")){session=result;break;}}
        if(session==null)throw new IOException();user=http("https://api.eufylife.com/v1/user/user_center_info",null,"Home");
        byte[] digest=MessageDigest.getInstance("MD5").digest(user.getString("user_center_id").getBytes("UTF-8"));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b&255));user.put("gtoken",hex.toString());
        mqttCredentials=http("https://aiot-clean-api-pr.eufylife.com/app/devicemanage/get_user_mqtt_info",new JSONObject(),null).getJSONObject("data");
        config.put("cache",new JSONObject().put("session",session).put("userInfo",user).put("mqttCredentials",mqttCredentials).put("savedAt",System.currentTimeMillis()));VacuumSecrets.save(activity,config.toString());
    }
    private JSONObject http(String url,JSONObject body,String category)throws Exception{
        if(System.currentTimeMillis()<activity.getSharedPreferences("eufy-backoff",0).getLong("until",0))throw new IOException();
        HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("User-Agent","EufyHome-Android-3.1.3-753");connection.setRequestProperty("openudid",config.getString("clientId"));
        if(category!=null)connection.setRequestProperty("category",category);
        if(session!=null)connection.setRequestProperty("token",session.optString("access_token"));
        if(user!=null){connection.setRequestProperty("x-auth-token",user.optString("user_center_token"));connection.setRequestProperty("gtoken",user.optString("gtoken"));}
        connection.setRequestProperty("app-name","eufy_home");connection.setRequestProperty("clientType","1");connection.setRequestProperty("language","en");connection.setRequestProperty("country","US");
        try{if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}}
            int status=connection.getResponseCode();if(status==429){activity.getSharedPreferences("eufy-backoff",0).edit().putLong("until",System.currentTimeMillis()+900000).commit();throw new IOException();}if(status!=200)throw new IOException();
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>524288)throw new IOException();out.write(buffer,0,n);}}return new JSONObject(new String(out.toByteArray(),"UTF-8"));
        }finally{connection.disconnect();}
    }
}
