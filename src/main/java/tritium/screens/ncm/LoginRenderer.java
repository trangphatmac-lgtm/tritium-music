package tritium.screens.ncm;

import lombok.Getter;
import org.lwjgl.input.Keyboard;
import tritium.interfaces.SharedConstants;
import tritium.ncm.music.QRCodeGenerator;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.ncm.OptionsUtil;
import tritium.ncm.music.CloudMusic;
import tritium.rendering.TextureManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.Image;
import tritium.rendering.Rect;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.texture.ITextureObject;
import tritium.utils.Location;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;

import static tritium.screens.ncm.NCMScreen.ColorType.PRIMARY_TEXT;
import static tritium.screens.ncm.NCMScreen.ColorType.SECONDARY_TEXT;

/**
 * @author IzumiiKonata
 * @since 6/16/2023 4:05 PM
 */
public class LoginRenderer implements SharedRenderingConstants, SharedConstants {

    @Getter
    public boolean closing = false;
    Thread loginThread;
    boolean success = false;
    float screeMaskAlpha = 0;
    double scale = 1;
    private LoginMode mode = LoginMode.QR;
    private final StringBuilder cookieInput = new StringBuilder();
    private String statusText = "";
    private double lastPosX;
    private double lastPosY;
    private double lastWidth;
    private double lastHeight;
    private volatile boolean acceptingQrLogin = true;

    public boolean avatarLoaded = false;
    public Location tempAvatar = Location.of("tritium/textures/TempAvatar.png");
    public String tempUsername = "";

