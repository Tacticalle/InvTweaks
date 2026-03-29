package tacticalle.invtweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.BundleItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import tacticalle.invtweaks.ContainerClassifier;

/**
 * Clipboard-based copy/paste of container layouts with history support.
 * Maintains a history ring of past clipboard snapshots with persistent storage.
 */
public class LayoutClipboard {

    /** Component strings that have already failed reconstruction — log once, then suppress. */
    private static final Set<String> failedComponentStrings = new HashSet<>();

    /**
     * A snapshot of one slot's contents.
     * The components field stores serialized NBT component data (null for simple items or old entries).
     */
    public record SlotData(Item item, int count, String components) {
        /** Sentinel value representing a locked crafter slot. */
        public static final SlotData LOCKED = new SlotData(null, 0, "LOCKED");

        /** Check if this SlotData represents a locked crafter slot. */
        public static boolean isLocked(SlotData sd) {
            return sd != null && sd.item() == null && "LOCKED".equals(sd.components());
        }
    }

    /**
     * A snapshot of a container layout.
     */
    public static class LayoutSnapshot {
        public final int slotCount;
        public final Map<Integer, SlotData> slots; // slot index -> contents (null item = empty)
        public final boolean isPlayerInventory;

        public LayoutSnapshot(int slotCount, Map<Integer, SlotData> slots, boolean isPlayerInventory) {
            this.slotCount = slotCount;
            this.slots = slots;
            this.isPlayerInventory = isPlayerInventory;
        }
    }

    /**
     * A history entry wrapping a snapshot with metadata.
     */
    public static class HistoryEntry {
        public final LayoutSnapshot snapshot;
        public final String label;           // e.g. "Chest (27 slots)" or "Player Inventory (36 slots)"
        public final long timestamp;         // System.currentTimeMillis() for display purposes
        public final long playtimeMinutes;   // in-game playtime at time of copy (for auto-delete)
        public String containerTitle;        // e.g. "Chest", "Ender Chest", null for player inventory
        public String entryType;             // "container", "player", or "bundle"
        public int bundleColor = -1;         // RGB dye color for bundles (-1 = no custom color / use default)

        public HistoryEntry(LayoutSnapshot snapshot, String label, long timestamp, long playtimeMinutes) {
            this(snapshot, label, timestamp, playtimeMinutes, null);
        }

        public HistoryEntry(LayoutSnapshot snapshot, String label, long timestamp, long playtimeMinutes, String containerTitle) {
            this(snapshot, label, timestamp, playtimeMinutes, containerTitle, snapshot.isPlayerInventory ? TYPE_PLAYER : TYPE_CONTAINER);
        }

        public HistoryEntry(LayoutSnapshot snapshot, String label, long timestamp, long playtimeMinutes, String containerTitle, String entryType) {
            this.snapshot = snapshot;
            this.label = label;
            this.timestamp = timestamp;
            this.playtimeMinutes = playtimeMinutes;
            this.containerTitle = containerTitle;
            this.entryType = entryType;
        }

        public boolean isBundle() {
            return TYPE_BUNDLE.equals(entryType);
        }
    }

    // Entry type constants
    public static final String TYPE_CONTAINER = "container";
    public static final String TYPE_PLAYER = "player";
    public static final String TYPE_BUNDLE = "bundle";
    public static final String TYPE_GRID9 = "grid9";
    public static final String TYPE_HOPPER5 = "hopper5";
    public static final String TYPE_FURNACE2 = "furnace2";

    // History ring
    private static final List<HistoryEntry> history = new ArrayList<>();
    private static int activeContainerIndex = -1;
    private static int activePlayerIndex = -1;
    private static int activeBundleIndex = -1;
    private static int activeGrid9Index = -1;
    private static int activeHopper5Index = -1;
    private static int activeFurnace2Index = -1;

    // ========== HISTORY ACCESS ==========

    public static List<HistoryEntry> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public static int getActiveContainerIndex() {
        return activeContainerIndex;
    }

    public static int getActivePlayerIndex() {
        return activePlayerIndex;
    }

    public static void setActiveContainerIndex(int index) {
        if (index >= -1 && index < history.size()) {
            activeContainerIndex = index;
        }
    }

    public static void setActivePlayerIndex(int index) {
        if (index >= -1 && index < history.size()) {
            activePlayerIndex = index;
        }
    }

    public static int getActiveBundleIndex() {
        return activeBundleIndex;
    }

    public static void setActiveBundleIndex(int index) {
        if (index >= -1 && index < history.size()) {
            activeBundleIndex = index;
        }
    }

    public static int getActiveGrid9Index() {
        return activeGrid9Index;
    }

    public static void setActiveGrid9Index(int index) {
        if (index >= -1 && index < history.size()) {
            activeGrid9Index = index;
        }
    }

    public static int getActiveHopper5Index() {
        return activeHopper5Index;
    }

    public static void setActiveHopper5Index(int index) {
        if (index >= -1 && index < history.size()) {
            activeHopper5Index = index;
        }
    }

    public static int getActiveFurnace2Index() {
        return activeFurnace2Index;
    }

    public static void setActiveFurnace2Index(int index) {
        if (index >= -1 && index < history.size()) {
            activeFurnace2Index = index;
        }
    }

    /**
     * Set the active index for the given history entry (auto-detects type).
     */
    public static void setActiveIndex(int index) {
        if (index < 0 || index >= history.size()) return;
        HistoryEntry entry = history.get(index);
        if (entry.isBundle()) {
            activeBundleIndex = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: bundle=%d label=%s", index, entry.label);
        } else if (entry.snapshot.isPlayerInventory) {
            activePlayerIndex = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: player=%d label=%s", index, entry.label);
        } else if (TYPE_GRID9.equals(entry.entryType)) {
            activeGrid9Index = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: grid9=%d label=%s", index, entry.label);
        } else if (TYPE_HOPPER5.equals(entry.entryType)) {
            activeHopper5Index = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: hopper5=%d label=%s", index, entry.label);
        } else if (TYPE_FURNACE2.equals(entry.entryType)) {
            activeFurnace2Index = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: furnace2=%d label=%s", index, entry.label);
        } else {
            activeContainerIndex = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: container=%d label=%s", index, entry.label);
        }
        ClipboardStorage.save();
    }

    public static void removeHistoryEntry(int index) {
        if (index < 0 || index >= history.size()) return;
        history.remove(index);
        // Adjust active indices
        if (activeContainerIndex == index) {
            activeContainerIndex = -1;
        } else if (activeContainerIndex > index) {
            activeContainerIndex--;
        }
        if (activePlayerIndex == index) {
            activePlayerIndex = -1;
        } else if (activePlayerIndex > index) {
            activePlayerIndex--;
        }
        if (activeBundleIndex == index) {
            activeBundleIndex = -1;
        } else if (activeBundleIndex > index) {
            activeBundleIndex--;
        }
        if (activeGrid9Index == index) {
            activeGrid9Index = -1;
        } else if (activeGrid9Index > index) {
            activeGrid9Index--;
        }
        if (activeHopper5Index == index) {
            activeHopper5Index = -1;
        } else if (activeHopper5Index > index) {
            activeHopper5Index--;
        }
        if (activeFurnace2Index == index) {
            activeFurnace2Index = -1;
        } else if (activeFurnace2Index > index) {
            activeFurnace2Index--;
        }
        ClipboardStorage.save();
    }

    public static void clearHistory() {
        history.clear();
        activeContainerIndex = -1;
        activePlayerIndex = -1;
        activeBundleIndex = -1;
        activeGrid9Index = -1;
        activeHopper5Index = -1;
        activeFurnace2Index = -1;
        ClipboardStorage.save();
    }

    /**
     * Add an entry to the front of the history list and prune if needed.
     */
    public static void addToHistory(HistoryEntry entry) {
        history.add(0, entry);

        // Shift active indices since we inserted at 0
        if (activeContainerIndex >= 0) activeContainerIndex++;
        if (activePlayerIndex >= 0) activePlayerIndex++;
        if (activeBundleIndex >= 0) activeBundleIndex++;
        if (activeGrid9Index >= 0) activeGrid9Index++;
        if (activeHopper5Index >= 0) activeHopper5Index++;
        if (activeFurnace2Index >= 0) activeFurnace2Index++;

        // Set the new entry as active for its type
        if (entry.isBundle()) {
            activeBundleIndex = 0;
        } else if (entry.snapshot.isPlayerInventory) {
            activePlayerIndex = 0;
        } else if (TYPE_GRID9.equals(entry.entryType)) {
            activeGrid9Index = 0;
        } else if (TYPE_HOPPER5.equals(entry.entryType)) {
            activeHopper5Index = 0;
        } else if (TYPE_FURNACE2.equals(entry.entryType)) {
            activeFurnace2Index = 0;
        } else {
            activeContainerIndex = 0;
        }

        // Prune excess entries
        int maxHistory = InvTweaksConfig.get().clipboardMaxHistory;
        while (history.size() > maxHistory) {
            int removeIdx = history.size() - 1;
            if (activeContainerIndex == removeIdx) activeContainerIndex = -1;
            if (activePlayerIndex == removeIdx) activePlayerIndex = -1;
            if (activeBundleIndex == removeIdx) activeBundleIndex = -1;
            if (activeGrid9Index == removeIdx) activeGrid9Index = -1;
            if (activeHopper5Index == removeIdx) activeHopper5Index = -1;
            if (activeFurnace2Index == removeIdx) activeFurnace2Index = -1;
            history.remove(removeIdx);
        }
    }

