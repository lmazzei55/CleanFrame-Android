package com.mazzei.cleanframe;
import java.util.*;
import java.io.*;
/** Bounded protobuf wire reader for C20 status; unknown fields are skipped. */
final class EufyProto {
    static final int REQUEST_STATUS=1;
    static Map<Integer,Object> read(byte[] data,boolean delimited) throws Exception {
        if(data.length>262144)throw new IOException();
        ByteArrayInputStream in=new ByteArrayInputStream(data);
        if(delimited){long size=varint(in);if(size!=in.available())throw new IOException();}
        Map<Integer,Object> result=new HashMap<>();
        while(in.available()>0){long tag=varint(in);int field=(int)(tag>>>3),wire=(int)(tag&7);if(field<1)throw new IOException();
            if(wire==0)result.put(field,varint(in));
            else if(wire==2){long n=varint(in);if(n>in.available())throw new IOException();byte[] bytes=new byte[(int)n];if(in.read(bytes)!=n&&n!=0)throw new IOException();result.put(field,bytes);}
            else if(wire==1||wire==5){int n=wire==1?8:4;if(in.skip(n)!=n)throw new IOException();}else throw new IOException();
        }return result;
    }
    static long varint(InputStream in)throws Exception{long n=0;for(int s=0;s<64;s+=7){int b=in.read();if(b<0)throw new IOException();n|=(long)(b&127)<<s;if((b&128)==0)return n;}throw new IOException();}
    // Proto3 omits enum zero (standby), including after a pause.
    static String workState(Map<Integer,Object> work)throws Exception{
        Object code=work.get(2);long n=code==null?0:code instanceof Long?(Long)code:-1;
        String[] names={"idle","idle","error","charging","mapping","cleaning","remote","returning","cruising"};
        String state=n>=0&&n<names.length?names[(int)n]:"unknown";
        int field="mapping".equals(state)?5:"cleaning".equals(state)?6:"returning".equals(state)?8:"cruising".equals(state)?9:0;
        if(field>0&&work.get(field) instanceof byte[]&&Long.valueOf(1).equals(read((byte[])work.get(field),false).get(1)))state="paused";
        if("charging".equals(state)&&work.get(3) instanceof byte[]&&Long.valueOf(1).equals(read((byte[])work.get(3),false).get(1)))state="docked";
        return state;
    }
    static boolean controlAllowed(String command,String state,int error,boolean tankOnly){
        // Pause is a stop request. Tank-empty is a dock issue, not a navigation fault.
        if("pause".equals(command)&&error>0)return true;
        if("unknown".equals(state))return false;
        if("error".equals(state))return "dock".equals(command)&&error==6011&&tankOnly;
        return error==0||("dock".equals(command)&&tankOnly);
    }
    static Map<Integer,Object> nested(Map<Integer,Object> map,int field)throws Exception{
        Object value=map.get(field);if(!(value instanceof byte[]))throw new IOException("Missing setting");
        return read((byte[])value,false);
    }
    static int enumValue(Map<Integer,Object> map,int field)throws Exception{
        Object value=map.get(field);if(value==null)return 0;if(!(value instanceof Long)||((Long)value)<0||((Long)value)>1000000)throw new IOException();return ((Long)value).intValue();
    }
    static int setting(byte[] response,String name)throws Exception{
        Map<Integer,Object> param=nested(read(response,true),1);
        int n;
        if("passes".equals(name)){n=enumValue(param,7);if(n<1||n>2)throw new IOException();return n;}
        int field="mode".equals(name)?1:"water".equals(name)?4:"suction".equals(name)?6:0;
        if(field==0)throw new IOException();n=enumValue(nested(param,field),1);
        int max="suction".equals(name)?3:2;if(n>max)throw new IOException();return n;
    }
    static void writeVar(ByteArrayOutputStream out,long value){do{int b=(int)(value&127);value>>>=7;out.write(b|(value!=0?128:0));}while(value!=0);}
    static byte[] field(int number,byte[] value){ByteArrayOutputStream out=new ByteArrayOutputStream();writeVar(out,number*8+2);writeVar(out,value.length);out.write(value,0,value.length);return out.toByteArray();}
    static byte[] scalar(int number,int value){ByteArrayOutputStream out=new ByteArrayOutputStream();writeVar(out,number*8);writeVar(out,value);return out.toByteArray();}
    static byte[] delimited(byte[] value){ByteArrayOutputStream out=new ByteArrayOutputStream();writeVar(out,value.length);out.write(value,0,value.length);return out.toByteArray();}
    // Replace only the requested field; preserve all unknown fields byte for byte.
    static byte[] replace(byte[] message,int number,byte[] replacement)throws Exception{
        read(message,false);ByteArrayInputStream in=new ByteArrayInputStream(message);ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(in.available()>0){int begin=message.length-in.available();long tag=varint(in);int wire=(int)(tag&7);
            if(wire==0)varint(in);else if(wire==2){long n=varint(in);if(in.skip(n)!=n)throw new IOException();}else if(in.skip(wire==1?8:4)!=(wire==1?8:4))throw new IOException();
            if((tag>>>3)!=number)out.write(message,begin,message.length-in.available()-begin);
        }out.write(replacement);return out.toByteArray();
    }
    static byte[] settingRequest(byte[] response,String name,int value)throws Exception{
        setting(response,name);int max="passes".equals(name)?2:"suction".equals(name)?3:2;
        if(value<("passes".equals(name)?1:0)||value>max)throw new IllegalArgumentException("Unsupported cleaning option.");
        byte[] param=(byte[])read(response,true).get(1);int number="mode".equals(name)?1:"water".equals(name)?4:"suction".equals(name)?6:7;
        byte[] replacement;
        if(number==7)replacement=scalar(7,value);
        else{byte[] nested=(byte[])read(param,false).get(number);replacement=field(number,replace(nested,1,scalar(1,value)));}
        return delimited(field(1,replace(param,number,replacement)));
    }
    static byte[] command(String action){return command(action,1);}
    static byte[] command(String action,int passes){
        if(passes<1||passes>2)throw new IllegalArgumentException("Unsupported pass count.");
        if("start".equals(action))return new byte[]{6,8,0,26,2,8,(byte)passes};
        if("resume".equals(action))return new byte[]{2,8,14};
        if("pause".equals(action))return new byte[]{2,8,13};
        if("dock".equals(action))return new byte[]{2,8,6};
        throw new IllegalArgumentException("Unsupported vacuum command.");
    }
}
