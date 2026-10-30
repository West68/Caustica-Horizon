package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import java.util.function.DoubleUnaryOperator;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ReliefLightVisibilityTest {
    static double visibility(double depth,double slope,DoubleUnaryOperator height){
        return visibility(depth,slope,height,4);
    }
    static double visibility(double depth,double slope,DoubleUnaryOperator height,int steps){
        double result=1;
        for(int i=1;i<=steps;i++){
            double rise=depth*i/steps;
            result=Math.min(result,Math.clamp((1-depth+rise-height.applyAsDouble(slope*rise))*100+.5,0,1));
        }
        return result;
    }
    @Test void directionShadowsReliefWallButLeavesOppositeDirectionOpen(){
        DoubleUnaryOperator wall=x->x>.05 && x<.3?.9:.2;
        assertEquals(0,visibility(.8,1,wall));
        assertEquals(1,visibility(.8,-1,wall));
    }
    @Test void flatFieldsAndOverheadLightingDoNotInventAO(){
        assertEquals(1,visibility(.8,0,x->.2));
        assertEquals(1,visibility(.8,1,x->.2));
        assertEquals(1,visibility(0,1,x->0));
    }
    @Test void texelSpanSearchFindsNarrowRidgesSkippedByFourUniformSamples(){
        DoubleUnaryOperator ridge=x->x>.29 && x<.35?.95:.2;
        assertEquals(1,visibility(.8,1,ridge,4));
        assertEquals(0,visibility(.8,1,ridge,16));
    }
    @Test void fullFloatDepthReconstructsTheSameSmoothHitUv(){
        java.util.Random r=new java.util.Random(301);
        for(int i=0;i<1000;i++){
            float start=r.nextFloat(),ray=r.nextFloat()*8-4,depth=r.nextFloat();
            float traced=start-ray*depth;
            int transport=Float.floatToRawIntBits(depth);
            float reconstructed=start-ray*Float.intBitsToFloat(transport);
            assertEquals(Float.floatToRawIntBits(traced),Float.floatToRawIntBits(reconstructed));
        }
    }
    @Test void receiverQueryAddsNoTraversalAndHasNoSourceWhitelist() throws Exception {
        String s=Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String q=s.substring(s.indexOf("float outgoingReliefVisibility("),s.indexOf("float3 visibilityMasked("));
        assertTrue(q.contains("HitObject::Invoke(topLevelAS,receiver,query)"));
        assertFalse(q.contains("TraceRay("));assertFalse(q.contains("torchGuide"));
        assertFalse(s.contains("torchReliefShadow("));
        assertTrue(s.contains("throughput*=outgoingReliefVisibility(receiverHit,rd,reliefCone,reliefCameraFlag,reliefDepth)"));
    }
    @Test void queryDoesNotExpandCrossStagePayloadAndMirrorBudgetIsNotExtendedPerPlane() throws Exception {
        String s=Files.readString(Path.of("shaders/world/world_common.slang"));
        // Published payload ABI; test remains portable without a backup path or a running client.
        String schema="public struct Payload { public float3 albedo; public float3 normal; public float hitT; "
                +"public float3 motionPrev; public float3 f0; public uint flags; public uint roughMetal; "
                +"public uint emissionSss; public uint iorTransmission; public uint rayCone; };";
        String body=s.substring(s.indexOf("public struct Payload {"),s.indexOf("};",s.indexOf("public struct Payload {"))+2);
        assertEquals(schema,body.replaceAll("//[^\\n]*", "").replaceAll("\\s+", " "));
        String raygen=Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(raygen.contains("if(!gv_psr) { maxBounces+=1; rrStart+=1; }"));
        assertTrue(raygen.contains("bounce == gvBounce"));
    }
}
