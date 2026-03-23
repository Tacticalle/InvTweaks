package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import tacticalle.invtweaks.mixin.HandledScreenAccessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Static utility class that manages and renders overlay messages next to the open GUI.
 */
public class InvTweaksOverlay {

    private static final List<OverlayMessage> activeMessages = new ArrayList<>();

    private static final long DEFAULT_DISPLAY_MS = 3000;
    private static final long DEFAULT_FADE_MS = 500;
    private static final int PADDING_FROM_GUI = 8;
    private static final int MARGIN_FROM_EDGE = 16;
    private static final int MIN_AVAILABLE_WIDTH = 60;
    private static final int BG_PADDING_H = 4;
    private static final int BG_PADDING_V = 3;
    private static final int MESSAGE_GAP = 2;

    public record OverlayMessage(String text, int color, long displayTime, long fadeStartTime, long fadeDuration) {
        public long endTime() {
            return fadeStartTime + fadeDuration;
        }
    }

    /**
     * Queue a message with default 3-second display + 0.5s fade.
     */
    public static void show(String message, int color) {
        show(message, color, DEFAULT_DISPLAY_MS);
    }

    /**
     * Queue a message with custom duration.
     */
    public static void show(String message, int color, long displayMs) {
        long now = System.currentTimeMillis();
        long fadeStart = now + displayMs;
        activeMessages.add(new OverlayMessage(message, color, now, fadeStart, DEFAULT_FADE_MS));
    }

    /**
     * Called from HandledScreenMixin (before tooltip) to draw all active messages.
     */
    public static void render(DrawContext context, HandledScreen<?> screen, int screenWidth, int screenHeight) {
        tick();
        if (activeMessages.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer textRenderer = mc.textRenderer;
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;

        int guiRight = accessor.getX() + accessor.getBackgroundWidth();
        int guiTop = accessor.getY();
        int guiHeight = accessor.getBackgroundHeight();
        int availableWidth = screenWidth - guiRight - MARGIN_FROM_EDGE;

        int renderX;
        boolean renderAbove = false;

        if (availableWidth < MIN_AVAILABLE_WIDTH) {
            // Fall back to rendering above the GUI
            renderAbove = true;
            renderX = accessor.getX();
            availableWidth = accessor.getBackgroundWidth();
        } else {
            renderX = guiRight + PADDING_FROM_GUI;
        }

        int maxTextWidth = availableWidth - (BG_PADDING_H * 2);
        if (maxTextWidth < 20) maxTextWidth = 20;

        long now = System.currentTimeMillis();

        // Pre-compute total message block height for vertical centering
        int totalHeight = 0;
        for (OverlayMessage msg : activeMessages) {
            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(msg.text()), maxTextWidth);
            int msgHeight = lines.size() * textRenderer.fontHeight + (BG_PADDING_V * 2);
            totalHeight += msgHeight + MESSAGE_GAP;
        }
        totalHeight -= MESSAGE_GAP; // no gap after last

        int renderY;
        if (renderAbove) {
            // Render upward from the top of the GUI
            renderY = guiTop - totalHeight - 4;
            if (renderY < 2) renderY = 2;
        } else {
            // Center the message block vertically with the container
            int guiMiddleY = guiTop + (guiHeight / 2);
            renderY = guiMiddleY - (totalHeight / 2);
            if (renderY < 2) renderY = 2;
        }

        int currentY = renderY;
        for (OverlayMessage msg : activeMessages) {
            float alpha = 1.0f;
            if (now >= msg.fadeStartTime()) {
                float fadeProgress = (float)(now - msg.fadeStartTime()) / msg.fadeDuration();
                alpha = 1.0f - Math.min(1.0f, fadeProgress);
            }
            if (alpha <= 0) continue;

            int alphaInt = (int)(alpha * 255) & 0xFF;

            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(msg.text()), maxTextWidth);
            int textHeight = lines.size() * textRenderer.fontHeight;

            // Draw background
            int bgX1 = renderX - BG_PADDING_H;
            int bgY1 = currentY - BG_PADDING_V;
            int bgX2 = renderX + maxTextWidth + BG_PADDING_H;
            int bgY2 = currentY + textHeight + BG_PADDING_V;
            int bgColor = (alphaInt * 3 / 4) << 24; // semi-transparent black
            context.fill(bgX1, bgY1, bgX2, bgY2, bgColor);

            // Draw text lines — centered horizontally within the box
            int textColor = (msg.color() & 0x00FFFFFF) | (alphaInt << 24);
            for (int i = 0; i < lines.size(); i++) {
                int lineWidth = textRenderer.getWidth(lines.get(i));
                int lineX = renderX + (maxTextWidth - lineWidth) / 2;
                context.drawTextWithShadow(textRenderer, lines.get(i), lineX, currentY + i * textRenderer.fontHeight, textColor);
            }

            currentY = bgY2 + MESSAGE_GAP;
        }
    }

    /**
     * Remove expired messages.
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        activeMessages.removeIf(msg -> now >= msg.endTime());
    }
}
