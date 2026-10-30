package dev.comfyfluffy.caustica.client;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
/** Per-client-level namespaces. Values must not retain the level or DH section strongly. */
final class DhSnapshotStore<T> {
 private record Entry<T>(WeakReference<Object> owner,T value) {}
 private final Map<Object,ConcurrentHashMap<Long,Entry<T>>> worlds=new WeakHashMap<>();
 private synchronized ConcurrentHashMap<Long,Entry<T>> world(Object level,boolean create) {
  if(level==null)return null;
  return create?worlds.computeIfAbsent(level,k->new ConcurrentHashMap<>()):worlds.get(level);
 }
 T get(Object level,long pos){var m=world(level,false);var e=m==null?null:m.get(pos);return e==null?null:e.value;}
 void put(Object level,long pos,Object owner,T value){world(level,true).put(pos,new Entry<>(new WeakReference<>(owner),value));}
 void remove(long pos,Object owner){List<ConcurrentHashMap<Long,Entry<T>>> maps;synchronized(this){maps=new ArrayList<>(worlds.values());}
  for(var m:maps){var e=m.get(pos);if(e!=null&&e.owner.get()==owner)m.remove(pos,e);}
 }
 List<T> values(Object level,BiPredicate<Object,T> enabled){var out=new ArrayList<T>();var m=world(level,false);if(m==null)return out;
  m.forEach((pos,e)->{var owner=e.owner.get();if(owner==null)m.remove(pos,e);else if(enabled.test(owner,e.value))out.add(e.value);});return out;
 }
 int count(Object level){var m=world(level,false);return m==null?0:m.size();}
 synchronized void clear(){worlds.clear();}
}
