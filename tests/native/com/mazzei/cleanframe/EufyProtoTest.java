package com.mazzei.cleanframe;
import java.util.*;
public final class EufyProtoTest {
    static int checks=9;
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
        check("mapping".equals(EufyProto.workState(EufyProto.read(new byte[]{2,16,4},true))));
        check("paused".equals(EufyProto.workState(EufyProto.read(new byte[]{6,16,4,42,2,8,1},true))));
        byte[] fixture=Base64.getDecoder().decode("GgoSCgIIAhIAGgAiAhABMgIIATgBGgQKAggB");
        for(String[] example:new String[][]{{"water","2","FgoUCgIIAhIAGgAiBAgCEAEyAggBOAE="},{"suction","2","FAoSCgIIAhIAGgAiAhABMgIIAjgB"},{"mode","0","EgoQCgASABoAIgIQATICCAE4AQ=="},{"passes","2","FAoSCgIIAhIAGgAiAhABMgIIATgC"}}){
            byte[] request=EufyProto.settingRequest(fixture,example[0],Integer.parseInt(example[1]));
            // Independent golden messages from the pinned protobufjs schema encoder.
            check(tree(EufyProto.read(request,true)).equals(tree(EufyProto.read(Base64.getDecoder().decode(example[2]),true))));
            check(EufyProto.setting(fixture,"passes")==1);
        }
        check(EufyProto.setting(fixture,"water")==0); // Omitted proto3 enum is Low, not unavailable.
        check(EufyProto.setting(fixture,"suction")==1);
        check(Long.valueOf(14).equals(EufyProto.read(EufyProto.command("resume"),true).get(1)));
        check(Long.valueOf(2).equals(EufyProto.nested(EufyProto.read(EufyProto.command("start",2),true),3).get(1)));
        for(String option:new String[]{"mode","suction","water","passes"}){boolean rejected=false;try{EufyProto.settingRequest(fixture,option,99);}catch(Exception e){rejected=true;}check(rejected);}
        byte[] param=(byte[])EufyProto.read(fixture,true).get(1);
        byte[] augmented=EufyProto.delimited(EufyProto.field(1,EufyProto.replace(param,99,EufyProto.scalar(99,123))));
        byte[] changed=EufyProto.settingRequest(augmented,"water",2);
        check(Long.valueOf(123).equals(EufyProto.nested(EufyProto.read(changed,true),1).get(99)));
        check(Long.valueOf(1).equals(EufyProto.nested(EufyProto.nested(EufyProto.read(changed,true),1),4).get(2)));
        byte[] quietThenWater=EufyProto.settingRequest(EufyProto.settingRequest(fixture,"suction",0),"water",1);
        check(EufyProto.setting(quietThenWater,"suction")==0);
        check(EufyProto.setting(quietThenWater,"water")==1);
        boolean missing=false;try{EufyProto.settingRequest(new byte[]{0},"water",1);}catch(Exception e){missing=true;}check(missing);
        System.out.println(checks+" native Eufy protocol and control checks passed.");
    }
    static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    static String tree(Map<Integer,Object> map)throws Exception{Map<Integer,String> sorted=new TreeMap<>();for(Map.Entry<Integer,Object> entry:map.entrySet())if(!Long.valueOf(0).equals(entry.getValue()))sorted.put(entry.getKey(),entry.getValue() instanceof byte[]?tree(EufyProto.read((byte[])entry.getValue(),false)):entry.getValue().toString());return sorted.toString();}
}
