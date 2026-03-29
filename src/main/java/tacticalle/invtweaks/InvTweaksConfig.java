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

    // Global modifier key GLFW codes (defaults)
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
    public boolean enableThrowHalf = true;
    // Throwing modifier keys
    public int throwAllBut1Key = GLFW.GLFW_KEY_LEFT_CONTROL;
    public int throwHalfKey = GLFW.GLFW_KEY_LEFT_ALT;
    public int fillExistingKey = GLFW.GLFW_KEY_LEFT_ALT;
    public int scrollLeave1Key = GLFW.GLFW_KEY_LEFT_CONTROL;
    public boolean enableHotbarModifiers = true;
    public boolean enableFillExisting = true;
    // Copy/paste layout
    public boolean enableCopyPaste = true;
    // Scroll transfer
    public boolean enableScrollTransfer = true;

    // Per-tweak modifier key overrides (-1 means "use global default")
    public int clickPickupAllBut1Key = -1;
    public int clickPickupOnly1Key = -1;
    public int shiftClickAllBut1Key = -1;
    public int shiftClickOnly1Key = -1;
    public int bundleExtractAllBut1Key = -1;
    public int bundleExtractOnly1Key = -1;
    public int bundleInsertBundleAllBut1Key = -1;
    public int bundleInsertBundleOnly1Key = -1;
    public int bundleInsertItemsAllBut1Key = -1;
    public int bundleInsertItemsOnly1Key = -1;
    public int hotbarModifiersAllBut1Key = -1;
    public int hotbarModifiersOnly1Key = -1;

    // Overlay display
    public boolean showOverlayMessages = true;

    // Clipboard history
    public int clipboardMaxHistory = 50;              // max entries to keep (range 5-200)
    public int clipboardExpiryPlaytimeHours = 0;      // 0 = never expire, otherwise hours of in-game playtime

    // Clipboard keybinds (-1 = legacy defaults)
    public int clipboardHistoryKey = -1;
    public int copyLayoutKey = -1;
    public int pasteLayoutKey = -1;
    public int cutLayoutKey = -1;

    // Size-mismatch paste mode: 0=Hover Position, 1=Menu Selection, 2=Arrow Keys
    public int sizeMismatchPasteMode = 1;

    // Debug logging
    public boolean enableDebugLogging = false;

    // ========== Per-tweak effective key helpers ==========

    /**
     * Get the effective "All But 1" key for a given tweak.
     * Returns the per-tweak override if set (not -1), otherwise the global key.
     */
    public int getEffectiveAllBut1Key(String tweakName) {
        int perTweak = switch (tweakName) {
            case "clickPickup" -> clickPickupAllBut1Key;
            case "shiftClick" -> shiftClickAllBut1Key;
            case "bundleExtract" -> bundleExtractAllBut1Key;
            case "bundleInsertBundle" -> bundleInsertBundleAllBut1Key;
            case "bundleInsertItems" -> bundleInsertItemsAllBut1Key;
            case "hotbarModifiers" -> hotbarModifiersAllBut1Key;
            default -> -1;
        };
        return perTweak != -1 ? perTweak : allBut1Key;
    }

    /**
     * Get the effective "Only 1" key for a given tweak.
     * Returns the per-tweak override if set (not -1), otherwise the global key.
     */
    public int getEffectiveOnly1Key(String tweakName) {
        int perTweak = switch (tweakName) {
            case "clickPickup" -> clickPickupOnly1Key;
            case "shiftClick" -> shiftClickOnly1Key;
            case "bundleExtract" -> bundleExtractOnly1Key;
            case "bundleInsertBundle" -> bundleInsertBundleOnly1Key;
            case "bundleInsertItems" -> bundleInsertItemsOnly1Key;
            case "hotbarModifiers" -> hotbarModifiersOnly1Key;
            default -> -1;
        };
        return perTweak != -1 ? perTweak : only1Key;
    }

    /**
     * Check if a tweak is using custom (non-global) keys.
     */
    public boolean hasCustomKeys(String tweakName) {
        return switch (tweakName) {
            case "clickPickup" -> clickPickupAllBut1Key != -1 || clickPickupOnly1Key != -1;
            case "shiftClick" -> shiftClickAllBut1Key != -1 || shiftClickOnly1Key != -1;
            case "bundleExtract" -> bundleExtractAllBut1Key != -1 || bundleExtractOnly1Key != -1;
            case "bundleInsertBundle" -> bundleInsertBundleAllBut1Key != -1 || bundleInsertBundleOnly1Key != -1;
            case "bundleInsertItems" -> bundleInsertItemsAllBut1Key != -1 || bundleInsertItemsOnly1Key != -1;
            case "hotbarModifiers" -> hotbarModifiersAllBut1Key != -1 || hotbarModifiersOnly1Key != -1;
            default -> false;
        };
    }

    /**
     * Set the per-tweak override keys. Pass -1 to reset to global.
     */
    public void setPerTweakAllBut1Key(String tweakName, int keyCode) {
        switch (tweakName) {
            case "clickPickup" -> clickPickupAllBut1Key = keyCode;
            case "shiftClick" -> shiftClickAllBut1Key = keyCode;
            case "bundleExtract" -> bundleExtractAllBut1Key = keyCode;
            case "bundleInsertBundle" -> bundleInsertBundleAllBut1Key = keyCode;
            case "bundleInsertItems" -> bundleInsertItemsAllBut1Key = keyCode;
            case "hotbarModifiers" -> hotbarModifiersAllBut1Key = keyCode;
        }
    }

    public void setPerTweakOnly1Key(String tweakName, int keyCode) {
        switch (tweakName) {
            case "clickPickup" -> clickPickupOnly1Key = keyCode;
            case "shiftClick" -> shiftClickOnly1Key = keyCode;
            case "bundleExtract" -> bundleExtractOnly1Key = keyCode;
            case "bundleInsertBundle" -> bundleInsertBundleOnly1Key = keyCode;
            case "bundleInsertItems" -> bundleInsertItemsOnly1Key = keyCode;
            case "hotbarModifiers" -> hotbarModifiersOnly1Key = keyCode;
        }
    }

    /**
     * Reset a tweak to use global keys.
     */
    public void resetToGlobal(String tweakName) {
        setPerTweakAllBut1Key(tweakName, -1);
        setPerTweakOnly1Key(tweakName, -1);
    }

    /**
     * Initialize a tweak's custom keys from the current global values.
     */
    public void initCustomFromGlobal(String tweakName) {
        setPerTweakAllBut1Key(tweakName, allBut1Key);
        setPerTweakOnly1Key(tweakName, only1Key);
    }

    // ========== Singleton & persistence ==========

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
        if (GLFW.glfwGetKey(windowHandle, glfwKey) == GLFW.GLFW_PRESS) return true;
        int pair = getPairedKey(glfwKey);
        if (pair != -1 && GLFW.glfwGetKey(windowHandle, pair) == GLFW.GLFW_PRESS) return true;
        return false;
    }

    /**
     * Check if the throw half key is pressed.
     */
    public boolean isThrowHalfKeyPressed() {
        return isKeyPressed(throwHalfKey);
    }

    /**
     * Check if the throw-all-but-1 key is pressed.
     */
    public boolean isFillExistingKeyPressed() {
        return isKeyPressed(fillExistingKey);
    }

    public boolean isThrowAllBut1KeyPressed() {
        return isKeyPressed(throwAllBut1Key);
    }

    /**
     * Check if the copy layout custom key is pressed.
     */
    public boolean isCopyLayoutKeyPressed() {
        return copyLayoutKey != -1 && isKeyPressed(copyLayoutKey);
    }

    /**
     * Check if the paste layout custom key is pressed.
     */
    public boolean isPasteLayoutKeyPressed() {
        return pasteLayoutKey != -1 && isKeyPressed(pasteLayoutKey);
    }

    /**
     * Check if the cut layout custom key is pressed.
     */
    public boolean isCutLayoutKeyPressed() {
        return cutLayoutKey != -1 && isKeyPressed(cutLayoutKey);
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
        if (glfwKey == -1) return "NONE";
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
     * Determine which mode is active for a feature using GLOBAL keys, 
     * Returns: "allbut1", "only1", or null if neither modifier is pressed.
     * @deprecated Use getActiveMode(boolean, String) for per-tweak key support.
     */
    @Deprecated
    public String getActiveMode() {
        boolean allBut1Pressed = isKeyPressed(allBut1Key);
        boolean only1Pressed = isKeyPressed(only1Key);

        if (allBut1Pressed && !only1Pressed) {
            return "allbut1";
        } else if (only1Pressed && !allBut1Pressed) {
            return "only1";
        }
        return null;
    }

    /**
     * Determine which mode is active for a feature using per-tweak effective keys.
     * Returns: "allbut1", "only1", or null if neither modifier is pressed.
     */
    public String getActiveMode(String tweakName) {
        int effectiveAllBut1 = getEffectiveAllBut1Key(tweakName);
        int effectiveOnly1 = getEffectiveOnly1Key(tweakName);

        boolean allBut1Pressed = isKeyPressed(effectiveAllBut1);
        boolean only1Pressed = isKeyPressed(effectiveOnly1);

        if (allBut1Pressed && !only1Pressed) {
            return "allbut1";
        } else if (only1Pressed && !allBut1Pressed) {
            return "only1";
        }
        return null;
    }

    /**
     * Check if ANY modifier key (across all tweaks) is currently pressed.
     * Used for the global PICKUP_ALL block in the mixin HEAD.
     */
    public boolean isAnyModifierPressed() {
        if (isKeyPressed(allBut1Key) || isKeyPressed(only1Key)) return true;
        String[] tweaks = {"clickPickup", "shiftClick", "bundleExtract", "bundleInsertBundle", "bundleInsertItems", "hotbarModifiers"};
        for (String tweak : tweaks) {
            if (hasCustomKeys(tweak)) {
                int ab1 = getEffectiveAllBut1Key(tweak);
                int o1 = getEffectiveOnly1Key(tweak);
                if (isKeyPressed(ab1) || isKeyPressed(o1)) return true;
            }
        }
        return false;
    }

    /**
     * Check if the "fill existing stacks" combo is active.
     * This fires when BOTH the allBut1 and only1 effective keys for "fillExisting"
     * (or the shiftClick tweak keys, if fillExisting has no custom keys) are pressed.
     * Uses fillExisting per-tweak keys if set, otherwise falls back to global keys.
     */
    public boolean isFillExistingActive() {
        return isKeyPressed(fillExistingKey);
    }

    /**
     * Determine the scroll transfer mode based on modifier keys.
     * Returns: "flush" (move all), "leave1" (leave 1 behind).
     *
     * Bare scroll always triggers flush mode (when scroll transfer is enabled).
     *   - No modifier held → "flush"
     *   - Leave-1 modifier held → "leave1"
     */
    public String getScrollTransferMode() {
        boolean leave1Pressed = isKeyPressed(scrollLeave1Key);
        if (leave1Pressed) return "leave1";
        return "flush";
    }

    /**
     * Debug logging helper with per-tweak tags.
     * Only logs when enableDebugLogging is true.
     * Usage: InvTweaksConfig.debugLog("SHIFT", "allbut1 mode | slot=%d | count=%d", slotId, count);
     */
    public static void debugLog(String tag, String message, Object... args) {
        InvTweaksConfig cfg = get();
        if (cfg == null || !cfg.enableDebugLogging) return;
        String formatted = args.length > 0 ? String.format(message, args) : message;
        LOGGER.info("[IT:{}] {}", tag, formatted);
    }
}
