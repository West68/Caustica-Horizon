package dev.comfyfluffy.caustica.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
class ExposureBoundsTest {
 @Test void underwaterFillTracksDaylightInsteadOfOnlySkyAccess() throws Exception {
  String s=Files.readString(Path.of("shaders/world/world.rgen.slang"));
  assertTrue(s.contains("lerp(.002,1.0,saturate(worldPush.sunDir.w))*worldPush.environment.w"));
  double access=1;double day=.025*access,night=.025*.002*access;
  assertEquals(.002,night/day,1e-12);
 }
 @Test void nightAdaptationDoesNotEraseSourceDynamicRange() throws Exception {
  String s=Files.readString(Path.of("shaders/display/exposure_resolve.comp"));
  assertTrue(s.contains("target=clamp(target,low,high)"));
  assertTrue(s.contains("max(pc.frameTimeSeconds,0.0)*60.0"));
  double day=.5,night=.001;
  java.util.function.DoubleUnaryOperator meter=l->Math.min(8,Math.max(Math.pow(2,-1.5),9.6/(23.9*Math.pow(l,1/1.1)+.08)));
  assertTrue(night*meter.applyAsDouble(night)<day*meter.applyAsDouble(day)*.1);
  for(double l:new double[]{0,1e-10,.001,.1,1,10000})assertTrue(meter.applyAsDouble(l)<=8);
  double t1=1-Math.pow(1-.025,1),t2=1-Math.pow(1-.025,2);
  assertEquals(t2,1-(1-t1)*(1-t1),1e-12);
 }
}
