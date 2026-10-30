package dev.comfyfluffy.caustica.streamline;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
class StreamlineBridgeAbiTest {
 @Test void javaForeignBindingsMatchFreshNativeBridgeWithoutInitializingSdk(){
  String dll=System.getProperty("caustica.test.streamlineBridge");
  assumeTrue(dll!=null,"optional Streamline build profile is disabled");
  var library=StreamlineLibrary.load(Path.of(dll));
  assertFalse(library.isInitialized());
  StreamlineAbi.validate(library);
  assertFalse(library.isInitialized(),"ABI validation must never initialize Streamline or a GPU");
 }
}
