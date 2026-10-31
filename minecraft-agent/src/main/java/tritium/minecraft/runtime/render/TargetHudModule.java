package tritium.minecraft.runtime.render;
import java.awt.Color;
import org.lwjgl.opengl.GL11;
import tritium.minecraft.runtime.hook.RenderAccess;
import tritium.minecraft.runtime.util.render.*;
/** OpenB4 Modern/Legacy HUD; normal targets are supplied by its combat owner. */
public final class TargetHudModule extends RenderModule {
 private static final int TEXT=0xffdcdcdc;
 private double shown,target=-1,from=-1;private long changedAt=-1;
 private Theme theme=Theme.DEFAULT;private final EspShaders shaders;private GlowShader glow;private Blur blur;private boolean editing;
 public TargetHudModule(RenderAccess g,Render3D d){super("targetHud",g,d);shaders=new EspShaders(g,d);}
 private GlowShader glow(){if(glow==null)glow=new GlowShader(g,draw);return glow;}
 private Blur blur(){if(blur==null)blur=new Blur(g,draw);return blur;}
 public void editor(boolean editing){this.editing=editing;}
 public void disable(){changedAt=-1;from=target=-1;}
 public String text(String name,float health,double ratio){StringBuilder sb=new StringBuilder(name);if(on("Show health"))sb.append(' ').append(RenderStrings.health(ratio,health));float own=g.health(g.player());if(on("Show difference"))sb.append(' ').append(RenderStrings.difference(own-health));if(on("Show win/loss"))sb.append(" §").append(ratio<=own/g.maxHealth(g.player())?"aW":"cL");String value=sb.toString();if(value.length()<14){int pad=(16-value.length()+1)/2;String spaces="          ".substring(0,pad);value=spaces+value+spaces;}return value;}
 private String preview(){return text(g.displayName(g.player(),false),RenderAccess.f(g.invoke(g.player(),"getHealth")),Math.min(RenderAccess.f(g.invoke(g.player(),"getHealth"))/g.maxHealth(g.player()),1));}
 public double[] hudBounds(double width,double height){String text=g.inWorld()?preview():"TargetHUD";int w=g.textWidth(text)+8;return new double[]{width/2-w/2+s.offsetX-8,height/2+s.offsetY-8,w+8,g.fontHeight()+28};}
 public void overlay(float pt){if(editing&&g.inWorld()){double hp=RenderAccess.d(g.invoke(g.player(),"getHealth"));render(preview(),Math.min(hp/g.maxHealth(g.player()),1),255);}}
 public void render(String text,double ratio,int opacity){theme=Theme.values()[(int)n("Theme")];if((int)n("Mode")==2)legacy(opacity,text,ratio);else modern(opacity,text,ratio);}
 public void close(){shaders.close();if(glow!=null){glow.close();glow=null;}if(blur!=null){blur.close();blur=null;}}
    public void legacy(int opacity, String s, double d) {
        int w = g.textWidth(s) + 8;
        int x = g.width() / 2 - w / 2 + (int)this.s.offsetX;
        int y = g.height() / 2 + (int)this.s.offsetY;
        int x1 = x - 8;
        int y1 = y - 8;
        int x2 = x + w;
        int y2 = y + g.fontHeight() - 1 + 8;
        int a = opacity;
        if (a <= 0) {
            return;
        }
        int bgA = Math.min(a, 100);
        int barA = Math.min(a, 210);
        int[] rgb = theme.getRGBArr();
        int bx1 = x1 + 6;
        int bx2 = x2 - 6;
        int by = y2;
        if (target != -1.0) {
            long now = System.currentTimeMillis();
            long age = now - changedAt;
            long dur = 125L;
            if (d != target) {
                from = age > dur ? target : shown;
                target = d;
                changedAt = now;
            } else if (age <= dur) {
                double t = Math.min(1.0, (double) age / (double) dur);
                shown = from + (target - from) * t;
            } else {
                shown = d;
            }
        } else {
            target = d;
        }
        d = shown;
        int c1;
        int c2;
        if (on("Traditional health color")) {
            c1 = c2 = RenderShapes.withAlpha(Render3D.healthColor(d), barA);
        } else {
            c1 = RenderShapes.withAlpha(rgb[0], barA);
            c2 = RenderShapes.withAlpha(RenderShapes.blendInt(rgb[0], rgb[1], 1.0 - d), barA);
        }
        RenderShapes.roundedRectBorder(x1, y1, x2, y2 + 13, 10.0f, RenderShapes.withAlpha(Color.black.getRGB(), bgA), RenderShapes.withAlpha(rgb[0], a),
                RenderShapes.withAlpha(rgb[1], a));
        if (d < 1.0) {
            RenderShapes.roundedRect(bx1, by, bx2, by + 5, 4.0f, RenderShapes.withAlpha(Color.black.getRGB(), bgA));
        }
        RenderShapes.roundedRectGradient(bx1, by, (float) ((double) bx2 + (double) (bx1 - bx2) * (1.0 - d)), by + 5, 4.0f, c1, c1, c2, c2);
        GL11.glEnable(3042);
        RawState.tryBlendFuncSeparate(770, 771, 1, 0);
        g.text(s, (float) x, (float) y, RenderShapes.withAlpha(TEXT, RenderShapes.clampAlpha(a)), true);
        GL11.glDisable(3042);
        RenderShapes.resetColor();
    }

