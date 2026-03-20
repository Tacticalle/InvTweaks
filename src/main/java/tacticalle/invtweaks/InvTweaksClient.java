package tacticalle.invtweaks;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

public class InvTweaksClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");

    // Exposed so InvTweaksConfigScreen can read/display the keybind
    public static KeyBinding openConfigKey;

    // Death detection state
    private static boolean wasAlive = true;
    private static java.util.Map<Integer, net.minecraft.item.ItemStack> cachedInventoryStacks = null;

    @Override
    public void onInitializeClient() {
        // Register keybind to open config screen (default: K)
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.invtweaks.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                new KeyBinding.Category(Identifier.of("category", "invtweaks"))
        ));

        // Load clipboard history from disk
        ClipboardStorage.load();

        // Check for config screen keybind press each tick + death detection
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new InvTweaksConfigScreen(null));
                }
            }

            // Death detection: cache inventory while alive, use cache on death
            if (client.player != null) {
                boolean isAlive = client.player.isAlive();

                if (isAlive) {
                    // Cache inventory ItemStack copies every tick while alive
                    cachedInventoryStacks = new java.util.LinkedHashMap<>();
                    net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
                    // Main inventory (keys 0-35) and hotbar
                    for (int i = 0; i < 36; i++) {
                        cachedInventoryStacks.put(i, inv.getStack(i).copy());
                    }
                    // Armor: inv slots 36-39
                    for (int i = 36; i <= 39; i++) {
                        cachedInventoryStacks.put(i, inv.getStack(i).copy());
                    }
                    // Offhand: inv slot 40
                    cachedInventoryStacks.put(40, inv.getStack(40).copy());
                }

                if (wasAlive && !isAlive && cachedInventoryStacks != null) {
                    // Player just died — convert cached stacks to SlotData with component serialization
                    InvTweaksConfig.debugLog("DEATH", "Player died, auto-copying cached inventory layout");
                    java.util.Map<Integer, LayoutClipboard.SlotData> slotDataMap = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<Integer, net.minecraft.item.ItemStack> entry : cachedInventoryStacks.entrySet()) {
                        int invSlot = entry.getKey();
                        net.minecraft.item.ItemStack stack = entry.getValue();
                        // Fix armor key mapping: PlayerInventory slot 36(FEET)→snapshot 39,
                        // 37(LEGS)→38, 38(CHEST)→37, 39(HEAD)→36
                        // This matches copyLayout where handler slot 5(HEAD)→key 36, 8(FEET)→key 39
                        int snapshotKey = invSlot;
                        if (invSlot >= 36 && invSlot <= 39) {
                            snapshotKey = 75 - invSlot;
                        }
                        if (stack.isEmpty()) {
                            slotDataMap.put(snapshotKey, new LayoutClipboard.SlotData(null, 0, null));
                        } else {
                            String components = LayoutClipboard.serializeComponents(stack);
                            slotDataMap.put(snapshotKey, new LayoutClipboard.SlotData(stack.getItem(), stack.getCount(), components));
                        }
                    }
                    LayoutClipboard.autoSaveFromCache(slotDataMap);
                    cachedInventoryStacks = null;
                }
                wasAlive = isAlive;
            } else {
                wasAlive = true;
                cachedInventoryStacks = null;
            }
        });

        // Register overlay rendering for all HandledScreen subclasses (including InventoryScreen)
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof HandledScreen<?> handledScreen) {
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, tickDelta) -> {
                    InvTweaksOverlay.render(context, handledScreen, scaledWidth, scaledHeight);
                });
            }
        });

        // Save clipboard history on game shutdown
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ClipboardStorage.save();
        });

        LOGGER.info("InvTweaks: Mod initialized - using mixin to intercept clicks");
    }
}
