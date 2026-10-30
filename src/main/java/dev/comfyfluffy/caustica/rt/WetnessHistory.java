package dev.comfyfluffy.caustica.rt;
/** Render-time state only: rainfall wets quickly; retained film dries slowly. No world data edits. */
public final class WetnessHistory {
 private Object world;private long previous;private float value;
 public float update(Object current,float rain,long now) {
  rain=Math.clamp(rain,0,1);
  if(current!=world || previous==0){world=current;value=rain;}
  else {double dt=Math.clamp((now-previous)*1e-9,0,.25);double tau=rain>value?8:90;
   value+=(rain-value)*(float)(-Math.expm1(-dt/tau));}
  previous=now;return value;
 }
}