    public void modern(int opacity, String s, double d) {
        int w = g.textWidth(s) + 8;
        int x = g.width() / 2 - w / 2 + (int)this.s.offsetX;
        int y = g.height() / 2 + (int)this.s.offsetY;
        int x1 = x - 8;
        int y1 = y - 8;
        int x2 = x + w;
        int y2 = y + g.fontHeight() - 1 + 8;
        int a = opacity;
        if (a <= 0) {
            return;
        }
        int bgA = Math.min(a, 100);
        int barA = Math.min(a, 210);
        int[] rgb = theme.getRGBArr();
        int bx1 = x1 + 6;
        int bx2 = x2 - 6;
        boolean falling = target < from;
        if (target == -1.0) {
            target = d;
        } else {
            long now = System.currentTimeMillis();
            long age = now - changedAt;
            long dur = !falling ? 500L : 800L;
            if (d == target) {
                if (age > dur) {
                    shown = d;
                } else {
                    double t = Math.min(1.0, (double) age / (double) dur);
                    t = (double) (1.0f - (float) Math.pow(1.0 - t, 5.0));
                    shown = from + (target - from) * t;
                }
            } else {
                if (age <= dur) {
                    from = shown;
                } else {
                    from = target;
                }
                target = d;
                changedAt = now;
            }
        }
        d = shown;
        int c1;
        int c2;
        int t1;
        int t2;
        if (!on("Traditional health color")) {
            double front = !falling ? d : target;
            c1 = RenderShapes.withAlpha(rgb[0], barA);
            c2 = RenderShapes.withAlpha(RenderShapes.blendInt(rgb[0], rgb[1], 1.0 - front), barA);
            t1 = RenderShapes.withAlpha(rgb[0], bgA);
            t2 = RenderShapes.withAlpha(RenderShapes.blendInt(rgb[0], rgb[1], 1.0 - d), bgA);
        } else {
            int hc = Render3D.healthColor(d);
            c1 = c2 = RenderShapes.withAlpha(hc, barA);
            t1 = t2 = RenderShapes.withAlpha(hc, bgA);
        }
        float f = (float) barA / 210.0f;
        glow().startCapture();
        shaders.roundRect((float) x1, (float) y1, (float) x2, (float) (y2 + 13), 5.0f, RenderShapes.withAlpha(Color.black.getRGB(), barA));
        glow().render(2, 1.3f * f);
        blur().startStencil();
        shaders.roundRect((float) x1, (float) y1, (float) x2, (float) (y2 + 13), 5.0f, -1);
        blur().render(2, 2.0f * f);
        if (d < 1.0) {
            RenderShapes.roundedRect((float) bx1, (float) y2, (float) bx2, (float) (y2 + 5), 4.0f, RenderShapes.withAlpha(Color.black.getRGB(), bgA));
        }
        if (!falling) {
            RenderShapes.roundedRectGradient((float) bx1, (float) y2, (float) ((double) bx2 + (double) (bx1 - bx2) * (1.0 - d)), (float) (y2 + 5), 4.0f, c1, c1, c2, c2);
        } else {
            if (target < 1.0) {
                RenderShapes.roundedRectGradient((float) bx1, (float) y2, (float) ((double) bx2 + (double) (bx1 - bx2) * (1.0 - d)), (float) (y2 + 5), 4.0f, t1, t1, t2, t2);
            }
            RenderShapes.roundedRectGradient((float) bx1, (float) y2, (float) ((double) bx2 + (double) (bx1 - bx2) * (1.0 - target)), (float) (y2 + 5), 4.0f, c1, c1, c2, c2);
        }
        GL11.glEnable(3042);
        RawState.tryBlendFuncSeparate(770, 771, 1, 0);
        g.text(s, (float) x, (float) y, RenderShapes.withAlpha(TEXT, RenderShapes.clampAlpha(a)), true);
        GL11.glDisable(3042);
    }

}
