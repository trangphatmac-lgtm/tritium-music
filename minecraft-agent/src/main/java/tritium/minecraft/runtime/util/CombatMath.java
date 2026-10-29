package tritium.minecraft.runtime.util;

public final class CombatMath {
    public static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }
    public static float pitch(float p) { return Math.max(-90, Math.min(90, p)); }
    public static float yaw(double x, double z, double tx, double tz) { return (float) -Math.toDegrees(Math.atan2(tx-x, tz-z)); }
    public static float[] sensitivity(float yaw, float pitch, float lastYaw, float lastPitch, float sensitivity, int jitter) {
        float f = sensitivity * .6f + .2f; double step = f*f*f*1.2;
        float y = lastYaw + (float)(Math.round((yaw-lastYaw)/step)*step);
        if (Math.abs(yaw-lastYaw) >= step) y += jitter*step;
        return new float[]{y, pitch(lastPitch + (float)(Math.round((pitch-lastPitch)/step)*step))};
    }
    public static float[] toBox(double[] eye, double[] b, float yaw, float pitch) {
        boolean inside = true; for (int i = 0; i < 3; i++) inside &= eye[i] > b[i] && eye[i] < b[i+3];
        if (inside) return new float[]{yaw,pitch};
        double[] p = {(b[0]+b[3])/2, b[1]+(b[4]-b[1])*.825, (b[2]+b[5])/2};
        // Raven's shared Settings defaults: Aim closest point = 90%.
        for (int i = 0; i < 3; i++) p[i] += (Math.max(b[i]+.03, Math.min(b[i+3]-.03, eye[i]))-p[i])*.9;
        double dx=p[0]-eye[0], dy=p[1]-eye[1], dz=p[2]-eye[2];
        float y = (float)Math.toDegrees(Math.atan2(dz,dx))-90;
        float pch = (float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));
        return new float[]{yaw+wrap(y-yaw), pitch(pitch+wrap(pch-pitch))};
    }
    public static double[] direction(float yaw, float pitch) {
        double y=Math.toRadians(yaw), p=Math.toRadians(pitch);
        return new double[]{-Math.sin(y)*Math.cos(p), -Math.sin(p), Math.cos(y)*Math.cos(p)};
    }
    public static double[] intercept(double[] eye, double[] box, float yaw, float pitch, double range) {
        double[] dir=direction(yaw,pitch); double min=0,max=range;
        for (int i=0;i<3;i++) {
            if (Math.abs(dir[i]) < 1e-10) { if (eye[i]<box[i] || eye[i]>box[i+3]) return null; continue; }
            double a=(box[i]-eye[i])/dir[i], b=(box[i+3]-eye[i])/dir[i];
            min=Math.max(min,Math.min(a,b)); max=Math.min(max,Math.max(a,b)); if (min>max) return null;
        }
        return new double[]{eye[0]+dir[0]*min,eye[1]+dir[1]*min,eye[2]+dir[2]*min};
    }
    private CombatMath() { }
}
