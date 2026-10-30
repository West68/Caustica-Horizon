package dev.comfyfluffy.caustica.client;
import java.util.*;
/** DH can reuse section owners after Minecraft replaces a ClientLevel on a dimension round trip. */
final class DhWorldNamespaces {
 private Object connection;
 private final Map<Object,Object> dimensions=new HashMap<>();
 private final Map<Object,Object> knownLevels=new WeakHashMap<>();
 private void connection(Object value){if(connection!=value){connection=value;dimensions.clear();knownLevels.clear();}}
 synchronized Object current(Object session,Object level,Object dimension){
  connection(session);if(session==null||level==null)return null;
  Object namespace=dimensions.computeIfAbsent(dimension,key->new Object());knownLevels.put(level,namespace);return namespace;
 }
 synchronized Object captured(Object session,Object level){connection(session);return session==null?null:knownLevels.get(level);}
 synchronized void clear(){connection=null;dimensions.clear();knownLevels.clear();}
}
