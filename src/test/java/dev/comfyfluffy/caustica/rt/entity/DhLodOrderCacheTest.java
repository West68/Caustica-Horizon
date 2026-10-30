package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.client.DhLodCapture.SectionSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DhLodOrderCacheTest {
    private static SectionSnapshot section(long pos,int x,long version){
        return new SectionSnapshot(pos,4,x,0,List.of(),0,version,0);
    }
    @Test void freshUnorderedListsKeepThePriorityAfterCacheHit(){
        var near=section(1,16,1);var far=section(2,1024,1);var cache=new DhLodOrderCache();
        var first=new ArrayList<>(List.of(far,near));cache.apply(first,123,0,0);
        first.subList(1,first.size()).clear(); // Caller admission cap must not truncate the cached sequence.
        var next=new ArrayList<>(List.of(far,near));cache.apply(next,123,0,0);
        assertEquals(List.of(near,far),next);
    }
    @Test void revisedGeometryAndCameraUseTheNewOrderAndSnapshots(){
        var near=section(1,16,1);var far=section(2,1024,1);var cache=new DhLodOrderCache();
        cache.apply(new ArrayList<>(List.of(far,near)),1,0,0);
        var revised=section(2,1024,2);var next=new ArrayList<>(List.of(near,revised));
        cache.apply(next,2,1030,0);assertSame(revised,next.getFirst());
        cache.clear();cache.apply(next,2,0,0);assertSame(near,next.getFirst());
    }
}