    public LoginRenderer() {
        loginThread = new Thread(() -> {
            try {
                String cookie = CloudMusic.qrCodeLogin();
                if (!acceptingQrLogin || cookie == null || cookie.isBlank()) {
                    return;
                }
                OptionsUtil.setCookie(cookie);
                success = true;
                this.closing = true;
            } catch (RuntimeException e) {
                if (acceptingQrLogin) {
                    statusText = "扫码登录失败";
                    e.printStackTrace();
                }
            }
        });

        loginThread.setName("Tritium QR Login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    public void render(double mouseX, double mouseY, double posX, double posY, double width, double height, float alpha) {
        this.lastPosX = posX;
        this.lastPosY = posY;
        this.lastWidth = width;
        this.lastHeight = height;
        screeMaskAlpha = Interpolations.interpolate(screeMaskAlpha * 255, this.isClosing() ? 0 : 120, 0.3f) * RenderSystem.DIVIDE_BY_255;
//        scale = Interpolations.interpolate(scale, this.isClosing() ? 0 : 0.99999, 0.2);

//        Rect.draw(posX, posY, width, height, RenderSystem.hexColor(0, 0, 0, (int) (screeMaskAlpha * 255)));

        api.getGLStateManager().pushMatrix();
        RenderSystem.translateAndScale(posX + width / 2.0, posY + height / 2.0, scale);

        double pWidth = width / 2.0;
        double pHeight = height / 1.2;

        double x = posX + width / 2.0 - pWidth / 2.0;
        double y = posY + height / 2.0 - pHeight / 2.0;

//        BLOOM.add(() -> {
//            roundedRect(x, y, pWidth, pHeight, 5, new Color(43, 43, 43));
//        });

//        RenderSystem.doScissor((inta) x, (int) y, (int) pWidth, (int) pHeight);

        Rect.draw(x, y, pWidth, pHeight, hexColor(34, 34, 34, (int) (Math.max(alpha, 0.85f) * 255)));
        Rect.draw(x, y, 4, pHeight, hexColor(195, 2, 24, (int) (Math.max(alpha, 0.85f) * 255)));

        double qWidth = 96, qHeight = 96;

        renderModeTabs(posX, posY, width, alpha);

        String title = mode == LoginMode.QR ? "扫码登录" : "Cookie 登录";
        String[] strings = FontManager.pf20.fitWidth(title, width - 24);

        double startY = posY + height / 6.0;

        int textColor = reAlpha(NCMScreen.getColor(PRIMARY_TEXT), alpha);
        int secondaryTextColor = reAlpha(NCMScreen.getColor(SECONDARY_TEXT), alpha);

        for (String string : strings) {
            FontManager.pf20.drawCenteredString(string, posX + width / 2.0, startY, textColor);
            startY += FontManager.pf20.getHeight();
        }

        if (mode == LoginMode.QR) {
            renderQrLogin(posX, posY, width, height, alpha, qWidth, qHeight, textColor);
        } else {
            renderCookieLogin(posX, posY, width, alpha, textColor, secondaryTextColor);
        }

        api.getGLStateManager().popMatrix();
    }

    private void renderQrLogin(double posX, double posY, double width, double height, float alpha, double qWidth, double qHeight, int textColor) {
        TextureManager textureManager = TextureManager.getInstance();
        ITextureObject qrCode = textureManager.getTexture(QRCodeGenerator.qrCode);

        if (qrCode != null) {
            api.getGLStateManager().color(1, 1, 1, alpha);
            Image.draw(qrCode.getGlTextureId(), posX + width / 2.0 - qWidth / 2.0, posY + height / 6.0 * 4.0 - qHeight / 2.0, qWidth, qHeight, Image.Type.NoColor);
        } else {
            Rect.draw(posX + width / 2.0 - qWidth / 2.0, posY + height / 6.0 * 4.0 - qHeight / 2.0, qWidth, qHeight, hexColor(128, 128, 128, (int) (alpha * 255)));
        }

        if (avatarLoaded) {
            FontManager.pf25bold.drawCenteredString(tempUsername, posX + width / 2.0, posY + height / 4.0, textColor);

            ITextureObject tempAvatarTexture = textureManager.getTexture(tempAvatar);
            if (tempAvatarTexture != null) {
                api.getGLStateManager().bindTexture(tempAvatarTexture.getGlTextureId());

                double size = 48;

                RenderSystem.linearFilter();

                api.getGLStateManager().color(1, 1, 1, alpha);
                Image.draw(posX + width / 2.0 - size / 2.0, posY + height / 3.2, size, size, Image.Type.NoColor);
            }

        }
    }

    private void renderCookieLogin(double posX, double posY, double width, float alpha, int textColor, int secondaryTextColor) {
        double inputWidth = Math.min(width * 0.55, 420);
        double inputHeight = 30;
        double inputX = posX + width / 2.0 - inputWidth / 2.0;
        double inputY = posY + heightAnchor(0.43);

        Rect.draw(inputX, inputY, inputWidth, inputHeight, reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), alpha));
        roundedRect(inputX, inputY, inputWidth, inputHeight, 4, reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND), alpha));

        String preview = cookieInput.isEmpty() ? "MUSIC_U=...; __csrf=..." : "Cookie length: " + cookieInput.length();
        FontManager.pf14.drawString(preview, inputX + 10, inputY + 9, cookieInput.isEmpty() ? secondaryTextColor : textColor);

        drawButton(buttonX(-1), buttonY(), 96, 28, "粘贴", alpha, textColor);
        drawButton(buttonX(1), buttonY(), 96, 28, "登录", alpha, textColor);

        if (!statusText.isBlank()) {
            FontManager.pf14.drawCenteredString(statusText, posX + width / 2.0, buttonY() + 38, secondaryTextColor);
        }
    }

    private void renderModeTabs(double posX, double posY, double width, float alpha) {
        double tabY = posY + 28;
        double qrX = posX + width / 2.0 - 88;
        double cookieX = posX + width / 2.0 + 8;
        drawModeTab(qrX, tabY, 80, 26, "扫码", mode == LoginMode.QR, alpha);
        drawModeTab(cookieX, tabY, 80, 26, "Cookie", mode == LoginMode.COOKIE, alpha);
    }

    private void drawModeTab(double x, double y, double width, double height, String text, boolean selected, float alpha) {
        int bg = selected ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER) : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND);
        roundedRect(x, y, width, height, 4, reAlpha(bg, alpha));
        FontManager.pf14bold.drawCenteredString(text, x + width / 2.0, y + 8, reAlpha(NCMScreen.getColor(PRIMARY_TEXT), alpha));
    }

    private void drawButton(double x, double y, double width, double height, String text, float alpha, int textColor) {
        roundedRect(x, y, width, height, 4, reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER), alpha));
        FontManager.pf14bold.drawCenteredString(text, x + width / 2.0, y + 8, textColor);
    }

    public boolean keyTyped(char typedChar, int keyCode) {
        if (mode != LoginMode.COOKIE) {
            if (keyCode == Keyboard.KEY_C) {
                mode = LoginMode.COOKIE;
            }
            return true;
        }

        if (keyCode == Keyboard.KEY_TAB) {
            mode = LoginMode.QR;
            return true;
        }

        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            submitCookie();
            return true;
        }

        if (isPasteShortcut(keyCode)) {
            pasteCookie();
            return true;
        }

        if (keyCode == Keyboard.KEY_BACK) {
            if (!cookieInput.isEmpty()) {
                cookieInput.deleteCharAt(cookieInput.length() - 1);
            }
            return true;
        }

        if (keyCode == Keyboard.KEY_DELETE) {
            cookieInput.setLength(0);
            return true;
        }

        if (typedChar >= 32 && typedChar != 127) {
            cookieInput.append(typedChar);
        }
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return true;
        }

        double tabY = lastPosY + 28;
        if (inside(mouseX, mouseY, lastPosX + lastWidth / 2.0 - 88, tabY, 80, 26)) {
            mode = LoginMode.QR;
            return true;
        }
        if (inside(mouseX, mouseY, lastPosX + lastWidth / 2.0 + 8, tabY, 80, 26)) {
            mode = LoginMode.COOKIE;
            return true;
        }

        if (mode == LoginMode.COOKIE) {
            if (inside(mouseX, mouseY, buttonX(-1), buttonY(), 96, 28)) {
                pasteCookie();
                return true;
            }
            if (inside(mouseX, mouseY, buttonX(1), buttonY(), 96, 28)) {
                submitCookie();
                return true;
            }
        }
        return true;
    }

    private void pasteCookie() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String text && !text.isBlank()) {
                cookieInput.setLength(0);
                cookieInput.append(text.trim());
                statusText = "已读取剪贴板";
            } else {
                statusText = "剪贴板为空";
            }
        } catch (Exception e) {
            statusText = "无法读取剪贴板";
        }
    }

    private void submitCookie() {
        String cookie = cookieInput.toString().trim();
        if (cookie.isEmpty()) {
            statusText = "Cookie 为空";
            return;
        }

        acceptingQrLogin = false;
        if (loginThread != null) {
            loginThread.interrupt();
        }
        OptionsUtil.setCookie(cookie);
        statusText = "正在登录";
        success = true;
        closing = true;
    }

    private boolean isPasteShortcut(int keyCode) {
        return keyCode == Keyboard.KEY_V &&
                (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) ||
                        Keyboard.isKeyDown(Keyboard.KEY_RCONTROL) ||
                        Keyboard.isKeyDown(Keyboard.KEY_LMETA) ||
                        Keyboard.isKeyDown(Keyboard.KEY_RMETA));
    }

    private boolean inside(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private double heightAnchor(double ratio) {
        return lastPosY + lastHeight * ratio;
    }

    private double buttonY() {
        return lastPosY + lastHeight * 0.53;
    }

    private double buttonX(int side) {
        return lastPosX + lastWidth / 2.0 + side * 56 - 48;
    }

    public boolean canClose() {
        return this.isClosing() && this.screeMaskAlpha <= 0.05 && success;
    }

    private enum LoginMode {
        QR,
        COOKIE
    }

}
