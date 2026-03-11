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
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Clipboard-based copy/paste of container layouts (Tier 1 — in-memory only).
 * Stores a snapshot of slot contents and can rearrange items to match.
 */
public class LayoutClipboard {

    // Separate clipboards for container and player inventory
    private static LayoutSnapshot containerClipboard = null;
    private static LayoutSnapshot playerClipboard = null;

    /**
     * A snapshot of one slot's contents.
     */
    public record SlotData(Item item, int count) {}

    /**
     * A snapshot of a container layout.
     */
    public static class LayoutSnapshot {
        public final int slotCount;
        public final Map<Integer, SlotData> slots; // slot index → contents (null item = empty)
        public final boolean isPlayerInventory;

        public LayoutSnapshot(int slotCount, Map<Integer, SlotData> slots, boolean isPlayerInventory) {
            this.slotCount = slotCount;
            this.slots = slots;
            this.isPlayerInventory = isPlayerInventory;
        }
    }

    // ========== COPY ==========

    /**
     * Copy the current container/inventory layout to the clipboard.
     */
    public static void copyLayout(ScreenHandler handler, boolean isPlayerOnly) {
        Map<Integer, SlotData> slots = new LinkedHashMap<>();

        if (isPlayerOnly) {
            // Player inventory screen: copy main inv (slots 9-35) + hotbar (slots 36-44)
            // InventoryScreen slot IDs: 0=craft output, 1-4=craft grid, 5-8=armor, 9-35=main inv, 36-44=hotbar, 45=offhand
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
            // Container screen: copy only container slots (not player inventory portion)
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);
                if (slot.inventory instanceof PlayerInventory) continue; // skip player inv slots
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    slots.put(i, new SlotData(null, 0));
                } else {
                    slots.put(i, new SlotData(stack.getItem(), stack.getCount()));
                }
            }
        }

        if (isPlayerOnly) {
            playerClipboard = new LayoutSnapshot(slots.size(), slots, isPlayerOnly);
        } else {
            containerClipboard = new LayoutSnapshot(slots.size(), slots, isPlayerOnly);
        }

        InvTweaksOverlay.show("Layout copied", 0xFF55FF55);
        InvTweaksConfig.debugLog("COPY", "copied %d slots | playerOnly=%s", slots.size(), isPlayerOnly);
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

        // Check clipboard (use the right one for context)
        LayoutSnapshot clipboard = isPlayerOnly ? playerClipboard : containerClipboard;
        if (clipboard == null) {
            InvTweaksOverlay.show("No layout copied", 0xFFFF5555);
            return;
        }

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

        // Build a mapping from clipboard relative index → actual slot ID in current container
        // The clipboard stores slot IDs from the original container, so we map by position
        List<Integer> clipboardSlotIds = new ArrayList<>(clipboard.slots.keySet());
        Collections.sort(clipboardSlotIds);

        int slotsToProcess = Math.min(clipboardSlotIds.size(), targetSlotIds.size());

        // Build the target layout: targetSlotId → desired SlotData
        Map<Integer, SlotData> targetLayout = new LinkedHashMap<>();
        for (int i = 0; i < slotsToProcess; i++) {
            int clipSlotId = clipboardSlotIds.get(i);
            int actualSlotId = targetSlotIds.get(i);
            targetLayout.put(actualSlotId, clipboard.slots.get(clipSlotId));
        }

        // Build item pool: what items are available across all accessible slots
        // For container: container slots + player inv slots
        // For player inv: just the player inv slots (9-44)
        Set<Integer> allAccessibleSlots = new HashSet<>(targetSlotIds);
        if (!isPlayerOnly) {
            // Also include player inventory slots for sourcing items
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

        // Quick check: is the layout already matching? If so, skip entirely
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

            // Re-read the slot fresh (items may have moved during previous operations)
            ItemStack currentStack = handler.slots.get(targetSlotId).getStack();

            if (desired.item() == null) {
                // Target should be empty
                if (currentStack.isEmpty()) {
                    matchedSlots++;
                    continue;
                }
                // Need to move this item out of the way
                boolean moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                if (moved) {
                    matchedSlots++;
                }
                continue;
            }

            // Target should have a specific item
            if (!currentStack.isEmpty() && currentStack.getItem() == desired.item()) {
                // Correct item already here
                if (currentStack.getCount() == desired.count()) {
                    matchedSlots++;
                    continue;
                }
                // Correct item but wrong count — adjust
                if (currentStack.getCount() > desired.count()) {
                    // Too many — remove excess
                    removeExcess(handler, im, player, targetSlotId, currentStack.getCount() - desired.count(),
                            allAccessibleSlots, targetLayout, isPlayerOnly);
                    matchedSlots++;
                } else {
                    // Too few — try to add more from elsewhere
                    boolean filled = addMore(handler, im, player, targetSlotId, desired.item(),
                            desired.count() - currentStack.getCount(), allAccessibleSlots, targetLayout, isPlayerOnly);
                    matchedSlots++;
                }
                continue;
            }

            // Wrong item or empty — need to: (1) displace current item if any, (2) source desired item
            if (!currentStack.isEmpty()) {
                boolean moved = moveItemOut(handler, im, player, targetSlotId, allAccessibleSlots, targetLayout, isPlayerOnly);
                if (!moved) {
                    InvTweaksConfig.debugLog("PASTE", "could not displace item at slot %d", targetSlotId);
                    continue; // can't clear this slot, skip it
                }
            }

            // Now slot should be empty — source the desired item
            boolean placed = sourceItem(handler, im, player, targetSlotId, desired.item(), desired.count(),
                    allAccessibleSlots, targetLayout, isPlayerOnly);
            if (placed) matchedSlots++;
        }

        // Final safety: make sure cursor is clean from mod operations
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
     * For player inventory, this just copies (nowhere to move items to).
     */
    public static void cutLayout(ScreenHandler handler, boolean isPlayerOnly) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerInteractionManager im = mc.interactionManager;
        PlayerEntity player = mc.player;
        if (im == null || player == null) return;

        // First, copy the layout
        copyLayout(handler, isPlayerOnly);

        if (isPlayerOnly) {
            // Player inventory: cut = just copy (nowhere to send items)
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

    // ========== PASTE HELPERS ==========

    /**
     * Move the item currently in targetSlotId out to somewhere else.
     * Returns true if the slot is now empty.
     */
    private static boolean moveItemOut(ScreenHandler handler, ClientPlayerInteractionManager im,
                                        PlayerEntity player, int targetSlotId,
                                        Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                        boolean isPlayerOnly) {
        ItemStack stack = handler.slots.get(targetSlotId).getStack();
        if (stack.isEmpty()) return true;

        // Find a destination: prefer slots that want this item, then empty slots not in the target layout,
        // then any empty slot
        Item itemType = stack.getItem();

        // First: find an empty slot that the layout says should be empty OR is outside the layout
        int dest = findEmptyNonTargetSlot(handler, allAccessible, targetLayout, targetSlotId);
        if (dest >= 0) {
            InvTweaksConfig.debugLog("PASTE", "moveOut slot=%d → empty=%d", targetSlotId, dest);
            im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, dest, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            // If cursor still has items (shouldn't happen with empty dest), put back
            if (!handler.getCursorStack().isEmpty()) {
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                return false;
            }
            return true;
        }

        // Second: find a compatible partial stack anywhere
        for (int i : allAccessible) {
            if (i == targetSlotId) continue;
            ItemStack destStack = handler.slots.get(i).getStack();
            if (!destStack.isEmpty() && destStack.getItem() == itemType
                    && destStack.getCount() < destStack.getMaxCount()) {
                InvTweaksConfig.debugLog("PASTE", "moveOut slot=%d → partial=%d", targetSlotId, i);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, i, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                // If overflow, put back somewhere
                if (!handler.getCursorStack().isEmpty()) {
                    int overflow = findAnyEmptySlot(handler, allAccessible);
                    if (overflow >= 0) {
                        im.clickSlot(handler.syncId, overflow, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    } else {
                        // No room, put back in original
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

    /**
     * Remove excess items from a slot (it has more than desired).
     */
    private static void removeExcess(ScreenHandler handler, ClientPlayerInteractionManager im,
                                      PlayerEntity player, int slotId, int excessCount,
                                      Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                      boolean isPlayerOnly) {
        int dest = findEmptyNonTargetSlot(handler, allAccessible, targetLayout, slotId);
        if (dest < 0) dest = findAnyEmptySlot(handler, allAccessible);
        if (dest < 0) return; // no room

        InvTweaksConfig.debugLog("PASTE", "removeExcess slot=%d | excess=%d → dest=%d", slotId, excessCount, dest);

        // Pick up stack from slot
        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
        // Right-click to place items back one at a time until we've left the desired count
        // desired count = current - excess. We need to place (desired) back, move (excess) out
        ItemStack cursor = handler.getCursorStack();
        int totalOnCursor = cursor.getCount();
        int toKeep = totalOnCursor - excessCount;

        // Place toKeep back via right-clicks
        for (int i = 0; i < toKeep; i++) {
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
        }
        // Put the excess in dest
        im.clickSlot(handler.syncId, dest, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);

        // Handle any cursor leftovers
        if (!handler.getCursorStack().isEmpty()) {
            int overflow = findAnyEmptySlot(handler, allAccessible);
            if (overflow >= 0) {
                im.clickSlot(handler.syncId, overflow, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }
    }

    /**
     * Add more of an item to a slot from elsewhere.
     */
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

            // Check if we'd be taking from a slot that also wants this item
            // (only take what's excess relative to that slot's target)
            int canTake = srcStack.getCount();
            SlotData srcTarget = targetLayout.get(srcSlot);
            if (srcTarget != null && srcTarget.item() == itemType) {
                canTake = Math.max(0, srcStack.getCount() - srcTarget.count());
            }
            if (canTake <= 0) continue;

            int toTake = Math.min(remaining, canTake);

            InvTweaksConfig.debugLog("PASTE", "addMore slot=%d | need=%d | src=%d | taking=%d", targetSlotId, remaining, srcSlot, toTake);

            if (toTake == srcStack.getCount()) {
                // Take all from source
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                // If there's overflow (target slot full), handle it
                if (!handler.getCursorStack().isEmpty()) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    remaining -= (toTake - handler.slots.get(srcSlot).getStack().getCount());
                } else {
                    remaining -= toTake;
                }
            } else {
                // Take partial: pick up source, right-click the amount we DON'T want back into source,
                // then left-click target
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                int putBack = srcStack.getCount() - toTake;
                for (int i = 0; i < putBack; i++) {
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                }
                // Now cursor has toTake items, put into target
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    // Overflow — put back in source
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
                remaining -= toTake;
            }

            // Safety: if cursor isn't empty something went wrong
            if (!handler.getCursorStack().isEmpty()) {
                int dump = findAnyEmptySlot(handler, allAccessible);
                if (dump >= 0) {
                    im.clickSlot(handler.syncId, dump, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                } else {
                    break; // can't continue safely
                }
            }
        }

        return remaining < needed; // at least some items were added
    }

    /**
     * Source an item into an empty target slot from elsewhere in the container/inventory.
     */
    private static boolean sourceItem(ScreenHandler handler, ClientPlayerInteractionManager im,
                                       PlayerEntity player, int targetSlotId, Item itemType, int desiredCount,
                                       Set<Integer> allAccessible, Map<Integer, SlotData> targetLayout,
                                       boolean isPlayerOnly) {
        int remaining = desiredCount;

        // Prioritize container slots first, then player inv
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

            // Don't take more than what's excess relative to that slot's target
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
                // Take all
                im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, targetSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                if (!handler.getCursorStack().isEmpty()) {
                    // Overflow, put back
                    im.clickSlot(handler.syncId, srcSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
                remaining -= toTake;
            } else {
                // Take partial
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

            // Safety
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

    /**
     * Find an empty slot that either (a) the target layout says should be empty, or (b) is not in the target layout.
     */
    private static int findEmptyNonTargetSlot(ScreenHandler handler, Set<Integer> allAccessible,
                                               Map<Integer, SlotData> targetLayout, int excludeSlot) {
        // Prefer slots outside the target layout first
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            if (targetLayout.containsKey(i)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Then slots where target says empty
        for (int i : allAccessible) {
            if (i == excludeSlot) continue;
            SlotData target = targetLayout.get(i);
            if (target != null && target.item() == null && handler.slots.get(i).getStack().isEmpty()) return i;
        }
        // Finally any empty slot
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
        return containerClipboard != null || playerClipboard != null;
    }

    public static void clearClipboard() {
        containerClipboard = null;
        playerClipboard = null;
    }
}
