package tacticalle.invtweaks;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

public class InvTweaksClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");

    // Exposed so InvTweaksConfigScreen can read/display the keybind
    public static KeyBinding openConfigKey;

    @Override
    public void onInitializeClient() {
        // Register keybind to open config screen (default: K)
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.invtweaks.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                new KeyBinding.Category(Identifier.of("category", "invtweaks"))
        ));

        // Check for config screen keybind press each tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new InvTweaksConfigScreen(null));
                }
            }
        });

        LOGGER.info("InvTweaks: Mod initialized - using mixin to intercept clicks");
    }
}
