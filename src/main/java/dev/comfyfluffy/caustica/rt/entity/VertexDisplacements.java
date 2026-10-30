package dev.comfyfluffy.caustica.rt.entity;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
/** Packs exact block-local vertex motion into reusable CPU staging storage. */
final class VertexDisplacements {
 static FloatArrayList fill(float[] current,int count,float[] previous,FloatArrayList output) {
  if(count%3!=0||count<0||count>current.length||count>previous.length)throw new IllegalArgumentException("Vertex topology mismatch");
  int vertices=count/3; output.size(vertices*4);
  float[] dst=output.elements();
  for(int i=0;i<vertices;i++) {
   dst[i*4]=current[i*3]-previous[i*3];
   dst[i*4+1]=current[i*3+1]-previous[i*3+1];
   dst[i*4+2]=current[i*3+2]-previous[i*3+2];
   dst[i*4+3]=0f;
  }
  return output;
 }
 private VertexDisplacements() {}
}
