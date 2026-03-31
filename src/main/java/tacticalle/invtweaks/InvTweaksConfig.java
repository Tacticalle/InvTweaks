package tacticalle.invtweaks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class InvTweaksConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("invtweaks.json");

    private static InvTweaksConfig INSTANCE;

    /**
     * Check if the current OS is macOS.
     */
    private static boolean isMacOS() {
        return Util.getOperatingSystem() == Util.OperatingSystem.OSX;
    }

    // Global modifier key GLFW codes (OS-specific defaults)
    // Mac: Cmd (L.Super), Windows/Linux: L.Alt
    public int allBut1Key = isMacOS() ? GLFW.GLFW_KEY_LEFT_SUPER : GLFW.GLFW_KEY_LEFT_ALT;
    // All platforms: L.Ctrl
    public int only1Key = GLFW.GLFW_KEY_LEFT_CONTROL;
    // Mac: L.Alt/Option, Windows/Linux: R.Alt
    public int miscModifierKey = isMacOS() ? GLFW.GLFW_KEY_LEFT_ALT : GLFW.GLFW_KEY_RIGHT_ALT;

    // Bundle modifier keys (shared pair for all bundle operations)
    // -1 = inherit from allBut1Key / only1Key
    public int bundleAllBut1Key = -1;
    public int bundleOnly1Key = -1;

    // Feature toggles
    public boolean enableClickPickup = true;
    public boolean enableShiftClickTransfer = true;
    public boolean enableBundleExtract = true;
    public boolean enableBundleInsertCursorBundle = true;
    public boolean enableBundleInsertCursorItems = true;
    public boolean enableThrowHalf = true;
    public boolean enableThrowAllBut1 = true;
    // Single-key tweak overrides (-1 = inherit from parent global)
    public int throwAllBut1Key = -1;
    public int throwHalfKey = -1;
    public int fillExistingKey = -1;
    public int scrollLeave1Key = -1;
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
    public int clipboardMaxHistory = 50;

    // Open config key (default: K)
    public int openConfigKey = GLFW.GLFW_KEY_K;

    // Clipboard keybinds (-1 = legacy defaults)
    public int clipboardHistoryKey = -1;
    public int copyLayoutKey = -1;
    public int pasteLayoutKey = -1;
    public int cutLayoutKey = -1;
    public int undoKey = -1;

    // Size-mismatch paste mode: 0=Hover Position, 1=Menu Selection, 2=Arrow Keys
    public int sizeMismatchPasteMode = 1;

    // Debug logging
    public boolean enableDebugLogging = false;

    // ========== Debug ring buffer (static, not serialized) ==========

    private static final int DEBUG_BUFFER_SIZE = 200;
    private static final String[] debugBuffer = new String[DEBUG_BUFFER_SIZE];
    private static int debugBufferIndex = 0;
    private static int debugBufferCount = 0;
    private static final DateTimeFormatter DEBUG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ========== Key inheritance helpers ==========

    /**
     * Returns the effective key code for a single-key tweak, resolving inheritance.
     * If tweakKey is -1, returns the parentGlobal key.
     */
    public int getEffectiveSingleKey(int tweakKey, int parentGlobal) {
        return tweakKey == -1 ? parentGlobal : tweakKey;
    }

    /**
     * Returns the effective key code for a bundle operation, resolving two-level inheritance.
     * per-operation override -> bundle global -> top-level global
     */
    public int getEffectiveBundleKey(int perOperationKey, int bundleGlobal, int globalKey) {
        if (perOperationKey != -1) return perOperationKey;
        if (bundleGlobal != -1) return bundleGlobal;
        return globalKey;
    }

    // ========== Per-tweak effective key helpers ==========

    /**
     * Get the effective "All But 1" key for a given tweak.
     * For bundle operations: per-operation -> bundleAllBut1Key -> allBut1Key
     * For other tweaks: per-tweak -> allBut1Key
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
        if (tweakName.equals("bundleExtract") || tweakName.equals("bundleInsertBundle") || tweakName.equals("bundleInsertItems")) {
            return getEffectiveBundleKey(perTweak, bundleAllBut1Key, allBut1Key);
        }
        return perTweak != -1 ? perTweak : allBut1Key;
    }

    /**
     * Get the effective "Only 1" key for a given tweak.
     * For bundle operations: per-operation -> bundleOnly1Key -> only1Key
     * For other tweaks: per-tweak -> only1Key
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
        if (tweakName.equals("bundleExtract") || tweakName.equals("bundleInsertBundle") || tweakName.equals("bundleInsertItems")) {
            return getEffectiveBundleKey(perTweak, bundleOnly1Key, only1Key);
        }
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
     * Check if the throw half key is pressed (resolves inheritance).
     */
    public boolean isThrowHalfKeyPressed() {
        return isKeyPressed(getEffectiveSingleKey(throwHalfKey, miscModifierKey));
    }

    /**
     * Check if the fill-existing key is pressed (resolves inheritance).
     */
    public boolean isFillExistingKeyPressed() {
        return isKeyPressed(getEffectiveSingleKey(fillExistingKey, miscModifierKey));
    }

    /**
     * Check if the throw-all-but-1 key is pressed (resolves inheritance).
     */
    public boolean isThrowAllBut1KeyPressed() {
        return isKeyPressed(getEffectiveSingleKey(throwAllBut1Key, allBut1Key));
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
     * @deprecated Use getActiveMode(String) for per-tweak key support.
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
     * Checks global keys, misc modifier, bundle keys, and per-tweak custom keys.
     */
    public boolean isAnyModifierPressed() {
        if (isKeyPressed(allBut1Key) || isKeyPressed(only1Key)) return true;
        if (isKeyPressed(miscModifierKey)) return true;
        if (bundleAllBut1Key != -1 && isKeyPressed(bundleAllBut1Key)) return true;
        if (bundleOnly1Key != -1 && isKeyPressed(bundleOnly1Key)) return true;
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
     * Check if the "fill existing stacks" modifier is active (resolves inheritance).
     */
    public boolean isFillExistingActive() {
        return isKeyPressed(getEffectiveSingleKey(fillExistingKey, miscModifierKey));
    }

    /**
     * Determine the scroll transfer mode based on modifier keys.
     * Returns: "flush" (move all), "leave1" (leave 1 behind).
     * Resolves scrollLeave1Key inheritance from allBut1Key.
     */
    public String getScrollTransferMode() {
        int effectiveKey = getEffectiveSingleKey(scrollLeave1Key, allBut1Key);
        boolean leave1Pressed = isKeyPressed(effectiveKey);
        if (leave1Pressed) return "leave1";
        return "flush";
    }

    // ========== Debug logging with ring buffer ==========

    /**
     * Debug logging helper with per-tweak tags.
     * Only logs when enableDebugLogging is true.
     * Also writes to the ring buffer for later retrieval.
     * Usage: InvTweaksConfig.debugLog("SHIFT", "allbut1 mode | slot=%d | count=%d", slotId, count);
     */
    public static void debugLog(String tag, String message, Object... args) {
        InvTweaksConfig cfg = get();
        if (cfg == null || !cfg.enableDebugLogging) return;
        String formatted = args.length > 0 ? String.format(message, args) : message;
        String logLine = "[IT:" + tag + "] " + formatted;
        LOGGER.info("[IT:{}] {}", tag, formatted);
        String timestamped = LocalDateTime.now().format(DEBUG_TIME_FMT) + " " + logLine;
        synchronized (debugBuffer) {
            debugBuffer[debugBufferIndex] = timestamped;
            debugBufferIndex = (debugBufferIndex + 1) % DEBUG_BUFFER_SIZE;
            if (debugBufferCount < DEBUG_BUFFER_SIZE) debugBufferCount++;
        }
    }

    /**
     * Get the contents of the debug ring buffer as a single string (oldest to newest).
     * Returns "No debug log entries" if the buffer is empty.
     */
    public static String getDebugLogContents() {
        synchronized (debugBuffer) {
            if (debugBufferCount == 0) return "No debug log entries";
            StringBuilder sb = new StringBuilder();
            int start;
            if (debugBufferCount < DEBUG_BUFFER_SIZE) {
                start = 0;
            } else {
                start = debugBufferIndex;
            }
            for (int i = 0; i < debugBufferCount; i++) {
                int idx = (start + i) % DEBUG_BUFFER_SIZE;
                if (i > 0) sb.append('\n');
                sb.append(debugBuffer[idx]);
            }
            return sb.toString();
        }
    }
}
