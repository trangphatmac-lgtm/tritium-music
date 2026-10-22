// SPDX-License-Identifier: AGPL-3.0-only
// Folia morphFlight, wallReflowMotion, Bravais tile flip and wave timings.
package tritium.screens.ncm.navigation;

public final class FoliaMotion {
    private FoliaMotion() { }
    public static double unit(double t) { return Math.clamp(t,0,1); }
    public static double ease(double t) { return 1-Math.pow(1-unit(t),3); }
    public static double flight(double ms) {
        if(ms<=0) return 0; if(ms>=460) return 1;
        double natural=2*Math.PI/.36, damped=natural*Math.sqrt(1-.86*.86), seconds=ms/1000;
        return 1-Math.exp(-.86*natural*seconds)*(Math.cos(damped*seconds)+.86*natural/damped*Math.sin(damped*seconds));
    }
    /** stiffness 300, damping 34, mass 1, matching Folia's nearly critical reflow spring. */
    public static double reflow(double ms) {
        if(ms<=0) return 0; if(ms>=800) return 1;
        double seconds=ms/1000, frequency=Math.sqrt(300-17*17);
        return 1-Math.exp(-17*seconds)*(Math.cos(frequency*seconds)+17/frequency*Math.sin(frequency*seconds));
    }
    public static FoliaGeometry.Box mix(FoliaGeometry.Box from,FoliaGeometry.Box to,double t) {
        return new FoliaGeometry.Box(to.index(),lerp(from.x(),to.x(),t),lerp(from.y(),to.y(),t),
                lerp(from.width(),to.width(),t),lerp(from.height(),to.height(),t));
    }
    public static double lerp(double a,double b,double t) { return a+(b-a)*t; }
}
