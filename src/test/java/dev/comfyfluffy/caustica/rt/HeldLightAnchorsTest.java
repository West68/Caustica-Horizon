package dev.comfyfluffy.caustica.rt;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HeldLightAnchorsTest {
    private static float[] triangle(float x,float y,float z) { return new float[]{x,y,z,x,y,z,x,y,z}; }

    @Test void followsDifferentModelHeightsWithoutVanillaDistanceGating() {
        var anchors=new HeldLightAnchors();var p=new Vector3f();
        anchors.record(0,10,triangle(.3f,.7f,-.4f),0,9,null,false);
        assertTrue(anchors.position(0,10,p)); assertEquals(.7f,p.y);
        anchors.record(0,11,triangle(.3f,3.7f,-.4f),0,9,null,false);
        assertTrue(anchors.position(0,11,p)); assertEquals(3.7f,p.y);
        assertEquals(.3f,p.x);assertEquals(-.4f,p.z);
    }

    @Test void flameTipFollowsLocalUpEvenWhenTheHandTurnsUpsideDown() {
        var anchors=new HeldLightAnchors();var result=new Vector3f();
        var pose=new Matrix4f().translation(2,3,4).rotateZ((float)Math.PI).scale(.5f);
        float[] vertices=new float[18];
        for(int i=0;i<6;i++) {
            var p=pose.transformPosition(new Vector3f(0,i<3?0:1,0));
            vertices[i*3]=p.x;vertices[i*3+1]=p.y;vertices[i*3+2]=p.z;
        }
        anchors.record(1,10,vertices,0,18,pose,true);
        assertTrue(anchors.position(1,10,result));
        assertEquals(2,result.x,1e-6);assertEquals(2.5,result.y,1e-6);assertEquals(4,result.z,1e-6);
    }

    @Test void combinesSplitItemLayersRatherThanJumpingToTheLastLayer() {
        var anchors=new HeldLightAnchors();var p=new Vector3f();
        anchors.record(0,10,triangle(1,1,1),0,9,null,false);
        anchors.record(0,10,triangle(3,3,3),0,9,null,false);
        assertTrue(anchors.position(0,10,p));assertEquals(new Vector3f(2,2,2),p);
        assertFalse(anchors.position(1,10,p));
        anchors.record(0,11,triangle(5,5,5),0,9,null,false);
        assertTrue(anchors.position(0,11,p));assertEquals(new Vector3f(5,5,5),p);
    }

    @Test void brieflyRetainsMissingFramesButInvalidatesOwnerWorldAndItemChanges() {
        var a=new HeldLightAnchors();var p=new Vector3f();Object owner=new Object(),world=new Object(),item=new Object();
        a.context(owner,world,item,null);a.record(0,10,triangle(1,2,3),0,9,null,false);
        a.context(owner,world,item,null);assertTrue(a.position(0,12,p));assertFalse(a.position(0,13,p));
        assertFalse(a.position(0,9,p));
        a.context(owner,world,new Object(),null);assertFalse(a.position(0,10,p));
        a.record(0,11,triangle(1,2,3),0,9,null,false);
        a.context(owner,new Object(),item,null);assertFalse(a.position(0,11,p));
        a.record(0,12,triangle(1,2,3),0,9,null,false);
        a.context(new Object(),world,item,null);assertFalse(a.position(0,12,p));
    }

    @Test void rejectsNonfiniteOrSingularGeometryWithoutPoisoningTheLight() {
        var a=new HeldLightAnchors();var p=new Vector3f();
        a.record(0,10,triangle(Float.NaN,1,2),0,9,null,false);
        assertFalse(a.position(0,10,p));
        a.record(0,10,triangle(1,2,3),0,9,new Matrix4f().zero(),true);
        assertFalse(a.position(0,10,p));
        a.record(0,10,triangle(1,2,3),0,9,null,false);
        assertTrue(a.position(0,10,p));assertEquals(new Vector3f(1,2,3),p);
    }

    @Test void creativeFlightOnlyLimitsNearbyInfluenceAndPreservesWalkingSettings() {
        assertEquals(3,HeldLightAnchors.influenceRadius(8.31f,true));
        assertEquals(8.31f,HeldLightAnchors.influenceRadius(8.31f,false));
        assertEquals(1,HeldLightAnchors.influenceRadius(1,true));
    }
}