    /**
     * Get the current in-game playtime in minutes.
     */
    public static long getCurrentPlaytimeMinutes() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return 0;
        try {
            int ticks = mc.player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME));
            return ticks / (20L * 60L);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Prune expired entries based on playtime config.
     */
    public static void pruneExpired() {
        InvTweaksConfig config = InvTweaksConfig.get();
        if (config.clipboardExpiryPlaytimeHours <= 0) return;

        long currentPlaytime = getCurrentPlaytimeMinutes();
        long expiryMinutes = config.clipboardExpiryPlaytimeHours * 60L;

        Iterator<HistoryEntry> it = history.iterator();
        int index = 0;
        while (it.hasNext()) {
            HistoryEntry entry = it.next();
            if ((currentPlaytime - entry.playtimeMinutes) > expiryMinutes) {
                it.remove();
                // Adjust active indices
                if (activeContainerIndex == index) activeContainerIndex = -1;
                else if (activeContainerIndex > index) activeContainerIndex--;
                if (activePlayerIndex == index) activePlayerIndex = -1;
                else if (activePlayerIndex > index) activePlayerIndex--;
                if (activeBundleIndex == index) activeBundleIndex = -1;
                else if (activeBundleIndex > index) activeBundleIndex--;
                if (activeGrid9Index == index) activeGrid9Index = -1;
                else if (activeGrid9Index > index) activeGrid9Index--;
                if (activeHopper5Index == index) activeHopper5Index = -1;
                else if (activeHopper5Index > index) activeHopper5Index--;
                if (activeFurnace2Index == index) activeFurnace2Index = -1;
                else if (activeFurnace2Index > index) activeFurnace2Index--;
                // Don't increment index since we removed
            } else {
                index++;
            }
        }
    }

    /**
     * Get the screen title for label generation.
     */
    private static String getScreenLabel(int slotCount, boolean isPlayerOnly) {
        if (isPlayerOnly) {
            return "Player Inventory";
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof HandledScreen<?> hs) {
            Text title = hs.getTitle();
            String titleStr = title.getString();
            if (titleStr != null && !titleStr.isEmpty()) {
                return titleStr;
            }
        }
        return "Container";
    }

    // Used by ClipboardStorage for deserialization
    public static void loadFromStorage(List<HistoryEntry> entries, int containerIdx, int playerIdx) {
        loadFromStorage(entries, containerIdx, playerIdx, -1);
    }

    public static void loadFromStorage(List<HistoryEntry> entries, int containerIdx, int playerIdx, int bundleIdx) {
        history.clear();
        history.addAll(entries);
        activeContainerIndex = containerIdx;
        activePlayerIndex = playerIdx;
        // Backwards compat: validate activeBundleIndex — if index 0 is not a bundle entry, treat as "none"
        if (bundleIdx >= 0 && bundleIdx < entries.size() && entries.get(bundleIdx).isBundle()) {
            activeBundleIndex = bundleIdx;
        } else if (bundleIdx == 0 && (entries.isEmpty() || !entries.get(0).isBundle())) {
            activeBundleIndex = -1;
        } else {
            activeBundleIndex = bundleIdx;
        }
    }

    public static void loadFromStorage(List<HistoryEntry> entries, int containerIdx, int playerIdx, int bundleIdx,
                                        int grid9Idx, int hopper5Idx, int furnace2Idx) {
        history.clear();
        history.addAll(entries);
        activeContainerIndex = containerIdx;
        activePlayerIndex = playerIdx;
        // Backwards compat: validate activeBundleIndex
        if (bundleIdx >= 0 && bundleIdx < entries.size() && entries.get(bundleIdx).isBundle()) {
            activeBundleIndex = bundleIdx;
        } else if (bundleIdx == 0 && (entries.isEmpty() || !entries.get(0).isBundle())) {
            activeBundleIndex = -1;
        } else {
            activeBundleIndex = bundleIdx;
        }
        activeGrid9Index = grid9Idx;
        activeHopper5Index = hopper5Idx;
        activeFurnace2Index = furnace2Idx;
    }

    // ========== COMPONENT SERIALIZATION ==========

    /**
     * Serialize an ItemStack's component data to a string, if it has non-default components.
     * Returns null if the stack is empty or has only default components.
     *
     * ⚠️ UNCERTAINTY: The exact method names for CODEC encoding may vary in Yarn 1.21.11.
     * If this doesn't compile, try the fallback chain described in batch 10a.1 notes.
     */
    public static String serializeComponents(ItemStack stack) {
        if (stack.isEmpty()) return null;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return null;
            RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, mc.world.getRegistryManager());
            var result = ItemStack.CODEC.encodeStart(ops, stack);
            NbtElement nbt = result.result().orElse(null);
            if (nbt instanceof NbtCompound compound) {
                if (compound.contains("components")) {
                    String comp = compound.get("components").toString();
                    return comp;
                }
            }
        } catch (Exception e) {
            InvTweaksConfig.debugLog("CLIPBOARD", "Failed to serialize components: %s", e.getMessage());
        }
        return "";
    }

    /**
     * Reconstruct a full ItemStack from item, count, and serialized components.
     * Falls back to a plain ItemStack if reconstruction fails.
     */
    public static ItemStack reconstructStack(Item item, int count, String components) {
        if (item == null) return ItemStack.EMPTY;
        if (components == null) return new ItemStack(item, count);
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return new ItemStack(item, count);
            String itemId = Registries.ITEM.getId(item).toString();
            String nbtStr = "{id:\"" + itemId + "\",count:" + count + ",components:" + components + "}";
            NbtElement nbt = NbtHelper.fromNbtProviderString(nbtStr);
            RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, mc.world.getRegistryManager());
            var result = ItemStack.CODEC.parse(ops, nbt);
            ItemStack reconstructed = result.result().orElse(null);
            if (reconstructed != null && !reconstructed.isEmpty()) {
                return reconstructed;
            }
        } catch (Exception e) {
            if (failedComponentStrings.add(components)) {
                InvTweaksConfig.debugLog("CLIPBOARD", "Failed to reconstruct stack (will not log again for this entry): %s", e.getMessage());
            }
        }
        return new ItemStack(item, count);
    }

    // ========== DEDUPLICATION ==========

    /**
     * Get the container title from the currently open screen.
     * Returns null for player inventory screens.
     */
    private static String getContainerTitle(boolean isPlayerOnly) {
        if (isPlayerOnly) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof HandledScreen<?> hs) {
            Text title = hs.getTitle();
            if (title != null) {
                String titleStr = title.getString();
                if (titleStr != null && !titleStr.isEmpty()) {
                    return titleStr;
                }
            }
        }
        return null;
    }

    /**
     * Find a duplicate entry in the history ring that has identical contents and container type.
     * Returns the index of the duplicate, or -1 if none found.
     */
    private static int findDuplicate(HistoryEntry newEntry) {
        for (int i = 0; i < history.size(); i++) {
            HistoryEntry existing = history.get(i);
            if (entriesMatch(newEntry, existing)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if two history entries have identical contents and container type.
     */
    private static boolean entriesMatch(HistoryEntry a, HistoryEntry b) {
        if (!Objects.equals(a.entryType, b.entryType)) return false;
        if (a.snapshot.isPlayerInventory != b.snapshot.isPlayerInventory) return false;
        if (!Objects.equals(a.containerTitle, b.containerTitle)) return false;
        if (a.snapshot.slotCount != b.snapshot.slotCount) return false;
        if (a.snapshot.slots.size() != b.snapshot.slots.size()) return false;

        for (Map.Entry<Integer, SlotData> entry : a.snapshot.slots.entrySet()) {
            int key = entry.getKey();
            SlotData sdA = entry.getValue();
            SlotData sdB = b.snapshot.slots.get(key);
            if (sdB == null) return false;
            if (!slotDataEquals(sdA, sdB)) return false;
        }
        return true;
    }

    /**
     * Compare two SlotData for equality.
     */
    private static boolean slotDataEquals(SlotData a, SlotData b) {
        if (a.item() != b.item()) return false;
        if (a.count() != b.count()) return false;
        return Objects.equals(a.components(), b.components());
    }

    /**
     * Remove a duplicate entry and adjust active indices accordingly.
     * Does NOT add the new entry — caller handles insertion.
     */
    private static void removeDuplicate(int dupIndex) {
        history.remove(dupIndex);
        if (activeContainerIndex == dupIndex) {
            activeContainerIndex = -1;
        } else if (activeContainerIndex > dupIndex) {
            activeContainerIndex--;
        }
        if (activePlayerIndex == dupIndex) {
            activePlayerIndex = -1;
        } else if (activePlayerIndex > dupIndex) {
            activePlayerIndex--;
        }
        if (activeBundleIndex == dupIndex) {
            activeBundleIndex = -1;
        } else if (activeBundleIndex > dupIndex) {
            activeBundleIndex--;
        }
        if (activeGrid9Index == dupIndex) {
            activeGrid9Index = -1;
        } else if (activeGrid9Index > dupIndex) {
            activeGrid9Index--;
        }
        if (activeHopper5Index == dupIndex) {
            activeHopper5Index = -1;
        } else if (activeHopper5Index > dupIndex) {
            activeHopper5Index--;
        }
        if (activeFurnace2Index == dupIndex) {
            activeFurnace2Index = -1;
        } else if (activeFurnace2Index > dupIndex) {
            activeFurnace2Index--;
        }
    }

    // ========== CONTAINER CATEGORY HELPERS ==========

    /**
     * Map a ContainerCategory to the clipboard entry type string.
     */
    public static String categoryToEntryType(ContainerClassifier.ContainerCategory category) {
        return switch (category) {
            case STANDARD -> TYPE_CONTAINER;
            case GRID9, CRAFTER, CRAFTING_TABLE -> TYPE_GRID9;
            case HOPPER -> TYPE_HOPPER5;
            case FURNACE -> TYPE_FURNACE2;
            case PLAYER_ONLY -> TYPE_PLAYER;
            default -> null;
        };
    }

    /**
     * Get the active clipboard index for the given container category.
     */
    public static int getActiveIndexForCategory(ContainerClassifier.ContainerCategory category) {
        return switch (category) {
            case STANDARD -> activeContainerIndex;
            case GRID9, CRAFTER, CRAFTING_TABLE -> activeGrid9Index;
            case HOPPER -> activeHopper5Index;
            case FURNACE -> activeFurnace2Index;
            case PLAYER_ONLY -> activePlayerIndex;
            default -> -1;
        };
    }

    // ========== COPY ==========

    /**
     * Copy the current container/inventory layout to the clipboard.
     */
    public static void copyLayout(ScreenHandler handler, boolean isPlayerOnly) {
        copyLayout(handler, isPlayerOnly, false);
    }

    /**
     * Copy the current container/inventory layout to the clipboard.
     * @param silent if true, suppress the "Layout copied" overlay message (used by cutLayout)
     */
    public static void copyLayout(ScreenHandler handler, boolean isPlayerOnly, boolean silent) {
        Map<Integer, SlotData> slots = new LinkedHashMap<>();

        if (isPlayerOnly) {
            // Main inventory: handler slots 9-35 → snapshot keys 9-35
            for (int i = 9; i <= 35; i++) {
                if (i >= handler.slots.size()) break;
                Slot slot = handler.slots.get(i);
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    slots.put(i, new SlotData(null, 0, null));
                } else {
                    slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                }
            }
            // Hotbar: handler slots 36-44 → snapshot keys 0-8
            for (int i = 36; i <= 44; i++) {
                if (i >= handler.slots.size()) break;
                Slot slot = handler.slots.get(i);
                ItemStack stack = slot.getStack();
                int playerSlot = i - 36; // maps to 0-8
                if (stack.isEmpty()) {
                    slots.put(playerSlot, new SlotData(null, 0, null));
                } else {
                    slots.put(playerSlot, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                }
            }
            // Armor: handler slots 5-8 → snapshot keys 36-39 (helmet=36, chest=37, legs=38, boots=39)
            for (int i = 5; i <= 8; i++) {
                if (i >= handler.slots.size()) break;
                Slot slot = handler.slots.get(i);
                ItemStack stack = slot.getStack();
                int snapshotKey = 36 + (i - 5); // 5→36, 6→37, 7→38, 8→39
                if (stack.isEmpty()) {
                    slots.put(snapshotKey, new SlotData(null, 0, null));
                } else {
                    slots.put(snapshotKey, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                }
            }
            // Offhand: handler slot 45 → snapshot key 40
            if (45 < handler.slots.size()) {
                Slot slot = handler.slots.get(45);
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    slots.put(40, new SlotData(null, 0, null));
                } else {
                    slots.put(40, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                }
            }
        } else {
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (slot.inventory instanceof PlayerInventory) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    slots.put(i, new SlotData(null, 0, null));
                } else {
                    slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                }
            }
        }

        LayoutSnapshot snapshot = new LayoutSnapshot(slots.size(), slots, isPlayerOnly);
        String label = getScreenLabel(slots.size(), isPlayerOnly);
        long timestamp = System.currentTimeMillis();
        long playtime = getCurrentPlaytimeMinutes();
        String containerTitle = getContainerTitle(isPlayerOnly);

        HistoryEntry entry = new HistoryEntry(snapshot, label, timestamp, playtime, containerTitle);

        int dupIndex = findDuplicate(entry);
        if (dupIndex >= 0) {
            InvTweaksConfig.debugLog("COPY", "dedup: replacing existing entry at index %d | label=%s", dupIndex, history.get(dupIndex).label);
            removeDuplicate(dupIndex);
        }

        addToHistory(entry);
        ClipboardStorage.save();

        if (!silent) {
            InvTweaksOverlay.show("Layout copied", 0xFF55FF55);
        }
        InvTweaksConfig.debugLog("COPY", "copied %d slots | playerOnly=%s | label=%s | containerTitle=%s | dedup=%s",
                slots.size(), isPlayerOnly, label, containerTitle, dupIndex >= 0);
    }

    /**
     * Copy the current container layout with container category awareness.
     * Handles crafting table output exclusion, furnace output exclusion, and crafter locked-slot tracking.
     */
    public static void copyLayout(ScreenHandler handler, boolean isPlayerOnly, boolean silent,
                                   ContainerClassifier.ContainerCategory category) {
        if (isPlayerOnly || category == null) {
            copyLayout(handler, isPlayerOnly, silent);
            return;
        }

        Map<Integer, SlotData> slots = new LinkedHashMap<>();

        switch (category) {
            case CRAFTING_TABLE -> {
                // Crafting table: handler slot 0 = output, slots 1-9 = input grid
                // Copy only input slots, remap to keys 0-8
                for (int i = 1; i <= 9; i++) {
                    if (i >= handler.slots.size()) break;
                    Slot slot = handler.slots.get(i);
                    ItemStack stack = slot.getStack();
                    int key = i - 1;
                    if (stack.isEmpty()) {
                        slots.put(key, new SlotData(null, 0, null));
                    } else {
                        slots.put(key, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                    }
                }
                InvTweaksConfig.debugLog("COPY", "crafting table: copied 9 input slots (output excluded)");
            }
            case FURNACE -> {
                // Furnace: slot 0 = input, slot 1 = fuel, slot 2 = output
                // Copy only input and fuel (slots 0-1)
                for (int i = 0; i <= 1; i++) {
                    if (i >= handler.slots.size()) break;
                    Slot slot = handler.slots.get(i);
                    ItemStack stack = slot.getStack();
                    if (stack.isEmpty()) {
                        slots.put(i, new SlotData(null, 0, null));
                    } else {
                        slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                    }
                }
                InvTweaksConfig.debugLog("COPY", "furnace: copied input + fuel (output excluded)");
            }
            case CRAFTER -> {
                // Crafter: 9 slots (handler slots 0-8), check for locked slots
                // TODO: verify crafter slot disabled detection in 1.21.11 Yarn
                // CrafterScreenHandler.isSlotDisabled(int) — NEEDS VERIFICATION
                for (int i = 0; i < 9; i++) {
                    if (i >= handler.slots.size()) break;
                    boolean isLocked = false;
                    if (handler instanceof net.minecraft.screen.CrafterScreenHandler crafterHandler) {
                        try {
                            isLocked = crafterHandler.isSlotDisabled(i);
                        } catch (Exception e) {
                            InvTweaksConfig.debugLog("CRAFTER", "isSlotDisabled() failed for slot %d: %s", i, e.getMessage());
                        }
                    }
                    if (isLocked) {
                        slots.put(i, SlotData.LOCKED);
                        InvTweaksConfig.debugLog("CRAFTER", "slot %d is locked", i);
                    } else {
                        Slot slot = handler.slots.get(i);
                        ItemStack stack = slot.getStack();
                        if (stack.isEmpty()) {
                            slots.put(i, new SlotData(null, 0, null));
                        } else {
                            slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                        }
                    }
                }
                InvTweaksConfig.debugLog("CRAFTER", "copied 9 crafter slots");
            }
            default -> {
                // GRID9, HOPPER, STANDARD: copy all container slots as normal
                for (int i = 0; i < handler.slots.size(); i++) {
                    Slot slot = handler.slots.get(i);
                    if (slot.inventory instanceof PlayerInventory) continue;
                    ItemStack stack = slot.getStack();
                    if (stack.isEmpty()) {
                        slots.put(i, new SlotData(null, 0, null));
                    } else {
                        slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
                    }
                }
            }
        }

        String entryType = categoryToEntryType(category);
        LayoutSnapshot snapshot = new LayoutSnapshot(slots.size(), slots, false);
        String label = getScreenLabel(slots.size(), false);
        long timestamp = System.currentTimeMillis();
        long playtime = getCurrentPlaytimeMinutes();
        String containerTitle = getContainerTitle(false);

        HistoryEntry entry = new HistoryEntry(snapshot, label, timestamp, playtime, containerTitle, entryType);

        int dupIndex = findDuplicate(entry);
        if (dupIndex >= 0) {
            InvTweaksConfig.debugLog("COPY", "dedup: replacing existing entry at index %d | label=%s", dupIndex, history.get(dupIndex).label);
            removeDuplicate(dupIndex);
        }

        addToHistory(entry);
        ClipboardStorage.save();

        if (!silent) {
            InvTweaksOverlay.show("Layout copied", 0xFF55FF55);
        }
        InvTweaksConfig.debugLog("COPY", "copied %d slots | category=%s | entryType=%s | label=%s | containerTitle=%s | dedup=%s",
                slots.size(), category, entryType, label, containerTitle, dupIndex >= 0);
    }

    // ========== PASTE ==========

    /**
     * Result of a paste operation for the caller to display appropriate messages.
     */
    public static class PasteResult {
        public final int slotsPlaced;
        public final int slotsTotal;
        public final boolean cursorWasOccupied;
        public final boolean alreadyMatched;
        public final boolean noMatchingItems;
        public final boolean sizeMismatch;
        public final boolean noClipboard;
        public final boolean typeMismatch;
        public final boolean quantityMaxOnly;
        public final String errorMessage;
        public final int lockedSlotsSkipped;

        private PasteResult(int slotsPlaced, int slotsTotal, boolean cursorWasOccupied,
                            boolean alreadyMatched, boolean noMatchingItems, boolean sizeMismatch,
                            boolean noClipboard, boolean typeMismatch, boolean quantityMaxOnly,
                            String errorMessage, int lockedSlotsSkipped) {
            this.slotsPlaced = slotsPlaced;
            this.slotsTotal = slotsTotal;
            this.cursorWasOccupied = cursorWasOccupied;
            this.alreadyMatched = alreadyMatched;
            this.noMatchingItems = noMatchingItems;
            this.sizeMismatch = sizeMismatch;
            this.noClipboard = noClipboard;
            this.typeMismatch = typeMismatch;
            this.quantityMaxOnly = quantityMaxOnly;
            this.errorMessage = errorMessage;
            this.lockedSlotsSkipped = lockedSlotsSkipped;
        }

        public static PasteResult success(int placed, int total, boolean cursorOccupied) {
            return new PasteResult(placed, total, cursorOccupied, false, false, false, false, false, false, null, 0);
        }
        public static PasteResult successQuantityMax(int placed, int total, boolean cursorOccupied) {
            return new PasteResult(placed, total, cursorOccupied, false, false, false, false, false, true, null, 0);
        }
        public static PasteResult alreadyMatched() {
            return new PasteResult(0, 0, false, true, false, false, false, false, false, null, 0);
        }
        public static PasteResult noClipboard() {
            return new PasteResult(0, 0, false, false, false, false, true, false, false, null, 0);
        }
        public static PasteResult noMatchingItems() {
            return new PasteResult(0, 0, false, false, true, false, false, false, false, null, 0);
        }
        public static PasteResult sizeMismatch(int clipSize, int containerSize) {
            return new PasteResult(0, 0, false, false, false, true, false, false, false,
                    "Layout size incompatible", 0);
        }
        public static PasteResult typeMismatch(String msg) {
            return new PasteResult(0, 0, false, false, false, false, false, true, false, msg, 0);
        }

        public PasteResult withLockedSlotsSkipped(int count) {
            return new PasteResult(slotsPlaced, slotsTotal, cursorWasOccupied, alreadyMatched,
                    noMatchingItems, sizeMismatch, noClipboard, typeMismatch, quantityMaxOnly,
                    errorMessage, count);
        }
    }

    /**
     * Paste the clipboard layout into the current container/inventory.
     * Uses the active clipboard entry for data.
     */
    public static PasteResult pasteLayout(ScreenHandler handler, boolean isPlayerOnly) {
        return pasteLayout(handler, isPlayerOnly, (Map<Integer,SlotData>) null);
    }

    /**
     * Paste a layout into the current container/inventory.
     * @param overrideData if non-null, use this data instead of the active clipboard entry.
     *                     Keys are clipboard slot keys (0-based), values are SlotData.
     */
    public static PasteResult pasteLayout(ScreenHandler handler, boolean isPlayerOnly,
                                           Map<Integer, SlotData> overrideData) {
        return pasteLayout(handler, isPlayerOnly, overrideData, false);
    }

    /**
     * Paste a layout with optional even distribution.
     * @param evenDistribution if true, distribute items evenly across slots of the same type
     *                         instead of filling each slot to max stack size.
     */
    public static PasteResult pasteLayout(ScreenHandler handler, boolean isPlayerOnly,
                                           Map<Integer, SlotData> overrideData,
                                           boolean evenDistribution) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerInteractionManager im = mc.interactionManager;
        PlayerEntity player = mc.player;
        if (im == null || player == null) return PasteResult.noClipboard();

        // Get clipboard data — either from override or active clipboard
        Map<Integer, SlotData> clipboardSlots;
        int clipboardSlotCount;

        if (overrideData != null) {
            clipboardSlots = overrideData;
            clipboardSlotCount = overrideData.size();
            InvTweaksConfig.debugLog("PASTE", "using override data: %d slots", clipboardSlotCount);
        } else {
            int activeIndex = isPlayerOnly ? activePlayerIndex : activeContainerIndex;
            InvTweaksConfig.debugLog("PASTE", "using clipboard entry: index=%d type=%s activeContainer=%d activePlayer=%d historySize=%d",
                    activeIndex, isPlayerOnly ? "player" : "container", activeContainerIndex, activePlayerIndex, history.size());
            if (activeIndex < 0 || activeIndex >= history.size()) {
                return PasteResult.noClipboard();
            }

            HistoryEntry activeEntry = history.get(activeIndex);
            InvTweaksConfig.debugLog("PASTE", "selected entry: label=%s isPlayer=%s slots=%d timestamp=%d",
                    activeEntry.label, activeEntry.snapshot.isPlayerInventory, activeEntry.snapshot.slotCount, activeEntry.timestamp);
            LayoutSnapshot clipboard = activeEntry.snapshot;

            // Check clipboard type matches current context
            if (clipboard.isPlayerInventory != isPlayerOnly) {
                String clipType = clipboard.isPlayerInventory ? "player inventory" : "container";
                String currentType = isPlayerOnly ? "player inventory" : "container";
                return PasteResult.typeMismatch("Layout was copied from " + clipType + ", can't paste into " + currentType);
            }

            clipboardSlots = clipboard.slots;
            clipboardSlotCount = clipboard.slotCount;
        }

        // Check if cursor has items — cursor-aware paste
        ItemStack cursorStack = handler.getCursorStack();
        boolean cursorOccupied = cursorStack != null && !cursorStack.isEmpty();
        int cursorStashSlot = -1;

        // Determine target slots and build clipboard-key → handler-slot mapping
        List<Integer> targetSlotIds = new ArrayList<>();
        Map<Integer, SlotData> targetLayout = new LinkedHashMap<>();
        boolean sizeMismatch = false;

        if (isPlayerOnly) {
            for (Map.Entry<Integer, SlotData> clipEntry : clipboardSlots.entrySet()) {
                int clipKey = clipEntry.getKey();
                int handlerSlot;
                if (clipKey >= 0 && clipKey <= 8) {
                    handlerSlot = clipKey + 36;
                } else if (clipKey >= 9 && clipKey <= 35) {
                    handlerSlot = clipKey;
                } else if (clipKey >= 36 && clipKey <= 39) {
                    handlerSlot = clipKey - 36 + 5;
                } else if (clipKey == 40) {
                    handlerSlot = 45;
                } else {
                    continue;
                }
                if (handlerSlot < handler.slots.size()) {
                    targetSlotIds.add(handlerSlot);
                    targetLayout.put(handlerSlot, clipEntry.getValue());
                }
            }
        } else {
            List<Integer> containerSlotIds = new ArrayList<>();
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (!(slot.inventory instanceof PlayerInventory)) {
                    containerSlotIds.add(i);
                }
            }
            targetSlotIds.addAll(containerSlotIds);

            // Only check size mismatch when using clipboard data (not override data).
            // Override data is pre-sliced by the caller to match the container size.
            if (overrideData == null && containerSlotIds.size() != clipboardSlotCount) {
                sizeMismatch = true;
                InvTweaksConfig.debugLog("PASTE", "size mismatch | clipboard=%d | target=%d", clipboardSlotCount, containerSlotIds.size());
            }

            List<Integer> clipboardSlotIdsSorted = new ArrayList<>(clipboardSlots.keySet());
            Collections.sort(clipboardSlotIdsSorted);
            for (int i = 0; i < clipboardSlotIdsSorted.size(); i++) {
                int clipSlotId = clipboardSlotIdsSorted.get(i);
                if (clipSlotId >= containerSlotIds.size()) continue;
                int actualSlotId = containerSlotIds.get(clipSlotId);
                targetLayout.put(actualSlotId, clipboardSlots.get(clipSlotId));
            }
        }

        // Build item pool
        Set<Integer> allAccessibleSlots = new HashSet<>(targetSlotIds);
        if (!isPlayerOnly) {
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (slot.inventory instanceof PlayerInventory) {
                    int invIdx = slot.getIndex();
                    if (invIdx >= 0 && invIdx <= 44) {
                        allAccessibleSlots.add(i);
                    }
                }
            }
        }

        // Build set of item types in clipboard
        Set<Item> clipboardItemTypes = new HashSet<>();
        for (SlotData sd : clipboardSlots.values()) {
            if (sd != null && sd.item() != null) {
                clipboardItemTypes.add(sd.item());
            }
        }

        // Check if any matching items are available
        int availableMatchCount = 0;
        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            SlotData desired = entry.getValue();
            if (desired.item() == null) continue;
            for (int slotId : allAccessibleSlots) {
                ItemStack stack = handler.slots.get(slotId).getStack();
                if (!stack.isEmpty() && stack.getItem() == desired.item()) {
                    availableMatchCount++;
                    break;
                }
            }
        }

        boolean clipboardHasItems = !clipboardItemTypes.isEmpty();
        if (clipboardHasItems && availableMatchCount == 0) {
            return PasteResult.noMatchingItems();
        }

        if (sizeMismatch) {
            return PasteResult.sizeMismatch(clipboardSlotCount, targetSlotIds.size());
        }

        InvTweaksConfig.debugLog("PASTE", "starting paste | targetSlots=%d | accessibleSlots=%d | cursorOccupied=%s",
                targetLayout.size(), allAccessibleSlots.size(), cursorOccupied);

        // Quick check: is the layout already matching?
        boolean alreadyMatches = true;
        boolean quantityMaxOnly = false;
        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            int slotId = entry.getKey();
            SlotData desired = entry.getValue();
            ItemStack current = handler.slots.get(slotId).getStack();
            if (desired.item() == null) {
                if (!current.isEmpty() && clipboardItemTypes.contains(current.getItem())) {
                    alreadyMatches = false; break;
                }
            } else {
                if (current.isEmpty() || current.getItem() != desired.item()) {
                    alreadyMatches = false; break;
                }
                if (desired.components() != null) {
                    String currentComponents = serializeComponents(current);
                    if (!desired.components().equals(currentComponents)) {
                        alreadyMatches = false; break;
                    }
                }
            }
        }
        if (alreadyMatches && !evenDistribution) {
            // Check if any target slot could receive more items (quantity maximization)
            boolean canTopUp = false;
            for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
                int slotId = entry.getKey();
                SlotData desired = entry.getValue();
                if (desired.item() == null) continue;
                ItemStack current = handler.slots.get(slotId).getStack();
                int maxStack = current.getMaxCount();
                if (current.getCount() < maxStack) {
                    // Check if surplus items of this type exist elsewhere
                    for (int srcSlot : allAccessibleSlots) {
                        if (srcSlot == slotId) continue;
                        ItemStack srcStack = handler.slots.get(srcSlot).getStack();
                        if (!srcStack.isEmpty() && srcStack.getItem() == desired.item()) {
                            // A source has surplus if it's not a target for this item type
                            SlotData srcTarget = targetLayout.get(srcSlot);
                            if (srcTarget == null || srcTarget.item() != desired.item()) {
                                canTopUp = true;
                                break;
                            }
                        }
                    }
                    if (canTopUp) break;
                }
            }
            if (!canTopUp) {
                return PasteResult.alreadyMatched();
            }
            quantityMaxOnly = true;
            InvTweaksConfig.debugLog("PASTE", "quantity maximization only — all types match, topping up stacks");
        }

        // Two-pass partial paste logic
        int totalTargets = 0;
        for (SlotData sd : targetLayout.values()) {
            if (sd.item() != null) totalTargets++;
            else {
                // Count empty-target slots that have layout items to clear
                // (these also count as targets we try to achieve)
                totalTargets++;
            }
        }

        // Stash cursor item in a non-target empty slot so normal paste logic can run
        if (cursorOccupied) {
            for (int slotId : allAccessibleSlots) {
                if (!targetLayout.containsKey(slotId) && handler.slots.get(slotId).getStack().isEmpty()) {
                    cursorStashSlot = slotId;
                    break;
                }
            }
            if (cursorStashSlot >= 0) {
                im.clickSlot(handler.syncId, cursorStashSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                InvTweaksConfig.debugLog("PASTE", "stashed cursor item in slot %d", cursorStashSlot);
                allAccessibleSlots.remove(cursorStashSlot);
            } else {
                InvTweaksConfig.debugLog("PASTE", "cannot stash cursor — no non-target empty slot available");
                return PasteResult.success(0, targetLayout.size(), true);
            }
        }

        // Even distribution: compute per-slot quantities for small containers
        Map<Integer, Integer> quantityOverrides = null;
        if (evenDistribution) {
            quantityOverrides = calculateEvenDistribution(targetLayout, handler, allAccessibleSlots);
        }

        // Even distribution pre-pass: trim excess from target slots before the main paste
        if (evenDistribution && quantityOverrides != null) {
            for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
                int slotId = entry.getKey();
                SlotData desired = entry.getValue();
                if (desired.item() == null) continue;
                Integer desiredQty = quantityOverrides.get(slotId);
                if (desiredQty == null) continue;
                ItemStack current = handler.slots.get(slotId).getStack();
                if (current.isEmpty() || current.getItem() != desired.item()) continue;
                if (desired.components() != null) {
                    String currentComp = serializeComponents(current);
                    if (!desired.components().equals(currentComp)) continue;
                }
                if (current.getCount() <= desiredQty) continue;
                // Slot has excess — trim it
                int excess = current.getCount() - desiredQty;
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                for (int r = 0; r < desiredQty; r++) {
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                }
                if (!handler.getCursorStack().isEmpty()) {
                    int dump = findAnyEmptySlot(handler, allAccessibleSlots);
                    if (dump >= 0) {
                        im.clickSlot(handler.syncId, dump, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    } else {
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    }
                }
                InvTweaksConfig.debugLog("PASTE", "pre-pass trimmed slot %d: had %d, want %d, removed %d",
                        slotId, current.getCount(), desiredQty, excess);
            }
        }

        int matchedSlots = 0;
        Set<Integer> failedSlots = new HashSet<>();

        // Pass 1: when even distribution is active, protect all target slots from being used as sources
        Set<Integer> pass1Protected = evenDistribution ? new HashSet<>(targetLayout.keySet()) : Collections.emptySet();
        matchedSlots = executePastePass(handler, im, player, targetLayout, allAccessibleSlots,
                clipboardItemTypes, isPlayerOnly, false, failedSlots, pass1Protected, quantityOverrides);

        // Pass 2: retry failed slots (displacement in pass 1 may have freed slots)
        if (!failedSlots.isEmpty()) {
            InvTweaksConfig.debugLog("PASTE", "pass 2: retrying %d failed slots", failedSlots.size());
            Map<Integer, SlotData> retryLayout = new LinkedHashMap<>();
            for (int failedSlot : failedSlots) {
                retryLayout.put(failedSlot, targetLayout.get(failedSlot));
            }
            // Protect pass-1 filled slots from being used as sources in pass 2
            Set<Integer> pass1FilledSlots = new HashSet<>();
            if (evenDistribution) {
                pass1FilledSlots.addAll(targetLayout.keySet());
            } else {
                for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
                    int slotId = entry.getKey();
                    if (!failedSlots.contains(slotId)) {
                        SlotData desired = entry.getValue();
                        if (desired.item() != null) {
                            ItemStack current = handler.slots.get(slotId).getStack();
                            if (!current.isEmpty() && current.getItem() == desired.item()) {
                                pass1FilledSlots.add(slotId);
                            }
                        }
                    }
                }
            }
            Set<Integer> retryFailed = new HashSet<>();
            int pass2Matched = executePastePass(handler, im, player, retryLayout, allAccessibleSlots,
                    clipboardItemTypes, isPlayerOnly, false, retryFailed, pass1FilledSlots, quantityOverrides);
            matchedSlots += pass2Matched;
        }

        // Final safety: if cursor was NOT occupied initially, make sure cursor is clean
        // If cursor WAS occupied, leave it alone
        if (!cursorOccupied && !handler.getCursorStack().isEmpty()) {
            int emptySlot = findAnyEmptySlot(handler, allAccessibleSlots);
            if (emptySlot >= 0) {
                im.clickSlot(handler.syncId, emptySlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }

        // Restore cursor item if we stashed it
        if (cursorStashSlot >= 0) {
            im.clickSlot(handler.syncId, cursorStashSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            InvTweaksConfig.debugLog("PASTE", "restored cursor item from slot %d", cursorStashSlot);
        }

        InvTweaksConfig.debugLog("PASTE", "complete | matched=%d/%d | cursorOccupied=%s | quantityMaxOnly=%s",
                matchedSlots, targetLayout.size(), cursorOccupied, quantityMaxOnly);
        if (quantityMaxOnly) {
            return PasteResult.successQuantityMax(matchedSlots, targetLayout.size(), cursorOccupied);
        }
        return PasteResult.success(matchedSlots, targetLayout.size(), cursorOccupied);
    }

    /**
     * Execute one pass of the paste algorithm over the given target layout.
     * Returns the number of slots successfully matched/placed.
     * Populates failedSlots with slot IDs that could not be processed.
     */
    private static int executePastePass(ScreenHandler handler, ClientPlayerInteractionManager im,
                                         PlayerEntity player, Map<Integer, SlotData> targetLayout,
                                         Set<Integer> allAccessibleSlots, Set<Item> clipboardItemTypes,
                                         boolean isPlayerOnly, boolean cursorOccupied,
                                         Set<Integer> failedSlots, Set<Integer> protectedSlots,
                                         Map<Integer, Integer> quantityOverrides) {
        int matchedSlots = 0;

        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            int targetSlotId = entry.getKey();
            SlotData desired = entry.getValue();

            // Skip armor/offhand slots if the desired item can't be equipped there
            if (isPlayerOnly && isArmorOrOffhandSlot(targetSlotId) && desired.item() != null) {
                if (!canEquipInSlot(desired.item(), targetSlotId)) {
                    InvTweaksConfig.debugLog("PASTE", "skipping armor/offhand slot %d: %s can't be equipped there", targetSlotId, desired.item());
                    continue;
                }
            }

            // Safety: if cursor picked up items from mod operations (and was NOT occupied at start),
            // try to put them away
            if (!cursorOccupied && !handler.getCursorStack().isEmpty()) {
                int dump = findAnyEmptySlot(handler, allAccessibleSlots);
                if (dump >= 0) {
                    im.clickSlot(handler.syncId, dump, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                } else {
                    InvTweaksConfig.debugLog("PASTE", "ABORT: cursor not empty and no room at slot %d", targetSlotId);
                    failedSlots.add(targetSlotId);
                    continue;
                }
            }

            ItemStack currentStack = handler.slots.get(targetSlotId).getStack();

            if (desired.item() == null) {
                if (currentStack.isEmpty()) {
                    matchedSlots++;
                    continue;
                }
                if (!clipboardItemTypes.contains(currentStack.getItem())) {
                    InvTweaksConfig.debugLog("PASTE", "skipping non-layout item at slot %d (%s)", targetSlotId, currentStack.getItem());
                    continue;
                }
                boolean moved;
                if (cursorOccupied) {
                    moved = moveItemOutQuickMove(handler, im, player, targetSlotId);
                } else {
                    moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                }
                if (moved) {
                    matchedSlots++;
                } else {
                    failedSlots.add(targetSlotId);
                }
                continue;
            }

            int maxStack = new ItemStack(desired.item()).getMaxCount();
            int desiredQuantity = (quantityOverrides != null && quantityOverrides.containsKey(targetSlotId))
                    ? Math.min(quantityOverrides.get(targetSlotId), maxStack)
                    : maxStack;

            boolean typeMatches = !currentStack.isEmpty() && currentStack.getItem() == desired.item();
            boolean componentMatches = true;
            if (typeMatches && desired.components() != null) {
                String currentComponents = serializeComponents(currentStack);
                componentMatches = desired.components().equals(currentComponents);
            }
            if (typeMatches && componentMatches) {
                if (currentStack.getCount() >= desiredQuantity) {
                    matchedSlots++;
                    continue;
                }
                if (!cursorOccupied) {
                    addMore(handler, im, player, targetSlotId, desired.item(),
                            desired.components(), desiredQuantity - currentStack.getCount(), allAccessibleSlots, targetLayout, isPlayerOnly, protectedSlots);
                }
                // When cursor is occupied, we can't do addMore (it uses PICKUP), but the type matches, so count it
                matchedSlots++;
                continue;
            }

            // Wrong item or empty — need to place the desired item here
            if (!currentStack.isEmpty()) {
                boolean moved;
                if (cursorOccupied) {
                    moved = moveItemOutQuickMove(handler, im, player, targetSlotId);
                } else {
                    moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                }
                if (!moved) {
                    InvTweaksConfig.debugLog("PASTE", "could not displace item at slot %d", targetSlotId);
                    failedSlots.add(targetSlotId);
                    continue;
                }
            }

            boolean placed;
            if (cursorOccupied) {
                placed = sourceItemQuickMove(handler, im, player, targetSlotId, desired.item(),
                        desired.components(), allAccessibleSlots, targetLayout);
            } else {
                placed = sourceItem(handler, im, player, targetSlotId, desired.item(), desiredQuantity,
                        desired.components(), allAccessibleSlots, targetLayout, isPlayerOnly, protectedSlots);
            }
            if (placed) {
                matchedSlots++;
            } else {
                failedSlots.add(targetSlotId);
            }
        }

        return matchedSlots;
    }


    // ========== CUT ==========

    /**
     * Cut: copy the layout to clipboard, then move all items out of the container.
     */
    public static void cutLayout(ScreenHandler handler, boolean isPlayerOnly) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerInteractionManager im = mc.interactionManager;
        PlayerEntity player = mc.player;
        if (im == null || player == null) return;

        // First, copy the layout (silent — we'll show our own "Layout cut" message)
        copyLayout(handler, isPlayerOnly, true);

        if (isPlayerOnly) {
            // Player-only inventory has nowhere to cut to — just copy
            InvTweaksOverlay.show("Layout copied", 0xFF55FF55);
            return;
        }

        // Move all container items to player inventory via shift-click
        int moved = 0;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            if (slot.inventory instanceof PlayerInventory) continue;
            if (slot.getStack().isEmpty()) continue;

            InvTweaksConfig.debugLog("CUT", "QUICK_MOVE slot=%d | item=%s | count=%d",
                    i, slot.getStack().getItem(), slot.getStack().getCount());
            im.clickSlot(handler.syncId, i, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.QUICK_MOVE, player);
            moved++;
        }

        InvTweaksOverlay.show("Layout cut", 0xFF55FF55);
        InvTweaksConfig.debugLog("CUT", "complete | moved=%d stacks", moved);
    }

    /**
     * Cut with category awareness. Copies the layout, then extracts items.
     */
    public static void cutLayout(ScreenHandler handler, boolean isPlayerOnly,
                                  ContainerClassifier.ContainerCategory category) {
        if (isPlayerOnly || category == null) {
            cutLayout(handler, isPlayerOnly);
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerInteractionManager im = mc.interactionManager;
        PlayerEntity player = mc.player;
        if (im == null || player == null) return;

        // Copy first (silent)
        copyLayout(handler, isPlayerOnly, true, category);

        // Move items to player inventory
        int moved = 0;
        switch (category) {
            case CRAFTING_TABLE -> {
                // Only extract from input slots 1-9 (skip output slot 0)
                for (int i = 1; i <= 9; i++) {
                    if (i >= handler.slots.size()) break;
                    if (handler.slots.get(i).getStack().isEmpty()) continue;
                    im.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, player);
                    moved++;
                }
            }
            case FURNACE -> {
                // Only extract from input (slot 0) and fuel (slot 1), skip output (slot 2)
                for (int i = 0; i <= 1; i++) {
                    if (i >= handler.slots.size()) break;
                    if (handler.slots.get(i).getStack().isEmpty()) continue;
                    im.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, player);
                    moved++;
                }
            }
            default -> {
                // All container slots (same as original)
                for (int i = 0; i < handler.slots.size(); i++) {
                    Slot slot = handler.slots.get(i);
                    if (slot.inventory instanceof PlayerInventory) continue;
                    if (slot.getStack().isEmpty()) continue;
                    im.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, player);
                    moved++;
                }
            }
        }

        InvTweaksOverlay.show("Layout cut", 0xFF55FF55);
        InvTweaksConfig.debugLog("CUT", "complete | moved=%d stacks | category=%s", moved, category);
    }

    // ========== DEATH AUTO-COPY ==========

    /**
     * Auto-save player inventory on death (legacy — reads inventory directly).
     * Kept for reference but no longer called; inventory is empty by the time death is detected.
     */
    public static void autoSavePlayerInventoryOnDeath(PlayerEntity player) {
        Map<Integer, SlotData> slots = new LinkedHashMap<>();
        PlayerInventory inv = player.getInventory();
        // Main inventory + hotbar (keys 0-35)
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                slots.put(i, new SlotData(null, 0, null));
            } else {
                slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
            }
        }
        // Armor: inv slots 36-39 → snapshot keys 36-39
        for (int i = 36; i <= 39; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                slots.put(i, new SlotData(null, 0, null));
            } else {
                slots.put(i, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
            }
        }
        // Offhand: inv slot 40 → snapshot key 40
        ItemStack offhand = inv.getStack(40);
        if (offhand.isEmpty()) {
            slots.put(40, new SlotData(null, 0, null));
        } else {
            slots.put(40, new SlotData(offhand.getItem(), offhand.getCount(), serializeComponents(offhand)));
        }

        boolean hasItems = false;
        for (SlotData sd : slots.values()) {
            if (sd.item() != null) { hasItems = true; break; }
        }
        if (!hasItems) {
            InvTweaksConfig.debugLog("DEATH", "Player died with empty inventory, skipping snapshot");
            return;
        }

        LayoutSnapshot snapshot = new LayoutSnapshot(slots.size(), slots, true);
        addToHistory(new HistoryEntry(snapshot, "Death snapshot",
                System.currentTimeMillis(), getCurrentPlaytimeMinutes()));
        ClipboardStorage.save();

        InvTweaksConfig.debugLog("DEATH", "Player died, auto-copied inventory layout");
    }

    /**
     * Auto-save from a pre-cached inventory snapshot (called on death).
     * The cache is built every tick while alive, so it reflects the last living state.
     */
    public static void autoSaveFromCache(Map<Integer, SlotData> cachedSlots) {
        // Check if the cache has any items
        boolean hasItems = false;
        for (SlotData sd : cachedSlots.values()) {
            if (sd.item() != null) { hasItems = true; break; }
        }
        if (!hasItems) {
            InvTweaksConfig.debugLog("DEATH", "Cached inventory was empty, skipping snapshot");
            return;
        }

        LayoutSnapshot snapshot = new LayoutSnapshot(cachedSlots.size(), cachedSlots, true);
        HistoryEntry entry = new HistoryEntry(snapshot, "Death snapshot",
                System.currentTimeMillis(), getCurrentPlaytimeMinutes(), null);

        int dupIndex = findDuplicate(entry);
        if (dupIndex >= 0) {
            InvTweaksConfig.debugLog("DEATH", "dedup: replacing existing death snapshot at index %d", dupIndex);
            removeDuplicate(dupIndex);
        }

        addToHistory(entry);
        ClipboardStorage.save();

        InvTweaksOverlay.show("Inventory saved (death)", 0xFFFFAA00);
        InvTweaksConfig.debugLog("DEATH", "Saved cached inventory as death snapshot | dedup=%s", dupIndex >= 0);
    }

    // ========== CURSOR-SAFE PASTE HELPERS (QUICK_MOVE only) ==========

    /**
     * Move an item out of a target slot using only QUICK_MOVE (shift-click).
     * Safe to use when cursor is occupied.
     */
    private static boolean moveItemOutQuickMove(ScreenHandler handler, ClientPlayerInteractionManager im,
                                                 PlayerEntity player, int targetSlotId) {
        ItemStack stack = handler.slots.get(targetSlotId).getStack();
        if (stack.isEmpty()) return true;

        InvTweaksConfig.debugLog("PASTE", "moveOutQuickMove slot=%d | item=%s | count=%d", targetSlotId, stack.getItem(), stack.getCount());
        im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.QUICK_MOVE, player);

        // Check if it was moved
        ItemStack after = handler.slots.get(targetSlotId).getStack();
        if (after.isEmpty()) return true;

        InvTweaksConfig.debugLog("PASTE", "moveOutQuickMove FAILED slot=%d | still has %s x%d", targetSlotId, after.getItem(), after.getCount());
        return false;
    }

    /**
     * Source an item into a target slot using only QUICK_MOVE.
     * Find a slot containing the desired item type elsewhere, QUICK_MOVE it, and hope it lands in/near the target.
     * This is a best-effort approach for cursor-occupied paste.
     */
    private static boolean sourceItemQuickMove(ScreenHandler handler, ClientPlayerInteractionManager im,
                                                PlayerEntity player, int targetSlotId, Item itemType,
                                                String desiredComponents,
                                                Set<Integer> allAccessible,
                                                Map<Integer, SlotData> targetLayout) {
        // Look for the item on the OTHER side from the target slot, then QUICK_MOVE it
        // QUICK_MOVE sends items to the other side, so we need the source to be on the opposite side
        boolean targetIsPlayer = handler.slots.get(targetSlotId).inventory instanceof PlayerInventory;

        List<Integer> rankedSources = rankSourceSlots(handler, targetSlotId, itemType,
                desiredComponents, allAccessible, targetLayout);

        for (int srcSlot : rankedSources) {
            ItemStack srcStack = handler.slots.get(srcSlot).getStack();
            if (srcStack.isEmpty() || srcStack.getItem() != itemType) continue;

            if (desiredComponents != null) {
                String srcComponents = serializeComponents(srcStack);
                if (!desiredComponents.equals(srcComponents)) continue;
            }

            // QUICK_MOVE only works cross-side (container↔player)
            boolean srcIsPlayer = handler.slots.get(srcSlot).inventory instanceof PlayerInventory;
            if (srcIsPlayer == targetIsPlayer) continue; // Same side — QUICK_MOVE won't help

            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                // This source is in its correct target position — skip to avoid disrupting it
                boolean rightVariant = true;
                if (srcTarget.components() != null) {
                    String srcComp = serializeComponents(srcStack);
                    rightVariant = srcTarget.components().equals(srcComp);
                }
                if (rightVariant) continue;
            }

            InvTweaksConfig.debugLog("PASTE", "sourceItemQuickMove target=%d | src=%d | item=%s", targetSlotId, srcSlot, itemType);
            im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.QUICK_MOVE, player);

            // Check if the target slot now has the item
            ItemStack targetAfter = handler.slots.get(targetSlotId).getStack();
            if (!targetAfter.isEmpty() && targetAfter.getItem() == itemType) {
                return true;
            }
            // QUICK_MOVE may have placed it in a different slot — that's OK for partial paste
            // but we didn't place it in our target. Try another source.
        }

        return false;
    }

    /**
     * Paste with category awareness. Handles cross-type guards and container-specific slot mapping.
     */
    public static PasteResult pasteLayout(ScreenHandler handler, boolean isPlayerOnly,
                                           ContainerClassifier.ContainerCategory category) {
        if (isPlayerOnly || category == null) {
            return pasteLayout(handler, isPlayerOnly);
        }

        // Get the active entry for this category
        String expectedType = categoryToEntryType(category);
        int activeIndex = getActiveIndexForCategory(category);

        if (activeIndex < 0 || activeIndex >= history.size()) {
            // Cross-type guard messages
            String msg = switch (category) {
                case GRID9, CRAFTER, CRAFTING_TABLE -> "No grid layout copied";
                case HOPPER -> "No hopper layout copied";
                case FURNACE -> "No furnace layout copied";
                default -> "No layout copied";
            };
            return PasteResult.typeMismatch(msg);
        }

        HistoryEntry activeEntry = history.get(activeIndex);

        // Verify entry type matches
        if (!expectedType.equals(activeEntry.entryType)) {
            return PasteResult.typeMismatch("Layout type incompatible");
        }

        Map<Integer, SlotData> clipboardSlots = activeEntry.snapshot.slots;

        // Category-specific paste logic
        switch (category) {
            case CRAFTER -> {
                // Phase 1: Match lock state before placing items
                if (handler instanceof net.minecraft.screen.CrafterScreenHandler crafterHandler) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    ClientPlayerInteractionManager im = mc.interactionManager;
                    PlayerEntity player = mc.player;
                    if (im != null && player != null) {
                        for (int i = 0; i < 9; i++) {
                            SlotData clipSlot = clipboardSlots.get(i);
                            boolean clipLocked = SlotData.isLocked(clipSlot);
                            boolean targetLocked = false;
                            try {
                                targetLocked = crafterHandler.isSlotDisabled(i);
                            } catch (Exception e) {
                                InvTweaksConfig.debugLog("CRAFTER", "isSlotDisabled check failed for slot %d: %s", i, e.getMessage());
                            }

                            if (clipLocked != targetLocked) {
                                boolean newEnabled = !clipLocked;
                                crafterHandler.setSlotEnabled(i, newEnabled);
                                im.slotChangedState(i, handler.syncId, newEnabled);
                                InvTweaksConfig.debugLog("CRAFTER", "toggled lock state on slot %d (was %s, want %s)", i, targetLocked, clipLocked);
                            }
                        }
                    }
                }
                // Phase 2: Build override data excluding locked slots
                Map<Integer, SlotData> pasteData = new LinkedHashMap<>();
                for (Map.Entry<Integer, SlotData> e : clipboardSlots.entrySet()) {
                    if (!SlotData.isLocked(e.getValue())) {
                        pasteData.put(e.getKey(), e.getValue());
                    }
                }
                return pasteLayout(handler, false, pasteData, true);
            }
            case CRAFTING_TABLE -> {
                // Remap clipboard keys 0-8 to handler slots 1-9 (skip output slot 0)
                Map<Integer, SlotData> remapped = new LinkedHashMap<>();
                int lockedCount = 0;
                for (Map.Entry<Integer, SlotData> e : clipboardSlots.entrySet()) {
                    if (SlotData.isLocked(e.getValue())) {
                        lockedCount++;
                        continue;
                    }
                    remapped.put(e.getKey() + 1, e.getValue()); // key 0 -> handler slot 1, etc.
                }
                PasteResult result = pasteLayout(handler, false, remapped, true);
                return lockedCount > 0 ? result.withLockedSlotsSkipped(lockedCount) : result;
            }
            case FURNACE -> {
                // Slots 0-1 only (input + fuel), no remapping needed
                Map<Integer, SlotData> filtered = new LinkedHashMap<>();
                for (Map.Entry<Integer, SlotData> e : clipboardSlots.entrySet()) {
                    if (e.getKey() <= 1) {
                        filtered.put(e.getKey(), e.getValue());
                    }
                }
                return pasteLayout(handler, false, filtered, true);
            }
            default -> {
                // GRID9, HOPPER, STANDARD: Filter out LOCKED entries, then normal paste
                Map<Integer, SlotData> filtered = new LinkedHashMap<>();
                int lockedCount = 0;
                for (Map.Entry<Integer, SlotData> e : clipboardSlots.entrySet()) {
                    if (SlotData.isLocked(e.getValue())) {
                        lockedCount++;
                    } else {
                        filtered.put(e.getKey(), e.getValue());
                    }
                }
                PasteResult result = pasteLayout(handler, false, filtered, true);
                return lockedCount > 0 ? result.withLockedSlotsSkipped(lockedCount) : result;
            }
        }
    }

    // ========== EVEN DISTRIBUTION (small containers) ==========

    /**
     * Calculate even distribution quantities for small containers.
     * Groups target slots by item type + component fingerprint, counts available items,
     * and distributes evenly across slots wanting the same type.
     *
     * @param targetLayout the paste target layout (handler slot ID → SlotData)
     * @param handler the screen handler to scan for available items
     * @param allAccessibleSlots all slots that can be used as sources
     * @return map of handler slot ID → desired quantity
     */
    private static Map<Integer, Integer> calculateEvenDistribution(
            Map<Integer, SlotData> targetLayout, ScreenHandler handler,
            Set<Integer> allAccessibleSlots) {

        // Group target slots by item type key (item + components)
        // key = "item_id|components", value = list of handler slot IDs wanting that type
        Map<String, List<Integer>> slotsByType = new LinkedHashMap<>();
        Map<String, Item> itemByType = new HashMap<>();
        Map<String, String> componentsByType = new HashMap<>();

        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            SlotData desired = entry.getValue();
            if (desired.item() == null) continue;
            String typeKey = desired.item().toString() + "|" + (desired.components() != null ? desired.components() : "");
            slotsByType.computeIfAbsent(typeKey, k -> new ArrayList<>()).add(entry.getKey());
            itemByType.put(typeKey, desired.item());
            componentsByType.put(typeKey, desired.components());
        }

        // Count available items per type from ALL accessible slots (including target slots)
        // Target slots with existing items contribute to the total pool for redistribution
        Map<String, Integer> availableByType = new HashMap<>();

        for (String typeKey : slotsByType.keySet()) {
            Item itemType = itemByType.get(typeKey);
            String desiredComponents = componentsByType.get(typeKey);
            int available = 0;
            for (int slotId : allAccessibleSlots) {
                ItemStack stack = handler.slots.get(slotId).getStack();
                if (stack.isEmpty() || stack.getItem() != itemType) continue;
                if (desiredComponents != null) {
                    String srcComponents = serializeComponents(stack);
                    if (!desiredComponents.equals(srcComponents)) continue;
                }
                available += stack.getCount();
            }
            availableByType.put(typeKey, available);
        }

        // Distribute evenly
        Map<Integer, Integer> quantities = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : slotsByType.entrySet()) {
            String typeKey = entry.getKey();
            List<Integer> slots = entry.getValue();
            int available = availableByType.getOrDefault(typeKey, 0);
            int numSlots = slots.size();
            int maxStack = new ItemStack(itemByType.get(typeKey)).getMaxCount();

            int base = Math.min(available / numSlots, maxStack);
            int remainder = available - base * numSlots;
            if (base == maxStack) remainder = 0;

            for (int i = 0; i < numSlots; i++) {
                int qty = base + (i < remainder ? 1 : 0);
                quantities.put(slots.get(i), qty);
                InvTweaksConfig.debugLog("EVEN-DIST", "slot %d -> %d items (type=%s)", slots.get(i), qty, typeKey);
            }
        }

        return quantities;
    }

    // ========== PASTE HELPERS ==========

    private static boolean moveItemOut(ScreenHandler handler, ClientPlayerInteractionManager im,
                                        PlayerEntity player, int targetSlotId,
                                        Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                        boolean isPlayerOnly) {
        ItemStack stack = handler.slots.get(targetSlotId).getStack();
        if (stack.isEmpty()) return true;

        Item itemType = stack.getItem();
        String itemComponents = serializeComponents(stack);

        // Smart displacement: prefer an empty target slot that actually wants this item type.
        // This places displaced items directly at their intended destination, avoiding
        // cascading displacements through "want empty" temp slots (critical for player
        // inventory paste where all accessible slots are target slots).
        int smartDest = -1;
        for (int i : allAccessible) {
            if (i == targetSlotId) continue;
            if (!handler.slots.get(i).getStack().isEmpty()) continue;
            SlotData destTarget = targetLayout.get(i);
            if (destTarget != null && destTarget.item() == itemType) {
                // Check component match if tracked
                boolean compMatch = destTarget.components() == null
                        || destTarget.components().equals(itemComponents);
                if (compMatch) {
                    smartDest = i;
                    break;
                }
            }
        }
        if (smartDest >= 0) {
            InvTweaksConfig.debugLog("PASTE", "moveOut slot=%d -> smartDest=%d (target wants this item)", targetSlotId, smartDest);
            im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, smartDest, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            if (!handler.getCursorStack().isEmpty()) {
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                return false;
            }
            return true;
        }

        int dest = findEmptyNonTargetSlot(handler, allAccessible, targetLayout, targetSlotId);
        if (dest >= 0) {
            InvTweaksConfig.debugLog("PASTE", "moveOut slot=%d -> empty=%d", targetSlotId, dest);
            im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, dest, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            if (!handler.getCursorStack().isEmpty()) {
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                return false;
            }
            return true;
        }

        for (int i : allAccessible) {
            if (i == targetSlotId) continue;
            ItemStack destStack = handler.slots.get(i).getStack();
            if (!destStack.isEmpty() && destStack.getItem() == itemType
                    && destStack.getCount() < destStack.getMaxCount()) {
                InvTweaksConfig.debugLog("PASTE", "moveOut slot=%d -> partial=%d", targetSlotId, i);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, i, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    int overflow = findAnyEmptySlot(handler, allAccessible);
                    if (overflow >= 0) {
                        im.clickSlot(handler.syncId, overflow, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    } else {
                        im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        return false;
                    }
                }
                return true;
            }
        }

        InvTweaksConfig.debugLog("PASTE", "moveOut FAILED slot=%d | no destination found", targetSlotId);
        return false;
    }

    private static void removeExcess(ScreenHandler handler, ClientPlayerInteractionManager im,
                                      PlayerEntity player, int slotId, int excessCount,
                                      Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                      boolean isPlayerOnly) {
        int dest = findEmptyNonTargetSlot(handler, allAccessible, targetLayout, slotId);
        if (dest < 0) dest = findAnyEmptySlot(handler, allAccessible);
        if (dest < 0) return;

        InvTweaksConfig.debugLog("PASTE", "removeExcess slot=%d | excess=%d -> dest=%d", slotId, excessCount, dest);

        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
        ItemStack cursor = handler.getCursorStack();
        int totalOnCursor = cursor.getCount();
        int toKeep = totalOnCursor - excessCount;

        for (int i = 0; i < toKeep; i++) {
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
        }
        im.clickSlot(handler.syncId, dest, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);

        if (!handler.getCursorStack().isEmpty()) {
            int overflow = findAnyEmptySlot(handler, allAccessible);
            if (overflow >= 0) {
                im.clickSlot(handler.syncId, overflow, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }
    }

    private static boolean addMore(ScreenHandler handler, ClientPlayerInteractionManager im,
                                    PlayerEntity player, int targetSlotId, Item itemType,
                                    String desiredComponents, int needed,
                                    Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                    boolean isPlayerOnly) {
        return addMore(handler, im, player, targetSlotId, itemType, desiredComponents, needed, allAccessible, targetLayout, isPlayerOnly, Collections.emptySet());
    }

    private static boolean addMore(ScreenHandler handler, ClientPlayerInteractionManager im,
                                    PlayerEntity player, int targetSlotId, Item itemType,
                                    String desiredComponents, int needed,
                                    Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                    boolean isPlayerOnly, Set<Integer> protectedSlots) {
        int remaining = needed;

        List<Integer> rankedSources = rankSourceSlots(handler, targetSlotId, itemType,
                desiredComponents, allAccessible, targetLayout, protectedSlots);

        for (int srcSlot : rankedSources) {
            if (remaining <= 0) break;

            ItemStack srcStack = handler.slots.get(srcSlot).getStack();
            if (srcStack.isEmpty() || srcStack.getItem() != itemType) continue;

            // Only exact-component matches for quantity maximization
            if (desiredComponents != null) {
                String srcComponents = serializeComponents(srcStack);
                if (!desiredComponents.equals(srcComponents)) continue;
            }

            int canTake = srcStack.getCount();
            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                // Only protect this item if it's the right variant for this slot
                boolean rightVariant = true;
                if (srcTarget.components() != null) {
                    String srcComp = serializeComponents(srcStack);
                    rightVariant = srcTarget.components().equals(srcComp);
                }
                if (rightVariant) {
                    canTake = Math.max(0, srcStack.getCount() - srcTarget.count());
                }
                // Wrong variant: fully available for sourcing (canTake unchanged)
            }
            if (canTake <= 0) continue;

            int toTake = Math.min(remaining, canTake);

            int srcCount = srcStack.getCount();
            InvTweaksConfig.debugLog("PASTE", "addMore slot=%d | need=%d | src=%d | taking=%d", targetSlotId, remaining, srcSlot, toTake);

            if (toTake == srcCount) {
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    remaining -= (toTake - handler.slots.get(srcSlot).getStack().getCount());
                } else {
                    remaining -= toTake;
                }
            } else {
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                int putBack = srcCount - toTake;
                for (int i = 0; i < putBack; i++) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                }
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
                remaining -= toTake;
            }

            if (!handler.getCursorStack().isEmpty()) {
                int dump = findAnyEmptySlot(handler, allAccessible);
                if (dump >= 0) {
                    im.clickSlot(handler.syncId, dump, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                } else {
                    break;
                }
            }
        }

        return remaining < needed;
    }

    private static boolean sourceItem(ScreenHandler handler, ClientPlayerInteractionManager im,
                                       PlayerEntity player, int targetSlotId, Item itemType, int desiredCount,
                                       String desiredComponents,
                                       Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                       boolean isPlayerOnly) {
        return sourceItem(handler, im, player, targetSlotId, itemType, desiredCount, desiredComponents, allAccessible, targetLayout, isPlayerOnly, Collections.emptySet());
    }

    private static boolean sourceItem(ScreenHandler handler, ClientPlayerInteractionManager im,
                                       PlayerEntity player, int targetSlotId, Item itemType, int desiredCount,
                                       String desiredComponents,
                                       Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                       boolean isPlayerOnly, Set<Integer> protectedSlots) {
        int remaining = desiredCount;

        List<Integer> rankedSources = rankSourceSlots(handler, targetSlotId, itemType,
                desiredComponents, allAccessible, targetLayout, protectedSlots);

        for (int srcSlot : rankedSources) {
            if (remaining <= 0) break;

            ItemStack srcStack = handler.slots.get(srcSlot).getStack();
            if (srcStack.isEmpty() || srcStack.getItem() != itemType) continue;

            // Only exact-component matches when components are tracked
            if (desiredComponents != null) {
                String srcComponents = serializeComponents(srcStack);
                if (!desiredComponents.equals(srcComponents)) continue;
            }

            int canTake = srcStack.getCount();
            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                // Only protect this item if it's the right variant for this slot
                boolean rightVariant = true;
                if (srcTarget.components() != null) {
                    String srcComp = serializeComponents(srcStack);
                    rightVariant = srcTarget.components().equals(srcComp);
                }
                if (rightVariant) {
                    canTake = Math.max(0, srcStack.getCount() - srcTarget.count());
                }
                // Wrong variant: fully available for sourcing (canTake unchanged)
            }
            if (canTake <= 0) continue;

            int toTake = Math.min(remaining, canTake);

            InvTweaksConfig.debugLog("PASTE", "sourceItem slot=%d | item=%s | need=%d | src=%d | taking=%d",
                    targetSlotId, itemType, remaining, srcSlot, toTake);

            int srcCount = srcStack.getCount();
            if (toTake == srcCount) {
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
                remaining -= toTake;
            } else {
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                int putBack = srcCount - toTake;
                for (int i = 0; i < putBack; i++) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                }
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
                remaining -= toTake;
            }

            if (!handler.getCursorStack().isEmpty()) {
                int dump = findAnyEmptySlot(handler, allAccessible);
                if (dump >= 0) {
                    im.clickSlot(handler.syncId, dump, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                } else {
                    break;
                }
            }
        }

        return remaining < desiredCount;
    }

    // ========== ARMOR/EQUIPMENT HELPERS ==========

    /**
     * Check if a handler slot is an armor or offhand slot (handler slots 5-8 or 45 in player inventory).
     */
    private static boolean isArmorOrOffhandSlot(int handlerSlot) {
        return (handlerSlot >= 5 && handlerSlot <= 8) || handlerSlot == 45;
    }

    /**
     * Check if an item can be equipped in the given armor/offhand handler slot.
     * Handler slot 5 = helmet, 6 = chestplate, 7 = leggings, 8 = boots, 45 = offhand.
     */
    private static boolean canEquipInSlot(Item item, int handlerSlot) {
        if (handlerSlot == 45) {
            InvTweaksConfig.debugLog("PASTE", "canEquipInSlot: item=%s slot=45(offhand) -> true", item);
            return true;
        }
        ItemStack testStack = new ItemStack(item);
        EquippableComponent equippable = testStack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            InvTweaksConfig.debugLog("PASTE", "canEquipInSlot: item=%s slot=%d -> false (no EQUIPPABLE component)", item, handlerSlot);
            return false;
        }

        EquipmentSlot expected;
        switch (handlerSlot) {
            case 5: expected = EquipmentSlot.HEAD; break;
            case 6: expected = EquipmentSlot.CHEST; break;
            case 7: expected = EquipmentSlot.LEGS; break;
            case 8: expected = EquipmentSlot.FEET; break;
            default:
                InvTweaksConfig.debugLog("PASTE", "canEquipInSlot: item=%s slot=%d -> false (unknown slot)", item, handlerSlot);
                return false;
        }
        boolean result = equippable.slot() == expected;
        InvTweaksConfig.debugLog("PASTE", "canEquipInSlot: item=%s slot=%d expected=%s actual=%s -> %s",
                item, handlerSlot, expected, equippable.slot(), result);
        return result;
    }

    // ========== SOURCE RANKING (Fixes 3 + 5) ==========

    /**
     * Rank source slots for item sourcing, combining identity matching (Fix 3)
     * and not-in-target-position preference (Fix 5).
     *
     * Tier 0: Exact component match AND not in its target position (best)
     * Tier 1: Exact component match AND in its target position
     * Tier 2: Type-only match AND not in its target position
     * Tier 3: Type-only match AND in its target position (worst)
     *
     * Within each tier, slots are sorted by handler index within the same side
     * (container slots before player slots, lowest index first within each side).
     */
    private static List<Integer> rankSourceSlots(ScreenHandler handler, int targetSlotId,
                                                  Item itemType, String desiredComponents,
                                                  Set<Integer> allAccessible,
                                                  Map<Integer, SlotData> targetLayout) {
        return rankSourceSlots(handler, targetSlotId, itemType, desiredComponents, allAccessible, targetLayout, Collections.emptySet());
    }

    private static List<Integer> rankSourceSlots(ScreenHandler handler, int targetSlotId,
                                                  Item itemType, String desiredComponents,
                                                  Set<Integer> allAccessible,
                                                  Map<Integer, SlotData> targetLayout,
                                                  Set<Integer> protectedSlots) {
        List<int[]> candidates = new ArrayList<>();

        for (int srcSlot : allAccessible) {
            if (srcSlot == targetSlotId) continue;
            if (protectedSlots.contains(srcSlot)) continue;
            ItemStack srcStack = handler.slots.get(srcSlot).getStack();
            if (srcStack.isEmpty() || srcStack.getItem() != itemType) continue;

            boolean inTargetPosition = false;
            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                inTargetPosition = true;
            }

            boolean exactMatch = false;
            if (desiredComponents != null) {
                String srcComponents = serializeComponents(srcStack);
                exactMatch = desiredComponents.equals(srcComponents);
            }

            int tier;
            if (exactMatch && !inTargetPosition) tier = 0;
            else if (exactMatch) tier = 1;
            else if (!inTargetPosition) tier = 2;
            else tier = 3;

            candidates.add(new int[]{srcSlot, tier});
        }

        candidates.sort((a, b) -> {
            if (a[1] != b[1]) return a[1] - b[1];
            boolean aIsPlayer = handler.slots.get(a[0]).inventory instanceof PlayerInventory;
            boolean bIsPlayer = handler.slots.get(b[0]).inventory instanceof PlayerInventory;
            if (aIsPlayer != bIsPlayer) return aIsPlayer ? 1 : -1;
            return a[0] - b[0];
        });

        List<Integer> result = new ArrayList<>();
        for (int[] c : candidates) result.add(c[0]);
        return result;
    }

    // ========== SLOT FINDING HELPERS ==========

    /**
     * Check if a handler slot is a hotbar slot.
     * For player inventory screens: handler slots 36-44 are hotbar.
     * For container screens: slots whose inventory index is 0-8 in PlayerInventory are hotbar.
     */
    private static boolean isHotbarSlot(ScreenHandler handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size()) return false;
        Slot slot = handler.slots.get(slotId);
        if (slot.inventory instanceof PlayerInventory) {
            int invIdx = slot.getIndex();
            return invIdx >= 0 && invIdx <= 8;
        }
        return false;
    }

    private static int findEmptyNonTargetSlot(ScreenHandler handler, Set<Integer> allAccessible,
                                               Map<Integer, SlotData> targetLayout, int excludeSlot) {
        // First pass: non-target, non-hotbar, non-armor/offhand empty slots
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (targetLayout.containsKey(i)) continue;
            if (isHotbarSlot(handler, i)) continue;
            if (isArmorOrOffhandSlot(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Second pass: non-target hotbar empty slots (fallback, still skip armor/offhand)
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (targetLayout.containsKey(i)) continue;
            if (isArmorOrOffhandSlot(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Third pass: target slots that want to be empty (skip armor/offhand)
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (isArmorOrOffhandSlot(i)) continue;
            SlotData target = targetLayout.get(i);
            if (target != null && target.item() == null && handler.slots.get(i).getStack().isEmpty()) return i;
        }
        return findAnyEmptySlot(handler, allAccessible, excludeSlot);
    }

    private static int findAnyEmptySlot(ScreenHandler handler, Set<Integer> allAccessible) {
        return findAnyEmptySlot(handler, allAccessible, -1);
    }

    private static int findAnyEmptySlot(ScreenHandler handler, Set<Integer> allAccessible, int excludeSlot) {
        // Prefer non-hotbar, non-armor/offhand slots first
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (isHotbarSlot(handler, i)) continue;
            if (isArmorOrOffhandSlot(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Fallback: hotbar slots (still skip armor/offhand)
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (isArmorOrOffhandSlot(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    // ========== PUBLIC API ==========

    public static boolean hasClipboard() {
        return activeContainerIndex >= 0 || activePlayerIndex >= 0 || activeBundleIndex >= 0;
    }

    public static void clearClipboard() {
        clearHistory();
    }

    /**
     * Get the active clipboard entry for the given context.
     * Returns null if no active clipboard exists.
     */
    public static HistoryEntry getActiveEntry(boolean isPlayerOnly) {
        int activeIndex = isPlayerOnly ? activePlayerIndex : activeContainerIndex;
        if (activeIndex < 0 || activeIndex >= history.size()) return null;
        return history.get(activeIndex);
    }

    /**
     * Get the active clipboard entry for the given context, with category awareness.
     */
    public static HistoryEntry getActiveEntry(boolean isPlayerOnly, ContainerClassifier.ContainerCategory category) {
        if (isPlayerOnly) {
            if (activePlayerIndex < 0 || activePlayerIndex >= history.size()) return null;
            return history.get(activePlayerIndex);
        }
        if (category != null) {
            int activeIndex = getActiveIndexForCategory(category);
            if (activeIndex < 0 || activeIndex >= history.size()) return null;
            return history.get(activeIndex);
        }
        if (activeContainerIndex < 0 || activeContainerIndex >= history.size()) return null;
        return history.get(activeContainerIndex);
    }

    /**
     * Get the active bundle clipboard entry.
     * Returns null if no active bundle clipboard exists.
     */
    public static HistoryEntry getActiveBundleEntry() {
        if (activeBundleIndex < 0 || activeBundleIndex >= history.size()) return null;
        HistoryEntry entry = history.get(activeBundleIndex);
        if (!entry.isBundle()) return null;
        return entry;
    }

    /**
     * Get the number of non-player (container) slots in the current handler.
     */
    public static int getContainerSlotCount(ScreenHandler handler) {
        int count = 0;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            if (!(slot.inventory instanceof PlayerInventory)) {
                count++;
            }
        }
        return count;
    }

    // ========== BUNDLE CLIPBOARD ==========

    public static boolean copyBundleLayout(ItemStack bundleStack, int bundleSlotIndex) {
        return copyBundleLayout(bundleStack, bundleSlotIndex, false);
    }

    /**
     * Copy the contents of a bundle ItemStack to the clipboard as a "bundle" type entry.
     * @param bundleStack the bundle ItemStack to copy from
     * @param bundleSlotIndex the slot index where the bundle is located (for debug logging)
     * @param silent if true, skip the overlay message (useful when caller shows its own message)
     * @return true if the copy was successful
     */
    public static boolean copyBundleLayout(ItemStack bundleStack, int bundleSlotIndex, boolean silent) {
        if (bundleStack.isEmpty()) return false;

        // VERIFY: DataComponentTypes.BUNDLE_CONTENTS — this is the Yarn name for 1.21.11
        BundleContentsComponent contents = bundleStack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (contents == null) {
            InvTweaksConfig.debugLog("BUNDLE-COPY", "no BUNDLE_CONTENTS component on stack");
            return false;
        }

        // Iterate bundle contents and build slot data
        Map<Integer, SlotData> slots = new LinkedHashMap<>();
        int slotIndex = 0;
        for (ItemStack stack : contents.iterate()) {
            if (stack.isEmpty()) continue;
            slots.put(slotIndex, new SlotData(stack.getItem(), stack.getCount(), serializeComponents(stack)));
            slotIndex++;
        }

        if (slots.isEmpty()) {
            InvTweaksOverlay.show("Bundle is empty", 0xFFFFFF55);
            InvTweaksConfig.debugLog("BUNDLE-COPY", "bundle is empty, skipping");
            return false;
        }

        // Determine bundle display name for the label
        // Format: "Bundle: [display name] (X items)" or "Bundle (X items)" for unnamed
        String bundleName;
        if (bundleStack.contains(DataComponentTypes.CUSTOM_NAME)) {
            bundleName = bundleStack.getName().getString();
        } else {
            bundleName = null;
        }
        String label;
        if (bundleName != null) {
            label = "Bundle: " + bundleName + " (" + slots.size() + " items)";
        } else {
            label = "Bundle (" + slots.size() + " items)";
        }

        // Build the snapshot — use isPlayerInventory=false since bundles are their own thing
        LayoutSnapshot snapshot = new LayoutSnapshot(slots.size(), slots, false);
        long timestamp = System.currentTimeMillis();
        long playtime = getCurrentPlaytimeMinutes();

        String containerTitle = bundleName != null ? bundleName : "Bundle";
        HistoryEntry entry = new HistoryEntry(snapshot, label, timestamp, playtime, containerTitle, TYPE_BUNDLE);

        // Store bundle dye color if present
        DyedColorComponent dyedColor = bundleStack.get(DataComponentTypes.DYED_COLOR);
        if (dyedColor != null) {
        }

        // Deduplication
        int dupIndex = findDuplicate(entry);
        if (dupIndex >= 0) {
            InvTweaksConfig.debugLog("BUNDLE-COPY", "dedup: replacing existing bundle entry at index %d | label=%s", dupIndex, history.get(dupIndex).label);
            removeDuplicate(dupIndex);
        }

        addToHistory(entry);
        ClipboardStorage.save();

        if (!silent) {
            InvTweaksOverlay.show("Bundle layout copied (" + slots.size() + " items)", 0xFF55FF55);
        }
        InvTweaksConfig.debugLog("BUNDLE-COPY", "copied bundle | slot=%d | items=%d | label=%s | dedup=%s",
                bundleSlotIndex, slots.size(), label, dupIndex >= 0);

        return true;
    }

    /**
     * Result of a bundle paste operation.
     */
    public static class BundlePasteResult {
        public final int itemsInserted;
        public final int itemsTotal;
        public final boolean noClipboard;
        public final boolean bundleOnCursor;
        public final boolean missingItems;
        public final String message;

        private BundlePasteResult(int inserted, int total, boolean noClipboard, boolean bundleOnCursor, boolean missingItems, String message) {
            this.itemsInserted = inserted;
            this.itemsTotal = total;
            this.noClipboard = noClipboard;
            this.bundleOnCursor = bundleOnCursor;
            this.missingItems = missingItems;
            this.message = message;
        }

        public static BundlePasteResult success(int inserted, int total) {
            return new BundlePasteResult(inserted, total, false, false, false, null);
        }
        public static BundlePasteResult partial(int inserted, int total, boolean missingItems) {
            return new BundlePasteResult(inserted, total, false, false, missingItems, null);
        }
        public static BundlePasteResult noClipboard() {
            return new BundlePasteResult(0, 0, true, false, false, null);
        }
        public static BundlePasteResult bundleOnCursor() {
            return new BundlePasteResult(0, 0, false, true, false, null);
        }
    }

    /**
     * Paste the active bundle clipboard layout into a bundle in the given slot.
     * Items are pulled from available inventory slots and inserted via simulated clicks.
     *
     * @param handler the current screen handler
     * @param bundleSlotId the handler slot ID containing the target bundle
     * @return a BundlePasteResult describing the outcome
     */
    public static BundlePasteResult pasteBundleLayout(ScreenHandler handler, int bundleSlotId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerInteractionManager im = mc.interactionManager;
        PlayerEntity player = mc.player;
        if (im == null || player == null) return BundlePasteResult.noClipboard();

        // Check for active bundle clipboard
        HistoryEntry bundleEntry = getActiveBundleEntry();
        if (bundleEntry == null) {
            return BundlePasteResult.noClipboard();
        }

        Map<Integer, SlotData> clipboardSlots = bundleEntry.snapshot.slots;
        int totalItems = 0;
        for (SlotData sd : clipboardSlots.values()) {
            if (sd.item() != null) totalItems++;
        }

        if (totalItems == 0) {
            return BundlePasteResult.success(0, 0);
        }

        InvTweaksConfig.debugLog("BUNDLE-PASTE", "starting | bundleSlot=%d | clipboardItems=%d", bundleSlotId, totalItems);

        // Collect all accessible source slots (player inventory + open container, but NOT other bundles)
        Set<Integer> sourceSlots = new LinkedHashSet<>();
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == bundleSlotId) continue;
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            // Don't pull from other bundles
            if (!stack.isEmpty() && stack.getItem() instanceof BundleItem) continue;
            sourceSlots.add(i);
        }

        int inserted = 0;
        boolean missingItems = false;

        // Process items in clipboard order
        List<Integer> sortedKeys = new ArrayList<>(clipboardSlots.keySet());
        Collections.sort(sortedKeys);

        for (int key : sortedKeys) {
            SlotData desired = clipboardSlots.get(key);
            if (desired.item() == null) continue;

            // Find a source slot with matching item
            int sourceSlot = findBundlePasteSource(handler, desired, sourceSlots);
            if (sourceSlot < 0) {
                InvTweaksConfig.debugLog("BUNDLE-PASTE", "no source for item %s (position %d)", desired.item(), key);
                missingItems = true;
                continue;
            }

            // Pick up from source slot
            ItemStack sourceStack = handler.slots.get(sourceSlot).getStack();
            int sourceCount = sourceStack.getCount();
            int wantCount = desired.count();

            InvTweaksConfig.debugLog("BUNDLE-PASTE", "inserting: slot %d -> bundle %d | item=%s | want=%d | sourceHas=%d",
                    sourceSlot, bundleSlotId, desired.item(), wantCount, sourceCount);

            if (wantCount >= sourceCount) {
                // Take the whole stack — left-click source to pick up, left-click bundle to insert
                im.clickSlot(handler.syncId, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, bundleSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);

                // If cursor still has items (bundle full), put them back
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    InvTweaksConfig.debugLog("BUNDLE-PASTE", "bundle full, returned items to slot %d", sourceSlot);

                    // Check if any items were actually inserted
                    ItemStack afterReturn = handler.slots.get(sourceSlot).getStack();
                    if (afterReturn.getCount() < sourceCount) {
                        inserted++;
                        InvTweaksConfig.debugLog("BUNDLE-PASTE", "partial insert success (position %d)", key);
                    }
                    break;
                }
                inserted++;
            } else {
                // Take only what we need — pick up stack, right-click to put back extras, then insert
                im.clickSlot(handler.syncId, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                // Put back (sourceCount - wantCount) items one at a time via right-click
                int putBack = sourceCount - wantCount;
                for (int i = 0; i < putBack; i++) {
                    im.clickSlot(handler.syncId, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                }
                // Now insert the remainder into the bundle
                im.clickSlot(handler.syncId, bundleSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);

                // If cursor still has items (bundle full), put them back
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, sourceSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    InvTweaksConfig.debugLog("BUNDLE-PASTE", "bundle full after partial, returned to slot %d", sourceSlot);
                    break;
                }
                inserted++;
            }

            InvTweaksConfig.debugLog("BUNDLE-PASTE", "insert success (position %d) | item=%s", key, desired.item());
        }

        // Ensure cursor is clean
        if (!handler.getCursorStack().isEmpty()) {
            int emptySlot = findAnyEmptySlot(handler, sourceSlots);
            if (emptySlot >= 0) {
                im.clickSlot(handler.syncId, emptySlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }

        InvTweaksConfig.debugLog("BUNDLE-PASTE", "complete | inserted=%d/%d | missingItems=%s", inserted, totalItems, missingItems);

        if (inserted == totalItems) {
            return BundlePasteResult.success(inserted, totalItems);
        } else {
            return BundlePasteResult.partial(inserted, totalItems, missingItems);
        }
    }

    /**
     * Find a source slot for bundle paste that matches the desired item.
     * Uses component-aware matching with preference for exact matches.
     */
    private static int findBundlePasteSource(ScreenHandler handler, SlotData desired, Set<Integer> sourceSlots) {
        int exactMatch = -1;
        int typeMatch = -1;

        for (int slotId : sourceSlots) {
            ItemStack stack = handler.slots.get(slotId).getStack();
            if (stack.isEmpty() || stack.getItem() != desired.item()) continue;

            if (desired.components() != null && !desired.components().isEmpty()) {
                String srcComponents = serializeComponents(stack);
                if (desired.components().equals(srcComponents)) {
                    if (exactMatch < 0) exactMatch = slotId;
                } else {
                    if (typeMatch < 0) typeMatch = slotId;
                }
            } else {
                if (exactMatch < 0) exactMatch = slotId;
            }
        }

        return exactMatch >= 0 ? exactMatch : typeMatch;
    }

    /**
     * Slice clipboard data to a specific half (for 54→27 paste).
     * @param fullData the full 54-slot clipboard data
     * @param half "top" for keys 0-26, "bottom" for keys 27-53 (remapped to 0-26)
     * @return sliced data with keys remapped to 0-26
     */
    public static Map<Integer, SlotData> sliceClipboardHalf(Map<Integer, SlotData> fullData, String half) {
        Map<Integer, SlotData> sliced = new LinkedHashMap<>();
        List<Integer> sortedKeys = new ArrayList<>(fullData.keySet());
        Collections.sort(sortedKeys);

        if (half.equals("top")) {
            // Take first 27 slots (keys 0-26)
            for (int key : sortedKeys) {
                if (key < 27) {
                    sliced.put(key, fullData.get(key));
                }
            }
        } else {
            // Take keys 27-53, remap to 0-26
            for (int key : sortedKeys) {
                if (key >= 27 && key < 54) {
                    sliced.put(key - 27, fullData.get(key));
                }
            }
        }

        InvTweaksConfig.debugLog("HALF-SELECT", "sliced %s half: %d slots from %d total", half, sliced.size(), fullData.size());
        return sliced;
    }
}
