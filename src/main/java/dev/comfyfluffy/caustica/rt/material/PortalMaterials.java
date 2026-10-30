package dev.comfyfluffy.caustica.rt.material;
/** Semantic classification of the renderer's special portal textures, not ordinary glass. */
public final class PortalMaterials {
 public static final int END=32,NETHER=64;
 public static int flags(String name) {
  if(name==null)return 0;
  String p=name.replace("textures/","").replace(".png","");
  if(p.endsWith(":entity/end_portal")||p.endsWith(":environment/end_sky")||p.endsWith(":entity/end_gateway"))return END;
  if(p.endsWith(":block/nether_portal"))return NETHER;
  return 0;
 }
 private PortalMaterials(){}
}
