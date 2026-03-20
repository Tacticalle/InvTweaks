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
    private static java.util.Map<Integer, LayoutClipboard.SlotData> cachedInventory = null;

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
                    // Cache inventory snapshot every tick while alive
                    cachedInventory = new java.util.LinkedHashMap<>();
                    net.minecraft.entity.player.PlayerInventory inv = client.player.getInventory();
                    for (int i = 0; i < 36; i++) {
                        net.minecraft.item.ItemStack stack = inv.getStack(i);
                        if (stack.isEmpty()) {
                            cachedInventory.put(i, new LayoutClipboard.SlotData(null, 0));
                        } else {
                            cachedInventory.put(i, new LayoutClipboard.SlotData(stack.getItem(), stack.getCount()));
                        }
                    }
                }

                if (wasAlive && !isAlive && cachedInventory != null) {
                    // Player just died — use the CACHED inventory (last known alive state)
                    InvTweaksConfig.debugLog("DEATH", "Player died, auto-copying cached inventory layout");
                    LayoutClipboard.autoSaveFromCache(cachedInventory);
                    cachedInventory = null;
                }
                wasAlive = isAlive;
            } else {
                wasAlive = true;
                cachedInventory = null;
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
