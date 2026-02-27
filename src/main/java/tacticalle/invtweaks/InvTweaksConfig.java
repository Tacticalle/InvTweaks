package tacticalle.invtweaks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class InvTweaksConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("invtweaks.json");

    private static InvTweaksConfig INSTANCE;

    // Modifier key GLFW codes
    // "All But 1" key: action takes/moves all but 1 (default: Left Ctrl)
    public int allBut1Key = GLFW.GLFW_KEY_LEFT_CONTROL;
    // "Only 1" key: action takes/moves exactly 1 (default: Left Alt)
    public int only1Key = GLFW.GLFW_KEY_LEFT_ALT;

    // Feature toggles
    public boolean enableClickPickup = true;
    public boolean enableShiftClickTransfer = true;
    public boolean enableBundleExtract = true;
    public boolean enableBundleInsertCursorBundle = true;
    public boolean enableBundleInsertCursorItems = true;

    // Per-feature flip: when true, the modifier keys swap behavior for this feature.
    // e.g., if flipped, the "All But 1" key does "Only 1" for this feature and vice versa.
    public boolean flipClickPickup = false;
    public boolean flipShiftClickTransfer = false;
    public boolean flipBundleExtract = false;
    public boolean flipBundleInsertCursorBundle = false;
    public boolean flipBundleInsertCursorItems = false;

    // Debug logging: when enabled, logs detailed info about every modifier-key action to the game log.
    public boolean enableDebugLogging = false;

    public static InvTweaksConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static InvTweaksConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                INSTANCE = GSON.fromJson(json, InvTweaksConfig.class);
                if (INSTANCE == null) {
                    INSTANCE = new InvTweaksConfig();
                }
                LOGGER.info("InvTweaks: Config loaded");
                return INSTANCE;
            } catch (Exception e) {
                LOGGER.error("InvTweaks: Failed to load config, using defaults", e);
            }
        }
        INSTANCE = new InvTweaksConfig();
        INSTANCE.save();
        return INSTANCE;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.error("InvTweaks: Failed to save config", e);
        }
    }

    /**
     * Check if a specific GLFW key is pressed (checks both left and right variants
     * for modifier keys like Ctrl, Alt, Shift).
     */
    public static boolean isKeyPressed(int glfwKey) {
        long windowHandle = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
        // Check exact key
        if (GLFW.glfwGetKey(windowHandle, glfwKey) == GLFW.GLFW_PRESS) return true;
        // Also check the paired modifier key (left/right)
        int pair = getPairedKey(glfwKey);
        if (pair != -1 && GLFW.glfwGetKey(windowHandle, pair) == GLFW.GLFW_PRESS) return true;
        return false;
    }

    /**
     * Get the paired left/right variant of a modifier key.
     */
    public static int getPairedKey(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_CONTROL -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT -> GLFW.GLFW_KEY_RIGHT_ALT;
            case GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_KEY_LEFT_ALT;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case GLFW.GLFW_KEY_LEFT_SUPER -> GLFW.GLFW_KEY_RIGHT_SUPER;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> GLFW.GLFW_KEY_LEFT_SUPER;
            default -> -1;
        };
    }

    /**
     * Get a human-readable name for a GLFW key code.
     */
    public static String getKeyName(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "CTRL";
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "ALT";
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "SHIFT";
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> "SUPER";
            default -> {
                String name = GLFW.glfwGetKeyName(glfwKey, 0);
                yield name != null ? name.toUpperCase() : "KEY " + glfwKey;
            }
        };
    }

    /**
     * Determine which mode is active for a feature, considering the flip state.
     * Returns: "allbut1", "only1", or null if neither modifier is pressed.
     */
    public String getActiveMode(boolean flipped) {
        boolean allBut1Pressed = isKeyPressed(allBut1Key);
        boolean only1Pressed = isKeyPressed(only1Key);

        if (allBut1Pressed && !only1Pressed) {
            return flipped ? "only1" : "allbut1";
        } else if (only1Pressed && !allBut1Pressed) {
            return flipped ? "allbut1" : "only1";
        }
        return null; // neither or both pressed
    }
}
