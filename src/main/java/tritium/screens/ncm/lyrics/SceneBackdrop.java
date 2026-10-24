package tritium.screens.ncm.lyrics;

import tritium.rendering.TextureManager;
import static tritium.screens.ncm.lyrics.LyricMath.*;

/** The shared dark canvas and cover-tinted light field used by Folia's typographic modes. */
final class SceneBackdrop {
    private SceneBackdrop() { }
    static void ambient(LyricCanvas c, LyricFrame f, int base) {
        c.rect(0, 0, f.width(), f.height(), base);
        if (f.song() != null && TextureManager.getInstance().getTexture(f.song().getBlurredCoverLocation()) != null)
            c.image(f.song().getBlurredCoverLocation(), 0, 0, f.width(), f.height(), .22);
        c.glow(f.width() * (.26 + .06 * Math.sin(f.time() * .12)), f.height() * .36,
                f.width() * .66, f.height() * .8, alpha(f.accent(), .15));
        c.glow(f.width() * .85, f.height() * .8, f.width() * .45, f.height() * .5, 0x143C6E9C);
    }
}
