package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

/**
 * Static utility class that manages a modal overlay for choosing which half
 * of a 54-slot clipboard to paste into a 27-slot container.
 * Shows two side-by-side 9x3 preview grids labeled "Top Half" and "Bottom Half".
 */
public class HalfSelectorOverlay {

    private static boolean active = false;
    private static Map<Integer, LayoutClipboard.SlotData> clipboardData = null;
    private static boolean isPlayerOnly = false;
    private static Consumer<String> onSelection = null;

    // Preview grid constants
    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 3;
    private static final int GRID_GAP = 40;

    // Colors
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF999999;
    private static final int DARK_GRAY = 0xFF666666;
    private static final int SLOT_BG = 0xFF373737;
    private static final int SLOT_BORDER = 0xFF8B8B8B;
    private static final int SLOT_EMPTY_BG = 0xFF4A4A4A;
    private static final int SLOT_EMPTY_BORDER = 0xFF2A2A2A;
    private static final int HOVER_HIGHLIGHT = 0x40FFFFFF;
    private static final int DIM_OVERLAY = 0xB0000000;

    // Cached layout positions (computed during render)
    private static int leftGridX, leftGridY, leftGridW, leftGridH;
    private static int rightGridX, rightGridY, rightGridW, rightGridH;

    // ========== PUBLIC API ==========

    public static void show(Map<Integer, LayoutClipboard.SlotData> data, boolean playerOnly,
                            Consumer<String> callback) {
        active = true;
        clipboardData = data;
        isPlayerOnly = playerOnly;
        onSelection = callback;
        InvTweaksConfig.debugLog("HALF-SELECT", "overlay shown | clipboardSize=%d", data.size());
    }

    public static void hide() {
        active = false;
        clipboardData = null;
        onSelection = null;
        InvTweaksConfig.debugLog("HALF-SELECT", "overlay hidden");
    }

    public static boolean isActive() {
        return active;
    }

    // ========== RENDER ==========

