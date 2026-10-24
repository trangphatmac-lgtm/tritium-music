// SPDX-License-Identifier: AGPL-3.0-only
package tritium.screens.ncm.lyrics;

/** Folia's seeded geometry and easing, evaluated from playback time rather than frame count. */
public final class LyricMath {
    private LyricMath() { }
    public static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
    public static double mix(double a, double b, double x) { return a + (b - a) * x; }
    public static double smooth(double x) { x = clamp(x); return x * x * (3 - 2 * x); }
    public static double out(double x) { return 1 - Math.pow(1 - clamp(x), 3); }
    public static double inOut(double x) { x = clamp(x); return x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; }
    public static double random(double seed) { double x = Math.sin(seed) * 10000; return x - Math.floor(x); }
    public static double radians(double degrees) { return degrees * Math.PI / 180; }
    public static double readableAngle(double degrees) {
        degrees %= 180;
        if (degrees > 90) degrees -= 180;
        if (degrees < -90) degrees += 180;
        return degrees;
    }
    public static int alpha(int color, double alpha) {
        return (color & 0xFFFFFF) | ((int) Math.round(((color >>> 24) & 255) * clamp(alpha)) << 24);
    }
    public static int color(int a, int b, double t) {
        t = clamp(t);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            result |= (int) Math.round(mix(a >>> shift & 255, b >>> shift & 255, t)) << shift;
        return result;
    }

    /** Closed-form spring step stays stable across low frame rates and a minimized window. */
    public static final class Spring {
        private final double stiffness, damping;
        public Spring() { this(100, 20); }
        public Spring(double stiffness, double damping) { this.stiffness = stiffness; this.damping = damping; }
        public double value, velocity;
        public boolean initialized;
        public void snap(double target) { value = target; velocity = 0; initialized = true; }
        public double step(double target, double dt) { return step(target, dt, stiffness, damping); }
        public double step(double target, double dt, double stiffness, double damping) {
            if (!initialized) snap(target);
            dt = Math.min(.1, Math.max(0, dt));
            double decay = damping / 2, discriminant = stiffness - decay * decay;
            if (Math.abs(discriminant) < 1e-7) {
                double offset = value - target, b = velocity + decay * offset, e = Math.exp(-decay * dt);
                value = target + (offset + b * dt) * e;
                velocity = (velocity - decay * b * dt) * e;
                return value;
            }
            if (discriminant < 0) {
                double root = Math.sqrt(-discriminant), r1 = -decay + root, r2 = -decay - root;
                double offset = value - target, c1 = (velocity - r2 * offset) / (r1 - r2), c2 = offset - c1;
                value = target + c1 * Math.exp(r1 * dt) + c2 * Math.exp(r2 * dt);
                velocity = r1 * c1 * Math.exp(r1 * dt) + r2 * c2 * Math.exp(r2 * dt);
                return value;
            }
            double omega = Math.sqrt(discriminant);
            double offset = value - target, phase = omega * dt, e = Math.exp(-decay * dt);
            double b = (velocity + decay * offset) / omega;
            value = target + e * (offset * Math.cos(phase) + b * Math.sin(phase));
            velocity = e * ((b * omega - decay * offset) * Math.cos(phase)
                    - (offset * omega + decay * b) * Math.sin(phase));
            return value;
        }
    }
}
