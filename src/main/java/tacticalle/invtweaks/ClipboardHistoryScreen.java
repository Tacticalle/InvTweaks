package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Identifier;

import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-screen clipboard history browser with item preview grid.
 * Opened via Shift+Tab while a container or player inventory is open.
 */
public class ClipboardHistoryScreen extends Screen {

    // ========== Scale constant — toggle between 1.0f and 0.6f to test ==========
    /**
     * Preview grid scale factor. Controls slot visual size in the preview.
     * 1.0f = full native vanilla slot size (18x18 per slot). Pixel-perfect.
     * 0.6f = compact slots (~11x11 per slot). Items still render at native 16x16
     *        and will overflow their slot bounds, which may or may not look good.
     */
    private static final float PREVIEW_SCALE = 1.0f;

    private static final int NATIVE_SLOT_SIZE = 18; // vanilla slot size in pixels (including 1px border)
    private static final int NATIVE_ITEM_SIZE = 16; // vanilla item icon size

    private static final int ROW_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFF999999;
    private static final int DARK_GRAY = 0xFF666666;
    private static final int AQUA = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA00;

    private static final int PANEL_BG = 0xFF2B2B2B;
    private static final int PANEL_BORDER = 0xFF555555;

    private static final int MULTI_SELECT_BG = 0x404488FF;
    private static final int GOLD = 0xFFFFD700;
    private static final int STAR_GRAY = 0xFFAAAAAA;

    private static final int TAB_ALL = 0;
    private static final int TAB_FAVORITES = 1;

    // ========== Vanilla sprite identifiers ==========
    // NOTE: These identifiers need verification against 1.21.11 vanilla assets.
    // If compilation or rendering fails, check the exact sprite paths in the game jar
    // at assets/minecraft/textures/gui/sprites/
    private static final Identifier SLOT_SPRITE = Identifier.ofVanilla("container/slot");
    private static final Identifier CRAFTER_DISABLED_SLOT_SPRITE = Identifier.ofVanilla("container/crafter/disabled_slot");
    private static final Identifier FURNACE_LIT_PROGRESS_SPRITE = Identifier.ofVanilla("container/furnace/lit_progress");

    /** Tracks a rendered preview slot's screen bounds and item for hover tooltip. */
    private record PreviewSlotInfo(int x, int y, int size, ItemStack stack) {}

    private final Screen parentScreen;
    private final ScreenHandler handler;
    private final boolean isPlayerOnly;

    private HistoryEntryList entryList;
    private int hoveredEntryIndex = -1;
    private int highlightedIndex = -1; // which entry is highlighted for deletion

    // Multi-select state
    private final Set<Integer> multiSelected = new LinkedHashSet<>();
    private int selectionAnchor = -1;
    private boolean confirmingBulkDelete = false;
    private ButtonWidget deleteButton;

    // Tab state
    private int activeTab = TAB_ALL;
    private ButtonWidget tabAllButton;
    private ButtonWidget tabFavoritesButton;

    // Mapping from display index (in the filtered/sorted list) to real history index
    private final List<Integer> displayToHistoryIndex = new ArrayList<>();

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

        // Layout: left 45%, right 45%, with gaps
        int totalWidth = Math.min(this.width - 40, 700);
        int startX = (this.width - totalWidth) / 2;

        leftPanelX = startX;
        leftPanelWidth = (int)(totalWidth * 0.45);
        rightPanelX = startX + leftPanelWidth + 10;
        rightPanelWidth = totalWidth - leftPanelWidth - 10;

        panelTop = 50;
        panelBottom = this.height - 36;

        // Tab buttons
        int tabW = 70;
        int tabGap = 4;
        int tabStartX = leftPanelX;
        int tabY = 28;

        tabAllButton = ButtonWidget.builder(Text.literal("All"), button -> {
            switchTab(TAB_ALL);
        }).dimensions(tabStartX, tabY, tabW, BUTTON_HEIGHT).build();
        addDrawableChild(tabAllButton);

        tabFavoritesButton = ButtonWidget.builder(Text.literal("Favorites"), button -> {
            switchTab(TAB_FAVORITES);
        }).dimensions(tabStartX + tabW + tabGap, tabY, tabW, BUTTON_HEIGHT).build();
        addDrawableChild(tabFavoritesButton);

        updateTabButtonStyles();

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

