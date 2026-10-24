package tritium.screens.ncm.panels;

import tritium.rendering.animation.spring.SpringAnimation;
import tritium.rendering.animation.spring.SpringParams;

/** Folia's hover delays and spring, with a stable geometry while a pointer is captured. */
final class FoliaMiniExpansion {
    private final SpringAnimation spring;
    private boolean targetExpanded;
    private boolean previousHover;
    private long hoverSince;
    private long previousTime = -1;

    FoliaMiniExpansion(boolean expanded) {
        targetExpanded = expanded;
        spring = new SpringAnimation(expanded ? 1 : 0);
        spring.setParams(new SpringParams(1, 24, 280, false));
    }

    double amount() {
        double amount = Math.max(0, Math.min(1, spring.getCurrentPosition()));
        return amount < .001 ? 0 : amount > .999 ? 1 : amount;
    }

    void update(boolean autoCollapse, boolean hovered, boolean captured, long now) {
        double delta = previousTime < 0 ? 0 : Math.max(0, Math.min(.1, (now - previousTime) / 1_000_000_000.0));
        if (previousTime < 0 || hovered != previousHover || captured) {
            hoverSince = now;
            previousHover = hovered;
        }
        previousTime = now;
        if (captured) return;
        boolean expanded = targetExpanded;
        if (!autoCollapse) expanded = true;
        else if (now - hoverSince >= (hovered ? 20_000_000L : 100_000_000L)) expanded = hovered;
        if (expanded != targetExpanded) {
            targetExpanded = expanded;
            spring.setTargetPosition(expanded ? 1 : 0);
        }
        spring.update(delta);
    }
}
