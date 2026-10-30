package dev.comfyfluffy.caustica.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhWorldNamespacesTest {
 @Test void recreatedClientLevelReusesTheSameDimensionWithinOneConnection(){
  var names=new DhWorldNamespaces();Object session=new Object(),oldWorld=new Object(),end=new Object(),newWorld=new Object();
  Object namespace=names.current(session,oldWorld,"overworld");Object endNamespace=names.current(session,end,"end");
  assertNotSame(namespace,endNamespace);assertSame(namespace,names.current(session,newWorld,"overworld"));
  assertSame(namespace,names.captured(session,oldWorld));assertSame(endNamespace,names.captured(session,end));
 }
 @Test void oldServerAndUnknownOwnersCannotContaminateTheNewSession(){
  var names=new DhWorldNamespaces();Object first=new Object(),old=new Object();Object a=names.current(first,old,"overworld");
  Object second=new Object(),fresh=new Object();Object b=names.current(second,fresh,"overworld");
  assertNotSame(a,b);assertNull(names.captured(second,old));assertNull(names.captured(second,new Object()));
  names.clear();assertNull(names.captured(second,fresh));
 }
}