    public static void render(DrawContext context, HandledScreen<?> screen, int screenWidth, int screenHeight) {
        if (!active || clipboardData == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer textRenderer = mc.textRenderer;

        // Semi-transparent dark background
        context.fill(0, 0, screenWidth, screenHeight, DIM_OVERLAY);

        // Calculate layout
        int gridW = GRID_COLS * SLOT_SIZE;
        int gridH = GRID_ROWS * SLOT_SIZE;
        int totalWidth = gridW * 2 + GRID_GAP;
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;

        // Title
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Choose layout half to paste"),
                centerX, centerY - gridH / 2 - 30, WHITE);

        // Left grid (Top Half: keys 0-26)
        leftGridX = centerX - totalWidth / 2;
        leftGridY = centerY - gridH / 2;
        leftGridW = gridW;
        leftGridH = gridH;

        // Right grid (Bottom Half: keys 27-53)
        rightGridX = centerX + totalWidth / 2 - gridW;
        rightGridY = centerY - gridH / 2;
        rightGridW = gridW;
        rightGridH = gridH;

        // Get mouse position
        double mouseX = mc.mouse.getX() * screenWidth / mc.getWindow().getWidth();
        double mouseY = mc.mouse.getY() * screenHeight / mc.getWindow().getHeight();

        boolean hoverLeft = mouseX >= leftGridX - 4 && mouseX <= leftGridX + leftGridW + 4
                && mouseY >= leftGridY - 4 && mouseY <= leftGridY + leftGridH + 4;
        boolean hoverRight = mouseX >= rightGridX - 4 && mouseX <= rightGridX + rightGridW + 4
                && mouseY >= rightGridY - 4 && mouseY <= rightGridY + rightGridH + 4;

        // Draw left grid with label
        renderGrid(context, textRenderer, leftGridX, leftGridY, gridW, gridH,
                clipboardData, 0, 26, "Top Half", hoverLeft);

        // Draw right grid with label
        renderGrid(context, textRenderer, rightGridX, rightGridY, gridW, gridH,
                clipboardData, 27, 53, "Bottom Half", hoverRight);

        // Keyboard hint labels below grids
        int hintY = leftGridY + gridH + 8;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("[A] Top Half"),
                leftGridX + gridW / 2, hintY, GRAY);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("[D] Bottom Half"),
                rightGridX + gridW / 2, hintY, GRAY);

        // ESC hint
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("[ESC] Cancel"),
                centerX, hintY + 14, DARK_GRAY);
    }

    private static void renderGrid(DrawContext context, TextRenderer textRenderer,
                                    int gridX, int gridY, int gridW, int gridH,
                                    Map<Integer, LayoutClipboard.SlotData> data,
                                    int keyStart, int keyEnd, String label, boolean hovered) {
        // Background with hover highlight
        int bgColor = hovered ? 0xFF3A3A5A : 0xFF2B2B2B;
        int borderColor = hovered ? 0xFF7777FF : 0xFF555555;

        context.fill(gridX - 5, gridY - 5, gridX + gridW + 5, gridY + gridH + 5, borderColor);
        context.fill(gridX - 4, gridY - 4, gridX + gridW + 4, gridY + gridH + 4, bgColor);

        // Container-style background
        context.fill(gridX - 3, gridY - 3, gridX + gridW + 3, gridY + gridH + 3, 0xFFC6C6C6);

        // Label above grid
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                gridX + gridW / 2, gridY - 16, WHITE);

        // Draw slots
        List<Integer> sortedKeys = new ArrayList<>();
        for (int k = keyStart; k <= keyEnd; k++) {
            if (data.containsKey(k)) {
                sortedKeys.add(k);
            }
        }
        Collections.sort(sortedKeys);

        for (int i = 0; i < sortedKeys.size() && i < GRID_COLS * GRID_ROWS; i++) {
            int key = sortedKeys.get(i);
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int sx = gridX + col * SLOT_SIZE;
            int sy = gridY + row * SLOT_SIZE;

            LayoutClipboard.SlotData sd = data.get(key);
            renderPreviewSlot(context, textRenderer, sd, sx, sy, SLOT_SIZE);
        }

        // Fill remaining empty slots if fewer than 27
        for (int i = sortedKeys.size(); i < GRID_COLS * GRID_ROWS; i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int sx = gridX + col * SLOT_SIZE;
            int sy = gridY + row * SLOT_SIZE;
            renderPreviewSlot(context, textRenderer, null, sx, sy, SLOT_SIZE);
        }

        // Hover highlight overlay
        if (hovered) {
            context.fill(gridX - 3, gridY - 3, gridX + gridW + 3, gridY + gridH + 3, HOVER_HIGHLIGHT);
        }
    }

    private static void renderPreviewSlot(DrawContext context, TextRenderer textRenderer,
                                           LayoutClipboard.SlotData sd, int sx, int sy, int slotSize) {
        boolean empty = (sd == null || sd.item() == null);

        int borderColorSlot = empty ? SLOT_EMPTY_BORDER : SLOT_BORDER;
        int interiorColor = empty ? SLOT_EMPTY_BG : SLOT_BG;
        context.fill(sx, sy, sx + slotSize, sy + slotSize, borderColorSlot);
        context.fill(sx + 1, sy + 1, sx + slotSize - 1, sy + slotSize - 1, interiorColor);

        if (!empty) {
            ItemStack displayStack = LayoutClipboard.reconstructStack(sd.item(), sd.count(), sd.components());
            int itemX = sx + 1 + Math.max(0, (slotSize - 2 - 16) / 2);
            int itemY = sy + 1 + Math.max(0, (slotSize - 2 - 16) / 2);
            context.drawItem(displayStack, itemX, itemY);
            context.drawStackOverlay(textRenderer, displayStack, itemX, itemY);
        }
    }

    // ========== INPUT HANDLING ==========

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active) return false;

        // Check if left grid was clicked
        if (mouseX >= leftGridX - 5 && mouseX <= leftGridX + leftGridW + 5
                && mouseY >= leftGridY - 5 && mouseY <= leftGridY + leftGridH + 5) {
            selectHalf("top");
            return true;
        }

        // Check if right grid was clicked
        if (mouseX >= rightGridX - 5 && mouseX <= rightGridX + rightGridW + 5
                && mouseY >= rightGridY - 5 && mouseY <= rightGridY + rightGridH + 5) {
            selectHalf("bottom");
            return true;
        }

        // Clicked outside both grids — cancel
        hide();
        return true;
    }

    public static boolean keyPressed(int keyCode) {
        if (!active) return false;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            hide();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A) {
            selectHalf("top");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_D) {
            selectHalf("bottom");
            return true;
        }
        // E key: return false so the caller can let it pass through to close inventory
        if (keyCode == GLFW.GLFW_KEY_E) {
            return false;
        }
        // Block all other keys
        return true;
    }

    private static void selectHalf(String half) {
        InvTweaksConfig.debugLog("HALF-SELECT", "user selected %s half", half);
        Consumer<String> callback = onSelection;
        hide();
        if (callback != null) {
            callback.accept(half);
        }
    }
}
