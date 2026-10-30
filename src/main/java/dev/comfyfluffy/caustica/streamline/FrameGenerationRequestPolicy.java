package dev.comfyfluffy.caustica.streamline;
/** Request admission only: this never changes the SDK capability or the submitted/generated count. */
public final class FrameGenerationRequestPolicy {
 public static boolean canRequest(int generated,boolean supported,boolean failed,int sdkMaximum,boolean managedRoute) {
  if(generated==0)return true;
  if(generated<1||generated>5||!supported||failed)return false;
  // Bootstrap has verified these exact ASI/Core binaries. A cold Off viewport has no capability
  // packet yet; retain the request while RtStreamlineFg safely starts at 2x and observes real presents.
  if(managedRoute)return generated<=5; // RTXMFG wrapper reports up to 6X; actual presents are verified at runtime
  return sdkMaximum>0 ? generated<=sdkMaximum : generated==1;
 }
 private FrameGenerationRequestPolicy(){}
}
