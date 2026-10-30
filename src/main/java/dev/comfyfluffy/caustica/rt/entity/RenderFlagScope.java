package dev.comfyfluffy.caustica.rt.entity;

import java.lang.invoke.*;
import java.lang.reflect.Modifier;
import java.util.*;

/** Per-invocation saved values make nested render contexts restore in LIFO order. */
final class RenderFlagScope implements AutoCloseable {
    record Slot(VarHandle field,Object neutral) {}
    private final List<Slot> slots;
    private final Object[] saved;
    private boolean closed;
    static List<Slot> bind(Class<?> type,String... names) throws IllegalAccessException {
        var slots=new ArrayList<Slot>();
        for(String name:names)try {
            var field=type.getField(name);
            if(!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))continue;
            if(field.getType()!=boolean.class && field.getType()!=Boolean.class)continue;
            slots.add(new Slot(MethodHandles.publicLookup().unreflectVarHandle(field),field.getType()==boolean.class?Boolean.FALSE:null));
        }catch(NoSuchFieldException ignored) {} // Optional fields differ between supported EMF versions.
        return List.copyOf(slots);
    }
    RenderFlagScope(List<Slot> slots) {
        this.slots=slots;saved=new Object[slots.size()];
        for(int i=0;i<slots.size();i++){var s=slots.get(i);saved[i]=s.field.get();s.field.set(s.neutral);}
    }
    @Override public void close() {
        if(closed)return;closed=true;
        for(int i=slots.size()-1;i>=0;i--)slots.get(i).field.set(saved[i]);
    }
}
