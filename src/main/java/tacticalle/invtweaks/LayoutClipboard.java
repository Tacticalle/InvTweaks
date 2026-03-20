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
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
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

/**
 * Clipboard-based copy/paste of container layouts with history support.
 * Maintains a history ring of past clipboard snapshots with persistent storage.
 */
public class LayoutClipboard {

    /**
     * A snapshot of one slot's contents.
     * The components field stores serialized NBT component data (null for simple items or old entries).
     */
    public record SlotData(Item item, int count, String components) {}

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

        public HistoryEntry(LayoutSnapshot snapshot, String label, long timestamp, long playtimeMinutes) {
            this.snapshot = snapshot;
            this.label = label;
            this.timestamp = timestamp;
            this.playtimeMinutes = playtimeMinutes;
        }
    }

    // History ring
    private static final List<HistoryEntry> history = new ArrayList<>();
    private static int activeContainerIndex = -1;
    private static int activePlayerIndex = -1;

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

    /**
     * Set the active index for the given history entry (auto-detects type).
     */
    public static void setActiveIndex(int index) {
        if (index < 0 || index >= history.size()) return;
        HistoryEntry entry = history.get(index);
        if (entry.snapshot.isPlayerInventory) {
            activePlayerIndex = index;
            InvTweaksConfig.debugLog("CLIPBOARD", "setActiveIndex: player=%d label=%s", index, entry.label);
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
        ClipboardStorage.save();
    }

    public static void clearHistory() {
        history.clear();
        activeContainerIndex = -1;
        activePlayerIndex = -1;
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

        // Set the new entry as active for its type
        if (entry.snapshot.isPlayerInventory) {
            activePlayerIndex = 0;
        } else {
            activeContainerIndex = 0;
        }

        // Prune excess entries
        int maxHistory = InvTweaksConfig.get().clipboardMaxHistory;
        while (history.size() > maxHistory) {
            int removeIdx = history.size() - 1;
            if (activeContainerIndex == removeIdx) activeContainerIndex = -1;
            if (activePlayerIndex == removeIdx) activePlayerIndex = -1;
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
        history.clear();
        history.addAll(entries);
        activeContainerIndex = containerIdx;
        activePlayerIndex = playerIdx;
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
            InvTweaksConfig.debugLog("CLIPBOARD", "serializeComponents: item=%s fullNbt=%s", stack.getItem(), nbt);
            if (nbt instanceof NbtCompound compound) {
                if (compound.contains("components")) {
                    String comp = compound.get("components").toString();
                    InvTweaksConfig.debugLog("CLIPBOARD", "serializeComponents: components=%s", comp);
                    return comp;
                }
            }
            InvTweaksConfig.debugLog("CLIPBOARD", "serializeComponents: no components key found, returning empty string");
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
            InvTweaksConfig.debugLog("CLIPBOARD", "Failed to reconstruct stack: %s", e.getMessage());
        }
        return new ItemStack(item, count);
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

        HistoryEntry entry = new HistoryEntry(snapshot, label, timestamp, playtime);
        addToHistory(entry);
        ClipboardStorage.save();

        if (!silent) {
            InvTweaksOverlay.show("Layout copied", 0xFF55FF55);
        }
        InvTweaksConfig.debugLog("COPY", "copied %d slots | playerOnly=%s | label=%s", slots.size(), isPlayerOnly, label);
    }

    // ========== PASTE ==========

    /**
     * Paste the clipboard layout into the current container/inventory.
     */
    public static void pasteLayout(ScreenHandler handler, boolean isPlayerOnly) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerInteractionManager im = mc.interactionManager;
        PlayerEntity player = mc.player;
        if (im == null || player == null) return;

        // Get the active clipboard entry
        int activeIndex = isPlayerOnly ? activePlayerIndex : activeContainerIndex;
        InvTweaksConfig.debugLog("PASTE", "using clipboard entry: index=%d type=%s activeContainer=%d activePlayer=%d historySize=%d",
                activeIndex, isPlayerOnly ? "player" : "container", activeContainerIndex, activePlayerIndex, history.size());
        if (activeIndex < 0 || activeIndex >= history.size()) {
            InvTweaksOverlay.show("No layout copied", 0xFFFF5555);
            return;
        }

        HistoryEntry activeEntry = history.get(activeIndex);
        InvTweaksConfig.debugLog("PASTE", "selected entry: label=%s isPlayer=%s slots=%d timestamp=%d",
                activeEntry.label, activeEntry.snapshot.isPlayerInventory, activeEntry.snapshot.slotCount, activeEntry.timestamp);
        LayoutSnapshot clipboard = activeEntry.snapshot;

        // Check clipboard type matches current context
        if (clipboard.isPlayerInventory != isPlayerOnly) {
            String clipType = clipboard.isPlayerInventory ? "player inventory" : "container";
            String currentType = isPlayerOnly ? "player inventory" : "container";
            InvTweaksOverlay.show("Layout was copied from " + clipType + ", can't paste into " + currentType, 0xFFFF5555);
            return;
        }

        // Check if cursor has items — abort paste if so
        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack != null && !cursorStack.isEmpty()) {
            InvTweaksOverlay.show("Put away held items first", 0xFFFFFF55);
            return;
        }

        // Determine target slots and build clipboard-key → handler-slot mapping
        // For player inventory: clipboard keys 0-8 → handler slots 36-44, keys 9-35 → handler slots 9-35
        // For containers: clipboard keys map sequentially to non-player slots
        List<Integer> targetSlotIds = new ArrayList<>();
        Map<Integer, SlotData> targetLayout = new LinkedHashMap<>();
        boolean sizeMismatch = false;

        if (isPlayerOnly) {
            // Build direct mapping from clipboard slot keys to handler slot IDs
            for (Map.Entry<Integer, SlotData> clipEntry : clipboard.slots.entrySet()) {
                int clipKey = clipEntry.getKey();
                int handlerSlot;
                if (clipKey >= 0 && clipKey <= 8) {
                    // Hotbar: snapshot key 0-8 → handler slot 36-44
                    handlerSlot = clipKey + 36;
                } else if (clipKey >= 9 && clipKey <= 35) {
                    // Main inventory: snapshot key 9-35 → handler slot 9-35
                    handlerSlot = clipKey;
                } else if (clipKey >= 36 && clipKey <= 39) {
                    // Armor: snapshot key 36-39 → handler slot 5-8
                    handlerSlot = clipKey - 36 + 5;
                } else if (clipKey == 40) {
                    // Offhand: snapshot key 40 → handler slot 45
                    handlerSlot = 45;
                } else {
                    continue; // unknown slot key, skip
                }
                if (handlerSlot < handler.slots.size()) {
                    targetSlotIds.add(handlerSlot);
                    targetLayout.put(handlerSlot, clipEntry.getValue());
                }
            }
        } else {
            // Container: collect non-player slots as targets
            List<Integer> containerSlotIds = new ArrayList<>();
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (!(slot.inventory instanceof PlayerInventory)) {
                    containerSlotIds.add(i);
                }
            }
            targetSlotIds.addAll(containerSlotIds);

            // Size mismatch detection (flag only, no message yet — Fix 1)
            if (containerSlotIds.size() != clipboard.slotCount) {
                sizeMismatch = true;
                InvTweaksConfig.debugLog("PASTE", "size mismatch | clipboard=%d | target=%d", clipboard.slotCount, containerSlotIds.size());
            }

            // Sequential mapping for containers
            List<Integer> clipboardSlotIds = new ArrayList<>(clipboard.slots.keySet());
            Collections.sort(clipboardSlotIds);
            int slotsToProcess = Math.min(clipboardSlotIds.size(), containerSlotIds.size());
            for (int i = 0; i < slotsToProcess; i++) {
                int clipSlotId = clipboardSlotIds.get(i);
                int actualSlotId = containerSlotIds.get(i);
                targetLayout.put(actualSlotId, clipboard.slots.get(clipSlotId));
            }
        }

        // Build item pool: what items are available across all accessible slots
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

        // Build set of item types present in the clipboard snapshot
        Set<Item> clipboardItemTypes = new HashSet<>();
        for (SlotData sd : clipboard.slots.values()) {
            if (sd != null && sd.item() != null) {
                clipboardItemTypes.add(sd.item());
            }
        }

        // Check if any matching items are available in accessible slots
        int availableMatchCount = 0;
        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            SlotData desired = entry.getValue();
            if (desired.item() == null) continue;
            // Check if this item type exists anywhere in accessible slots
            for (int slotId : allAccessibleSlots) {
                ItemStack stack = handler.slots.get(slotId).getStack();
                if (!stack.isEmpty() && stack.getItem() == desired.item()) {
                    availableMatchCount++;
                    break;
                }
            }
        }

        // Fix 1: "No matching items" takes priority over "size mismatch"
        boolean clipboardHasItems = !clipboardItemTypes.isEmpty();
        if (clipboardHasItems && availableMatchCount == 0) {
            InvTweaksOverlay.show("No matching items available", 0xFFFFFF55);
            return;
        }

        // Fix 2: Block all size-mismatched pastes (after no-match check, before any item movement)
        if (sizeMismatch) {
            InvTweaksOverlay.show("Layout size incompatible", 0xFFFFFF55);
            InvTweaksConfig.debugLog("PASTE", "blocked: clipboard=%d target=%d", clipboard.slotCount,
                    (int) targetSlotIds.stream().count());
            return;
        }

        InvTweaksConfig.debugLog("PASTE", "starting paste | targetSlots=%d | accessibleSlots=%d", targetLayout.size(), allAccessibleSlots.size());

        int matchedSlots = 0;

        // Quick check: is the layout already matching? (checks item TYPE only, not count)
        boolean alreadyMatches = true;
        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            int slotId = entry.getKey();
            SlotData desired = entry.getValue();
            ItemStack current = handler.slots.get(slotId).getStack();
            if (desired.item() == null) {
                // Clipboard says empty — only fail if slot has an item that IS in the clipboard layout
                // (non-layout items are left alone by paste, so they don't count as a mismatch)
                if (!current.isEmpty() && clipboardItemTypes.contains(current.getItem())) {
                    InvTweaksConfig.debugLog("PASTE", "alreadyMatches: slot=%d should be empty but has layout item %s", slotId, current.getItem());
                    alreadyMatches = false; break;
                }
            } else {
                if (current.isEmpty() || current.getItem() != desired.item()) {
                    InvTweaksConfig.debugLog("PASTE", "alreadyMatches: slot=%d clipboard=%s actual=%s", slotId, desired.item(), current.isEmpty() ? "empty" : current.getItem());
                    alreadyMatches = false; break;
                }
                // Also check components — two ominous bottles at different levels are NOT a match
                if (desired.components() != null) {
                    String currentComponents = serializeComponents(current);
                    if (!desired.components().equals(currentComponents)) {
                        InvTweaksConfig.debugLog("PASTE", "alreadyMatches: slot=%d type matches but components differ", slotId);
                        alreadyMatches = false; break;
                    }
                }
            }
        }
        if (alreadyMatches) {
            InvTweaksOverlay.show("Layout already matches!", 0xFF55FF55);
            return;
        }

        // Process each target slot
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

            // Safety: if cursor has items from mod operations, try to put them away first
            if (!handler.getCursorStack().isEmpty()) {
                int dump = findAnyEmptySlot(handler, allAccessibleSlots);
                if (dump >= 0) {
                    im.clickSlot(handler.syncId, dump, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                } else {
                    InvTweaksConfig.debugLog("PASTE", "ABORT: cursor not empty and no room at slot %d", targetSlotId);
                    break;
                }
            }

            // Re-read the slot fresh
            ItemStack currentStack = handler.slots.get(targetSlotId).getStack();

            if (desired.item() == null) {
                if (currentStack.isEmpty()) {
                    matchedSlots++;
                    continue;
                }
                // Fix 2: Only move this item out if its type is part of the clipboard layout
                if (!clipboardItemTypes.contains(currentStack.getItem())) {
                    // This item type isn't in the clipboard — leave it alone
                    InvTweaksConfig.debugLog("PASTE", "skipping non-layout item at slot %d (%s)", targetSlotId, currentStack.getItem());
                    continue;
                }
                boolean moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                if (moved) {
                    matchedSlots++;
                }
                continue;
            }

            // Fix 5: Paste quantity maximization — fill to max stack size, not clipboard count
            int maxStack = new ItemStack(desired.item()).getMaxCount();

            // Target should have a specific item
            boolean typeMatches = !currentStack.isEmpty() && currentStack.getItem() == desired.item();
            boolean componentMatches = true;
            if (typeMatches && desired.components() != null) {
                String currentComponents = serializeComponents(currentStack);
                componentMatches = desired.components().equals(currentComponents);
            }
            if (typeMatches && componentMatches) {
                if (currentStack.getCount() >= maxStack) {
                    matchedSlots++;
                    continue;
                }
                // Try to fill up to max stack size
                boolean filled = addMore(handler, im, player, targetSlotId, desired.item(),
                        desired.components(), maxStack - currentStack.getCount(), allAccessibleSlots, targetLayout, isPlayerOnly);
                matchedSlots++;
                continue;
            }

            // Wrong item or empty — need to place the desired item here
            if (!currentStack.isEmpty()) {
                // Fix 2: Only displace if this item type is part of the clipboard layout
                if (!clipboardItemTypes.contains(currentStack.getItem())) {
                    // Non-layout item occupying a target slot — skip, don't displace
                    InvTweaksConfig.debugLog("PASTE", "skipping non-layout item at target slot %d (%s)", targetSlotId, currentStack.getItem());
                    continue;
                }
                boolean moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                if (!moved) {
                    InvTweaksConfig.debugLog("PASTE", "could not displace item at slot %d", targetSlotId);
                    continue;
                }
            }

            boolean placed = sourceItem(handler, im, player, targetSlotId, desired.item(), maxStack,
                    desired.components(), allAccessibleSlots, targetLayout, isPlayerOnly);
            if (placed) matchedSlots++;
        }

        // Final safety: make sure cursor is clean
        if (!handler.getCursorStack().isEmpty()) {
            int emptySlot = findAnyEmptySlot(handler, allAccessibleSlots);
            if (emptySlot >= 0) {
                im.clickSlot(handler.syncId, emptySlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }

        InvTweaksOverlay.show("Layout pasted", 0xFF55FF55);
        InvTweaksConfig.debugLog("PASTE", "complete | matched=%d/%d", matchedSlots, targetLayout.size());
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
        addToHistory(new HistoryEntry(snapshot, "Death snapshot",
                System.currentTimeMillis(), getCurrentPlaytimeMinutes()));
        ClipboardStorage.save();

        InvTweaksOverlay.show("Inventory saved (death)", 0xFFFFAA00); // orange
        InvTweaksConfig.debugLog("DEATH", "Saved cached inventory as death snapshot");
    }

    // ========== PASTE HELPERS ==========

    private static boolean moveItemOut(ScreenHandler handler, ClientPlayerInteractionManager im,
                                        PlayerEntity player, int targetSlotId,
                                        Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                        boolean isPlayerOnly) {
        ItemStack stack = handler.slots.get(targetSlotId).getStack();
        if (stack.isEmpty()) return true;

        Item itemType = stack.getItem();

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
        int remaining = needed;

        List<Integer> rankedSources = rankSourceSlots(handler, targetSlotId, itemType,
                desiredComponents, allAccessible, targetLayout);

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

            InvTweaksConfig.debugLog("PASTE", "addMore slot=%d | need=%d | src=%d | taking=%d", targetSlotId, remaining, srcSlot, toTake);

            if (toTake == srcStack.getCount()) {
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
                int putBack = srcStack.getCount() - toTake;
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
        int remaining = desiredCount;

        List<Integer> rankedSources = rankSourceSlots(handler, targetSlotId, itemType,
                desiredComponents, allAccessible, targetLayout);

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

            if (toTake == srcStack.getCount()) {
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
                remaining -= toTake;
            } else {
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                int putBack = srcStack.getCount() - toTake;
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
        List<int[]> candidates = new ArrayList<>();

        for (int srcSlot : allAccessible) {
            if (srcSlot == targetSlotId) continue;
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
        // First pass: non-target, non-hotbar empty slots
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (targetLayout.containsKey(i)) continue;
            if (isHotbarSlot(handler, i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Second pass: non-target hotbar empty slots (fallback)
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (targetLayout.containsKey(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Third pass: target slots that want to be empty
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            SlotData target = targetLayout.get(i);
            if (target != null && target.item() == null && handler.slots.get(i).getStack().isEmpty()) return i;
        }
        return findAnyEmptySlot(handler, allAccessible, excludeSlot);
    }

    private static int findAnyEmptySlot(ScreenHandler handler, Set<Integer> allAccessible) {
        return findAnyEmptySlot(handler, allAccessible, -1);
    }

    private static int findAnyEmptySlot(ScreenHandler handler, Set<Integer> allAccessible, int excludeSlot) {
        // Prefer non-hotbar slots first
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (isHotbarSlot(handler, i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Fallback: hotbar slots
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    // ========== PUBLIC API ==========

    public static boolean hasClipboard() {
        return activeContainerIndex >= 0 || activePlayerIndex >= 0;
    }

    public static void clearClipboard() {
        clearHistory();
    }
}
