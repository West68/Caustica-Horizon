package dev.comfyfluffy.caustica.rt.entity;

/** Conservative camera-centred retraction shared by the final animated hand AND item mesh. */
public final class HandCollision {
    private HandCollision() {}
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        double min(int axis) { return switch(axis) { case 0 -> minX; case 1 -> minY; default -> minZ; }; }
        double max(int axis) { return switch(axis) { case 0 -> maxX; case 1 -> maxY; default -> maxZ; }; }
        public double reachScale(double reach) {
            double far=Math.max(Math.max(Math.abs(minX),Math.abs(maxX)),Math.max(
                    Math.max(Math.abs(minY),Math.abs(maxY)),Math.max(Math.abs(minZ),Math.abs(maxZ))));
            return Double.isFinite(far)?Math.min(1,reach/Math.max(far,1e-9)):0;
        }
    }
    /** Earliest overlap of scaled model AABB with a collider, over the entire shrink sweep. */
    public static double firstContact(Bounds model,Bounds obstacle,double limit) {
        double enter=0,exit=limit;
        for(int axis=0;axis<3;axis++) {
            double hi=model.max(axis),lo=model.min(axis),a=obstacle.min(axis),b=obstacle.max(axis);
            if(hi>0)enter=Math.max(enter,a/hi);
            else if(hi<0)exit=Math.min(exit,a/hi);
            else if(a>0)return limit;
            if(lo>0)exit=Math.min(exit,b/lo);
            else if(lo<0)enter=Math.max(enter,b/lo);
            else if(b<0)return limit;
            if(exit<enter)return limit;
        }
        if(exit<0 || enter>limit)return limit;
        return Math.max(0,Math.nextDown(enter));
    }
}
