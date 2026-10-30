package dev.comfyfluffy.caustica.rt;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Vector3f;
import java.util.*;

/** Two camera-relative analytic emitters; no terrain light updates, geometry rebuilds or new GPU resources. */
public final class RtHeldLights {
    private static float cachedKelvin=Float.NaN;
    private static float[] cachedTorchColor;
    private static float[] referenceTorchColor() {
        float kelvin=CausticaConfig.Rt.Look.TORCH_KELVIN.value();
        if(kelvin!=cachedKelvin){cachedTorchColor=ItrpLookMath.torchColor(kelvin);cachedKelvin=kelvin;}
        return cachedTorchColor;
    }
    private static final Float4 ZERO=new Float4(0,0,0,0);
    private static long epoch=-1;
    private static final Map<String,float[]> rules=new HashMap<>();
    private static final Set<String> explicitColors=new HashSet<>();
    private static final List<HeldLightRule> lambRules=new ArrayList<>();
    public record Frame(Float4 mainPosition,Float4 mainColor,Float4 offPosition,Float4 offColor){}
    private static void reload(Minecraft mc) {
        long e=RtMaterialRegistry.INSTANCE.epoch();if(e==epoch)return;epoch=e;rules.clear();lambRules.clear();explicitColors.clear();HeldLightColors.clear();
        var rm=mc.getResourceManager();
        for(var entry:rm.listResources("dynamiclights/item",id->id.getPath().endsWith(".json")).entrySet()) {
            try(var in=entry.getValue().openAsReader()) {lambRules.add(HeldLightRule.parse(JsonParser.parseReader(in).getAsJsonObject()));}
            catch(Exception ex){CausticaMod.LOGGER.warn("Held lights: unsupported/invalid Lamb item rule {}: {}",entry.getKey(),ex.getMessage());}
        }
        // The resource manager resolves pack priority. OptiFine item rules use the last ':' for light level.
        for(var r:rm.listResources("optifine",id->id.getPath().endsWith("dynamic_lights.properties")).entrySet()) {
            try(var in=r.getValue().openAsReader()) {
                var properties=new Properties();properties.load(in);
                for(String text:VisualMath.splitRules(properties.getProperty("items", ""))) {
                    if(text.isBlank())continue;
                    var value=VisualMath.parseRule(text);String id=text.substring(0,text.lastIndexOf(':'));
                    if(!id.contains(":"))id=r.getKey().getNamespace()+":"+id;
                    rules.put(id,value);
                }
            }catch(Exception ex){CausticaMod.LOGGER.warn("Invalid held-light OptiFine rule {}",r.getKey(),ex);}
        }
        for(var r:rm.listResources("caustica/held_lights",id->id.getPath().endsWith(".json")).entrySet()) {
            try(var in=r.getValue().openAsReader()) {
                var o=JsonParser.parseReader(in).getAsJsonObject();int level=o.get("level").getAsInt();
                if(level<0 || level>15)throw new IllegalArgumentException("level must be 0..15");
                var color=o.getAsJsonArray("color");float[] v={level,1,.5f,.2f};
                if(color!=null)for(int i=0;i<3;i++){v[i+1]=color.get(i).getAsFloat();if(!Float.isFinite(v[i+1])||v[i+1]<0||v[i+1]>1)throw new IllegalArgumentException("linear RGB must be 0..1");}
                String id=HeldLightRule.id(o.get("item").getAsString());rules.put(id,v);
                if(color!=null)explicitColors.add(id);
            }catch(Exception ex){CausticaMod.LOGGER.warn("Invalid held-light JSON {}",r.getKey(),ex);}
        }
    }
    static float[] emission(ItemStack stack,boolean submerged) {
        if(stack.isEmpty())return new float[4];
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        float[] override=rules.get(id);if(override!=null && explicitColors.contains(id))return override;
        int level=stack.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState().getLightEmission() : 0;
        if(id.equals("minecraft:glow_ink_sac")||id.equals("minecraft:glow_berries"))level=7;
        if(id.equals("minecraft:blaze_rod")||id.equals("minecraft:blaze_powder"))level=10;
        if(id.equals("minecraft:lava_bucket"))level=15;
        // Glow lichen's default block state has no faces (emission 0); a placed face emits 7.
        if(id.equals("minecraft:glow_lichen"))level=7;
        // Common glowing non-block items, at the levels the usual held-light mods give them.
        if(id.equals("minecraft:glowstone_dust")||id.equals("minecraft:magma_cream"))level=8;
        if(id.equals("minecraft:fire_charge"))level=10;
        if(id.equals("minecraft:nether_star"))level=14;
        if(submerged && (id.equals("minecraft:torch")||id.equals("minecraft:soul_torch")||id.equals("minecraft:redstone_torch")
                ||id.equals("minecraft:campfire")||id.equals("minecraft:soul_campfire")||id.equals("minecraft:fire_charge")
                ||id.equals("minecraft:blaze_powder")))level=0;
        int fromRules=-1;
        for(var rule:lambRules)if(rule.items().contains(id)) {
            int value=rule.waterSensitive()&&submerged?0:rule.level();
            if(value<0) value=rule.block()!=null
                    ?BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(rule.block())).defaultBlockState().getLightEmission()
                    :stack.getItem() instanceof BlockItem b?b.getBlock().defaultBlockState().getLightEmission():0;
            fromRules=Math.max(fromRules,value);
        }
        if(fromRules>=0)level=fromRules;
        if(override!=null)level=Math.round(override[0]);
        if(level<=0)return new float[4]; // No texture work for non-emitting items.
        float r=1,g=.5f,b=.2f;
        if(id.contains("soul_")){r=.15f;g=.7f;b=1;}
        else if(id.contains("sea_lantern")){r=.5f;g=.9f;b=1;}
        else if(id.contains("redstone")){r=1;g=.06f;b=.02f;}
        else if(id.contains("end_rod")){r=.85f;g=.75f;b=1;}
        float[] color=HeldLightColors.of(stack,new float[]{r,g,b});
        if(CausticaConfig.Rt.Look.ITRP.value() && CausticaConfig.Rt.Visuals.ITRP_LIGHT_COLORS.value()) {
            if(id.equals("minecraft:torch") || id.equals("minecraft:lantern") || id.equals("minecraft:campfire"))color=referenceTorchColor();
            else if(id.equals("minecraft:redstone_torch"))color=new float[]{1f,(float)Math.pow(.2,2.2),(float)Math.pow(.17,2.2)};
        }
        return new float[]{level,color[0],color[1],color[2]};
    }
    // Emitter point measured on the player's own captured body (what mirrors show), entity-anchor relative,
    // per hand (0 main, 1 off), plus the frame it was seen. Render thread only.
    private static final HeldLightAnchors anchors=new HeldLightAnchors();
    private static final Vector3f measured=new Vector3f();
    private static boolean capturingPlayer;
    public static void beginPlayerCapture(boolean player){
        capturingPlayer=player;
        if(player)syncAnchorContext(Minecraft.getInstance());
    }
    private static void syncAnchorContext(Minecraft mc) {
        anchors.context(mc.player,mc.level,mc.player==null?null:mc.player.getMainHandItem().getItem(),
                mc.player==null?null:mc.player.getOffhandItem().getItem());
    }
    /** Called by the entity collector after a third-person held item was meshed into anchor-local verts. */
    public static void recordHeldItem(Minecraft mc,boolean rightArm,float[] verts,int from,int to,org.joml.Matrix4fc itemPose) {
        if(!capturingPlayer || mc.player==null || to-from<9)return;
        int hand=(mc.player.getMainArm()==HumanoidArm.RIGHT)==rightArm?0:1;
        ItemStack stack=hand==0?mc.player.getMainHandItem():mc.player.getOffhandItem();
        String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        // A torch/candle flame is the top of the held model; lanterns and glowing blocks glow from their middle.
        boolean flameTop=id.contains("torch") || id.contains("candle");
        anchors.record(hand,RtComposite.frameCounter(),verts,from,to,itemPose,flameTop);
    }

    public static Frame frame(Minecraft mc,double cx,double cy,double cz,int bx,int by,int bz) {
        syncAnchorContext(mc);
        if(mc.player==null || !CausticaConfig.Rt.Visuals.HELD_LIGHTS.value())return new Frame(ZERO,ZERO,ZERO,ZERO);
        reload(mc);
        float partial=mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // The emitter sits where the held item is on the player's own body, which is what mirrors and other
        // views show: vanilla holding pose (shoulder 22/16 up, arm 10/16 raised ~18 deg forward, item tip
        // above the hand) puts the flame ~1.0 block above the feet, 0.37 to the side and 0.4 ahead along the
        // BODY yaw (the arm follows the body, not the head). First person sees the same point near its torch.
        var feet=mc.player.getPosition(partial);
        float scale=mc.player.getScale();
        float bodyYaw=(float)Math.toRadians(net.minecraft.util.Mth.rotLerp(partial,mc.player.yBodyRotO,mc.player.yBodyRot));
        Vector3f forward=new Vector3f((float)-Math.sin(bodyYaw),0,(float)Math.cos(bodyYaw));
        Vector3f right=new Vector3f(-forward.z,0,forward.x);
        double handY=feet.y+(mc.player.isCrouching()?.78:1.0)*scale;
        float handed=mc.player.getMainArm()==HumanoidArm.RIGHT?1:-1;
        Float4[] values=new Float4[4];
        for(int hand=0;hand<2;hand++) {
            var v=emission(hand==0?mc.player.getMainHandItem():mc.player.getOffhandItem(),mc.player.isUnderWater());
            float radius=HeldLightAnchors.influenceRadius(CausticaConfig.Rt.Visuals.HELD_RADIUS.value()*v[0]/15f,
                    mc.player.getAbilities().instabuild && mc.player.getAbilities().flying);
            float side=(hand==0?handed:-handed)*.37f*scale;
            float power=48f*(v[0]/15f)*(v[0]/15f)*CausticaConfig.Rt.Visuals.HELD_BRIGHTNESS.value();
            if(power<=0 || v[1]+v[2]+v[3]<=0)radius=0;
            float ahead=.4f*scale;
            if(anchors.position(hand,RtComposite.frameCounter(),measured)) {
                values[hand*2]=new Float4((float)(feet.x-bx)+measured.x,(float)(feet.y-by)+measured.y,
                        (float)(feet.z-bz)+measured.z,radius);
            } else {
                values[hand*2]=new Float4((float)(feet.x-bx)+right.x*side+forward.x*ahead,
                        (float)(handY-by),(float)(feet.z-bz)+right.z*side+forward.z*ahead,radius);
            }
            values[hand*2+1]=new Float4(v[1]*power,v[2]*power,v[3]*power,hand==0?CausticaConfig.Rt.Visuals.HELD_SOFTNESS.value():CausticaConfig.Rt.Visuals.HELD_SHADOW_FLOOR.value());
        }
        return new Frame(values[0],values[1],values[2],values[3]);
    }
    private RtHeldLights(){}
}