        deleteButton = ButtonWidget.builder(Text.literal("Delete"), button -> {
            onDeleteButtonClicked();
        }).dimensions(btnStartX, btnY, btnW, BUTTON_HEIGHT).build();
        addDrawableChild(deleteButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear All"), button -> {
            multiSelected.clear();
            selectionAnchor = -1;
            confirmingBulkDelete = false;
            LayoutClipboard.clearUnfavoritedHistory();
            hoveredEntryIndex = -1;
            highlightedIndex = -1;
            updateDeleteButtonText();
            rebuildEntryList();
        }).dimensions(btnStartX + btnW + btnGap, btnY, btnW, BUTTON_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> {
            close();
        }).dimensions(btnStartX + 2 * (btnW + btnGap), btnY, btnW, BUTTON_HEIGHT).build());
    }

    private void onDeleteButtonClicked() {
        if (multiSelected.size() >= 2) {
            if (!confirmingBulkDelete) {
                confirmingBulkDelete = true;
                deleteButton.setMessage(Text.literal("Confirm?"));
            } else {
                performBulkDelete();
            }
        } else {
            int realIndex = getDisplayRealIndex(highlightedIndex);
            if (realIndex >= 0 && realIndex < LayoutClipboard.getHistory().size()) {
                if (LayoutClipboard.getHistory().get(realIndex).favorited) {
                    return;
                }
                LayoutClipboard.removeHistoryEntry(realIndex);
                ClipboardStorage.save();
                highlightedIndex = -1;
                confirmingBulkDelete = false;
                updateDeleteButtonText();
                rebuildEntryList();
            }
        }
    }

    private void performBulkDelete() {
        List<Integer> realIndices = new ArrayList<>();
        for (int displayIdx : multiSelected) {
            int realIdx = getDisplayRealIndex(displayIdx);
            if (realIdx >= 0 && realIdx < LayoutClipboard.getHistory().size()) {
                if (!LayoutClipboard.getHistory().get(realIdx).favorited) {
                    realIndices.add(realIdx);
                }
            }
        }
        Collections.sort(realIndices, Collections.reverseOrder());
        for (int idx : realIndices) {
            LayoutClipboard.removeHistoryEntry(idx);
        }
        ClipboardStorage.save();
        multiSelected.clear();
        selectionAnchor = -1;
        highlightedIndex = -1;
        confirmingBulkDelete = false;
        updateDeleteButtonText();
        rebuildEntryList();
    }

    private int getDisplayRealIndex(int displayIndex) {
        if (displayIndex >= 0 && displayIndex < displayToHistoryIndex.size()) {
            return displayToHistoryIndex.get(displayIndex);
        }
        return -1;
    }

    private void updateDeleteButtonText() {
        if (deleteButton == null) return;
        if (confirmingBulkDelete) {
            deleteButton.setMessage(Text.literal("Confirm?"));
        } else if (multiSelected.size() >= 2) {
            deleteButton.setMessage(Text.literal("Del (" + multiSelected.size() + ")"));
        } else {
            deleteButton.setMessage(Text.literal("Delete"));
        }
    }

    private void populateEntryList() {
        displayToHistoryIndex.clear();
        List<LayoutClipboard.HistoryEntry> history = LayoutClipboard.getHistory();
        int activeContainer = LayoutClipboard.getActiveContainerIndex();
        int activePlayer = LayoutClipboard.getActivePlayerIndex();
        int activeBundle = LayoutClipboard.getActiveBundleIndex();
        int activeGrid9 = LayoutClipboard.getActiveGrid9Index();
        int activeHopper5 = LayoutClipboard.getActiveHopper5Index();
        int activeFurnace2 = LayoutClipboard.getActiveFurnace2Index();

        List<Integer> orderedIndices = new ArrayList<>();
        if (activeTab == TAB_FAVORITES) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).favorited) {
                    orderedIndices.add(i);
                }
            }
        } else {
            // TAB_ALL: show all entries in natural recency order (newest first)
            for (int i = 0; i < history.size(); i++) {
                orderedIndices.add(i);
            }
        }

        for (int displayIdx = 0; displayIdx < orderedIndices.size(); displayIdx++) {
            int realIdx = orderedIndices.get(displayIdx);
            displayToHistoryIndex.add(realIdx);
            LayoutClipboard.HistoryEntry entry = history.get(realIdx);
            boolean isActive;
            if (entry.isBundle()) {
                isActive = (realIdx == activeBundle);
            } else if (entry.snapshot.isPlayerInventory) {
                isActive = (realIdx == activePlayer);
            } else if (LayoutClipboard.TYPE_GRID9.equals(entry.entryType)) {
                isActive = (realIdx == activeGrid9);
            } else if (LayoutClipboard.TYPE_HOPPER5.equals(entry.entryType)) {
                isActive = (realIdx == activeHopper5);
            } else if (LayoutClipboard.TYPE_FURNACE2.equals(entry.entryType)) {
                isActive = (realIdx == activeFurnace2);
            } else {
                isActive = (realIdx == activeContainer);
            }
            entryList.addConfigEntry(new HistoryItemEntry(displayIdx, realIdx, entry, isActive));
        }

        if (orderedIndices.isEmpty()) {
            entryList.addConfigEntry(new EmptyEntry());
        }
    }

    private void switchTab(int tab) {
        activeTab = tab;
        multiSelected.clear();
        selectionAnchor = -1;
        highlightedIndex = -1;
        hoveredEntryIndex = -1;
        confirmingBulkDelete = false;
        updateDeleteButtonText();
        updateTabButtonStyles();
        rebuildEntryList();
    }

    private void updateTabButtonStyles() {
        if (tabAllButton != null) {
            tabAllButton.setMessage(Text.literal(activeTab == TAB_ALL ? "[All]" : "All"));
        }
        if (tabFavoritesButton != null) {
            tabFavoritesButton.setMessage(Text.literal(activeTab == TAB_FAVORITES ? "[Favorites]" : "Favorites"));
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

    // ========== Helper: scaled slot size ==========

    /** Returns the visual pixel size of one slot at the current PREVIEW_SCALE. */
    private static int scaledSlotSize() {
        return Math.max(8, (int)(NATIVE_SLOT_SIZE * PREVIEW_SCALE));
    }

    // ========== Helper: draw a single vanilla slot sprite ==========

    /**
     * Draws a vanilla slot background sprite at the given position and size.
     */
    private void drawSlotSprite(DrawContext context, int x, int y, int size) {
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, size, size);
    }

    /**
     * Draws a vanilla crafter disabled-slot sprite at the given position and size.
     */
    private void drawLockedSlotSprite(DrawContext context, int x, int y, int size) {
        // Draw the base slot first, then the disabled overlay
        drawSlotSprite(context, x, y, size);
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, CRAFTER_DISABLED_SLOT_SPRITE, x, y, size, size);
    }

    /**
     * Draws an empty armor slot with its vanilla placeholder sprite (helmet/chestplate/leggings/boots/shield outline).
     */
    private void drawEmptyArmorSlot(DrawContext context, int x, int y, int size, Identifier emptyTexture) {
        drawSlotSprite(context, x, y, size);
        // Scale the placeholder sprite to fit within the slot content area (1px border each side)
        int iconSize = Math.max(4, size - 2);
        int iconX = x + (size - iconSize) / 2;
        int iconY = y + (size - iconSize) / 2;
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, emptyTexture, iconX, iconY, iconSize, iconSize);
    }

    // ========== Preview rendering ==========

    private void renderPreview(DrawContext context, int mouseX, int mouseY) {
        List<LayoutClipboard.HistoryEntry> history = LayoutClipboard.getHistory();

        // Priority: hovered entry > highlighted entry > nothing
        int realPreviewIndex = -1;
        if (hoveredEntryIndex >= 0) {
            realPreviewIndex = getDisplayRealIndex(hoveredEntryIndex);
        }
        if (realPreviewIndex < 0 && highlightedIndex >= 0) {
            realPreviewIndex = getDisplayRealIndex(highlightedIndex);
        }

        if (realPreviewIndex < 0 || realPreviewIndex >= history.size()) {
            // Show "Hover to preview" text
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Hover an entry to preview"),
                    rightPanelX + rightPanelWidth / 2, panelTop + (panelBottom - panelTop) / 2, GRAY);
            return;
        }

        LayoutClipboard.HistoryEntry entry = history.get(realPreviewIndex);
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

        // Grid starts below the header area
        int gridY = panelTop + 38;
        int gridBottomY;

        // Available space for the grid inside the panel (with padding)
        int panelPadding = 8;
        int availableWidth = rightPanelWidth - panelPadding * 2;
        int availableHeight = panelBottom - gridY - 30 - panelPadding;

        int s = scaledSlotSize();

        if (entry.isBundle()) {
            gridBottomY = renderBundlePreview(context, snapshot, entry, gridY, availableWidth, availableHeight, s, previewSlots);
        } else if (snapshot.isPlayerInventory) {
            gridBottomY = renderPlayerInventoryPreview(context, snapshot, gridY, availableWidth, availableHeight, s, previewSlots);
        } else if (LayoutClipboard.TYPE_GRID9.equals(entry.entryType)) {
            gridBottomY = renderGrid9Preview(context, snapshot, gridY, availableWidth, availableHeight, s, previewSlots);
        } else if (LayoutClipboard.TYPE_HOPPER5.equals(entry.entryType)) {
            gridBottomY = renderHopper5Preview(context, snapshot, gridY, availableWidth, availableHeight, s, previewSlots);
        } else if (LayoutClipboard.TYPE_FURNACE2.equals(entry.entryType)) {
            gridBottomY = renderFurnace2Preview(context, snapshot, gridY, availableWidth, availableHeight, s, previewSlots);
        } else {
            gridBottomY = renderContainerPreview(context, snapshot, gridY, availableWidth, availableHeight, s, previewSlots);
        }

        // Show item count summary below grid
        int nonEmptyCount = 0;
        int totalSlots = snapshot.slotCount > 0 ? snapshot.slotCount : snapshot.slots.size();
        for (LayoutClipboard.SlotData sd : snapshot.slots.values()) {
            if (sd != null && sd.item() != null) {
                nonEmptyCount++;
            }
        }
        String summary;
        if (entry.isBundle()) {
            summary = nonEmptyCount + " items";
        } else if (LayoutClipboard.TYPE_GRID9.equals(entry.entryType)) {
            int lockedCount = 0;
            for (LayoutClipboard.SlotData sd : snapshot.slots.values()) {
                if (LayoutClipboard.SlotData.isLocked(sd)) lockedCount++;
            }
            summary = (nonEmptyCount + lockedCount) + "/9 slots taken";
        } else if (LayoutClipboard.TYPE_HOPPER5.equals(entry.entryType)) {
            summary = nonEmptyCount + "/5 slots taken";
        } else if (LayoutClipboard.TYPE_FURNACE2.equals(entry.entryType)) {
            summary = nonEmptyCount + "/2 slots taken";
        } else {
            summary = nonEmptyCount + "/" + totalSlots + " slots taken";
        }
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(summary),
                rightPanelX + rightPanelWidth / 2, gridBottomY + 10, DARK_GRAY);

        // Render hover tooltip (drawn last so it's on top of everything)
        for (PreviewSlotInfo slotInfo : previewSlots) {
            if (mouseX >= slotInfo.x && mouseX < slotInfo.x + slotInfo.size
                    && mouseY >= slotInfo.y && mouseY < slotInfo.y + slotInfo.size
                    && !slotInfo.stack.isEmpty()) {
                context.drawItemTooltip(this.textRenderer, slotInfo.stack, mouseX, mouseY);
                break;
            }
        }
    }

    // ========== Container preview (9-col standard) ==========

    private int renderContainerPreview(DrawContext context, LayoutClipboard.LayoutSnapshot snapshot,
                                       int gridY, int availableWidth, int availableHeight, int s,
                                       List<PreviewSlotInfo> previewSlots) {
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
        } else {
            cols = 9;
            rows = (int) Math.ceil(slotCount / 9.0);
        }

        // Clamp slot size to fit available space
        int fitSize = Math.min(availableWidth / cols, availableHeight / rows);
        fitSize = Math.max(fitSize, 8);
        int slotSize = Math.min(s, fitSize);

        int gridWidth = cols * slotSize;
        int gridHeight = rows * slotSize;
        int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

        // Draw container background
        drawContainerBackground(context, gridX, gridY, gridWidth, gridHeight);

        // Render all slot positions (0 to slotCount-1), drawing empty slots for missing keys
        for (int i = 0; i < slotCount; i++) {
            int col = i % cols;
            int row = i / cols;
            int sx = gridX + col * slotSize;
            int sy = gridY + row * slotSize;
            renderPreviewSlot(context, snapshot.slots.get(i), sx, sy, slotSize, previewSlots);
        }

        return gridY + gridHeight;
    }

    // ========== Player inventory preview — vanilla layout ==========

    private int renderPlayerInventoryPreview(DrawContext context, LayoutClipboard.LayoutSnapshot snapshot,
                                              int gridY, int availableWidth, int availableHeight, int s,
                                              List<PreviewSlotInfo> previewSlots) {
        int gap = Math.max(2, (int)(4 * PREVIEW_SCALE));

        // Layout: [OH] gap [armor] gap [main 9-col]
        // Width = s + gap + s + gap + 9*s = 11s + 2*gap
        // Height = 4 rows + hotbar gap = 4s + gap
        int mainRows = 4;

        // Clamp slot size to fit available space
        int fitW = (availableWidth - 2 * gap) / 11;
        int fitH = (availableHeight - gap) / mainRows;
        s = Math.max(8, Math.min(s, Math.min(fitW, fitH)));
        gap = Math.max(2, (int)(4 * PREVIEW_SCALE));

        int totalWidth = 11 * s + 2 * gap;
        int totalHeight = mainRows * s + gap; // gap between main row 3 and hotbar

        int gridX = rightPanelX + (rightPanelWidth - totalWidth) / 2;

        drawContainerBackground(context, gridX, gridY, totalWidth, totalHeight);

        // Column X positions: offhand at gridX, gap, armor, gap, main grid
        int offhandX = gridX;
        int armorX = gridX + s + gap;
        int mainX = armorX + s + gap;

        // Main grid row Y positions (rows 0-2 contiguous, hotbar after gap)
        int row0Y = gridY;
        int row1Y = gridY + s;
        int row2Y = gridY + 2 * s;
        int hotbarY = gridY + 3 * s + gap;

        // --- Armor column (uniform spacing, no hotbar gap) ---
        int armorY = gridY;
        Identifier[] armorSprites = {
            PlayerScreenHandler.EMPTY_HELMET_SLOT_TEXTURE,
            PlayerScreenHandler.EMPTY_CHESTPLATE_SLOT_TEXTURE,
            PlayerScreenHandler.EMPTY_LEGGINGS_SLOT_TEXTURE,
            PlayerScreenHandler.EMPTY_BOOTS_SLOT_TEXTURE
        };
        int[] armorKeys = {36, 37, 38, 39};
        for (int i = 0; i < 4; i++) {
            int sy = armorY + i * s;
            LayoutClipboard.SlotData sd = snapshot.slots.get(armorKeys[i]);
            boolean empty = (sd == null || sd.item() == null);
            if (empty) {
                drawEmptyArmorSlot(context, armorX, sy, s, armorSprites[i]);
            } else {
                renderPreviewSlot(context, sd, armorX, sy, s, previewSlots);
            }
        }

        // --- Offhand (one gap-width left of armor, vertically centered between armor C and L) ---
        // Vertically centered on the boundary between armor row 1 (C) and row 2 (L)
        int offhandY = armorY + s + s / 2;
        LayoutClipboard.SlotData offhandSd = snapshot.slots.get(40);
        boolean offhandEmpty = (offhandSd == null || offhandSd.item() == null);
        if (offhandEmpty) {
            drawEmptyArmorSlot(context, offhandX, offhandY, s, PlayerScreenHandler.EMPTY_OFF_HAND_SLOT_TEXTURE);
        } else {
            renderPreviewSlot(context, offhandSd, offhandX, offhandY, s, previewSlots);
        }

        // --- Main inventory (3×9, keys 9-35) ---
        int[] mainRowY = {row0Y, row1Y, row2Y};
        for (int i = 9; i <= 35; i++) {
            int idx = i - 9;
            int col = idx % 9;
            int row = idx / 9;
            renderPreviewSlot(context, snapshot.slots.get(i), mainX + col * s, mainRowY[row], s, previewSlots);
        }

        // --- Hotbar (1×9, keys 0-8) ---
        for (int i = 0; i <= 8; i++) {
            renderPreviewSlot(context, snapshot.slots.get(i), mainX + i * s, hotbarY, s, previewSlots);
        }

        return gridY + totalHeight;
    }

    // ========== Grid9 preview (3×3, e.g. crafter/dispenser) ==========

    private int renderGrid9Preview(DrawContext context, LayoutClipboard.LayoutSnapshot snapshot,
                                    int gridY, int availableWidth, int availableHeight, int s,
                                    List<PreviewSlotInfo> previewSlots) {
        int cols = 3;
        int rows = 3;

        int fitSize = Math.min(availableWidth / cols, availableHeight / rows);
        fitSize = Math.max(fitSize, 8);
        int slotSize = Math.min(s, fitSize);

        int gridWidth = cols * slotSize;
        int gridHeight = rows * slotSize;
        int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

        drawContainerBackground(context, gridX, gridY, gridWidth, gridHeight);

        for (int i = 0; i < 9; i++) {
            int col = i % cols;
            int row = i / cols;
            int sx = gridX + col * slotSize;
            int sy = gridY + row * slotSize;
            LayoutClipboard.SlotData sd = snapshot.slots.get(i);
            if (LayoutClipboard.SlotData.isLocked(sd)) {
                drawLockedSlotSprite(context, sx, sy, slotSize);
            } else {
                renderPreviewSlot(context, sd, sx, sy, slotSize, previewSlots);
            }
        }

        return gridY + gridHeight;
    }

    // ========== Hopper5 preview (5×1) ==========

    private int renderHopper5Preview(DrawContext context, LayoutClipboard.LayoutSnapshot snapshot,
                                      int gridY, int availableWidth, int availableHeight, int s,
                                      List<PreviewSlotInfo> previewSlots) {
        int cols = 5;
        int rows = 1;

        int fitSize = Math.min(availableWidth / cols, availableHeight / rows);
        fitSize = Math.max(fitSize, 8);
        int slotSize = Math.min(s, fitSize);

        int gridWidth = cols * slotSize;
        int gridHeight = rows * slotSize;
        int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

        drawContainerBackground(context, gridX, gridY, gridWidth, gridHeight);

        for (int i = 0; i < 5; i++) {
            int sx = gridX + i * slotSize;
            int sy = gridY;
            renderPreviewSlot(context, snapshot.slots.get(i), sx, sy, slotSize, previewSlots);
        }

        return gridY + gridHeight;
    }

    // ========== Furnace2 preview (vertical: input on top, fuel on bottom) ==========

    private int renderFurnace2Preview(DrawContext context, LayoutClipboard.LayoutSnapshot snapshot,
                                       int gridY, int availableWidth, int availableHeight, int s,
                                       List<PreviewSlotInfo> previewSlots) {
        // 1 column, 2 rows with a slot-sized gap between (fire icon space)
        // Total height ≈ 3 slot-heights
        int fitSize = Math.min(availableWidth, availableHeight / 3);
        fitSize = Math.max(fitSize, 8);
        int slotSize = Math.min(s, fitSize);

        int fireGap = slotSize; // space where fire icon would be in vanilla
        int gridWidth = slotSize;
        int gridHeight = 2 * slotSize + fireGap;
        int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

        drawContainerBackground(context, gridX, gridY, gridWidth, gridHeight);

        // Input slot (top)
        renderPreviewSlot(context, snapshot.slots.get(0), gridX, gridY, slotSize, previewSlots);

        // Lit flame icon centered in the gap between input and fuel
        int fireSize = Math.max(4, (int)(slotSize * 0.7));
        int fireX = gridX + (slotSize - fireSize) / 2;
        int fireY = gridY + slotSize + (fireGap - fireSize) / 2;
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, FURNACE_LIT_PROGRESS_SPRITE, fireX, fireY, fireSize, fireSize);

        // Fuel slot (bottom, after fire gap)
        renderPreviewSlot(context, snapshot.slots.get(1), gridX, gridY + slotSize + fireGap, slotSize, previewSlots);

        return gridY + gridHeight;
    }

    // ========== Bundle preview (dynamic grid) ==========

    private int renderBundlePreview(DrawContext context, LayoutClipboard.LayoutSnapshot snapshot,
                                     LayoutClipboard.HistoryEntry entry, int gridY,
                                     int availableWidth, int availableHeight, int s,
                                     List<PreviewSlotInfo> previewSlots) {
        int itemCount = snapshot.slots.size();
        int cols = Math.max(2, (int) Math.ceil(Math.sqrt(itemCount)));
        cols = Math.min(cols, 8);
        int rows = (int) Math.ceil((double) itemCount / cols);

        int fitSize = Math.min(availableWidth / cols, availableHeight / Math.max(rows, 1));
        fitSize = Math.max(fitSize, 8);
        int slotSize = Math.min(s, fitSize);

        int gridWidth = cols * slotSize;
        int gridHeight = rows * slotSize;
        int gridX = rightPanelX + (rightPanelWidth - gridWidth) / 2;

        // Dark tooltip-style background for bundle (vanilla-like muted greys)
        context.fill(gridX - 4, gridY - 4, gridX + gridWidth + 4, gridY + gridHeight + 4, 0xFF3A3A3E);
        context.fill(gridX - 3, gridY - 3, gridX + gridWidth + 3, gridY + gridHeight + 3, 0xFF0C0C0F);

        List<Integer> sortedSlotIds = new java.util.ArrayList<>(snapshot.slots.keySet());
        java.util.Collections.sort(sortedSlotIds);

        for (int i = 0; i < sortedSlotIds.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int sx = gridX + col * slotSize;
            int sy = gridY + row * slotSize;
            int slotId = sortedSlotIds.get(i);
            renderBundlePreviewSlot(context, snapshot.slots.get(slotId), sx, sy, slotSize, previewSlots);
        }

        return gridY + gridHeight;
    }

    /**
     * Draw a dark slot background for bundle preview (matches vanilla bundle tooltip style).
     */
    private void drawBundleSlotBackground(DrawContext context, int x, int y, int size) {
        context.fill(x, y, x + size, y + size, 0xFF404044); // subtle grey border
        context.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xFF2C2C2E); // dark grey fill
    }

    /**
     * Render a single slot in the bundle preview with dark tooltip-style background.
     */
    private void renderBundlePreviewSlot(DrawContext context, LayoutClipboard.SlotData sd, int sx, int sy, int slotSize,
                                          List<PreviewSlotInfo> previewSlots) {
        boolean empty = (sd == null || sd.item() == null);

        drawBundleSlotBackground(context, sx, sy, slotSize);

        if (!empty) {
            ItemStack displayStack = LayoutClipboard.reconstructStack(sd.item(), sd.count(), sd.components());

            float itemScale = (float)(slotSize - 2) / NATIVE_ITEM_SIZE;
            var matrices = context.getMatrices();
            matrices.pushMatrix();
            matrices.translate((float)(sx + 1), (float)(sy + 1));
            matrices.scale(itemScale, itemScale);
            context.drawItem(displayStack, 0, 0);
            context.drawStackOverlay(this.textRenderer, displayStack, 0, 0);
            matrices.popMatrix();

            if (previewSlots != null) {
                previewSlots.add(new PreviewSlotInfo(sx, sy, slotSize, displayStack));
            }
        }
    }

    // ========== Shared rendering helpers ==========

    /**
     * Draws a container background (gray panel with border) behind a grid area.
     */
    private void drawContainerBackground(DrawContext context, int gridX, int gridY, int gridWidth, int gridHeight) {
        context.fill(gridX - 4, gridY - 4, gridX + gridWidth + 4, gridY + gridHeight + 4, PANEL_BORDER);
        context.fill(gridX - 3, gridY - 3, gridX + gridWidth + 3, gridY + gridHeight + 3, 0xFFC6C6C6);
    }

    /**
     * Render a single slot in the preview grid using vanilla slot sprites.
     * The slot sprite is drawn at the scaled size. Items are rendered at native 16×16 size,
     * centered within the slot's visual area.
     *
     * If previewSlots is non-null, the slot info is recorded for hover tooltip detection.
     */
    private void renderPreviewSlot(DrawContext context, LayoutClipboard.SlotData sd, int sx, int sy, int slotSize,
                                    List<PreviewSlotInfo> previewSlots) {
        boolean empty = (sd == null || sd.item() == null);

        // Draw vanilla slot sprite background (scaled to slotSize)
        drawSlotSprite(context, sx, sy, slotSize);

        // Draw item if present, scaled proportionally to fit within the slot
        if (!empty) {
            ItemStack displayStack = LayoutClipboard.reconstructStack(sd.item(), sd.count(), sd.components());

            // Scale item and overlays to fit within slot content area (1px border each side)
            float itemScale = (float)(slotSize - 2) / NATIVE_ITEM_SIZE;
            var matrices = context.getMatrices();
            matrices.pushMatrix();
            matrices.translate((float)(sx + 1), (float)(sy + 1));
            matrices.scale(itemScale, itemScale);
            context.drawItem(displayStack, 0, 0);
            context.drawStackOverlay(this.textRenderer, displayStack, 0, 0);
            matrices.popMatrix();

            // Track for tooltip — use the full slot bounds for hit testing
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

        // Shift+Tab or custom clipboard history key closes
        InvTweaksConfig config = InvTweaksConfig.get();
        if (config.clipboardHistoryKey == -1) {
            if (input.hasShift() && keyCode == GLFW.GLFW_KEY_TAB) {
                close();
                return true;
            }
        } else {
            if (keyCode == config.clipboardHistoryKey) {
                close();
                return true;
            }
        }

        return super.keyPressed(input);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parentScreen);
    }

    // ========== Modifier key helpers (GLFW-based, not Screen.hasXxxDown) ==========

    private static boolean isCtrlOrCmdHeld() {
        long wh = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
    }

    private static boolean isShiftHeld() {
        long wh = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
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
        private final int displayIndex;
        private final int realIndex;
        private final LayoutClipboard.HistoryEntry entry;
        private final boolean isActive;
        private final ButtonWidget selectBtn;
        private static final int STAR_WIDTH = 14;

        HistoryItemEntry(int displayIndex, int realIndex, LayoutClipboard.HistoryEntry entry, boolean isActive) {
            this.displayIndex = displayIndex;
            this.realIndex = realIndex;
            this.entry = entry;
            this.isActive = isActive;

            this.selectBtn = ButtonWidget.builder(Text.literal("Select"), button -> {
                LayoutClipboard.setActiveIndex(realIndex);
                rebuildEntryList();
            }).dimensions(0, 0, 50, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();

            if (hovered) {
                hoveredEntryIndex = displayIndex;
            }

            if (multiSelected.contains(displayIndex)) {
                context.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, MULTI_SELECT_BG);
            }

            if (highlightedIndex == displayIndex && !multiSelected.contains(displayIndex)) {
                context.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, 0x6055AAFF);
            }

            if (hovered && highlightedIndex != displayIndex && !multiSelected.contains(displayIndex)) {
                context.fill(x - 2, y - 1, x + w + 2, y + ROW_HEIGHT - 3, 0x40FFFFFF);
            }

            // Star icon (left side)
            String starChar = entry.favorited ? "\u2605" : "\u2606";
            int starColor = entry.favorited ? GOLD : STAR_GRAY;
            context.drawTextWithShadow(textRenderer, Text.literal(starChar), x, y + 2, starColor);

            int labelX = x + STAR_WIDTH;

            // Active indicator
            int textColor;
            if (entry.isBundle()) {
                textColor = entry.bundleColor != -1 ? (0xFF000000 | entry.bundleColor) : ORANGE;
            } else {
                textColor = entry.snapshot.isPlayerInventory ? AQUA : YELLOW;
            }
            String prefix = isActive ? "\u25B6 " : "  ";
            String label = prefix + entry.label;

            int availableLabelWidth = w - selectBtn.getWidth() - 6 - STAR_WIDTH;
            String displayLabel = textRenderer.trimToWidth(label, availableLabelWidth);
            context.drawTextWithShadow(textRenderer, Text.literal(displayLabel), labelX, y + 2, textColor);

            // Time
            String timeStr = formatRelativeTime(entry.timestamp);
            context.drawTextWithShadow(textRenderer, Text.literal("  " + timeStr), labelX, y + 14, DARK_GRAY);

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

            if (click.button() == 0) {
                // Check if the star was clicked (left 14px of the entry)
                int x = getX();
                if (click.x() >= x && click.x() < x + STAR_WIDTH) {
                    boolean success = LayoutClipboard.toggleFavorite(realIndex);
                    if (success) {
                        if (entry.favorited) {
                            InvTweaksOverlay.show("Entry favorited", GOLD);
                        } else {
                            InvTweaksOverlay.show("Entry unfavorited", WHITE);
                        }
                    } else {
                        InvTweaksOverlay.show("Favorite limit reached (50)", YELLOW);
                    }
                    rebuildEntryList();
                    return true;
                }

                if (isCtrlOrCmdHeld()) {
                    if (highlightedIndex >= 0 && !multiSelected.contains(highlightedIndex)) {
                        multiSelected.add(highlightedIndex);
                    }
                    highlightedIndex = -1;
                    if (multiSelected.contains(displayIndex)) {
                        multiSelected.remove(displayIndex);
                    } else {
                        multiSelected.add(displayIndex);
                    }
                    selectionAnchor = displayIndex;
                    confirmingBulkDelete = false;
                    updateDeleteButtonText();
                    return true;
                } else if (isShiftHeld()) {
                    int anchor = selectionAnchor >= 0 ? selectionAnchor : 0;
                    int from = Math.min(anchor, displayIndex);
                    int to = Math.max(anchor, displayIndex);
                    for (int i = from; i <= to; i++) {
                        multiSelected.add(i);
                    }
                    confirmingBulkDelete = false;
                    updateDeleteButtonText();
                    return true;
                } else {
                    multiSelected.clear();
                    selectionAnchor = displayIndex;
                    confirmingBulkDelete = false;
                    highlightedIndex = displayIndex;
                    updateDeleteButtonText();
                    return true;
                }
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
