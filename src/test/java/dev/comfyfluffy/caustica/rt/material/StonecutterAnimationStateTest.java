package dev.comfyfluffy.caustica.rt.material;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StonecutterAnimationStateTest {
    @Test void actualMinecraftAnimationTicksAndActualMaterialReaderAgreeOnThreeFrameCycle() throws Exception {
        // Real installed 26.2 classes, no Minecraft instance, GPU or render loop.
        Class<?> fi=Class.forName("net.minecraft.client.renderer.texture.SpriteContents$FrameInfo");
        var ctor=fi.getDeclaredConstructor(int.class,int.class);ctor.setAccessible(true);
        List<Object> frames=new ArrayList<>();
        for(int i:new int[]{2,0,1})frames.add(ctor.newInstance(i,2)); // nonsequential image indexes
        Class<?> info=Class.forName("net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture");
        Class<?> state=Class.forName("net.minecraft.client.renderer.texture.SpriteContents$AnimationState");
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");
        Field uf=unsafe.getDeclaredField("theUnsafe");uf.setAccessible(true);
        Object u=uf.get(null);
        Object animation=unsafe.getMethod("allocateInstance",Class.class).invoke(u,info);
        Object ticker=unsafe.getMethod("allocateInstance",Class.class).invoke(u,state);
        Field framesField=info.getDeclaredField("frames");framesField.setAccessible(true);framesField.set(animation,frames);
        Field infoField=state.getDeclaredField("animationInfo");infoField.setAccessible(true);infoField.set(ticker,animation);
        Field frameField=state.getDeclaredField("frame");frameField.setAccessible(true);
        Class<?> reader=Class.forName("dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry$AnimationFrames");
        Map<String,Object> bindings=Map.of("framesField",framesField,"frameField",frameField,"infoField",infoField,"indexMethod",fi.getDeclaredMethod("index"));
        ((Method)bindings.get("indexMethod")).setAccessible(true);
        Map<Field,Object> old=new HashMap<>();
        try {
            for(var e:bindings.entrySet()){
                Field f=reader.getDeclaredField(e.getKey());f.setAccessible(true);old.put(f,f.get(null));f.set(null,e.getValue());
            }
            var imageFrame=reader.getDeclaredMethod("imageFrame",Object.class);imageFrame.setAccessible(true);
            var tick=state.getDeclaredMethod("tick");tick.setAccessible(true);
            for(int expected:new int[]{2,2,0,0,1,1,2,2,0,0,1,1}){
                assertEquals(expected,imageFrame.invoke(null,ticker));tick.invoke(ticker);
            }
            assertEquals(-1,imageFrame.invoke(null,new Object[]{null}));
        } finally {for(var e:old.entrySet())e.getKey().set(null,e.getValue());}
    }
}
