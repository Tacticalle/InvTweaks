package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;

import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full-screen clipboard history browser with item preview grid.
 * Opened via Shift+Tab while a container or player inventory is open.
 */
public class ClipboardHistoryScreen extends Screen {

    private static final int ROW_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SLOT_SIZE = 18;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFF999999;
    private static final int DARK_GRAY = 0xFF666666;
    private static final int AQUA = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA00;

    private static final int SLOT_BG = 0xFF373737;
    private static final int SLOT_BORDER = 0xFF8B8B8B;
    private static final int SLOT_EMPTY_BG = 0xFF4A4A4A;
    private static final int SLOT_EMPTY_BORDER = 0xFF2A2A2A;
    private static final int PANEL_BG = 0xFF2B2B2B;
    private static final int PANEL_BORDER = 0xFF555555;

    /** Tracks a rendered preview slot's screen bounds and item for hover tooltip. */
    private record PreviewSlotInfo(int x, int y, int size, ItemStack stack) {}

    private final Screen parentScreen;
    private final ScreenHandler handler;
    private final boolean isPlayerOnly;

    private HistoryEntryList entryList;
    private int hoveredEntryIndex = -1;
    private int highlightedIndex = -1; // which entry is highlighted for deletion

    // Layout
    private int leftPanelX, leftPanelWidth;
    private int rightPanelX, rightPanelWidth;
    private int panelTop, panelBottom;

    public ClipboardHistoryScreen(HandledScreen<?> parent, ScreenHandler handler, boolean isPlayerOnly) {
        super(Text.literal("Clipboard History"));
        this.parentScreen = parent;
        this.handler = handler;
        this.isPlayerOnly = isPlayerOnly;
    }

