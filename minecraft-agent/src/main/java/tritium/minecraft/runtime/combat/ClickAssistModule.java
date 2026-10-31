package tritium.minecraft.runtime.combat;

import tritium.minecraft.runtime.hook.ExtendedCombatRuntime;
import tritium.minecraft.runtime.util.OpenB4Module;

import java.awt.AWTException;
import java.awt.Robot;

/** OpenB4's native mouse assist. Robot events are paired with their own recursion guard. */
public final class ClickAssistModule extends OpenB4Module {
    private Robot robot;
    private boolean robotLeft, robotRight;

    public ClickAssistModule(ExtendedCombatRuntime runtime) {
        super(runtime, "clickAssist");
    }

    @Override
    public void enable() {
        try {
            robot = new Robot();
        } catch (AWTException | SecurityException e) {
            runtime.disable(id);
        }
    }

    private void release(int button) {
        if (robot != null && (button == 0 ? robotLeft : robotRight) && !game.mouse(button))
            robot.mouseRelease(button == 0 ? 16 : 4);
    }

    @Override
    public void disable(boolean worldChanged) {
        release(0);
        release(1);
        robotRight = robotLeft = false;
        robot = null;
    }

    public void mouse(boolean down, int button) {
        if (robot == null || button < 0 || !down || !game.inWorld() || runtime.autoClicking())
            return;
        if (on("Disable in creative") && game.creative()
                || on("Disable in replays") && runtime.serverState().replay()) return;
        if (game.screen() != null || game.eating()) {
            release(0);
            release(1);
            return;
        }
        if (button == 0 && on("Left click")) {
            if (robotLeft) robotLeft = false;
            else {
                if (on("Weapon only") && !game.weapon()) return;
                if (on("Only while targeting")
                        && !game.kind(game.hitEntity(), "entity.EntityLivingBase")) return;
                if (!runtime.chance(number("Chance left"))) {
                    release(0);
                    return;
                }
                robot.mouseRelease(16);
                robot.mousePress(16);
                robotLeft = true;
            }
        } else if (button == 1 && on("Right click")) {
            if (robotRight) robotRight = false;
            else {
                if (on("Blocks only") && !game.blockItem()) {
                    release(1);
                    return;
                }
                if (on("Above 5 cps") && runtime.rightCps() <= 5) {
                    release(1);
                    return;
                }
                if (!runtime.chance(number("Chance right"))) {
                    release(1);
                    return;
                }
                robot.mouseRelease(4);
                robot.mousePress(4);
                robotRight = true;
            }
        }
        release(0);
        release(1);
    }
}
