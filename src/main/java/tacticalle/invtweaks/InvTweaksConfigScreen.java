package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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

    // Key capture state
    private String capturingKey = null;
    private ButtonWidget capturingButton = null;

    // Tab state
    private enum Tab { ALL, TWEAKS, HOTKEYS, DEBUG }
    private Tab activeTab = Tab.ALL;
    private final List<ButtonWidget> tabButtons = new ArrayList<>();

    // Global key buttons (tracked for capture updates)
    private ButtonWidget globalAllBut1KeyBtn;
    private ButtonWidget globalOnly1KeyBtn;
    private ButtonWidget openConfigKeyBtn;

    // Per-tweak key buttons (tracked for capture updates)
    private record TweakKeyButtons(String tweakName, ButtonWidget allBut1Btn, ButtonWidget only1Btn) {}
    private final List<TweakKeyButtons> tweakKeyButtons = new ArrayList<>();

    // Current entry list (for rebuilding on tab switch)
    private ConfigEntryList entryList;

    // Responsive layout calculations
    private int rowWidth;
    private int contentLeft;

    public InvTweaksConfigScreen(Screen parent) {
        super(Text.literal("InvTweaks Configuration"));
        this.parent = parent;
    }

    // ========== Responsive layout helpers ==========

    private void calculateLayout() {
        // Row width: 70% of screen, clamped between 350 and 600
        rowWidth = Math.max(350, Math.min(600, (int)(this.width * 0.7)));
        contentLeft = (this.width - rowWidth) / 2;
    }

    private int scaledButtonWidth(int baseWidth) {
        // Scale buttons proportionally with row width, based on 400px reference
        return Math.max(baseWidth, (int)(baseWidth * (rowWidth / 400.0)));
    }

    // ========== Init ==========

    @Override
    protected void init() {
        super.init();
        config = InvTweaksConfig.get();
        tweakKeyButtons.clear();
        tabButtons.clear();
        calculateLayout();

        int centerX = this.width / 2;

        // ---- Tab bar ----
        int tabCount = Tab.values().length;
        int tabBarWidth = Math.min(rowWidth, this.width - 20);
        int tabGap = 4;
        int totalGaps = (tabCount - 1) * tabGap;
        int tabW = (tabBarWidth - totalGaps) / tabCount;
        int tabBarLeft = (this.width - tabBarWidth) / 2;
        int tabY = 28;

        String[] tabLabels = {"All", "Tweaks", "Hotkeys", "Debug"};
        Tab[] tabValues = Tab.values();
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
            // Active tab gets highlighted text, inactive gets plain
            String label = switch (tabs[i]) {
                case ALL -> "All";
                case TWEAKS -> "Tweaks";
                case HOTKEYS -> "Hotkeys";
                case DEBUG -> "Debug";
            };
            if (active) {
                btn.setMessage(Text.literal("\u00a7n" + label)); // underlined
            } else {
                btn.setMessage(Text.literal(label));
            }
        }
    }

    private void rebuildEntryList() {

        // Remove the old entry list widget entirely
        remove(entryList);
        // Recreate it
        int listTop = 28 + BUTTON_HEIGHT + 6;
        int listBottom = this.height - 32;
        entryList = new ConfigEntryList(this.client, this.width, listBottom - listTop, listTop, ROW_HEIGHT);
        populateEntryList();
        addDrawableChild(entryList);

    }

    // ========== Populate entries based on active tab ==========

    private void populateEntryList() {
        int keyBtnW = scaledButtonWidth(80);
        int toggleW = scaledButtonWidth(40);

        switch (activeTab) {
            case ALL -> {
                addHotkeyEntries(keyBtnW);
                addTweakEntries(toggleW);
                addPerTweakKeyEntries(keyBtnW);
                addDebugEntries(toggleW);
            }
            case TWEAKS -> {
                addTweakEntries(toggleW);
            }
            case HOTKEYS -> {
                addHotkeyEntries(keyBtnW);
                addPerTweakKeyEntries(keyBtnW);
            }
            case DEBUG -> {
                addDebugEntries(toggleW);
            }
        }
    }

    // ---- Hotkey section: global keys + open config key ----

    private void addHotkeyEntries(int keyBtnW) {
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Global Modifier Keys ---"));

        globalAllBut1KeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.allBut1Key)),
                button -> startCapture("global_allbut1", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("All But 1 key:", "(take/move all but 1)", YELLOW, globalAllBut1KeyBtn));

        globalOnly1KeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.only1Key)),
                button -> startCapture("global_only1", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Only 1 key:", "(take/move exactly 1)", AQUA, globalOnly1KeyBtn));

        // Open Config keybind row
        KeyBinding configKey = InvTweaksClient.openConfigKey;
        String configKeyName = configKey != null ? configKey.getBoundKeyLocalizedText().getString() : "?";
        openConfigKeyBtn = ButtonWidget.builder(
                Text.literal(configKeyName),
                button -> startCapture("openconfig", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Open Config key:", "(open this screen)", GRAY, openConfigKeyBtn));

        // ---- Throwing Hotkeys ----
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Throwing ---"));

        ButtonWidget throwAB1KeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.throwAllBut1Key)),
                button -> startCapture("throwAllBut1Key", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Throw All But 1:", "(modifier + Q)", YELLOW, throwAB1KeyBtn));

        ButtonWidget throwHalfKeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.throwHalfKey)),
                button -> startCapture("throwHalfKey", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Throw Half:", "(modifier + Q)", AQUA, throwHalfKeyBtn));

        // ---- Fill Existing ----
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Fill Existing Stacks ---"));

        ButtonWidget fillExistingKeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.fillExistingKey)),
                button -> startCapture("fillExistingKey", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Fill Existing:", "(modifier + shift-click)", GREEN, fillExistingKeyBtn));

        // ---- Scroll Transfer ----
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Scroll Transfer ---"));

        ButtonWidget scrollModKeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.scrollLeave1Key)),
                button -> startCapture("scrollModifierKey", button)
        ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Scroll Leave-1:", "(hold + scroll)", AQUA, scrollModKeyBtn));

        // ---- Copy/Paste Layout ----
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Copy/Paste Layout ---"));
        entryList.addConfigEntry(new InfoTextEntry("  Copy: Ctrl+C in container GUI", GREEN));
        entryList.addConfigEntry(new InfoTextEntry("  Paste: Ctrl+V in container GUI", GREEN));
        entryList.addConfigEntry(new InfoTextEntry("  (Cmd+C/V also works on macOS)", DARK_GRAY));
        entryList.addConfigEntry(new InfoTextEntry("  History: Shift+Tab in container GUI", GREEN));
    }

    // ---- Per-tweak key overrides ----

    private void addPerTweakKeyEntries(int keyBtnW) {
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Per-Tweak Key Overrides ---"));

        addPerTweakKeyRow("clickPickup", "Click Pickup", keyBtnW);
        addPerTweakKeyRow("shiftClick", "Shift+Click Transfer", keyBtnW);
        addPerTweakKeyRow("bundleExtract", "Bundle Extract", keyBtnW);
        addPerTweakKeyRow("bundleInsertBundle", "Bundle Insert (cursor bundle)", keyBtnW);
        addPerTweakKeyRow("bundleInsertItems", "Bundle Insert (cursor items)", keyBtnW);
        addPerTweakKeyRow("hotbarModifiers", "Hotbar Button Modifiers", keyBtnW);
    }

    private void addPerTweakKeyRow(String tweakName, String displayName, int keyBtnW) {
        boolean isCustom = config.hasCustomKeys(tweakName);
        int toggleW = scaledButtonWidth(55);

        // Global/Custom toggle button
        ButtonWidget modeToggle = ButtonWidget.builder(
                Text.literal(isCustom ? "\u00a7eCustom" : "Global"),
                button -> {
                    if (config.hasCustomKeys(tweakName)) {
                        config.resetToGlobal(tweakName);
                    } else {
                        config.initCustomFromGlobal(tweakName);
                    }
                    rebuildEntryList();
                }
        ).dimensions(0, 0, toggleW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new TweakKeyModeEntry(displayName, modeToggle));

        if (isCustom) {
            // Show the two custom key buttons
            ButtonWidget ab1Btn = ButtonWidget.builder(
                    Text.literal(InvTweaksConfig.getKeyName(config.getEffectiveAllBut1Key(tweakName))),
                    button -> startCapture("tweak_ab1_" + tweakName, button)
            ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();

            ButtonWidget o1Btn = ButtonWidget.builder(
                    Text.literal(InvTweaksConfig.getKeyName(config.getEffectiveOnly1Key(tweakName))),
                    button -> startCapture("tweak_o1_" + tweakName, button)
            ).dimensions(0, 0, keyBtnW, BUTTON_HEIGHT).build();

            tweakKeyButtons.add(new TweakKeyButtons(tweakName, ab1Btn, o1Btn));
            entryList.addConfigEntry(new TweakKeyPairEntry("  All But 1:", "  Only 1:", YELLOW, AQUA, ab1Btn, o1Btn));
        } else {
            entryList.addConfigEntry(new InfoTextEntry("  Using global keys (" +
                    InvTweaksConfig.getKeyName(config.allBut1Key) + " / " +
                    InvTweaksConfig.getKeyName(config.only1Key) + ")", DARK_GRAY));
        }
    }

    // ---- Tweak toggle section ----

    private void addTweakEntries(int toggleW) {
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Feature Toggles ---"));

        entryList.addConfigEntry(new FeatureEntry("Click Pickup", toggleW,
                () -> config.enableClickPickup, v -> config.enableClickPickup = v));
        entryList.addConfigEntry(new FeatureEntry("Shift+Click Transfer", toggleW,
                () -> config.enableShiftClickTransfer, v -> config.enableShiftClickTransfer = v));
        entryList.addConfigEntry(new FeatureEntry("Bundle Extract", toggleW,
                () -> config.enableBundleExtract, v -> config.enableBundleExtract = v));
        entryList.addConfigEntry(new FeatureEntry("Bundle Insert (picking up with bundle)", toggleW,
                () -> config.enableBundleInsertCursorBundle, v -> config.enableBundleInsertCursorBundle = v));
        entryList.addConfigEntry(new FeatureEntry("Bundle Insert (placing into bundle)", toggleW,
                () -> config.enableBundleInsertCursorItems, v -> config.enableBundleInsertCursorItems = v));
        entryList.addConfigEntry(new FeatureEntry("Throw Half", toggleW,
                () -> config.enableThrowHalf, v -> config.enableThrowHalf = v));
        entryList.addConfigEntry(new FeatureEntry("Hotbar Button Modifiers", toggleW,
                () -> config.enableHotbarModifiers, v -> config.enableHotbarModifiers = v));
        entryList.addConfigEntry(new FeatureEntry("Fill Existing Stacks", toggleW,
                () -> config.enableFillExisting, v -> config.enableFillExisting = v));
        entryList.addConfigEntry(new FeatureEntry("Scroll Transfer", toggleW,
                () -> config.enableScrollTransfer, v -> config.enableScrollTransfer = v));
        entryList.addConfigEntry(new FeatureEntry("Copy/Paste Layout", toggleW,
                () -> config.enableCopyPaste, v -> config.enableCopyPaste = v));

        // Clipboard history settings
        entryList.addConfigEntry(new SectionHeaderEntry("\u00a7l--- Clipboard History ---"));
        entryList.addConfigEntry(new IntValueEntry("Max Clipboard History", 5, 200,
                () -> config.clipboardMaxHistory, v -> config.clipboardMaxHistory = v));
        entryList.addConfigEntry(new IntValueEntry("Auto-delete after (hrs playtime)", 0, 500,
                () -> config.clipboardExpiryPlaytimeHours, v -> config.clipboardExpiryPlaytimeHours = v));
        entryList.addConfigEntry(new InfoTextEntry("  (0 = never expire)", DARK_GRAY));
    }

    // ---- Debug section ----

    private void addDebugEntries(int toggleW) {
        entryList.addConfigEntry(new DebugEntry("Debug Logging", toggleW,
                () -> config.enableDebugLogging, v -> config.enableDebugLogging = v));
    }

    // ========== Key capture ==========

    private void startCapture(String captureId, ButtonWidget button) {
        capturingKey = captureId;
        capturingButton = button;
        button.setMessage(Text.literal("> Press a key <"));
    }

    private void applyCapture(int keyCode) {
        if (capturingKey == null) return;

        if (capturingKey.equals("global_allbut1")) {
            config.allBut1Key = keyCode;
            globalAllBut1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
        } else if (capturingKey.equals("global_only1")) {
            config.only1Key = keyCode;
            globalOnly1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
        } else if (capturingKey.equals("openconfig")) {
            KeyBinding configKey = InvTweaksClient.openConfigKey;
            if (configKey != null) {
                configKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
                KeyBinding.updateKeysByCode();
            }
            openConfigKeyBtn.setMessage(Text.literal(
                    configKey != null ? configKey.getBoundKeyLocalizedText().getString() : InvTweaksConfig.getKeyName(keyCode)));
        } else if (capturingKey.equals("fillExistingKey")) {
            config.fillExistingKey = keyCode;
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            }
        } else if (capturingKey.equals("scrollModifierKey")) {
            config.scrollLeave1Key = keyCode;
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            }
        } else if (capturingKey.equals("throwAllBut1Key")) {
            config.throwAllBut1Key = keyCode;
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            }
        } else if (capturingKey.equals("throwHalfKey")) {
            config.throwHalfKey = keyCode;
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            }
        } else if (capturingKey.startsWith("tweak_ab1_")) {
            String tweakName = capturingKey.substring("tweak_ab1_".length());
            config.setPerTweakAllBut1Key(tweakName, keyCode);
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            }
        } else if (capturingKey.startsWith("tweak_o1_")) {
            String tweakName = capturingKey.substring("tweak_o1_".length());
            config.setPerTweakOnly1Key(tweakName, keyCode);
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            }
        }

        capturingKey = null;
        capturingButton = null;
    }

    private void cancelCapture() {
        if (capturingKey == null) return;

        // Restore button text to current value
        if (capturingKey.equals("global_allbut1")) {
            globalAllBut1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.allBut1Key)));
        } else if (capturingKey.equals("global_only1")) {
            globalOnly1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.only1Key)));
        } else if (capturingKey.equals("openconfig")) {
            KeyBinding configKey = InvTweaksClient.openConfigKey;
            openConfigKeyBtn.setMessage(Text.literal(
                    configKey != null ? configKey.getBoundKeyLocalizedText().getString() : "?"));
        } else if (capturingKey.equals("fillExistingKey")) {
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.fillExistingKey)));
            }
        } else if (capturingKey.equals("scrollModifierKey")) {
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.scrollLeave1Key)));
            }
        } else if (capturingKey.equals("throwAllBut1Key")) {
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.throwAllBut1Key)));
            }
        } else if (capturingKey.equals("throwHalfKey")) {
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.throwHalfKey)));
            }
        } else if (capturingKey.startsWith("tweak_ab1_") || capturingKey.startsWith("tweak_o1_")) {
            // Restore from config
            String tweakName;
            int currentKey;
            if (capturingKey.startsWith("tweak_ab1_")) {
                tweakName = capturingKey.substring("tweak_ab1_".length());
                currentKey = config.getEffectiveAllBut1Key(tweakName);
            } else {
                tweakName = capturingKey.substring("tweak_o1_".length());
                currentKey = config.getEffectiveOnly1Key(tweakName);
            }
            if (capturingButton != null) {
                capturingButton.setMessage(Text.literal(InvTweaksConfig.getKeyName(currentKey)));
            }
        }

        capturingKey = null;
        capturingButton = null;
    }

    // ========== Mouse click handling for IntValueEntry edit mode ==========

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        double mouseX = click.x();
        double mouseY = click.y();
        // If an IntValueEntry is being edited, clicking anywhere outside the value button applies the edit
        if (activeEditEntry != null && activeEditEntry.isEditing()) {
            // Check if the click was on the value button itself (handled by the button's own click handler)
            ButtonWidget valBtn = activeEditEntry.valueBtn;
            if (mouseX >= valBtn.getX() && mouseX < valBtn.getX() + valBtn.getWidth()
                    && mouseY >= valBtn.getY() && mouseY < valBtn.getY() + valBtn.getHeight()) {
                // Let the button handle it (it will toggle edit mode)
            } else {
                // Clicked elsewhere: apply edit
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

    // ========== Entry base ==========

    private abstract static class ConfigEntry extends ElementListWidget.Entry<ConfigEntry> {
    }

    // ========== Section header (divider text) ==========

    private class SectionHeaderEntry extends ConfigEntry {
        private final String text;

        SectionHeaderEntry(String text) {
            this.text = text;
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

    // ========== Key binding row ==========

    private class KeyBindEntry extends ConfigEntry {
        private final String label;
        private final String desc;
        private final int labelColor;
        private final ButtonWidget button;

        KeyBindEntry(String label, String desc, int labelColor, ButtonWidget button) {
            this.label = label;
            this.desc = desc;
            this.labelColor = labelColor;
            this.button = button;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, labelColor);
            int descX = x + textRenderer.getWidth(label) + 6;
            // Only draw desc if it fits before the button
            int btnLeft = x + w - button.getWidth();
            if (descX + textRenderer.getWidth(desc) < btnLeft - 4) {
                context.drawTextWithShadow(textRenderer, Text.literal(desc), descX, y + 6, DARK_GRAY);
            }
            button.setX(btnLeft);
            button.setY(y);
            button.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(button); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(button); }
    }

    // ========== Column header (Enabled / Keys) ==========

    private class HeaderEntry extends ConfigEntry {
        private final String col1;
        private final String col2;

        HeaderEntry(String col1, String col2) {
            this.col1 = col1;
            this.col2 = col2;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            // Position columns relative to row width
            int toggleX = x + (int)(w * 0.55);
            int flipX = x + (int)(w * 0.70);
            context.drawTextWithShadow(textRenderer, Text.literal(col1), toggleX - 2, y + 8, GRAY);
            context.drawTextWithShadow(textRenderer, Text.literal(col2), flipX + 10, y + 8, GRAY);
        }

        @Override
        public List<? extends Element> children() { return List.of(); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(); }
    }

    // ========== Feature toggle row ==========

    private class FeatureEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget toggleBtn;

        FeatureEntry(String label, int toggleW,
                     Supplier<Boolean> enabledGet, Consumer<Boolean> enabledSet) {
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

    // ========== Debug toggle row ==========

    private class DebugEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget toggleBtn;

        DebugEntry(String label, int toggleW, Supplier<Boolean> get, Consumer<Boolean> set) {
            this.label = label;
            this.toggleBtn = ButtonWidget.builder(
                    Text.literal(get.get() ? "\u00a7aON" : "\u00a7cOFF"),
                    button -> {
                        set.accept(!get.get());
                        button.setMessage(Text.literal(get.get() ? "\u00a7aON" : "\u00a7cOFF"));
                    }).dimensions(0, 0, toggleW, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, GRAY);
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

    // ========== Per-tweak key mode toggle row (Global / Custom) ==========

    private class TweakKeyModeEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget modeBtn;

        TweakKeyModeEntry(String label, ButtonWidget modeBtn) {
            this.label = label;
            this.modeBtn = modeBtn;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);
            modeBtn.setX(x + w - modeBtn.getWidth());
            modeBtn.setY(y);
            modeBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(modeBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(modeBtn); }
    }

    // ========== Per-tweak key pair row (two key buttons on one row) ==========

    private class TweakKeyPairEntry extends ConfigEntry {
        private final String label1;
        private final String label2;
        private final int color1;
        private final int color2;
        private final ButtonWidget btn1;
        private final ButtonWidget btn2;

        TweakKeyPairEntry(String label1, String label2, int color1, int color2,
                          ButtonWidget btn1, ButtonWidget btn2) {
            this.label1 = label1;
            this.label2 = label2;
            this.color1 = color1;
            this.color2 = color2;
            this.btn1 = btn1;
            this.btn2 = btn2;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            // Split the row: left half for allBut1, right half for only1
            int halfW = w / 2;
            // Label + button for allBut1
            context.drawTextWithShadow(textRenderer, Text.literal(label1), x, y + 6, color1);
            int label1W = textRenderer.getWidth(label1);
            btn1.setX(x + label1W + 6);
            btn1.setY(y);
            btn1.render(context, mouseX, mouseY, delta);
            // Label + button for only1
            int rightX = x + halfW + 10;
            context.drawTextWithShadow(textRenderer, Text.literal(label2), rightX, y + 6, color2);
            int label2W = textRenderer.getWidth(label2);
            btn2.setX(rightX + label2W + 6);
            btn2.setY(y);
            btn2.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() { return List.of(btn1, btn2); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(btn1, btn2); }
    }

    // ========== Info text row (non-interactive) ==========

    private class InfoTextEntry extends ConfigEntry {
        private final String text;
        private final int color;

        InfoTextEntry(String text, int color) {
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

    /** Tracks which IntValueEntry is currently in edit mode (only one at a time). */
    private IntValueEntry activeEditEntry = null;

    private class IntValueEntry extends ConfigEntry {
        private final String label;
        private final int min;
        private final int max;
        private final java.util.function.Supplier<Integer> getter;
        private final java.util.function.Consumer<Integer> setter;
        private final ButtonWidget minusBtn;
        private final ButtonWidget plusBtn;
        private final ButtonWidget valueBtn;

        // Edit mode state
        private boolean editing = false;
        private String editBuffer = "";
        private int originalValue;

        IntValueEntry(String label, int min, int max,
                      java.util.function.Supplier<Integer> getter, java.util.function.Consumer<Integer> setter) {
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
            // Close any other active edit first
            if (activeEditEntry != null && activeEditEntry != this) {
                activeEditEntry.applyEdit();
            }
            editing = true;
            originalValue = getter.get();
            editBuffer = "";
            activeEditEntry = IntValueEntry.this;
            valueBtn.setMessage(Text.literal("\u00a7e_")); // yellow cursor
        }

        private void applyEdit() {
            editing = false;
            if (activeEditEntry == this) activeEditEntry = null;
            if (editBuffer.isEmpty()) {
                // Empty input: restore original
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
            // Digit keys (top row: 48-57, numpad: 320-329)
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
            // Ignore all other keys while editing
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

            // If editing, check if user clicked outside this entry to apply
            if (editing && !hovered) {
                // We can't easily detect "clicked elsewhere" in render, so this is handled via keyPressed
            }
        }

        @Override
        public List<? extends Element> children() { return List.of(minusBtn, valueBtn, plusBtn); }
        @Override
        public List<? extends Selectable> selectableChildren() { return List.of(minusBtn, valueBtn, plusBtn); }
    }

    // Override keyPressed to route to active IntValueEntry edit mode
    @Override
    public boolean keyPressed(KeyInput input) {
        // IntValueEntry edit mode takes priority over key capture
        if (activeEditEntry != null && activeEditEntry.isEditing()) {
            if (activeEditEntry.handleKeyPress(input.key())) {
                return true;
            }
        }
        if (capturingKey != null) {
            int keyCode = input.key();
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                // ESC = set key to "none" (-1)
                applyCapture(-1);
                return true;
            }
            applyCapture(keyCode);
            return true;
        }
        return super.keyPressed(input);
    }
}
