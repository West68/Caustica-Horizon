package dev.comfyfluffy.caustica.rt;
import dev.comfyfluffy.caustica.client.DhLodCapture;
import dev.comfyfluffy.caustica.rt.entity.DhLodSelection;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhLodSelectionTest {
    private static DhLodCapture.SectionSnapshot s(long id,int detail,int x,int z) {
        return new DhLodCapture.SectionSnapshot(id,detail,x,z,List.of(),0,1,-64);
    }
    @Test void distanceUsesNearestSectionEdgeNotItsDistantCentre() {
        var large=s(1,12,-4096,-4096);
        assertEquals(0,DhLodSelection.distance(large,-1,-1));
        assertEquals(100,DhLodSelection.distance(large,10,-1));
        assertEquals(200,DhLodSelection.distance(large,10,10));
    }
    @Test void denseNearSectionsKeepAllOriginalFacesAndVerticalLayers() {
        var face=new DhLodCapture.LodQuad((short)0,(short)80,(short)0,(short)1,(short)2,0xff00ff00,(short)1,(byte)0,(byte)15,(byte)0,4,false,false,true);
        var dense=new DhLodCapture.SectionSnapshot(1,10,0,0,Collections.nCopies(4096,face),0,1,-64);
        assertEquals(64,DhLodSelection.resolution(dense,1025,500));
        assertEquals(16,DhLodSelection.resolution(dense,1600,500));
        assertEquals(8,DhLodSelection.resolution(dense,4000,500));
    }
    @Test void incompleteChildrenKeepParentWithoutOverlappingReadyChildren() {
        var parent=s(1,7,0,0);var a=s(2,6,0,0);var b=s(3,6,64,0);var c=s(4,6,0,64);var d=s(5,6,64,64);
        var wanted=List.of(a,b,c,d);var resident=new HashMap<Long,DhLodCapture.SectionSnapshot>();resident.put(1L,parent);
        for(var child:List.of(a,b,c)) {
            resident.put(child.pos(),child);
            assertEquals(List.of(parent),DhLodSelection.handoff(wanted,resident));
        }
        resident.put(d.pos(),d);
        assertEquals(wanted,DhLodSelection.handoff(wanted,resident));
    }
    @Test void movingAwayKeepsOldChildrenUntilCoarserReplacementExists() {
        var parent=s(1,7,0,0);var a=s(2,6,0,0);var b=s(3,6,64,0);
        var resident=new HashMap<Long,DhLodCapture.SectionSnapshot>();resident.put(2L,a);resident.put(3L,b);
        assertEquals(Set.of(a,b),Set.copyOf(DhLodSelection.handoff(List.of(parent),resident)));
        resident.put(1L,parent);
        assertEquals(List.of(parent),DhLodSelection.handoff(List.of(parent),resident));
    }
    @Test void staleNonoverlappingSectionsAreNotRetainedAndNegativeCoordinatesRemainExact() {
        var a=s(1,6,-30000000,-30000000);var b=s(2,6,-29999936,-30000000);
        assertFalse(DhLodSelection.contains(a,b));
        assertTrue(DhLodSelection.handoff(List.of(b),Map.of(1L,a)).isEmpty());
        assertEquals(1,DhLodSelection.distance(a,-30000001,-30000000));
    }
    @Test void uncapturedChildDoesNotDisappearFromCoverageAccounting() {
        var parent=s(1,7,0,0);var a=s(2,6,0,0);var b=s(3,6,64,0);var c=s(4,6,0,64);
        var wanted=List.of(a,b,c);
        var resident=Map.of(1L,parent,2L,a,3L,b,4L,c);
        assertEquals(List.of(parent),DhLodSelection.handoff(wanted,resident));
        // The fourth quadrant can instead be owned by real fine RT terrain.
        assertEquals(wanted,DhLodSelection.handoff(wanted,resident,(x,z,width)->x>=64 && z>=64 && (long)x+width<=128 && (long)z+width<=128));
    }
    @Test void readyEmptyChildReleasesCoarseParentInsteadOfBlockingRefinement() {
        var parent=s(1,7,0,0);var a=s(2,6,0,0);var b=s(3,6,64,0);var c=s(4,6,0,64);
        // No BLAS exists for empty D, but its completed snapshot certifies the fourth quadrant.
        var empty=s(5,6,64,64);var wanted=List.of(a,b,c,empty);
        assertEquals(List.of(parent),DhLodSelection.handoff(wanted,Map.of(1L,parent,2L,a,3L,b,4L,c)));
        assertEquals(wanted,DhLodSelection.handoff(wanted,Map.of(1L,parent,2L,a,3L,b,4L,c,5L,empty)));
    }
}