    @Override
    protected void init() {
        super.init();

        // Prune expired entries on open
        LayoutClipboard.pruneExpired();

        // Layout: left 45%, right 45%, with gaps
        int totalWidth = Math.min(this.width - 40, 700);
        int startX = (this.width - totalWidth) / 2;

        leftPanelX = startX;
        leftPanelWidth = (int)(totalWidth * 0.45);
        rightPanelX = startX + leftPanelWidth + 10;
        rightPanelWidth = totalWidth - leftPanelWidth - 10;

        panelTop = 30;
        panelBottom = this.height - 36;

        // Entry list (left panel)
        entryList = new HistoryEntryList(this.client, leftPanelWidth, panelBottom - panelTop, panelTop, ROW_HEIGHT);
        entryList.setX(leftPanelX);
        populateEntryList();
        addDrawableChild(entryList);

        // Bottom buttons
        int btnY = this.height - 30;
        int btnW = 70;
        int btnGap = 6;
        int totalBtnWidth = btnW * 3 + btnGap * 2;
        int btnStartX = (this.width - totalBtnWidth) / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), button -> {
            if (highlightedIndex >= 0 && highlightedIndex < LayoutClipboard.getHistory().size()) {
                LayoutClipboard.removeHistoryEntry(highlightedIndex);
                ClipboardStorage.save();
                highlightedIndex = -1;
                rebuildEntryList();
            }
        }).dimensions(btnStartX, btnY, btnW, BUTTON_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Delete All"), button -> {
            LayoutClipboard.clearHistory();
            hoveredEntryIndex = -1;
            rebuildEntryList();
        }).dimensions(btnStartX + btnW + btnGap, btnY, btnW, BUTTON_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> {
            close();
        }).dimensions(btnStartX + 2 * (btnW + btnGap), btnY, btnW, BUTTON_HEIGHT).build());
    }

    private void populateEntryList() {
        List<LayoutClipboard.HistoryEntry> history = LayoutClipboard.getHistory();
        int activeContainer = LayoutClipboard.getActiveContainerIndex();
        int activePlayer = LayoutClipboard.getActivePlayerIndex();

        for (int i = 0; i < history.size(); i++) {
            LayoutClipboard.HistoryEntry entry = history.get(i);
            boolean isActive = (entry.snapshot.isPlayerInventory && i == activePlayer)
                            || (!entry.snapshot.isPlayerInventory && i == activeContainer);
            entryList.addConfigEntry(new HistoryItemEntry(i, entry, isActive));
        }

        if (history.isEmpty()) {
            entryList.addConfigEntry(new EmptyEntry());
        }
    }

    private void rebuildEntryList() {
        remove(entryList);
        entryList = new HistoryEntryList(this.client, leftPanelWidth, panelBottom - panelTop, panelTop, ROW_HEIGHT);
        entryList.setX(leftPanelX);
        populateEntryList();
        addDrawableChild(entryList);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, WHITE);

        // Right panel background
        context.fill(rightPanelX - 2, panelTop - 2, rightPanelX + rightPanelWidth + 2, panelBottom + 2, PANEL_BORDER);
        context.fill(rightPanelX, panelTop, rightPanelX + rightPanelWidth, panelBottom, PANEL_BG);

        // Render widgets (entry list, buttons)
        super.render(context, mouseX, mouseY, delta);

        // Render preview on right panel
        renderPreview(context, mouseX, mouseY);
    }

    private void renderPreview(DrawContext context, int mouseX, int mouseY) {
        List<LayoutClipboard.HistoryEntry> history = LayoutClipboard.getHistory();

        // Priority: hovered entry > highlighted entry > nothing
        int previewIndex = -1;
        if (hoveredEntryIndex >= 0 && hoveredEntryIndex < history.size()) {
            previewIndex = hoveredEntryIndex;
        } else if (highlightedIndex >= 0 && highlightedIndex < history.size()) {
            previewIndex = highlightedIndex;
        }

        if (previewIndex < 0) {
            // Show "Hover to preview" text
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Hover an entry to preview"),
                    rightPanelX + rightPanelWidth / 2, panelTop + (panelBottom - panelTop) / 2, GRAY);
            return;
        }

        LayoutClipboard.HistoryEntry entry = history.get(previewIndex);
        LayoutClipboard.LayoutSnapshot snapshot = entry.snapshot;

        // Track rendered slots for hover tooltips
        List<PreviewSlotInfo> previewSlots = new ArrayList<>();

        // Label
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(entry.label),
                rightPanelX + rightPanelWidth / 2, panelTop + 8, WHITE);

        // Time info
        String timeStr = formatRelativeTime(entry.timestamp);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(timeStr),
                rightPanelX + rightPanelWidth / 2, panelTop + 20, GRAY);

        // Grid starts below the header area (title at +8, time label at +20, font ~9px)
        int gridY = panelTop + 38;
        int gridBottomY;

        // Available space for the grid inside the panel (with padding)
        int panelPadding = 8;
        int availableWidth = rightPanelWidth - panelPadding * 2;
        int availableHeight = panelBottom - gridY - 20 - panelPadding; // 20px reserved for summary text below

        if (snapshot.isPlayerInventory) {
            // Player inventory layout:
            // Row 0-2: Main inventory (keys 9-35), 9 columns
            // Gap (4px)
            // Row 3: Hotbar (keys 0-8), 9 columns
            // Gap (4px)
            // Row 4: Armor (keys 36-39) as 4 slots + gap + Offhand (key 40) as 1 slot
            int cols = 9;
            int mainRows = 3;
            int gap = 4;
            boolean hasArmorOffhand = snapshot.slots.containsKey(36) || snapshot.slots.containsKey(40);
            int totalRows = mainRows + 1 + (hasArmorOffhand ? 1 : 0);
            int totalGaps = hasArmorOffhand ? 2 : 1;

            // Solve for slotSize: totalRows * slotSize + totalGaps * gap <= availableHeight
            int slotSize = Math.min(availableWidth / cols, (availableHeight - totalGaps * gap) / totalRows);
            slotSize = Math.max(slotSize, 8); // minimum 8px per slot
            slotSize = Math.min(slotSize, SLOT_SIZE); // don't exceed default size

            int gridWidth = cols * slotSize;
            int gridHeight = totalRows * slotSize + totalGaps * gap;
            int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

            // Draw container background
            context.fill(gridX - 4, gridY - 4, gridX + gridWidth + 4, gridY + gridHeight + 4, PANEL_BORDER);
            context.fill(gridX - 3, gridY - 3, gridX + gridWidth + 3, gridY + gridHeight + 3, 0xFFC6C6C6);

            // Main inventory: 3 rows (slot keys 9-35)
            for (int i = 9; i <= 35; i++) {
                int gridIdx = i - 9; // 0-26
                int col = gridIdx % cols;
                int row = gridIdx / cols;
                int sx = gridX + col * slotSize;
                int sy = gridY + row * slotSize;
                renderPreviewSlotScaled(context, snapshot.slots.get(i), sx, sy, slotSize, previewSlots);
            }

            // Hotbar: 1 row with gap (slot keys 0-8)
            int hotbarY = gridY + (mainRows * slotSize) + gap;
            for (int i = 0; i <= 8; i++) {
                int sx = gridX + i * slotSize;
                renderPreviewSlotScaled(context, snapshot.slots.get(i), sx, hotbarY, slotSize, previewSlots);
            }

            // Armor + Offhand row (if present)
            if (hasArmorOffhand) {
                int armorY = hotbarY + slotSize + gap;
                // Armor: keys 36-39 (4 slots, left-aligned)
                for (int i = 36; i <= 39; i++) {
                    int col = i - 36;
                    int sx = gridX + col * slotSize;
                    renderPreviewSlotScaled(context, snapshot.slots.get(i), sx, armorY, slotSize, previewSlots);
                }
                // Offhand: key 40 (1 slot, after a gap of 1 slot)
                if (snapshot.slots.containsKey(40)) {
                    int offhandX = gridX + 5 * slotSize; // skip 1 slot gap after armor
                    renderPreviewSlotScaled(context, snapshot.slots.get(40), offhandX, armorY, slotSize, previewSlots);
                }
            }

            gridBottomY = gridY + gridHeight;
        } else {
            // Container: determine grid dimensions from slot count
            int slotCount = snapshot.slotCount;
            int cols, rows;
            if (slotCount <= 5) {
                cols = slotCount;
                rows = 1;
            } else if (slotCount == 9) {
                cols = 3;
                rows = 3;
            } else if (slotCount <= 27) {
                cols = 9;
                rows = 3;
            } else if (slotCount <= 54) {
                cols = 9;
                rows = (int) Math.ceil(slotCount / 9.0);
            } else {
                cols = 9;
                rows = (int) Math.ceil(slotCount / 9.0);
            }

            // Calculate slot size that fits within panel bounds
            int slotSize = Math.min(availableWidth / cols, availableHeight / rows);
            slotSize = Math.max(slotSize, 8); // minimum 8px per slot
            slotSize = Math.min(slotSize, SLOT_SIZE); // don't exceed default size

            int gridWidth = cols * slotSize;
            int gridHeight = rows * slotSize;
            int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

            // Draw container background
            context.fill(gridX - 4, gridY - 4, gridX + gridWidth + 4, gridY + gridHeight + 4, PANEL_BORDER);
            context.fill(gridX - 3, gridY - 3, gridX + gridWidth + 3, gridY + gridHeight + 3, 0xFFC6C6C6);

            // Sort slot keys and render sequentially
            List<Integer> sortedSlotIds = new java.util.ArrayList<>(snapshot.slots.keySet());
            java.util.Collections.sort(sortedSlotIds);

            for (int i = 0; i < sortedSlotIds.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int sx = gridX + col * slotSize;
                int sy = gridY + row * slotSize;
                int slotId = sortedSlotIds.get(i);
                renderPreviewSlotScaled(context, snapshot.slots.get(slotId), sx, sy, slotSize, previewSlots);
            }

            gridBottomY = gridY + gridHeight;
        }

        // Show item count summary below grid (Fix 6: "X/Y slots taken" format)
        int nonEmptyCount = 0;
        int totalSlots = snapshot.slots.size();
        for (LayoutClipboard.SlotData sd : snapshot.slots.values()) {
            if (sd != null && sd.item() != null) {
                nonEmptyCount++;
            }
        }
        String summary = nonEmptyCount + "/" + totalSlots + " slots taken";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(summary),
                rightPanelX + rightPanelWidth / 2, gridBottomY + 10, DARK_GRAY);

        // Render hover tooltip (drawn last so it's on top of everything)
        for (PreviewSlotInfo slotInfo : previewSlots) {
            if (mouseX >= slotInfo.x && mouseX < slotInfo.x + slotInfo.size
                    && mouseY >= slotInfo.y && mouseY < slotInfo.y + slotInfo.size) {
                context.drawTooltip(this.textRenderer,
                        slotInfo.stack.getTooltip(Item.TooltipContext.DEFAULT, null, TooltipType.BASIC),
                        mouseX, mouseY);
                break;
            }
        }
    }

    /**
     * Render a single slot in the preview grid with a specified slot size.
     * The slot is drawn as a border + interior fill, with a scaled item icon if present.
     * If previewSlots is non-null, the slot info is recorded for hover tooltip detection.
     */
    private void renderPreviewSlotScaled(DrawContext context, LayoutClipboard.SlotData sd, int sx, int sy, int slotSize,
                                          List<PreviewSlotInfo> previewSlots) {
        boolean empty = (sd == null || sd.item() == null);

        // Draw slot background: border + interior (different colors for empty vs filled)
        int borderColor = empty ? SLOT_EMPTY_BORDER : SLOT_BORDER;
        int interiorColor = empty ? SLOT_EMPTY_BG : SLOT_BG;
        context.fill(sx, sy, sx + slotSize, sy + slotSize, borderColor);
        context.fill(sx + 1, sy + 1, sx + slotSize - 1, sy + slotSize - 1, interiorColor);

        // Draw item if present, scaled to match slot size
        if (!empty) {
            ItemStack displayStack = new ItemStack(sd.item(), sd.count());
            // Draw item at slot position — 16x16 native size, centered if slot is larger
            int itemX = sx + 1 + Math.max(0, (slotSize - 2 - 16) / 2);
            int itemY = sy + 1 + Math.max(0, (slotSize - 2 - 16) / 2);
            context.drawItem(displayStack, itemX, itemY);
            context.drawStackOverlay(this.textRenderer, displayStack, itemX, itemY);

            // Track for tooltip
            if (previewSlots != null) {
                previewSlots.add(new PreviewSlotInfo(sx, sy, slotSize, displayStack));
            }
        }
    }

    private String formatRelativeTime(long timestamp) {
        long elapsed = System.currentTimeMillis() - timestamp;
        if (elapsed < 0) return "just now";

        long seconds = elapsed / 1000;
        if (seconds < 60) return seconds + " sec ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hr ago";
        long days = hours / 24;
        return days + " day" + (days != 1 ? "s" : "") + " ago";
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();

        // ESC always closes
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }

        // E closes (inventory key)
        if (keyCode == GLFW.GLFW_KEY_E) {
            close();
            return true;
        }

        // Shift+Tab closes
        if (input.hasShift() && keyCode == GLFW.GLFW_KEY_TAB) {
            close();
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parentScreen);
    }

    // ========== Entry List ==========

    private class HistoryEntryList extends ElementListWidget<HistoryListEntry> {
        public HistoryEntryList(MinecraftClient client, int width, int height, int y, int itemHeight) {
            super(client, width, height, y, itemHeight);
        }

        public void addConfigEntry(HistoryListEntry entry) {
            super.addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return leftPanelWidth - 20;
        }

        @Override
        protected int getScrollbarX() {
            return leftPanelX + leftPanelWidth - 6;
        }
    }

    // ========== Entry base ==========

    private abstract static class HistoryListEntry extends ElementListWidget.Entry<HistoryListEntry> {
    }

    // ========== History item entry ==========

    private class HistoryItemEntry extends HistoryListEntry {
        private final int index;
        private final LayoutClipboard.HistoryEntry entry;
        private final boolean isActive;
        private final ButtonWidget selectBtn;

        HistoryItemEntry(int index, LayoutClipboard.HistoryEntry entry, boolean isActive) {
            this.index = index;
            this.entry = entry;
            this.isActive = isActive;

            this.selectBtn = ButtonWidget.builder(Text.literal("Select"), button -> {
                LayoutClipboard.setActiveIndex(index);
                // Rebuild the entry list to update the ▶ indicator (stay open)
                rebuildEntryList();
            }).dimensions(0, 0, 50, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();

            // Update hovered entry for preview
            if (hovered) {
                hoveredEntryIndex = index;
            }

            // Visual highlight: highlighted entry gets a light blue border
            if (highlightedIndex == index) {
                context.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, 0x6055AAFF); // light blue bg
            }

            // Hover highlight (dimmer than selected)
            if (hovered && highlightedIndex != index) {
                context.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, 0x40FFFFFF);
            }

            // Active indicator
            int textColor = entry.snapshot.isPlayerInventory ? AQUA : YELLOW;
            String prefix = isActive ? "\u25B6 " : "  ";
            String label = prefix + entry.label;

            // Truncate label to prevent overlap with Select button (Fix 4)
            int availableLabelWidth = w - selectBtn.getWidth() - 6;
            String displayLabel = textRenderer.trimToWidth(label, availableLabelWidth);
            context.drawTextWithShadow(textRenderer, Text.literal(displayLabel), x, y + 2, textColor);

            // Time
            String timeStr = formatRelativeTime(entry.timestamp);
            context.drawTextWithShadow(textRenderer, Text.literal("  " + timeStr), x, y + 14, DARK_GRAY);

            // Select button
            selectBtn.setX(x + w - selectBtn.getWidth());
            selectBtn.setY(y + 2);
            selectBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseClicked(Click click, boolean focused) {
            // Check if Select button was clicked first
            if (selectBtn.mouseClicked(click, focused)) {
                return true;
            }
            // Otherwise, clicking the row highlights it for deletion
            if (click.button() == 0) {
                highlightedIndex = index;
                return true;
            }
            return false;
        }

        @Override
        public List<? extends Element> children() { return List.of(selectBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(selectBtn); }
    }

    // ========== Empty state entry ==========

    private class EmptyEntry extends HistoryListEntry {
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No clipboard history"),
                    leftPanelX + leftPanelWidth / 2, getY() + 6, GRAY);
        }

        @Override
        public List<? extends Element> children() { return List.of(); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(); }
    }
}
