package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class InvTweaksConfigScreen extends Screen {
    private final Screen parent;
    private InvTweaksConfig config;

    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 20;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFF999999;
    private static final int DARK_GRAY = 0xFF666666;
    private static final int AQUA = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int ORANGE = 0xFFFFAA00;

    // Tab state (4 tabs: no Advanced tab)
    private enum Tab { ALL, TWEAKS, HOTKEYS, DEBUG }
    private Tab activeTab = Tab.ALL;
    private final List<ButtonWidget> tabButtons = new ArrayList<>();

    // Current entry list
    private ConfigEntryList entryList;

    // Responsive layout calculations
    private int rowWidth;
    private int contentLeft;

    // Tooltip state
    private ConfigEntry hoveredEntry = null;
    private long hoverStartTime = 0;
    private static final long TOOLTIP_DELAY_MS = 300;

    // Key capture state (entry-managed capture with screen-level tracking)
    private CaptureHandler activeCaptureHandler = null;

    // IntValueEntry edit mode tracking
    private IntValueEntry activeEditEntry = null;

    // Debug "Copy Debug Log" button feedback
    private long copiedTimestamp = 0;

    // Advanced Options collapsible state (not persisted)
    private boolean advancedOverridesExpanded = false;
    private final List<ConfigEntry> advancedOverrideEntries = new ArrayList<>();

    public InvTweaksConfigScreen(Screen parent) {
        super(Text.literal("InvTweaks Configuration"));
        this.parent = parent;
    }

    // ========== Capture handler interface ==========

    interface CaptureHandler {
        void onKeyCapture(int keyCode);
        void onCaptureCancel();
    }

    void startCapture(CaptureHandler handler) {
        if (activeCaptureHandler != null) {
            activeCaptureHandler.onCaptureCancel();
        }
        activeCaptureHandler = handler;
    }

    void endCapture() {
        activeCaptureHandler = null;
    }

    // ========== Responsive layout helpers ==========

    private void calculateLayout() {
        rowWidth = Math.max(350, Math.min(600, (int)(this.width * 0.7)));
        contentLeft = (this.width - rowWidth) / 2;
    }

    private int scaledButtonWidth(int baseWidth) {
        return Math.max(baseWidth, (int)(baseWidth * (rowWidth / 400.0)));
    }

    // ========== Init ==========

    @Override
    protected void init() {
        super.init();
        config = InvTweaksConfig.get();
        tabButtons.clear();
        calculateLayout();

        int centerX = this.width / 2;

        // ---- Tab bar (4 tabs) ----
        Tab[] tabValues = Tab.values();
        int tabCount = tabValues.length;
        int tabBarWidth = Math.min(rowWidth, this.width - 20);
        int tabGap = 4;
        int totalGaps = (tabCount - 1) * tabGap;
        int tabW = (tabBarWidth - totalGaps) / tabCount;
        int tabBarLeft = (this.width - tabBarWidth) / 2;
        int tabY = 28;

        String[] tabLabels = {"All", "Tweaks", "Hotkeys", "Debug"};
        for (int i = 0; i < tabCount; i++) {
            final Tab tab = tabValues[i];
            int tx = tabBarLeft + i * (tabW + tabGap);
            ButtonWidget tabBtn = ButtonWidget.builder(
                    Text.literal(tabLabels[i]),
                    button -> switchTab(tab)
            ).dimensions(tx, tabY, tabW, BUTTON_HEIGHT).build();
            tabButtons.add(tabBtn);
            addDrawableChild(tabBtn);
        }
        updateTabHighlights();

        // ---- Entry list ----
        int listTop = tabY + BUTTON_HEIGHT + 6;
        int listBottom = this.height - 32;
        entryList = new ConfigEntryList(this.client, this.width, listBottom - listTop, listTop, ROW_HEIGHT);
        populateEntryList();
        addDrawableChild(entryList);

        // ---- Done button (fixed at bottom) ----
        int doneW = Math.max(100, scaledButtonWidth(100));
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            config.save();
            if (client != null) client.setScreen(parent);
        }).dimensions(centerX - doneW / 2, this.height - 27, doneW, BUTTON_HEIGHT).build());
    }

    // ========== Tab switching ==========

    private void switchTab(Tab tab) {
        activeTab = tab;
        updateTabHighlights();
        rebuildEntryList();
    }

    private void updateTabHighlights() {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabButtons.size(); i++) {
            ButtonWidget btn = tabButtons.get(i);
            boolean active = tabs[i] == activeTab;
            String label = switch (tabs[i]) {
                case ALL -> "All";
                case TWEAKS -> "Tweaks";
                case HOTKEYS -> "Hotkeys";
                case DEBUG -> "Debug";
            };
            if (active) {
                btn.setMessage(Text.literal("\u00a7n" + label));
            } else {
                btn.setMessage(Text.literal(label));
            }
        }
    }

    private void rebuildEntryList() {
        remove(entryList);
        int listTop = 28 + BUTTON_HEIGHT + 6;
        int listBottom = this.height - 32;
        entryList = new ConfigEntryList(this.client, this.width, listBottom - listTop, listTop, ROW_HEIGHT);
        populateEntryList();
        addDrawableChild(entryList);
    }

    // ========== Populate entries based on active tab ==========

    private void populateEntryList() {
        switch (activeTab) {
            case ALL -> populateAllTab();
            case TWEAKS -> populateTweaksTab();
            case HOTKEYS -> populateHotkeysTab();
            case DEBUG -> populateDebugTab();
        }
    }

    // ---- ALL tab: simplified overview (no advanced overrides, no hotbar modifiers) ----

    private void populateAllTab() {
        int toggleW = scaledButtonWidth(40);
        int keyBtnW = scaledButtonWidth(80);

        // Modifier Keys
        addGlobalModifierKeys(keyBtnW);

        // Modifier-Pair Tweaks
        addModifierPairTweakToggles(toggleW);

        // Copy/Paste
        addCopyPasteToggles(toggleW);

        // Clipboard Settings
        addClipboardSettings(keyBtnW);

        // Bundle Keys
        addBundleKeys(keyBtnW);

        // Other Tweaks
        addOtherTweakToggles(toggleW);
    }

    // ---- TWEAKS tab ----

    private void populateTweaksTab() {
        int toggleW = scaledButtonWidth(40);
        int keyBtnW = scaledButtonWidth(80);

        addModifierPairTweakToggles(toggleW);
        addCopyPasteToggles(toggleW);
        addClipboardSettings(keyBtnW);
        addOtherTweakToggles(toggleW);
    }

    // ---- HOTKEYS tab ----

    private void populateHotkeysTab() {
        int keyBtnW = scaledButtonWidth(80);

        addGlobalModifierKeys(keyBtnW);
        addBundleKeys(keyBtnW);
        addCopyPasteKeys(keyBtnW);

        // Open Config Key (single entry, no section header)
        entryList.addConfigEntry(new KeyBindEntry("Open Config Key:", keyBtnW, GREEN,
                () -> config.openConfigKey, v -> config.openConfigKey = v,
                "Key to open this config screen from in-game. Default: K.",
                null));

        // Advanced Options (collapsible)
        addAdvancedOverridesSection();
    }

    // ---- DEBUG tab ----

    private void populateDebugTab() {
        int toggleW = scaledButtonWidth(40);

        entryList.addConfigEntry(new FeatureEntry("Enable Debug Logging", toggleW,
                () -> config.enableDebugLogging, v -> {
                    config.enableDebugLogging = v;
                    double savedScroll = entryList.getScrollY();
                    rebuildEntryList();
                    entryList.setScrollY(savedScroll);
                },
                "Logs detailed mod activity to the game log. Adds overhead \u2014 leave off during normal play."));

        if (config.enableDebugLogging) {
            entryList.addConfigEntry(new ActionButtonEntry("Copy Debug Log", () -> {
                String contents = InvTweaksConfig.getDebugLogContents();
                MinecraftClient.getInstance().keyboard.setClipboard(contents);
                copiedTimestamp = System.currentTimeMillis();
            }, "Copies recent debug entries to your clipboard for bug reports."));
        }

        entryList.addConfigEntry(new ActionButtonEntry("Report Bug", () -> {
            try {
                Util.getOperatingSystem().open(new URI("https://github.com/Tacticalle/InvTweaks/issues/new"));
            } catch (Exception e) {
                InvTweaksConfig.debugLog("CONFIG", "Failed to open bug report URL: %s", e.getMessage());
            }
        }, "Opens the InvTweaks issue tracker in your browser."));
    }

    // ========== Shared section builders ==========

    private void addModifierPairTweakToggles(int toggleW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Enable/Disable Tweaks"));
        entryList.addConfigEntry(new FeatureEntry("Click Pickup", toggleW,
                () -> config.enableClickPickup, v -> config.enableClickPickup = v,
                "Modifies left-click to pick up a partial stack instead of the full stack."));
        entryList.addConfigEntry(new FeatureEntry("Shift+Click Transfer", toggleW,
                () -> config.enableShiftClickTransfer, v -> config.enableShiftClickTransfer = v,
                "Modifies shift-click to transfer a partial stack instead of the full stack."));
        entryList.addConfigEntry(new FeatureEntry("Bundle Extract", toggleW,
                () -> config.enableBundleExtract, v -> config.enableBundleExtract = v,
                "Modifies right-click extraction from bundles to pull a partial amount instead of the full stack."));
        entryList.addConfigEntry(new FeatureEntry("Bundle Insert (cursor=bundle)", toggleW,
                () -> config.enableBundleInsertCursorBundle, v -> config.enableBundleInsertCursorBundle = v,
                "When your cursor holds a bundle over loose items, modifies insertion to add a partial amount into the bundle."));
        entryList.addConfigEntry(new FeatureEntry("Bundle Insert (cursor=items)", toggleW,
                () -> config.enableBundleInsertCursorItems, v -> config.enableBundleInsertCursorItems = v,
                "When your cursor holds items over a bundle, modifies insertion to add a partial amount from your cursor."));
    }

    private void addOtherTweakToggles(int toggleW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Other Tweaks"));
        entryList.addConfigEntry(new FeatureEntry("Throw Half", toggleW,
                () -> config.enableThrowHalf, v -> config.enableThrowHalf = v,
                "Hold the Misc modifier + Q to throw half the items in the hovered stack."));
        entryList.addConfigEntry(new FeatureEntry("Throw All But 1", toggleW,
                () -> config.enableThrowAllBut1, v -> config.enableThrowAllBut1 = v,
                "Hold the All-But-1 modifier + Q to throw all but one item from the hovered stack."));
        entryList.addConfigEntry(new FeatureEntry("Fill Existing Stacks", toggleW,
                () -> config.enableFillExisting, v -> config.enableFillExisting = v,
                "Hold the Misc modifier + shift-click or scroll to only fill existing partial stacks, skipping empty slots."));
        entryList.addConfigEntry(new FeatureEntry("Scroll Transfer", toggleW,
                () -> config.enableScrollTransfer, v -> config.enableScrollTransfer = v,
                "Scroll over a slot to transfer all matching items. Hold All-But-1 while scrolling to leave one behind. Hold Misc to only fill existing stacks. Hold both to combine."));
    }

    private void addCopyPasteToggles(int toggleW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Copy/Paste"));
        entryList.addConfigEntry(new FeatureEntry("Enable Copy/Paste", toggleW,
                () -> config.enableCopyPaste, v -> config.enableCopyPaste = v,
                "Enables copying, pasting, cutting, undoing, and clipboard history for inventory layouts."));
        entryList.addConfigEntry(new FeatureEntry("Show Overlay Messages", toggleW,
                () -> config.showOverlayMessages, v -> config.showOverlayMessages = v,
                "Shows status messages like 'Layout copied' next to the inventory. Turn off to hide them."));
        addSizeMismatchModeEntry();
    }

    private void addGlobalModifierKeys(int keyBtnW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Modifier Keys"));
        entryList.addConfigEntry(new KeyBindEntry("All But 1 Key:", keyBtnW, YELLOW,
                () -> config.allBut1Key, v -> config.allBut1Key = v,
                "The modifier key for 'all but one' actions across the mod.",
                null));
        entryList.addConfigEntry(new KeyBindEntry("Only 1 Key:", keyBtnW, AQUA,
                () -> config.only1Key, v -> config.only1Key = v,
                "The modifier key for 'only one' actions across the mod.",
                null));
        entryList.addConfigEntry(new KeyBindEntry("Misc Modifier Key:", keyBtnW, GREEN,
                () -> config.miscModifierKey, v -> config.miscModifierKey = v,
                "The modifier key for throw-half and fill-existing actions.",
                null));
    }

    private void addBundleKeys(int keyBtnW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Bundle Keys"));
        entryList.addConfigEntry(new KeyBindEntry("Bundle All But 1 Key:", keyBtnW, YELLOW,
                () -> config.bundleAllBut1Key, v -> config.bundleAllBut1Key = v,
                "Override the All-But-1 key for bundle operations only. When set to Global, uses the main All-But-1 key.",
                () -> InvTweaksConfig.getKeyName(config.allBut1Key)));
        entryList.addConfigEntry(new KeyBindEntry("Bundle Only 1 Key:", keyBtnW, AQUA,
                () -> config.bundleOnly1Key, v -> config.bundleOnly1Key = v,
                "Override the Only-1 key for bundle operations only. When set to Global, uses the main Only-1 key.",
                () -> InvTweaksConfig.getKeyName(config.only1Key)));
    }

    private void addCopyPasteKeys(int keyBtnW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Copy/Paste Layout"));
        entryList.addConfigEntry(new KeyBindEntry("Copy Layout Key:", keyBtnW, GREEN,
                () -> config.copyLayoutKey, v -> config.copyLayoutKey = v,
                "Default: Ctrl+C. Bind a specific key to make that single key trigger copy instead.",
                () -> "Default"));
        entryList.addConfigEntry(new KeyBindEntry("Paste Layout Key:", keyBtnW, GREEN,
                () -> config.pasteLayoutKey, v -> config.pasteLayoutKey = v,
                "Default: Ctrl+V. Bind a specific key to make that single key trigger paste instead.",
                () -> "Default"));
        entryList.addConfigEntry(new KeyBindEntry("Cut Layout Key:", keyBtnW, GREEN,
                () -> config.cutLayoutKey, v -> config.cutLayoutKey = v,
                "Default: Ctrl+X. Bind a specific key to make that single key trigger cut instead.",
                () -> "Default"));
        entryList.addConfigEntry(new KeyBindEntry("Undo Paste Key:", keyBtnW, GREEN,
                () -> config.undoKey, v -> config.undoKey = v,
                "Default: Ctrl+Z. Undoes the most recent paste or cut. Only one level of undo.",
                () -> "Default"));
    }

    private void addClipboardSettings(int keyBtnW) {
        entryList.addConfigEntry(new SectionHeaderEntry("Clipboard Settings"));
        entryList.addConfigEntry(new IntValueEntry("Max History", 5, 200,
                () -> config.clipboardMaxHistory, v -> config.clipboardMaxHistory = v,
                "How many clipboard entries to keep. Oldest entries are removed when full. Range: 5\u2013200."));
        entryList.addConfigEntry(new KeyBindEntry("Clipboard History Key:", keyBtnW, WHITE,
                () -> config.clipboardHistoryKey, v -> config.clipboardHistoryKey = v,
                "Key to open the clipboard history browser in any open inventory screen. Default: Shift+Tab.",
                () -> "Default"));
    }

    // ---- Advanced Options collapsible section (bottom of Hotkeys tab) ----

    private void addAdvancedOverridesSection() {
        String arrow = advancedOverridesExpanded ? "\u25BC" : "\u25B6";
        entryList.addConfigEntry(new ClickableHeaderEntry("Advanced Options " + arrow, ORANGE, () -> {
            advancedOverridesExpanded = !advancedOverridesExpanded;
            toggleAdvancedOverrideEntries();
        }));

        if (advancedOverridesExpanded) {
            appendAdvancedOverrideEntries();
        }
    }

    // ---- Advanced tab helpers ----

    /**
     * Toggle advanced override entries without rebuilding the entire list.
     * Appends or removes entries to preserve scroll position.
     */
    private void toggleAdvancedOverrideEntries() {
        double savedScroll = entryList.getScrollY();
        rebuildEntryList();
        entryList.setScrollY(savedScroll);
    }

    private void appendAdvancedOverrideEntries() {
        advancedOverrideEntries.clear();

        // Modifier-Pair Overrides
        addTrackedAdvancedEntry(new SectionHeaderEntry("Modifier-Pair Overrides"));
        addTrackedAdvancedEntry(new OverridePairEntry("Click Pickup", "clickPickup", false,
                "Use different All-But-1 / Only-1 keys for click pickup only."));
        addTrackedAdvancedEntry(new OverridePairEntry("Shift+Click Transfer", "shiftClick", false,
                "Use different All-But-1 / Only-1 keys for shift-click transfer only."));
        addTrackedAdvancedEntry(new OverridePairEntry("Hotbar Modifiers", "hotbarModifiers", false,
                "Use different All-But-1 / Only-1 keys for hotbar modifiers only."));

        // Bundle Operation Overrides
        addTrackedAdvancedEntry(new SectionHeaderEntry("Bundle Operation Overrides"));
        addTrackedAdvancedEntry(new OverridePairEntry("Bundle Extract", "bundleExtract", true,
                "Use different keys for bundle extraction only."));
        addTrackedAdvancedEntry(new OverridePairEntry("Bundle Insert (cursor=bundle)", "bundleInsertBundle", true,
                "Use different keys for bundle insert when cursor holds a bundle."));
        addTrackedAdvancedEntry(new OverridePairEntry("Bundle Insert (cursor=items)", "bundleInsertItems", true,
                "Use different keys for bundle insert when cursor holds items."));

        // Single-Key Overrides
        addTrackedAdvancedEntry(new SectionHeaderEntry("Single-Key Overrides"));
        addTrackedAdvancedEntry(new OverrideSingleEntry("Throw All But 1 Key",
                () -> config.throwAllBut1Key, v -> config.throwAllBut1Key = v,
                "Use a different key for throw-all-but-1 only."));
        addTrackedAdvancedEntry(new OverrideSingleEntry("Throw Half Key",
                () -> config.throwHalfKey, v -> config.throwHalfKey = v,
                "Use a different key for throw-half only."));
        addTrackedAdvancedEntry(new OverrideSingleEntry("Fill Existing Key",
                () -> config.fillExistingKey, v -> config.fillExistingKey = v,
                "Use a different key for fill-existing only."));
        addTrackedAdvancedEntry(new OverrideSingleEntry("Scroll Leave-1 Key",
                () -> config.scrollLeave1Key, v -> config.scrollLeave1Key = v,
                "Use a different key for scroll leave-one only."));
    }

    private void addTrackedAdvancedEntry(ConfigEntry entry) {
        entryList.addConfigEntry(entry);
        advancedOverrideEntries.add(entry);
    }

    // ---- Size mismatch mode cycling entry ----

    private static final String[] SIZE_MISMATCH_MODE_LABELS = {"Hover Position", "Menu Selection", "Arrow Keys"};

    private void addSizeMismatchModeEntry() {
        int cycleBtnW = scaledButtonWidth(110);
        String currentLabel = SIZE_MISMATCH_MODE_LABELS[Math.max(0, Math.min(2, config.sizeMismatchPasteMode))];
        ButtonWidget cycleBtn = ButtonWidget.builder(
                Text.literal(currentLabel),
                button -> {
                    config.sizeMismatchPasteMode = (config.sizeMismatchPasteMode + 1) % 3;
                    button.setMessage(Text.literal(SIZE_MISMATCH_MODE_LABELS[config.sizeMismatchPasteMode]));
                }
        ).dimensions(0, 0, cycleBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new CyclingModeEntry("Size Mismatch Paste Mode", cycleBtn,
                "How to choose which half when pasting a small layout into a double chest.\n\nHover Position: Uses your cursor's Y position relative to the container.\nMenu Selection: Shows a visual picker with preview grids.\nArrow Keys: Hold Up or Down arrow while pressing paste."));
    }

    // ========== Mouse click handling for IntValueEntry edit mode ==========

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (activeEditEntry != null && activeEditEntry.isEditing()) {
            ButtonWidget valBtn = activeEditEntry.valueBtn;
            if (mouseX >= valBtn.getX() && mouseX < valBtn.getX() + valBtn.getWidth()
                    && mouseY >= valBtn.getY() && mouseY < valBtn.getY() + valBtn.getHeight()) {
                // Let the button handle it
            } else {
                activeEditEntry.applyEdit();
            }
        }
        return super.mouseClicked(click, focused);
    }

    // ========== Render ==========

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, WHITE);

        // Tooltip rendering (after everything else so it draws on top)
        renderTooltip(context, mouseX, mouseY);
    }

    private void renderTooltip(DrawContext context, int mouseX, int mouseY) {
        if (entryList == null) return;

        ConfigEntry currentHovered = null;

        // Only check for tooltip hover if mouse is within screen bounds
        if (mouseX >= 0 && mouseX < this.width && mouseY >= 0 && mouseY < this.height) {
            // Use screen-absolute coordinates for the label area, not entry.getX() which
            // may be list-relative. The entry list is centered: contentLeft = (width - rowWidth) / 2.
            int labelLeft = (this.width - rowWidth) / 2;
            int labelRight = labelLeft + (int)(rowWidth * 0.35);

            if (mouseX >= labelLeft && mouseX < labelRight) {
                for (ConfigEntry entry : entryList.children()) {
                    int entryY = entry.getY();
                    if (mouseY >= entryY && mouseY < entryY + ROW_HEIGHT) {
                        currentHovered = entry;
                        break;
                    }
                }
            }
        }

        if (currentHovered != hoveredEntry) {
            hoveredEntry = currentHovered;
            hoverStartTime = System.currentTimeMillis();
        }

        if (hoveredEntry != null && hoveredEntry.tooltip != null && !hoveredEntry.tooltip.isEmpty()) {
            long elapsed = System.currentTimeMillis() - hoverStartTime;
            if (elapsed >= TOOLTIP_DELAY_MS) {
                List<Text> lines = wrapTooltipText(hoveredEntry.tooltip, 250);
                // drawTooltip internally adds +12 to X and -12 to Y (HoveredTooltipPositioner),
                // so we counteract those offsets to get precise screen placement.
                int tooltipX = hoveredEntry.getX() - 12;

                // Calculate tooltip height
                int approxTooltipHeight = lines.size() * 12 + 8;

                // Desired screen position: below entry with 4px gap
                int desiredTop = hoveredEntry.getY() + ROW_HEIGHT + 4;

                // If tooltip would go below screen, flip above the entry row
                if (desiredTop + approxTooltipHeight > this.height) {
                    desiredTop = hoveredEntry.getY() - approxTooltipHeight - 4;
                }

                // Add 12 to counteract drawTooltip's internal -12 Y offset
                int tooltipY = desiredTop + 12;

                context.drawTooltip(this.textRenderer, lines, tooltipX, tooltipY);
            }
        }
    }

    private List<Text> wrapTooltipText(String text, int maxWidth) {
        List<Text> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        // Split on explicit newlines first, then word-wrap each segment
        String[] paragraphs = text.split("\n");
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add(Text.literal(""));
                continue;
            }
            String[] words = paragraph.split(" ");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                String test = currentLine.length() == 0 ? word : currentLine + " " + word;
                if (this.textRenderer.getWidth(test) > maxWidth && currentLine.length() > 0) {
                    lines.add(Text.literal(currentLine.toString()));
                    currentLine = new StringBuilder(word);
                } else {
                    if (currentLine.length() > 0) currentLine.append(" ");
                    currentLine.append(word);
                }
            }
            if (currentLine.length() > 0) {
                lines.add(Text.literal(currentLine.toString()));
            }
        }
        return lines;
    }

    @Override
    public void close() {
        config.save();
        if (client != null) client.setScreen(parent);
    }

    // ========== Scrollable list with responsive sizing ==========

    private class ConfigEntryList extends ElementListWidget<ConfigEntry> {
        public ConfigEntryList(MinecraftClient client, int width, int height, int y, int itemHeight) {
            super(client, width, height, y, itemHeight);
        }

        public void addConfigEntry(ConfigEntry entry) {
            super.addEntry(entry);
        }

        public void clearEntries() {
            super.children().clear();
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
        }

        @Override
        protected int getScrollbarX() {
            return (InvTweaksConfigScreen.this.width + rowWidth) / 2 + 10;
        }
    }

    // ========== Entry base with optional tooltip ==========

    private abstract static class ConfigEntry extends ElementListWidget.Entry<ConfigEntry> {
        protected String tooltip;

        ConfigEntry() {
            this.tooltip = null;
        }

        ConfigEntry(String tooltip) {
            this.tooltip = tooltip;
        }
    }

    // ========== Section header (divider text, no dashes) ==========

    private class SectionHeaderEntry extends ConfigEntry {
        private final String text;

        SectionHeaderEntry(String text) {
            super();
            this.text = "\u00a7l" + text;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            context.drawTextWithShadow(textRenderer, Text.literal(text), x, y + 6, ORANGE);
        }

        @Override
        public List<? extends Element> children() { return List.of(); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(); }
    }

    // ========== Clickable header entry (for collapsible sections) ==========

    private class ClickableHeaderEntry extends ConfigEntry {
        private final String text;
        private final int color;
        private final ButtonWidget headerBtn;

        ClickableHeaderEntry(String text, int color, Runnable onClick) {
            super();
            this.text = text;
            this.color = color;
            this.headerBtn = ButtonWidget.builder(
                    Text.literal("\u00a7l" + text),
                    btn -> onClick.run()
            ).dimensions(0, 0, rowWidth, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            headerBtn.setX(x);
            headerBtn.setY(y);
            headerBtn.setWidth(getWidth());
            headerBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(headerBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(headerBtn); }
    }

    // ========== Key binding row (with optional defaultDisplayText for inherited keys) ==========

    private class KeyBindEntry extends ConfigEntry implements CaptureHandler {
        private final String label;
        private final int labelColor;
        private final ButtonWidget button;
        private final IntSupplier getter;
        private final IntConsumer setter;
        private final Supplier<String> defaultDisplayText;

        KeyBindEntry(String label, int btnWidth, int labelColor,
                     IntSupplier getter, IntConsumer setter, String tooltip,
                     Supplier<String> defaultDisplayText) {
            super(tooltip);
            this.label = label;
            this.labelColor = labelColor;
            this.getter = getter;
            this.setter = setter;
            this.defaultDisplayText = defaultDisplayText;
            this.button = ButtonWidget.builder(
                    Text.literal(getDisplayText(getter.getAsInt())),
                    btn -> startCapture(this)
            ).dimensions(0, 0, btnWidth, BUTTON_HEIGHT).build();
        }

        private String getDisplayText(int keyCode) {
            if (keyCode == -1 && defaultDisplayText != null) {
                return defaultDisplayText.get();
            }
            return InvTweaksConfig.getKeyName(keyCode);
        }

        @Override
        public void onKeyCapture(int keyCode) {
            setter.accept(keyCode);
            button.setMessage(Text.literal(getDisplayText(keyCode)));
            endCapture();
        }

        @Override
        public void onCaptureCancel() {
            button.setMessage(Text.literal(getDisplayText(getter.getAsInt())));
            endCapture();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, labelColor);
            int btnLeft = x + w - button.getWidth();
            button.setX(btnLeft);
            button.setY(y);
            // Update display text dynamically (in case parent key changed)
            if (activeCaptureHandler == this) {
                button.setMessage(Text.literal("> Press a key <"));
            } else {
                button.setMessage(Text.literal(getDisplayText(getter.getAsInt())));
            }
            button.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(button); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(button); }
    }

    // ========== Feature toggle row ==========

    private class FeatureEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget toggleBtn;

        FeatureEntry(String label, int toggleW,
                     Supplier<Boolean> enabledGet, Consumer<Boolean> enabledSet, String tooltip) {
            super(tooltip);
            this.label = label;
            this.toggleBtn = ButtonWidget.builder(
                    Text.literal(enabledGet.get() ? "\u00a7aON" : "\u00a7cOFF"),
                    button -> {
                        enabledSet.accept(!enabledGet.get());
                        button.setMessage(Text.literal(enabledGet.get() ? "\u00a7aON" : "\u00a7cOFF"));
                    }).dimensions(0, 0, toggleW, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);
            int toggleX = x + w - toggleBtn.getWidth();
            toggleBtn.setX(toggleX);
            toggleBtn.setY(y);
            toggleBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(toggleBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(toggleBtn); }
    }

    // ========== Override pair entry (Advanced section: two key buttons per row) ==========

    private class OverridePairEntry extends ConfigEntry {
        private final String label;
        private final String tweakName;
        private final ButtonWidget allBut1Btn;
        private final ButtonWidget only1Btn;
        private final CaptureHandler ab1Handler;
        private final CaptureHandler o1Handler;

        OverridePairEntry(String label, String tweakName, boolean isBundleOp, String tooltip) {
            super(tooltip);
            this.label = label;
            this.tweakName = tweakName;

            int btnW = scaledButtonWidth(65);

            IntSupplier ab1Getter = () -> {
                return switch (tweakName) {
                    case "clickPickup" -> config.clickPickupAllBut1Key;
                    case "shiftClick" -> config.shiftClickAllBut1Key;
                    case "bundleExtract" -> config.bundleExtractAllBut1Key;
                    case "bundleInsertBundle" -> config.bundleInsertBundleAllBut1Key;
                    case "bundleInsertItems" -> config.bundleInsertItemsAllBut1Key;
                    case "hotbarModifiers" -> config.hotbarModifiersAllBut1Key;
                    default -> -1;
                };
            };
            IntSupplier o1Getter = () -> {
                return switch (tweakName) {
                    case "clickPickup" -> config.clickPickupOnly1Key;
                    case "shiftClick" -> config.shiftClickOnly1Key;
                    case "bundleExtract" -> config.bundleExtractOnly1Key;
                    case "bundleInsertBundle" -> config.bundleInsertBundleOnly1Key;
                    case "bundleInsertItems" -> config.bundleInsertItemsOnly1Key;
                    case "hotbarModifiers" -> config.hotbarModifiersOnly1Key;
                    default -> -1;
                };
            };

            this.ab1Handler = new CaptureHandler() {
                @Override
                public void onKeyCapture(int keyCode) {
                    config.setPerTweakAllBut1Key(tweakName, keyCode);
                    allBut1Btn.setMessage(Text.literal(getAB1ButtonText(keyCode)));
                    endCapture();
                }
                @Override
                public void onCaptureCancel() {
                    allBut1Btn.setMessage(Text.literal(getAB1ButtonText(ab1Getter.getAsInt())));
                    endCapture();
                }
            };

            this.o1Handler = new CaptureHandler() {
                @Override
                public void onKeyCapture(int keyCode) {
                    config.setPerTweakOnly1Key(tweakName, keyCode);
                    only1Btn.setMessage(Text.literal(getO1ButtonText(keyCode)));
                    endCapture();
                }
                @Override
                public void onCaptureCancel() {
                    only1Btn.setMessage(Text.literal(getO1ButtonText(o1Getter.getAsInt())));
                    endCapture();
                }
            };

            this.allBut1Btn = ButtonWidget.builder(
                    Text.literal(getAB1ButtonText(ab1Getter.getAsInt())),
                    btn -> {
                        startCapture(ab1Handler);
                        btn.setMessage(Text.literal("> Press a key <"));
                    }
            ).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();

            this.only1Btn = ButtonWidget.builder(
                    Text.literal(getO1ButtonText(o1Getter.getAsInt())),
                    btn -> {
                        startCapture(o1Handler);
                        btn.setMessage(Text.literal("> Press a key <"));
                    }
            ).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();

            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);

            int gap = 4;
            int btnW = only1Btn.getWidth();
            only1Btn.setX(x + w - btnW);
            only1Btn.setY(y);
            allBut1Btn.setX(only1Btn.getX() - allBut1Btn.getWidth() - gap);
            allBut1Btn.setY(y);

            allBut1Btn.render(context, mouseX, mouseY, delta);
            only1Btn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(allBut1Btn, only1Btn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(allBut1Btn, only1Btn); }
    }

    // ========== Override single entry (Advanced section: one key button per row) ==========

    private class OverrideSingleEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget keyBtn;
        private final IntSupplier getter;
        private final IntConsumer setter;
        private final CaptureHandler handler;

        OverrideSingleEntry(String label, IntSupplier getter, IntConsumer setter, String tooltip) {
            super(tooltip);
            this.label = label;
            this.getter = getter;
            this.setter = setter;

            int btnW = scaledButtonWidth(65);

            this.handler = new CaptureHandler() {
                @Override
                public void onKeyCapture(int keyCode) {
                    setter.accept(keyCode);
                    keyBtn.setMessage(Text.literal(getOverrideButtonText(keyCode)));
                    endCapture();
                }
                @Override
                public void onCaptureCancel() {
                    keyBtn.setMessage(Text.literal(getOverrideButtonText(getter.getAsInt())));
                    endCapture();
                }
            };

            this.keyBtn = ButtonWidget.builder(
                    Text.literal(getOverrideButtonText(getter.getAsInt())),
                    btn -> {
                        startCapture(handler);
                        btn.setMessage(Text.literal("> Press a key <"));
                    }
            ).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);
            keyBtn.setX(x + w - keyBtn.getWidth());
            keyBtn.setY(y);
            keyBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(keyBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(keyBtn); }
    }

    /**
     * Get button text for override entries: "Global" if -1, key name otherwise.
     */
    private static String getOverrideButtonText(int keyCode) {
        return keyCode == -1 ? "Global" : InvTweaksConfig.getKeyName(keyCode);
    }

    private static String getAB1ButtonText(int keyCode) {
        return keyCode == -1 ? "AB1: Global" : "AB1: " + InvTweaksConfig.getKeyName(keyCode);
    }

    private static String getO1ButtonText(int keyCode) {
        return keyCode == -1 ? "O1: Global" : "O1: " + InvTweaksConfig.getKeyName(keyCode);
    }

    // ========== Action button entry (Debug tab: Copy Debug Log, Report Bug) ==========

    private class ActionButtonEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget actionBtn;
        private final boolean isCopyButton;

        ActionButtonEntry(String label, Runnable action, String tooltip) {
            super(tooltip);
            this.label = label;
            this.isCopyButton = label.equals("Copy Debug Log");

            int btnW = scaledButtonWidth(110);
            this.actionBtn = ButtonWidget.builder(
                    Text.literal(label),
                    btn -> action.run()
            ).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();

            if (isCopyButton && copiedTimestamp > 0) {
                long elapsed = System.currentTimeMillis() - copiedTimestamp;
                if (elapsed < 2000) {
                    actionBtn.setMessage(Text.literal("\u00a7aCopied!"));
                } else {
                    copiedTimestamp = 0;
                    actionBtn.setMessage(Text.literal(label));
                }
            }

            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);
            actionBtn.setX(x + w - actionBtn.getWidth());
            actionBtn.setY(y);
            actionBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(actionBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(actionBtn); }
    }

    // ========== Info text row (non-interactive) ==========

    private class InfoTextEntry extends ConfigEntry {
        private final String text;
        private final int color;

        InfoTextEntry(String text, int color) {
            super();
            this.text = text;
            this.color = color;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            context.drawTextWithShadow(textRenderer, Text.literal(text), getX(), getY() + 6, color);
        }

        @Override
        public List<? extends Element> children() { return List.of(); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(); }
    }

    // ========== Integer value row with +/- buttons and keyboard edit ==========

    private class IntValueEntry extends ConfigEntry {
        private final String label;
        private final int min;
        private final int max;
        private final java.util.function.Supplier<Integer> getter;
        private final java.util.function.Consumer<Integer> setter;
        private final ButtonWidget minusBtn;
        private final ButtonWidget plusBtn;
        private final ButtonWidget valueBtn;

        private boolean editing = false;
        private String editBuffer = "";
        private int originalValue;

        IntValueEntry(String label, int min, int max,
                      java.util.function.Supplier<Integer> getter, java.util.function.Consumer<Integer> setter,
                      String tooltip) {
            super(tooltip);
            this.label = label;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;

            int btnW = 20;
            int valW = 50;

            this.valueBtn = ButtonWidget.builder(Text.literal(String.valueOf(getter.get())), button -> {
                if (editing) {
                    applyEdit();
                } else {
                    startEdit();
                }
            }).dimensions(0, 0, valW, BUTTON_HEIGHT).build();

            this.minusBtn = ButtonWidget.builder(Text.literal("-"), button -> {
                if (editing) cancelEdit();
                int v = Math.max(min, getter.get() - 1);
                setter.accept(v);
                valueBtn.setMessage(Text.literal(String.valueOf(v)));
            }).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();

            this.plusBtn = ButtonWidget.builder(Text.literal("+"), button -> {
                if (editing) cancelEdit();
                int v = Math.min(max, getter.get() + 1);
                setter.accept(v);
                valueBtn.setMessage(Text.literal(String.valueOf(v)));
            }).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        }

        private void startEdit() {
            if (activeEditEntry != null && activeEditEntry != this) {
                activeEditEntry.applyEdit();
            }
            editing = true;
            originalValue = getter.get();
            editBuffer = "";
            activeEditEntry = IntValueEntry.this;
            valueBtn.setMessage(Text.literal("\u00a7e_"));
        }

        void applyEdit() {
            editing = false;
            if (activeEditEntry == this) activeEditEntry = null;
            if (editBuffer.isEmpty()) {
                setter.accept(originalValue);
                valueBtn.setMessage(Text.literal(String.valueOf(originalValue)));
                InvTweaksConfig.debugLog("CONFIG", "int value edit cancelled (empty) | field=%s | restored=%d", label, originalValue);
            } else {
                try {
                    int parsed = Integer.parseInt(editBuffer);
                    int clamped = Math.max(min, Math.min(max, parsed));
                    setter.accept(clamped);
                    valueBtn.setMessage(Text.literal(String.valueOf(clamped)));
                    InvTweaksConfig.debugLog("CONFIG", "int value edited | field=%s | old=%d | new=%d", label, originalValue, clamped);
                } catch (NumberFormatException e) {
                    setter.accept(originalValue);
                    valueBtn.setMessage(Text.literal(String.valueOf(originalValue)));
                }
            }
        }

        private void cancelEdit() {
            editing = false;
            if (activeEditEntry == this) activeEditEntry = null;
            setter.accept(originalValue);
            valueBtn.setMessage(Text.literal(String.valueOf(originalValue)));
            InvTweaksConfig.debugLog("CONFIG", "int value edit cancelled | field=%s | restored=%d", label, originalValue);
        }

        boolean handleKeyPress(int keyCode) {
            if (!editing) return false;
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!editBuffer.isEmpty()) {
                    editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                    updateEditDisplay();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                editBuffer = "";
                updateEditDisplay();
                return true;
            }
            if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
                editBuffer += (char) ('0' + (keyCode - GLFW.GLFW_KEY_0));
                updateEditDisplay();
                return true;
            }
            if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
                editBuffer += (char) ('0' + (keyCode - GLFW.GLFW_KEY_KP_0));
                updateEditDisplay();
                return true;
            }
            return true;
        }

        private void updateEditDisplay() {
            String display = editBuffer.isEmpty() ? "\u00a7e_" : "\u00a7e" + editBuffer + "_";
            valueBtn.setMessage(Text.literal(display));
        }

        boolean isEditing() {
            return editing;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);

            int rightEdge = x + w;
            plusBtn.setX(rightEdge - plusBtn.getWidth());
            plusBtn.setY(y);
            plusBtn.render(context, mouseX, mouseY, delta);

            valueBtn.setX(plusBtn.getX() - valueBtn.getWidth() - 2);
            valueBtn.setY(y);
            valueBtn.render(context, mouseX, mouseY, delta);

            minusBtn.setX(valueBtn.getX() - minusBtn.getWidth() - 2);
            minusBtn.setY(y);
            minusBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(minusBtn, valueBtn, plusBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(minusBtn, valueBtn, plusBtn); }
    }

    // ========== Cycling mode row (for sizeMismatchPasteMode) ==========

    private class CyclingModeEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget cycleBtn;

        CyclingModeEntry(String label, ButtonWidget cycleBtn, String tooltip) {
            super(tooltip);
            this.label = label;
            this.cycleBtn = cycleBtn;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);
            int btnX = x + w - cycleBtn.getWidth();
            cycleBtn.setX(btnX);
            cycleBtn.setY(y);
            cycleBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(cycleBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(cycleBtn); }
    }

    // ========== Key press routing ==========

    @Override
    public boolean keyPressed(KeyInput input) {
        // IntValueEntry edit mode takes priority
        if (activeEditEntry != null && activeEditEntry.isEditing()) {
            if (activeEditEntry.handleKeyPress(input.key())) {
                return true;
            }
        }
        // Key capture mode
        if (activeCaptureHandler != null) {
            int keyCode = input.key();
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                // ESC = set key to "none" (-1) via the capture handler
                activeCaptureHandler.onKeyCapture(-1);
                return true;
            }
            activeCaptureHandler.onKeyCapture(keyCode);
            return true;
        }
        return super.keyPressed(input);
    }
}
