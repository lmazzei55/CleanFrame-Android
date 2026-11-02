package com.mazzei.cleanframe;
import java.util.*;
public final class EufyProtoTest {
    public static void main(String[] args)throws Exception{
        if(!Long.valueOf(0).equals(EufyProto.read(EufyProto.command("start"),true).get(1)))throw new AssertionError();
        if(!Long.valueOf(13).equals(EufyProto.read(EufyProto.command("pause"),true).get(1)))throw new AssertionError();
        if(!Long.valueOf(6).equals(EufyProto.read(EufyProto.command("dock"),true).get(1)))throw new AssertionError();
        if(!Long.valueOf(100).equals(EufyProto.read(new byte[]{2,8,100},true).get(1)))throw new AssertionError();
        for(byte[] bad:new byte[][]{{4,8,1},{1,0},{1,15},{(byte)128}}){boolean failed=false;try{EufyProto.read(bad,true);}catch(Exception e){failed=true;}if(!failed)throw new AssertionError();}
        boolean refused=false;try{EufyProto.command("scene");}catch(IllegalArgumentException e){refused=true;}if(!refused)throw new AssertionError();
        System.out.println("9 native Eufy protocol checks passed.");
    }
}
