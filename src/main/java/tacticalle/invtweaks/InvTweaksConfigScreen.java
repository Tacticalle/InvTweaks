package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
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

    private String capturingKey = null;
    private ButtonWidget allBut1KeyBtn;
    private ButtonWidget only1KeyBtn;
    private ButtonWidget openConfigKeyBtn;

    public InvTweaksConfigScreen(Screen parent) {
        super(Text.literal("InvTweaks Configuration"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        config = InvTweaksConfig.get();

        int listTop = 32;
        int listBottom = this.height - 32;
        int centerX = this.width / 2;

        ConfigEntryList entryList = new ConfigEntryList(this.client, this.width, listBottom - listTop, listTop, ROW_HEIGHT);

        int btnW = 80;
        int toggleW = 40;
        int flipW = 55;

        // Key assignment rows
        allBut1KeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.allBut1Key)),
                button -> {
                    capturingKey = "allbut1";
                    button.setMessage(Text.literal("> Press a key <"));
                }).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("All But 1 key:", "(take/move all but 1)", YELLOW, allBut1KeyBtn));

        only1KeyBtn = ButtonWidget.builder(
                Text.literal(InvTweaksConfig.getKeyName(config.only1Key)),
                button -> {
                    capturingKey = "only1";
                    button.setMessage(Text.literal("> Press a key <"));
                }).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        entryList.addConfigEntry(new KeyBindEntry("Only 1 key:", "(take/move exactly 1)", AQUA, only1KeyBtn));

        // Open Config keybind row — reads from the Fabric KeyBinding directly
        KeyBinding configKey = InvTweaksClient.openConfigKey;
        String configKeyName = configKey != null
                ? configKey.getBoundKeyLocalizedText().getString()
                : "?";
        openConfigKeyBtn = ButtonWidget.builder(
                Text.literal(configKeyName),
                button -> {
                    capturingKey = "openconfig";
                    button.setMessage(Text.literal("> Press a key <"));
                }).dimensions(0, 0, btnW, BUTTON_HEIGHT).build();
        // Section header
        entryList.addConfigEntry(new HeaderEntry("Enabled", "Keys"));

        // Feature rows
        entryList.addConfigEntry(new FeatureEntry("Click Pickup", toggleW, flipW,
                () -> config.enableClickPickup, v -> config.enableClickPickup = v,
                () -> config.flipClickPickup, v -> config.flipClickPickup = v));
        entryList.addConfigEntry(new FeatureEntry("Shift+Click Transfer", toggleW, flipW,
                () -> config.enableShiftClickTransfer, v -> config.enableShiftClickTransfer = v,
                () -> config.flipShiftClickTransfer, v -> config.flipShiftClickTransfer = v));
        entryList.addConfigEntry(new FeatureEntry("Bundle Extract", toggleW, flipW,
                () -> config.enableBundleExtract, v -> config.enableBundleExtract = v,
                () -> config.flipBundleExtract, v -> config.flipBundleExtract = v));
        entryList.addConfigEntry(new FeatureEntry("Bundle Insert (picking up with bundle)", toggleW, flipW,
                () -> config.enableBundleInsertCursorBundle, v -> config.enableBundleInsertCursorBundle = v,
                () -> config.flipBundleInsertCursorBundle, v -> config.flipBundleInsertCursorBundle = v));
        entryList.addConfigEntry(new FeatureEntry("Bundle Insert (placing into bundle)", toggleW, flipW,
                () -> config.enableBundleInsertCursorItems, v -> config.enableBundleInsertCursorItems = v,
                () -> config.flipBundleInsertCursorItems, v -> config.flipBundleInsertCursorItems = v));

        entryList.addConfigEntry(new KeyBindEntry("Open Config key:", "(open this screen)", GRAY, openConfigKeyBtn));
        // Debug logging
        entryList.addConfigEntry(new DebugEntry("Debug Logging", toggleW,
                () -> config.enableDebugLogging, v -> config.enableDebugLogging = v));

        addDrawableChild(entryList);

        // Done button (fixed at bottom)
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            config.save();
            if (client != null) client.setScreen(parent);
        }).dimensions(centerX - 50, this.height - 27, 100, BUTTON_HEIGHT).build());
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (capturingKey != null) {
            int keyCode = input.key();
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelCapture();
                return true;
            }
            if (capturingKey.equals("allbut1")) {
                config.allBut1Key = keyCode;
                allBut1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            } else if (capturingKey.equals("only1")) {
                config.only1Key = keyCode;
                only1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(keyCode)));
            } else if (capturingKey.equals("openconfig")) {
                // Update the Fabric KeyBinding directly
                KeyBinding configKey = InvTweaksClient.openConfigKey;
                if (configKey != null) {
                    configKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
                    KeyBinding.updateKeysByCode();
                }
                openConfigKeyBtn.setMessage(Text.literal(
                        configKey != null ? configKey.getBoundKeyLocalizedText().getString() : InvTweaksConfig.getKeyName(keyCode)));
            }
            capturingKey = null;
            return true;
        }
        return super.keyPressed(input);
    }

    private void cancelCapture() {
        if (capturingKey != null) {
            if (capturingKey.equals("allbut1")) {
                allBut1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.allBut1Key)));
            } else if (capturingKey.equals("only1")) {
                only1KeyBtn.setMessage(Text.literal(InvTweaksConfig.getKeyName(config.only1Key)));
            } else if (capturingKey.equals("openconfig")) {
                KeyBinding configKey = InvTweaksClient.openConfigKey;
                openConfigKeyBtn.setMessage(Text.literal(
                        configKey != null ? configKey.getBoundKeyLocalizedText().getString() : "?"));
            }
            capturingKey = null;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, WHITE);
    }

    @Override
    public void close() {
        config.save();
        if (client != null) client.setScreen(parent);
    }

    // ========== Scrollable list ==========

    private class ConfigEntryList extends ElementListWidget<ConfigEntry> {
        public ConfigEntryList(MinecraftClient client, int width, int height, int y, int itemHeight) {
            super(client, width, height, y, itemHeight);
        }

        // Use a distinct name to avoid overriding the int-returning addEntry
        public void addConfigEntry(ConfigEntry entry) {
            super.addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, this.width - 40);
        }

        @Override
        protected int getScrollbarX() {
            return this.width / 2 + 210;
        }
    }

    // ========== Entry base ==========

    private abstract static class ConfigEntry extends ElementListWidget.Entry<ConfigEntry> {
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
            context.drawTextWithShadow(textRenderer, Text.literal(desc), descX, y + 6, DARK_GRAY);
            button.setX(x + w - button.getWidth());
            button.setY(y);
            button.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(button);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(button);
        }
    }

    // ========== Column header ==========

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
            int toggleX = x + w / 2 + 10;
            int flipX = x + w / 2 + 56;
            context.drawTextWithShadow(textRenderer, Text.literal(col1), toggleX - 2, y + 8, GRAY);
            context.drawTextWithShadow(textRenderer, Text.literal(col2), flipX + 10, y + 8, GRAY);
        }

        @Override
        public List<? extends Element> children() {
            return List.of();
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of();
        }
    }

    // ========== Feature toggle row ==========

    private class FeatureEntry extends ConfigEntry {
        private final String label;
        private final ButtonWidget toggleBtn;
        private final ButtonWidget flipBtn;

        FeatureEntry(String label, int toggleW, int flipW,
                     Supplier<Boolean> enabledGet, Consumer<Boolean> enabledSet,
                     Supplier<Boolean> flipGet, Consumer<Boolean> flipSet) {
            this.label = label;
            this.toggleBtn = ButtonWidget.builder(
                    Text.literal(enabledGet.get() ? "\u00a7aON" : "\u00a7cOFF"),
                    button -> {
                        enabledSet.accept(!enabledGet.get());
                        button.setMessage(Text.literal(enabledGet.get() ? "\u00a7aON" : "\u00a7cOFF"));
                    }).dimensions(0, 0, toggleW, BUTTON_HEIGHT).build();
            this.flipBtn = ButtonWidget.builder(
                    Text.literal(flipGet.get() ? "\u00a7eFlipped" : "Default"),
                    button -> {
                        flipSet.accept(!flipGet.get());
                        button.setMessage(Text.literal(flipGet.get() ? "\u00a7eFlipped" : "Default"));
                    }).dimensions(0, 0, flipW, BUTTON_HEIGHT).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            context.drawTextWithShadow(textRenderer, Text.literal(label), x, y + 6, WHITE);
            int toggleX = x + w / 2 + 10;
            int flipX = x + w / 2 + 56;
            toggleBtn.setX(toggleX);
            toggleBtn.setY(y);
            toggleBtn.render(context, mouseX, mouseY, delta);
            flipBtn.setX(flipX);
            flipBtn.setY(y);
            flipBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(toggleBtn, flipBtn);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(toggleBtn, flipBtn);
        }
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
            int toggleX = x + w / 2 + 10;
            toggleBtn.setX(toggleX);
            toggleBtn.setY(y);
            toggleBtn.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(toggleBtn);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(toggleBtn);
        }
    }
}
