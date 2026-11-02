package com.mazzei.cleanframe;
import java.util.*;
import java.io.*;
/** Bounded protobuf wire reader for C20 status; unknown fields are skipped. */
final class EufyProto {
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
    static byte[] command(String action){
        if("start".equals(action))return new byte[]{6,8,0,26,2,8,1};
        if("pause".equals(action))return new byte[]{2,8,13};
        if("dock".equals(action))return new byte[]{2,8,6};
        throw new IllegalArgumentException("Unsupported vacuum command.");
    }
}
