package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.client.DhLodCapture.SectionSnapshot;
import java.util.*;

/** Retains the actual ordered sequence, not just the signature of a previously sorted temporary list. */
final class DhLodOrderCache {
    private long signature;
    private List<SectionSnapshot> ordered=List.of();
    private boolean valid;
    void apply(List<SectionSnapshot> snapshots,long nextSignature,double x,double z) {
        if(!valid || signature!=nextSignature || ordered.size()!=snapshots.size()) {
            snapshots.sort(Comparator.<SectionSnapshot>comparingDouble(s->DhLodSelection.distance(s,x,z))
                    .thenComparingLong(SectionSnapshot::pos));
            ordered=List.copyOf(snapshots);signature=nextSignature;valid=true;
        } else {
            snapshots.clear();snapshots.addAll(ordered);
        }
    }
    void clear(){valid=false;ordered=List.of();}
}
