package dev.comfyfluffy.caustica.enhanced.test;
/** Test-only one-time option seeding before the separate client gametest launch. */
public final class OptionsSeeder implements net.fabricmc.api.ClientModInitializer {
 public void onInitializeClient(){
  if(Boolean.getBoolean("ce.entityPortalRegression")){EntityPortalRegression.start();return;}
  if(Boolean.getBoolean("ce.dhRefinement")){DhRefinementRegression.start();return;}
  if(Boolean.getBoolean("ce.rtHandRecovery")){RtHandRecovery.start();return;}
  if(Boolean.getBoolean("ce.dhRegression")){DhHardwareRegression.start();return;}
  if(Boolean.getBoolean("ce.nativeFgRegression")){NativeFgRegression.start();return;}
  if(Boolean.getBoolean("ce.compatSmoke")){CompatibilitySmoke.start();return;}
  if(Boolean.getBoolean("ce.deviceLostSmoke")){TerrainDeviceLostSmoke.start();return;}
  if(Boolean.getBoolean("ce.normalClient")){NormalClientSmoke.start();return;}
  if(!Boolean.getBoolean("ce.seedOptions"))return;
  int[] ticks={0};net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc->{
   if(++ticks[0]!=50)return;
   mc.options.save();
   try {java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("ce.evidence"),"options-seeded.json"),"{\"keyMappings\":"+mc.options.keyMappings.length+"}");}
   catch(java.io.IOException e){throw new RuntimeException(e);}
   mc.stop();
  });
 }
}
