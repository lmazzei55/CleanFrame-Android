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
        check("idle".equals(EufyProto.workState(EufyProto.read(new byte[]{0},true))));
        check("paused".equals(EufyProto.workState(EufyProto.read(new byte[]{6,16,5,50,2,8,1},true))));
        check("cleaning".equals(EufyProto.workState(EufyProto.read(new byte[]{2,16,5},true))));
        check("error".equals(EufyProto.workState(EufyProto.read(new byte[]{2,16,2},true))));
        check(EufyProto.controlAllowed("pause","cleaning",6011,true));
        check(EufyProto.controlAllowed("pause","error",7,false));
        check(EufyProto.controlAllowed("dock","cleaning",6011,true));
        check(!EufyProto.controlAllowed("start","cleaning",6011,true));
        check(!EufyProto.controlAllowed("dock","cleaning",6011,false));
        check(EufyProto.controlAllowed("dock","error",6011,true));
        check(!EufyProto.controlAllowed("dock","error",7,false));
        check(!EufyProto.controlAllowed("dock","unknown",6011,true));
        System.out.println("21 native Eufy protocol and control checks passed.");
    }
    static void check(boolean value){if(!value)throw new AssertionError();}
}
