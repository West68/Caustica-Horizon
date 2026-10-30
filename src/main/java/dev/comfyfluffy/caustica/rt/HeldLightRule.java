package dev.comfyfluffy.caustica.rt;
import com.google.gson.*;
import java.util.*;
/** Supported data-only subset of the public LambDynamicLights item-light format. */
record HeldLightRule(Set<String> items,int level,String block,boolean waterSensitive) {
    static HeldLightRule parse(JsonObject o) {
        JsonObject match=o.getAsJsonObject("match");
        if(match.size()!=1 || !match.has("items"))throw new IllegalArgumentException("Only explicit item IDs supported; complex predicates are not approximated");
        Set<String> ids=new HashSet<>();JsonElement values=match.get("items");
        if(values.isJsonArray())for(var v:values.getAsJsonArray())ids.add(id(v.getAsString()));
        else ids.add(id(values.getAsString()));
        int level=-1;String block=null;JsonElement luminance=o.get("luminance");
        if(luminance.isJsonPrimitive()) {level=luminance.getAsInt();if(luminance.getAsDouble()!=level || level<0||level>15)throw new IllegalArgumentException("level must be 0..15");}
        else {
            var value=luminance.getAsJsonObject();String type=value.get("type").getAsString();
            if(type.equals("block"))block=id(value.get("block").getAsString());
            else if(!type.equals("block_self"))throw new IllegalArgumentException("Unsupported luminance provider "+type);
        }
        return new HeldLightRule(Set.copyOf(ids),level,block,o.has("water_sensitive")&&o.get("water_sensitive").getAsBoolean());
    }
    static String id(String text) {
        if(text.startsWith("#") || !text.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?"))throw new IllegalArgumentException("Explicit item identifier required: "+text);
        return text.contains(":")?text:"minecraft:"+text;
    }
}
