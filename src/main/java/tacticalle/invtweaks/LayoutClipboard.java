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
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Clipboard-based copy/paste of container layouts with history support.
 * Maintains a history ring of past clipboard snapshots with persistent storage.
 */
public class LayoutClipboard {

    /**
     * A snapshot of one slot's contents.
     */
    public record SlotData(Item item, int count) {}

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
        } else {
            activeContainerIndex = index;
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

    // ========== COPY ==========

    /**
     * Copy the current container/inventory layout to the clipboard.
     */
    public static void copyLayout(ScreenHandler handler, boolean isPlayerOnly) {
        Map<Integer, SlotData> slots = new LinkedHashMap<>();

        if (isPlayerOnly) {
            for (int i = 9; i <= 44; i++) {
                if (i >= handler.slots.size()) break;
                Slot slot = handler.slots.get(i);
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    slots.put(i, new SlotData(null, 0));
                } else {
                    slots.put(i, new SlotData(stack.getItem(), stack.getCount()));
                }
            }
        } else {
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (slot.inventory instanceof PlayerInventory) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    slots.put(i, new SlotData(null, 0));
                } else {
                    slots.put(i, new SlotData(stack.getItem(), stack.getCount()));
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

        InvTweaksOverlay.show("Layout copied", 0xFF55FF55);
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
        if (activeIndex < 0 || activeIndex >= history.size()) {
            InvTweaksOverlay.show("No layout copied", 0xFFFF5555);
            return;
        }

        LayoutSnapshot clipboard = history.get(activeIndex).snapshot;

        // Check clipboard type matches current context
        if (clipboard.isPlayerInventory != isPlayerOnly) {
            String clipType = clipboard.isPlayerInventory ? "player inventory" : "container";
            String currentType = isPlayerOnly ? "player inventory" : "container";
            InvTweaksOverlay.show("Layout was copied from " + clipType + ", can't paste into " + currentType, 0xFFFF5555);
            return;
        }

        // Determine the target slots for this container
        List<Integer> targetSlotIds = new ArrayList<>();
        if (isPlayerOnly) {
            for (int i = 9; i <= 44; i++) {
                if (i < handler.slots.size()) targetSlotIds.add(i);
            }
        } else {
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (!(slot.inventory instanceof PlayerInventory)) {
                    targetSlotIds.add(i);
                }
            }
        }

        // Size mismatch warning
        if (targetSlotIds.size() != clipboard.slotCount) {
            InvTweaksOverlay.show("Layout size mismatch. Pasting what fits.", 0xFFFFFF55);
            InvTweaksConfig.debugLog("PASTE", "size mismatch | clipboard=%d | target=%d", clipboard.slotCount, targetSlotIds.size());
        }

        // Build a mapping from clipboard relative index -> actual slot ID in current container
        List<Integer> clipboardSlotIds = new ArrayList<>(clipboard.slots.keySet());
        Collections.sort(clipboardSlotIds);

        int slotsToProcess = Math.min(clipboardSlotIds.size(), targetSlotIds.size());

        // Build the target layout: targetSlotId -> desired SlotData
        Map<Integer, SlotData> targetLayout = new LinkedHashMap<>();
        for (int i = 0; i < slotsToProcess; i++) {
            int clipSlotId = clipboardSlotIds.get(i);
            int actualSlotId = targetSlotIds.get(i);
            targetLayout.put(actualSlotId, clipboard.slots.get(clipSlotId));
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

        InvTweaksConfig.debugLog("PASTE", "starting paste | targetSlots=%d | accessibleSlots=%d", slotsToProcess, allAccessibleSlots.size());

        int matchedSlots = 0;

        // If cursor has items, save them to a temp slot first
        int cursorSaveSlot = -1;
        if (!handler.getCursorStack().isEmpty()) {
            cursorSaveSlot = findAnyEmptySlot(handler, allAccessibleSlots);
            if (cursorSaveSlot >= 0) {
                InvTweaksConfig.debugLog("PASTE", "saving cursor items to temp slot %d", cursorSaveSlot);
                im.clickSlot(handler.syncId, cursorSaveSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            } else {
                InvTweaksOverlay.show("No room to temporarily store held items", 0xFFFF5555);
                return;
            }
        }

        // Quick check: is the layout already matching?
        boolean alreadyMatches = true;
        for (Map.Entry<Integer, SlotData> entry : targetLayout.entrySet()) {
            int slotId = entry.getKey();
            SlotData desired = entry.getValue();
            ItemStack current = handler.slots.get(slotId).getStack();
            if (desired.item() == null) {
                if (!current.isEmpty()) { alreadyMatches = false; break; }
            } else {
                if (current.isEmpty() || current.getItem() != desired.item() || current.getCount() != desired.count()) {
                    alreadyMatches = false; break;
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
                boolean moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                if (moved) {
                    matchedSlots++;
                }
                continue;
            }

            // Target should have a specific item
            if (!currentStack.isEmpty() && currentStack.getItem() == desired.item()) {
                if (currentStack.getCount() == desired.count()) {
                    matchedSlots++;
                    continue;
                }
                if (currentStack.getCount() > desired.count()) {
                    removeExcess(handler, im, player, targetSlotId, currentStack.getCount() - desired.count(),
                            allAccessibleSlots, targetLayout, isPlayerOnly);
                    matchedSlots++;
                } else {
                    boolean filled = addMore(handler, im, player, targetSlotId, desired.item(),
                            desired.count() - currentStack.getCount(), allAccessibleSlots, targetLayout, isPlayerOnly);
                    matchedSlots++;
                }
                continue;
            }

            // Wrong item or empty
            if (!currentStack.isEmpty()) {
                boolean moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                if (!moved) {
                    InvTweaksConfig.debugLog("PASTE", "could not displace item at slot %d", targetSlotId);
                    continue;
                }
            }

            boolean placed = sourceItem(handler, im, player, targetSlotId, desired.item(), desired.count(),
                    allAccessibleSlots, targetLayout, isPlayerOnly);
            if (placed) matchedSlots++;
        }

        // Final safety: make sure cursor is clean
        if (!handler.getCursorStack().isEmpty()) {
            int emptySlot = findAnyEmptySlot(handler, allAccessibleSlots);
            if (emptySlot >= 0) {
                im.clickSlot(handler.syncId, emptySlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }

        // Restore cursor items if we saved them earlier
        if (cursorSaveSlot >= 0 && !handler.slots.get(cursorSaveSlot).getStack().isEmpty()) {
            InvTweaksConfig.debugLog("PASTE", "restoring cursor items from slot %d", cursorSaveSlot);
            im.clickSlot(handler.syncId, cursorSaveSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
        }

        InvTweaksOverlay.show("Layout pasted", 0xFF55FF55);
        InvTweaksConfig.debugLog("PASTE", "complete | matched=%d/%d", matchedSlots, slotsToProcess);
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

        // First, copy the layout
        copyLayout(handler, isPlayerOnly);

        if (isPlayerOnly) {
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
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                slots.put(i, new SlotData(null, 0));
            } else {
                slots.put(i, new SlotData(stack.getItem(), stack.getCount()));
            }
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
                                    PlayerEntity player, int targetSlotId, Item itemType, int needed,
                                    Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                    boolean isPlayerOnly) {
        int remaining = needed;

        for (int srcSlot : allAccessible) {
            if (srcSlot == targetSlotId) continue;
            if (remaining <= 0) break;

            ItemStack srcStack = handler.slots.get(srcSlot).getStack();
            if (srcStack.isEmpty() || srcStack.getItem() != itemType) continue;

            int canTake = srcStack.getCount();
            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                canTake = Math.max(0, srcStack.getCount() - srcTarget.count());
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
                                       Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                       boolean isPlayerOnly) {
        int remaining = desiredCount;

        List<Integer> containerSlots = new ArrayList<>();
        List<Integer> playerSlots = new ArrayList<>();
        for (int i : allAccessible) {
            if (i == targetSlotId) continue;
            Slot slot = handler.slots.get(i);
            if (slot.inventory instanceof PlayerInventory) {
                playerSlots.add(i);
            } else {
                containerSlots.add(i);
            }
        }

        List<Integer> searchOrder = new ArrayList<>(containerSlots);
        searchOrder.addAll(playerSlots);

        for (int srcSlot : searchOrder) {
            if (remaining <= 0) break;

            ItemStack srcStack = handler.slots.get(srcSlot).getStack();
            if (srcStack.isEmpty() || srcStack.getItem() != itemType) continue;

            int canTake = srcStack.getCount();
            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                canTake = Math.max(0, srcStack.getCount() - srcTarget.count());
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

    // ========== SLOT FINDING HELPERS ==========

    private static int findEmptyNonTargetSlot(ScreenHandler handler, Set<Integer> allAccessible,
                                               Map<Integer, SlotData> targetLayout, int excludeSlot) {
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (targetLayout.containsKey(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
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
