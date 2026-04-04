package tacticalle.invtweaks;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.ResourceLocation;

public class InvTweaksClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");

    // Exposed so InvTweaksConfigScreen can read/display the keybind
    public static KeyMapping openConfigKey;

    // Config key debounce (for the raw GLFW check fallback)
    private static boolean configKeyWasDown = false;

    // Death detection state
    private static boolean wasAlive = true;
    private static java.util.Map<Integer, net.minecraft.world.item.ItemStack> cachedInventoryStacks = new java.util.LinkedHashMap<>();
    private static boolean cachedInventoryValid = false;

    @Override
    public void onInitializeClient() {
        // Register keybind to open config screen (default: K)
        openConfigKey = KeyMappingHelper.registerKeyBinding(new KeyMapping(
                "key.invtweaks.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                new KeyMapping.Category(ResourceLocation.of("category", "invtweaks"))
        ));

        // Load clipboard history from disk
        ClipboardStorage.load();

        // Check for config screen keybind press each tick + death detection
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Drain Fabric KeyMapping queue (kept registered for Mod Menu integration) but don't act on it
            while (openConfigKey.consumeClick()) { /* discard */ }
            // Only use the GLFW-based check via the config field value
            InvTweaksConfig cfg = InvTweaksConfig.get();
            boolean configKeyPressed = false;
            if (cfg.openConfigKey != -1) {
                long windowHandle = client.getWindow().getHandle();
                boolean isDown = org.lwjgl.glfw.GLFW.glfwGetKey(windowHandle, cfg.openConfigKey) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (isDown && !configKeyWasDown) {
                    configKeyPressed = true;
                }
                configKeyWasDown = isDown;
            } else {
                configKeyWasDown = false;
            }
            if (configKeyPressed && client.screen == null) {
                client.setScreen(new InvTweaksConfigScreen(null));
            }

            // Death detection: cache inventory while alive, use cache on death
            if (client.player != null) {
                boolean isAlive = client.player.isAlive();

                if (isAlive) {
                    // Cache inventory ItemStack copies every tick while alive
                    cachedInventoryStacks.clear();
                    net.minecraft.world.entity.player.Inventory inv = client.player.getInventory();
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
                    cachedInventoryValid = true;
                }

                if (wasAlive && !isAlive && cachedInventoryValid) {
                    // Player just died — convert cached stacks to SlotData with component serialization
                    InvTweaksConfig.debugLog("DEATH", "Player died, auto-copying cached inventory layout");
                    java.util.Map<Integer, LayoutClipboard.SlotData> slotDataMap = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<Integer, net.minecraft.world.item.ItemStack> entry : cachedInventoryStacks.entrySet()) {
                        int invSlot = entry.getKey();
                        net.minecraft.world.item.ItemStack stack = entry.getValue();
                        // Fix armor key mapping: Inventory slot 36(FEET)→snapshot 39,
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
                    cachedInventoryValid = false;
                }
                wasAlive = isAlive;
            } else {
                wasAlive = true;
                cachedInventoryValid = false;
            }
        });

        // Register overlay rendering for all AbstractContainerScreen subclasses (including InventoryScreen)
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?> handledScreen) {
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, tickDelta) -> {
                    if (HalfSelectorOverlay.isActive()) {
                        HalfSelectorOverlay.render(context, handledScreen, scaledWidth, scaledHeight);
                    }
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
