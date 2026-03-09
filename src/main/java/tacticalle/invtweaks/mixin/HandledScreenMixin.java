package tacticalle.invtweaks.mixin;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.BundleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import tacticalle.invtweaks.InvTweaksConfig;

import java.util.HashMap;
import java.util.Map;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");

    @Shadow
    protected ScreenHandler handler;

    @Shadow
    private Slot focusedSlot;

    // Pre-click snapshot for shift-click tracking
    @Unique private Item it_preClickItem = null;
    @Unique private int it_preClickCount = 0;
    @Unique private Map<Integer, Integer> it_preClickSnapshot = null;
    @Unique private String it_shiftClickMode = null;
    @Unique private int it_shiftClickSlotId = -1;

    // Bundle insert tracking
    @Unique private int it_bundleInsertSlotCount = 0;
    @Unique private boolean it_bundleInsertPending = false;
    @Unique private boolean it_bundleInsertReverse = false;
    @Unique private String it_bundleInsertMode = null;

    // ========== HEAD INJECTION ==========

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V", at = @At("HEAD"), cancellable = true)
    private void beforeOnMouseClick(Slot slot, int slotId, int button, SlotActionType actionType, CallbackInfo ci) {
        if (slot == null) return;
        InvTweaksConfig config = InvTweaksConfig.get();
        long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean shiftPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                              GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        // Comprehensive HEAD debug logging
        boolean anyModDown = config.isAnyModifierPressed();
        if (config.enableDebugLogging && anyModDown) {
            String screenType = ((Object) this).getClass().getSimpleName();
            InvTweaksConfig.debugLog("HEAD", "beforeOnMouseClick | screen=%s | slot=%d | button=%d | action=%s | allBut1=%s(%s) | only1=%s(%s) | shift=%s | fillExisting=%s",
                screenType, slotId, button, actionType,
                InvTweaksConfig.getKeyName(config.allBut1Key), InvTweaksConfig.isKeyPressed(config.allBut1Key),
                InvTweaksConfig.getKeyName(config.only1Key), InvTweaksConfig.isKeyPressed(config.only1Key),
                shiftPressed, config.isFillExistingActive());
        }

        // Block vanilla PICKUP_ALL (double-click gather) when modifier keys are held.
        // This prevents double-clicking from collecting matching items from all slots.
        // Note: THROW (Ctrl+Q drop) is intentionally NOT blocked — players need it.
        if (anyModDown) {
            if (actionType == SlotActionType.PICKUP_ALL) {
                ci.cancel();
                return;
            }
        }

        // Throwing tweaks: modifier + Q (THROW action) in GUI
        if (actionType == SlotActionType.THROW && config.enableThrowHalf) {
            ItemStack slotStack = slot.getStack();
            if (!slotStack.isEmpty() && slotStack.getCount() > 1) {
                boolean throwHalf = config.isThrowHalfKeyPressed();
                boolean throwAB1 = config.isThrowAllBut1KeyPressed();

                if (throwHalf || throwAB1) {
                    ci.cancel();
                    var mc = MinecraftClient.getInstance();
                    var im = mc.interactionManager;
                    var player = mc.player;
                    if (im == null || player == null) return;

                    int count = slotStack.getCount();

                    if (throwAB1) {
                        // Throw all but 1: pick up stack, right-click 1 into source slot, throw cursor
                        // Note: right-click places HALF, not 1. So we use a temp slot.
                        InvTweaksConfig.debugLog("THROW-HALF", "allbut1 | slot=%d | count=%d", slotId, count);
                        int tempSlot = it_findEmptyPlayerSlot(slotId);
                        if (tempSlot < 0) return; // no temp slot available
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        // Place 1 in temp slot
                        im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                        // Throw remaining from cursor
                        im.clickSlot(handler.syncId, -999, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        // Move the 1 from temp back to source
                        im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    } else {
                        // Throw half: right-click to pick up half, then throw cursor
                        InvTweaksConfig.debugLog("THROW-HALF", "half | slot=%d | count=%d | dropping=%d", slotId, count, count / 2);
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, -999, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    }
                    return;
                }
            } else if (!slotStack.isEmpty() && slotStack.getCount() == 1) {
                // Stack of 1: if either throw modifier is held, do nothing
                if (config.isThrowHalfKeyPressed() || config.isThrowAllBut1KeyPressed()) {
                    ci.cancel();
                    return;
                }
            }
        }

        // Hotbar Button Modifiers: modifier + number key (SWAP action) in GUI
        if (actionType == SlotActionType.SWAP && config.enableHotbarModifiers) {
            String hotbarMode = config.getActiveMode("hotbarModifiers");
            if (hotbarMode != null) {
                ItemStack sourceStack = slot.getStack();
                if (sourceStack.isEmpty()) return; // nothing to move
                if (sourceStack.getCount() <= 1) return; // only 1 item, just do vanilla swap

                ci.cancel();
                var mc = MinecraftClient.getInstance();
                var im = mc.interactionManager;
                var player = mc.player;
                if (im == null || player == null) return;

                int hotbarIndex = button; // button = 0-8 for hotbar slots
                int hotbarSlotId = it_findHotbarSlotId(hotbarIndex);
                if (hotbarSlotId < 0) return;

                if (config.enableDebugLogging) InvTweaksConfig.debugLog("HOTBAR", "mode=%s | sourceSlot=%d | hotbarSlot=%d | hotbarIndex=%d", hotbarMode, slotId, hotbarSlotId, hotbarIndex);

                ItemStack hotbarStack = handler.slots.get(hotbarSlotId).getStack();

                if (hotbarMode.equals("allbut1")) {
                    // Move all-but-1 from source to hotbar slot
                    if (hotbarStack.isEmpty()) {
                        // Empty hotbar slot: pick up stack, right-click 1 back to source, left-click hotbar
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    } else if (hotbarStack.getItem() == sourceStack.getItem()
                            && ItemStack.areItemsAndComponentsEqual(hotbarStack, sourceStack)) {
                        // Same item in hotbar: pick up source, right-click 1 back, left-click hotbar to stack
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        // If there's overflow on cursor (hotbar full), put it back in source
                        if (!handler.getCursorStack().isEmpty()) {
                            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        }
                    } else {
                        // Different item in hotbar: swap hotbar item to source, then place N-1 in hotbar
                        // Pick up source stack
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        // Place into hotbar (swaps: cursor gets hotbar item, hotbar gets source)
                        im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        // Cursor now has the old hotbar item. Place it in source.
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        // Now source has old hotbar item, hotbar has full source stack.
                        // We need to pull 1 back from hotbar to source... but source is occupied.
                        // Use a temp slot approach:
                        int tempSlot = it_findEmptyPlayerSlot2(slotId, hotbarSlotId);
                        if (tempSlot >= 0) {
                            // Move old hotbar item from source to temp
                            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                            im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                            // Pick up from hotbar, right-click 1 to source, put rest back in hotbar
                            im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                            im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                            // Move old hotbar item back from temp to source... but source has 1 item
                            // Actually, let's swap: pick up temp, left-click source (swap 1 item with old hotbar item)
                            // This gets complex. Simpler: just leave old hotbar item in temp.
                            // The user gets: 1 of source item in source, N-1 in hotbar, old hotbar item in temp.
                            // That's acceptable behavior.
                        }
                    }
                } else {
                    // "only1" mode: move exactly 1 from source to hotbar slot
                    if (hotbarStack.isEmpty()) {
                        // Empty hotbar: pick up source, right-click 1 into hotbar, put rest back
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    } else if (hotbarStack.getItem() == sourceStack.getItem()
                            && ItemStack.areItemsAndComponentsEqual(hotbarStack, sourceStack)
                            && hotbarStack.getCount() < hotbarStack.getMaxCount()) {
                        // Same item, room to stack: pick up source, right-click 1 into hotbar, put rest back
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                        im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    }
                    // Different item or full stack: do nothing (don't swap for only1 with incompatible)
                }
                return;
            }
        }

        // Fill Existing Stacks: both modifier keys held + shift + click
        if (shiftPressed && actionType == SlotActionType.QUICK_MOVE && config.enableFillExisting
                && config.isFillExistingActive()) {
            ci.cancel();
            ItemStack sourceStack = slot.getStack();
            if (sourceStack.isEmpty()) return;

            var mc = MinecraftClient.getInstance();
            var im = mc.interactionManager;
            var player = mc.player;
            if (im == null || player == null) return;

            Item itemType = sourceStack.getItem();
            int sourceCount = sourceStack.getCount();
            boolean isPlayerOnlyScreen = it_isPlayerOnlyScreen();

            // Find all partial stacks of the same item on the other side
            java.util.List<int[]> partialStacks = new java.util.ArrayList<>();
            for (int i = 0; i < handler.slots.size(); i++) {
                if (i == slotId) continue;
                if (!it_isOtherSide(slotId, i, isPlayerOnlyScreen)) continue;
                ItemStack destStack = handler.slots.get(i).getStack();
                if (!destStack.isEmpty() && destStack.getItem() == itemType
                        && ItemStack.areItemsAndComponentsEqual(destStack, sourceStack)
                        && destStack.getCount() < destStack.getMaxCount()) {
                    int space = destStack.getMaxCount() - destStack.getCount();
                    partialStacks.add(new int[]{i, space});
                }
            }

            if (partialStacks.isEmpty()) {
                InvTweaksConfig.debugLog("FILL", "no partial stacks found | item=%s | slot=%d", itemType, slotId);
                return;
            }

            // Sort by slot ID (lowest first) for consistent fill order
            partialStacks.sort((a, b) -> Integer.compare(a[0], b[0]));

            InvTweaksConfig.debugLog("FILL", "filling | item=%s | sourceSlot=%d | sourceCount=%d | targets=%d",
                    itemType, slotId, sourceCount, partialStacks.size());

            // Pick up source stack
            it_debugClickSlot(im, handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player, "FILL");

            // Left-click on each partial stack destination — items merge automatically up to max
            for (int[] partial : partialStacks) {
                int destSlotId = partial[0];
                ItemStack cursor = handler.getCursorStack();
                if (cursor.isEmpty()) break; // nothing left to distribute

                it_debugClickSlot(im, handler.syncId, destSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player, "FILL");
            }

            // Put any remainder back in source slot
            if (!handler.getCursorStack().isEmpty()) {
                it_debugClickSlot(im, handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player, "FILL");
            }

            InvTweaksConfig.debugLog("FILL", "result | sourceSlotNow=%d | cursorNow=%d",
                    slot.getStack().getCount(), handler.getCursorStack().getCount());
            return;
        }

        // Shift+Click transfer
        if (shiftPressed && actionType == SlotActionType.QUICK_MOVE && config.enableShiftClickTransfer) {
            String mode = config.getActiveMode("shiftClick");
            if (mode != null) {
                // Guard against macOS Command+Shift+Click bulk-move:
                // macOS fires QUICK_MOVE for ALL slots with matching items, not just the clicked one.
                // Mouse Tweaks also fires QUICK_MOVE for multiple slots (shift-drag), but via reflection.
                // We detect macOS bulk-move by checking:
                // 1. Is this for a different slot than focusedSlot? (cursor isn't over it)
                // 2. Is the call NOT coming through reflection? (not Mouse Tweaks)
                // If both true, it's a macOS bulk-move ghost — block it.
                if (focusedSlot == null || focusedSlot.id != slotId) {
                    // Slot doesn't match cursor — could be Mouse Tweaks or bulk-move.
                    // Check if Mouse Tweaks is calling us via reflection.
                    if (!it_isCalledViaReflection()) {
                        // Not reflection — this is a macOS bulk-move ghost, block it
                        ci.cancel();
                        return;
                    }
                    // Otherwise: Mouse Tweaks shift-drag — allow it through
                }

                if (mode.equals("only1")) {
                    // CANCEL vanilla and handle "only1" directly — no undo needed
                    ci.cancel();
                    ItemStack sourceStack = slot.getStack();
                    if (sourceStack.isEmpty() || sourceStack.getCount() < 1) return;

                    var mc = MinecraftClient.getInstance();
                    var im = mc.interactionManager;
                    var player = mc.player;
                    if (im == null || player == null) return;

                    // Find best destination: partial stack of same item first, then empty slot
                    int destSlot = it_findCompatibleSlotOnOtherSide(sourceStack.getItem(), slotId);
                    if (destSlot < 0) return; // no room

                    if (config.enableDebugLogging) InvTweaksConfig.debugLog("SHIFT", "only1 mode | slot=%d | dest=%d", slotId, destSlot);

                    // Pick up stack, right-click dest (places 1), put rest back
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, destSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    return;
                }

                // "allbut1" mode: take snapshot and let vanilla proceed, fix in RETURN
                ItemStack sourceStack = slot.getStack();
                // If only 1 item, allbut1 is meaningless — let vanilla handle it normally
                if (sourceStack.getCount() <= 1) {
                    it_shiftClickMode = null;
                    return;
                }
                it_shiftClickMode = mode;
                it_shiftClickSlotId = slotId;
                it_preClickItem = sourceStack.isEmpty() ? null : sourceStack.getItem();
                it_preClickCount = sourceStack.getCount();
                it_preClickSnapshot = new HashMap<>();
                boolean isPlayerOnlyScreen = it_isPlayerOnlyScreen();

                for (int i = 0; i < handler.slots.size(); i++) {
                    Slot s = handler.slots.get(i);
                    boolean isDestination = it_isValidDestination(slotId, i, isPlayerOnlyScreen, slot);
                    if (isDestination) {
                        ItemStack stack = s.getStack();
                        if (!stack.isEmpty() && stack.getItem() == it_preClickItem) {
                            it_preClickSnapshot.put(i, stack.getCount());
                        } else if (stack.isEmpty()) {
                            it_preClickSnapshot.put(i, 0);
                        }
                    }
                }
            } else {
                it_shiftClickMode = null;
            }
        } else {
            // Non-shift-click: reset shift state
            it_preClickItem = null;
            it_preClickCount = 0;
            it_preClickSnapshot = null;
            it_shiftClickMode = null;
            it_shiftClickSlotId = -1;
        }

        // Bundle insert tracking
        it_bundleInsertPending = false;
        it_bundleInsertReverse = false;
        it_bundleInsertMode = null;
        if (!shiftPressed && actionType == SlotActionType.PICKUP && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            ItemStack cursorStack = handler.getCursorStack();
            ItemStack slotStack = slot.getStack();
            if (cursorStack.getItem() instanceof BundleItem && !slotStack.isEmpty()
                    && config.enableBundleInsertCursorBundle) {
                String mode = config.getActiveMode("bundleInsertBundle");
                if (mode != null) {
                    it_bundleInsertSlotCount = slotStack.getCount();
                    it_bundleInsertPending = true;
                    it_bundleInsertMode = mode;
                }
            } else if (slotStack.getItem() instanceof BundleItem && !cursorStack.isEmpty()
                       && !(cursorStack.getItem() instanceof BundleItem)
                       && config.enableBundleInsertCursorItems) {
                String mode = config.getActiveMode("bundleInsertItems");
                if (mode != null) {
                    it_bundleInsertSlotCount = cursorStack.getCount();
                    it_bundleInsertPending = true;
                    it_bundleInsertReverse = true;
                    it_bundleInsertMode = mode;
                }
            }
        }
    }

    // ========== RETURN INJECTION ==========

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V", at = @At("RETURN"))
    private void afterOnMouseClick(Slot slot, int slotId, int button, SlotActionType actionType, CallbackInfo ci) {
        if (slot == null) return;
        InvTweaksConfig config = InvTweaksConfig.get();

        ClientPlayerInteractionManager im = MinecraftClient.getInstance().interactionManager;
        if (im == null) return;
        var player = MinecraftClient.getInstance().player;
        if (player == null) return;

        long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean shiftPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                              GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        // Comprehensive RETURN debug logging
        if (config.enableDebugLogging && config.isAnyModifierPressed()) {
            InvTweaksConfig.debugLog("RETURN", "afterOnMouseClick | slot=%d | button=%d | action=%s | shift=%s | cursorStack=%s(%d) | slotStack=%s(%d)",
                slotId, button, actionType, shiftPressed,
                slot.getStack().isEmpty() ? "empty" : slot.getStack().getItem().toString(), slot.getStack().getCount(),
                handler.getCursorStack().isEmpty() ? "empty" : handler.getCursorStack().getItem().toString(), handler.getCursorStack().getCount());
        }

        // Shift+Click Transfer
        if (shiftPressed && actionType == SlotActionType.QUICK_MOVE
                && it_shiftClickMode != null
                && slotId == it_shiftClickSlotId) {
            if (InvTweaksConfig.get().enableDebugLogging) InvTweaksConfig.debugLog("SHIFT", "handleShiftClick | mode=%s | slot=%d", it_shiftClickMode, slotId);
            it_handleShiftClick(slot, slotId, im, player, it_shiftClickMode);
            return;
        }

        // Non-shift PICKUP actions
        if (!shiftPressed && actionType == SlotActionType.PICKUP) {
            // Bundle extract: right-click on bundle
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && slot.getStack().getItem() instanceof BundleItem
                    && config.enableBundleExtract) {
                String mode = config.getActiveMode("bundleExtract");
                if (mode != null) {
                    it_handleBundleExtract(slot, slotId, im, player, mode);
                    return;
                }
            }

            // Bundle insert (tracked from HEAD)
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && it_bundleInsertPending) {
                it_handleBundleInsert(slot, slotId, im, player);
                return;
            }

            // Click pickup
            if (config.enableClickPickup) {
                String mode = config.getActiveMode("clickPickup");
                if (mode != null) {
                    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        it_handlePickup(slot, slotId, im, player, mode);
                    } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        it_handleMacOSCtrlClick(slot, slotId, im, player, mode);
                    }
                }
            }
        }
    }

    // ========== SCROLL TRANSFER ==========

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        LOGGER.info("[IT:SCROLL-RAW] verticalAmount={} horizontalAmount={}", verticalAmount, horizontalAmount);
        InvTweaksConfig config = InvTweaksConfig.get();
        if (!config.enableScrollTransfer) return;

        // Player-only inventory (E key): do nothing for now (deferred to v2)
        if (it_isPlayerOnlyScreen()) return;

        // Need a focused slot with items
        if (focusedSlot == null) return;
        ItemStack hoveredStack = focusedSlot.getStack();
        if (hoveredStack.isEmpty()) return;

        // Allow scroll even with items on cursor — cursor items stay

        // Determine scroll mode (flush / leave1 / null)
        String scrollMode = config.getScrollTransferMode();
        if (scrollMode == null) return;

        // Determine scroll direction
        boolean scrollUp = verticalAmount > 0;
        boolean scrollDown = verticalAmount < 0;
        if (!scrollUp && !scrollDown) return;

        // Determine which side the hovered slot is on
        boolean hoveredIsPlayer = focusedSlot.inventory instanceof net.minecraft.entity.player.PlayerInventory;

        // Scroll up = items go UP to chest = scan player side to QUICK_MOVE them
        // Scroll down = items go DOWN to player = scan container side to QUICK_MOVE them
        // This is independent of which side the cursor is hovering
        boolean scanPlayerSide = scrollUp;

        // Cancel vanilla scroll (hotbar scrolling)
        cir.setReturnValue(true);

        var mc = MinecraftClient.getInstance();
        var im = mc.interactionManager;
        var player = mc.player;
        if (im == null || player == null) return;

        Item itemType = hoveredStack.getItem();
        int hoveredSlotId = focusedSlot.id;

        // Find all slots on the SAME side as the hovered slot that contain the same item type
        java.util.List<Integer> matchingSlots = new java.util.ArrayList<>();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot s = handler.slots.get(i);
            if (s.getStack().isEmpty()) continue;
            if (s.getStack().getItem() != itemType) continue;
            if (!ItemStack.areItemsAndComponentsEqual(s.getStack(), hoveredStack)) continue;

            // Must be on the side we're scanning
            boolean slotIsPlayer = s.inventory instanceof net.minecraft.entity.player.PlayerInventory;
            if (slotIsPlayer != scanPlayerSide) continue;

            matchingSlots.add(i);
        }

        if (matchingSlots.isEmpty()) return;

        InvTweaksConfig.debugLog("SCROLL", "%s | item=%s | side=%s | direction=%s | matchingSlots=%d",
                scrollMode, itemType, scanPlayerSide ? "player" : "container",
                scrollUp ? "up" : "down", matchingSlots.size());

        if (scrollMode.equals("flush")) {
            // Flush mode: shift-click every matching slot to move full stacks
            for (int slotId : matchingSlots) {
                ItemStack slotStack = handler.slots.get(slotId).getStack();
                if (slotStack.isEmpty()) continue; // already moved by a previous QUICK_MOVE

                InvTweaksConfig.debugLog("SCROLL", "flush QUICK_MOVE | slot=%d | count=%d", slotId, slotStack.getCount());
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.QUICK_MOVE, player);
            }
        } else {
            // Leave-1 mode: for each matching slot, move all but 1
            for (int slotId : matchingSlots) {
                ItemStack slotStack = handler.slots.get(slotId).getStack();
                if (slotStack.isEmpty()) continue;
                if (slotStack.getCount() <= 1) {
                    InvTweaksConfig.debugLog("SCROLL", "leave1 skip | slot=%d | count=1", slotId);
                    continue; // already has only 1, nothing to move
                }

                InvTweaksConfig.debugLog("SCROLL", "leave1 | slot=%d | count=%d", slotId, slotStack.getCount());

                // Sequence:
                // 1. Pick up full stack from slot → cursor=N, slot=empty
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                // 2. Right-click to place 1 back → cursor=N-1, slot=1
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                // 3. Deposit cursor (N-1) into a temp slot, then QUICK_MOVE it to the other side
                int tempSlot = it_findEmptySlotOnSameSide(slotId, scanPlayerSide);
                if (tempSlot >= 0) {
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.QUICK_MOVE, player);
                } else {
                    // No temp slot — put items back in source slot
                    InvTweaksConfig.debugLog("SCROLL", "leave1 no temp slot | slot=%d | putting back", slotId);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
            }
        }

        InvTweaksConfig.debugLog("SCROLL", "complete | cursorEmpty=%s", handler.getCursorStack().isEmpty());
    }

    // ========== SCROLL TRANSFER HELPERS ==========

    /**
     * Find an empty slot on the same side as the source slot (for temp storage during leave-1 scroll).
     */
    @Unique
    private int it_findEmptySlotOnSameSide(int sourceSlotId, boolean sourceIsPlayer) {
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == sourceSlotId) continue;
            Slot s = handler.slots.get(i);
            if (!s.getStack().isEmpty()) continue;

            boolean slotIsPlayer = s.inventory instanceof net.minecraft.entity.player.PlayerInventory;
            if (slotIsPlayer != sourceIsPlayer) continue;

            // For player inventory, skip crafting/armor slots (indices 0-8 in InventoryScreen)
            // But since we already checked it_isPlayerOnlyScreen() and returned, we're in a container screen
            // where all player inv slots are valid
            return i;
        }
        return -1;
    }

    // ========== REFLECTION DETECTION ==========
    // Mouse Tweaks calls onMouseClick via reflection (GuiContainerHandler.clickSlot -> Method.invoke).
    // macOS bulk-move comes through the normal Minecraft input pipeline.
    // We use this to distinguish legitimate Mouse Tweaks shift-drag from macOS ghost events.

    @Unique
    private static boolean it_isCalledViaReflection() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            // Mouse Tweaks calls via java.lang.reflect.Method.invoke
            // or via modern MethodHandle/LambdaForm paths
            if (cls.startsWith("java.lang.reflect.") || cls.startsWith("jdk.internal.reflect.")) {
                return true;
            }
            // Also check for Mouse Tweaks directly in the stack
            if (cls.startsWith("yalter.mousetweaks.")) {
                return true;
            }
        }
        return false;
    }

    // ========== CLICK PICKUP ==========

    @Unique
    private void it_handlePickup(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player, String mode) {
        var cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) return;
        int cursorCount = cursorStack.getCount();
        if (slot.getStack().getCount() > 0 || cursorCount <= 1) return;

        if (InvTweaksConfig.get().enableDebugLogging) InvTweaksConfig.debugLog("PICKUP", "%s mode | cursorCount=%d | slotCount=%d", mode, cursorCount, slot.getStack().getCount());

        if (mode.equals("allbut1")) {
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
        } else {
            int tempSlot = it_findEmptyPlayerSlot(slotId);
            if (tempSlot >= 0) {
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }
    }

    // ========== MACOS CTRL+CLICK ==========

    @Unique
    private void it_handleMacOSCtrlClick(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player, String mode) {
        var cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) return;
        int cursorCount = cursorStack.getCount();
        int slotCount = slot.getStack().getCount();

        if (cursorCount > 0 && slotCount > 0) {
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            it_handlePickup(slot, slotId, im, player, mode);
        } else if (slotCount == 0 && cursorCount > 1) {
            it_handlePickup(slot, slotId, im, player, mode);
        }
    }

    // ========== SHIFT+CLICK TRANSFER ==========

    @Unique
    private void it_handleShiftClick(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player, String mode) {
        // This handler only processes "allbut1" mode.
        // "only1" is handled directly in HEAD with ci.cancel().
        ItemStack remaining = slot.getStack();

        if (remaining.isEmpty() && it_preClickItem != null && it_preClickSnapshot != null) {
            // Vanilla moved everything. Find ALL destination slots where items increased.
            // We need to undo so that exactly 1 item ends up back in the source slot.
            //
            // Strategy: collect all items from all destination slots that received items,
            // then redistribute: place 1 in source, put the rest back filling partial stacks first.

            // Build a list of (slotId, increase) for all slots that gained items
            java.util.List<int[]> changedSlots = new java.util.ArrayList<>();
            int totalIncrease = 0;
            for (Map.Entry<Integer, Integer> entry : it_preClickSnapshot.entrySet()) {
                int idx = entry.getKey();
                int oldCount = entry.getValue();
                ItemStack current = handler.getSlot(idx).getStack();
                int newCount = (!current.isEmpty() && current.getItem() == it_preClickItem) ? current.getCount() : 0;
                int increase = newCount - oldCount;
                if (increase > 0) {
                    changedSlots.add(new int[]{idx, increase, oldCount, newCount});
                    totalIncrease += increase;
                }
            }

            if (totalIncrease <= 1) {
                // Only 1 item was moved — nothing to undo for allbut1
                // (we'd want to keep 1 in source but only 1 was moved, so put it back)
                if (totalIncrease == 1 && changedSlots.size() == 1) {
                    int destIdx = changedSlots.get(0)[0];
                    im.clickSlot(handler.syncId, destIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
            } else if (changedSlots.size() == 1) {
                // Simple case: all items went to one slot. Pick up, right-click 1 back to source, put rest back.
                int destIdx = changedSlots.get(0)[0];
                im.clickSlot(handler.syncId, destIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, destIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            } else {
                // Complex case: items were split across multiple destination slots.
                // The typical scenario: vanilla filled a partial stack to its max, then overflowed
                // the remainder into another slot.
                //
                // We need to return exactly 1 item to source. Strategy:
                // 1. Pick up the items from the OVERFLOW slot (the one that was empty before, i.e. oldCount==0)
                //    or the slot with the smallest increase.
                // 2. Right-click 1 into the source slot.
                // 3. Put the rest back into the overflow slot.
                //
                // Find the best slot to pull from: prefer the overflow slot (was empty before),
                // since pulling from the filled partial stack would leave it at max-1 again.
                // Sort: prefer slots where oldCount was 0 (overflow), then smallest increase.
                changedSlots.sort((a, b) -> {
                    // Prefer overflow slots (oldCount == 0)
                    if (a[2] == 0 && b[2] != 0) return -1;
                    if (a[2] != 0 && b[2] == 0) return 1;
                    // Then prefer smallest increase (less disruption)
                    return Integer.compare(a[1], b[1]);
                });

                int pullSlot = changedSlots.get(0)[0];
                im.clickSlot(handler.syncId, pullSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, pullSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        } else if (remaining.getCount() > 1) {
            // Vanilla couldn't move everything (destination full or partial move)
            Item itemType = remaining.getItem();
            // Pick up remainder, place 1 back, find dest for the rest
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
            int destSlot = it_findCompatibleSlotOnOtherSide(itemType, slotId);
            if (destSlot >= 0) {
                im.clickSlot(handler.syncId, destSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            } else {
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }

        it_preClickItem = null;
        it_preClickCount = 0;
        it_preClickSnapshot = null;
    }

    // ========== BUNDLE EXTRACT ==========

    @Unique
    private void it_handleBundleExtract(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player, String mode) {
        var cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty() || cursorStack.getCount() <= 1) return;

        int tempSlot = it_findEmptyPlayerSlot(slotId);
        if (tempSlot < 0) return;

        if (InvTweaksConfig.get().enableDebugLogging) InvTweaksConfig.debugLog("BUNDLE-EXT", "%s mode | count=%d", mode, cursorStack.getCount());

        if (mode.equals("only1")) {
            im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
        } else {
            int tempSlot2 = it_findEmptyPlayerSlot2(slotId, tempSlot);
            if (tempSlot2 >= 0) {
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            } else {
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            }
        }
    }

    // ========== BUNDLE INSERT ==========

    @Unique
    private void it_handleBundleInsert(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player) {
        boolean reverse = it_bundleInsertReverse;
        String mode = it_bundleInsertMode;
        it_bundleInsertPending = false;
        it_bundleInsertReverse = false;
        it_bundleInsertMode = null;

        if (reverse) {
            it_handleBundleInsertReverse(slot, slotId, im, player, mode);
        } else {
            it_handleBundleInsertForward(slot, slotId, im, player, mode);
        }
    }

    @Unique
    private void it_handleBundleInsertForward(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player, String mode) {
        int slotCount = slot.getStack().getCount();

        if (slotCount == 0 && it_bundleInsertSlotCount > 1) {
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
            int newSlotCount = slot.getStack().getCount();
            if (newSlotCount <= 1) return;

            int tempSlot = it_findEmptyPlayerSlot(slotId);
            if (tempSlot < 0) return;

            if (mode.equals("allbut1")) {
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
            } else {
                int tempSlot2 = it_findEmptyPlayerSlot2(slotId, tempSlot);
                if (tempSlot2 >= 0) {
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
            }
        }
    }

    @Unique
    private void it_handleBundleInsertReverse(Slot slot, int slotId, ClientPlayerInteractionManager im,
            net.minecraft.entity.player.PlayerEntity player, String mode) {
        ItemStack cursorNow = handler.getCursorStack();

        if (cursorNow.isEmpty() && it_bundleInsertSlotCount > 1) {
            im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
            int extractedCount = handler.getCursorStack().getCount();
            if (extractedCount <= 1) return;

            if (mode.equals("allbut1")) {
                int tempSlot = it_findEmptyPlayerSlot(slotId);
                if (tempSlot >= 0) {
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
            } else {
                int tempSlot = it_findEmptyPlayerSlot(slotId);
                int tempSlot2 = it_findEmptyPlayerSlot2(slotId, tempSlot);
                if (tempSlot >= 0 && tempSlot2 >= 0) {
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                }
            }
        }
    }

    // ========== DEBUG-WRAPPED CLICK SLOT ==========

    /**
     * Wrapper for im.clickSlot that logs the synthetic click when debug is enabled.
     */
    @Unique
    private void it_debugClickSlot(ClientPlayerInteractionManager im, int syncId, int slotId, int button,
            SlotActionType action, net.minecraft.entity.player.PlayerEntity player, String tag) {
        InvTweaksConfig.debugLog("CLICK", "synthetic | tag=%s | slot=%d | button=%d | action=%s", tag, slotId, button, action);
        im.clickSlot(syncId, slotId, button, action, player);
    }

    // ========== UTILITY METHODS ==========

    @Unique
    private int it_findHotbarSlotId(int hotbarIndex) {
        // Find the screen handler slot ID that corresponds to a given hotbar index (0-8).
        // In most screens, hotbar slots are the last 9 slots and have inventory index 0-8.
        // In InventoryScreen, hotbar slots are IDs 36-44.
        if (it_isPlayerOnlyScreen()) {
            return 36 + hotbarIndex;
        }
        // For container screens, search for the slot with the matching hotbar inventory index
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot s = handler.slots.get(i);
            if (s.inventory instanceof net.minecraft.entity.player.PlayerInventory && s.getIndex() == hotbarIndex) {
                return i;
            }
        }
        return -1;
    }

    @Unique
    private int it_findEmptyPlayerSlot(int excludeSlotId) {
        if (it_isPlayerOnlyScreen()) {
            for (int i = 1; i <= 4; i++) {
                if (i == excludeSlotId) continue;
                if (i < handler.slots.size() && handler.slots.get(i).getStack().isEmpty()) return i;
            }
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == excludeSlotId) continue;
            Slot s = handler.slots.get(i);
            if (!(s.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
            int invIndex = s.getIndex();
            if (invIndex >= 9 && invIndex <= 44 && s.getStack().isEmpty()) return i;
        }
        return -1;
    }

    @Unique
    private int it_findEmptyPlayerSlot2(int exclude1, int exclude2) {
        if (it_isPlayerOnlyScreen()) {
            for (int i = 1; i <= 4; i++) {
                if (i == exclude1 || i == exclude2) continue;
                if (i < handler.slots.size() && handler.slots.get(i).getStack().isEmpty()) return i;
            }
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == exclude1 || i == exclude2) continue;
            Slot s = handler.slots.get(i);
            if (!(s.inventory instanceof net.minecraft.entity.player.PlayerInventory)) continue;
            int invIndex = s.getIndex();
            if (invIndex >= 9 && invIndex <= 44 && s.getStack().isEmpty()) return i;
        }
        return -1;
    }

    @Unique
    private int it_findCompatibleSlotOnOtherSide(Item item, int sourceSlotId) {
        boolean isPlayerOnly = it_isPlayerOnlyScreen();
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == sourceSlotId) continue;
            if (!it_isOtherSide(sourceSlotId, i, isPlayerOnly)) continue;
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == item && stack.getCount() < stack.getMaxCount()) return i;
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            if (i == sourceSlotId) continue;
            if (!it_isOtherSide(sourceSlotId, i, isPlayerOnly)) continue;
            if (handler.slots.get(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    @Unique
    private boolean it_isPlayerOnlyScreen() {
        return ((Object) this) instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen;
    }

    @Unique
    private int it_playerInvRegion(int slotId) {
        if (slotId >= 36 && slotId <= 44) return 1; // hotbar
        if (slotId >= 9 && slotId <= 35) return 2;  // main inventory
        return 0; // crafting grid (0-4), armor (5-8) — NOT valid destinations
    }

    // Check if a candidate slot is a valid destination for transfers
    // Excludes crafting grid and armor slots in InventoryScreen
    @Unique
    private boolean it_isValidDestination(int sourceSlotId, int candidateSlotId, boolean isPlayerOnlyScreen, Slot sourceSlot) {
        if (isPlayerOnlyScreen) {
            // In InventoryScreen: only allow hotbar (36-44) and main inv (9-35) as destinations
            // Never allow crafting output (0), crafting grid (1-4), armor (5-8)
            int candidateRegion = it_playerInvRegion(candidateSlotId);
            if (candidateRegion == 0) return false; // crafting/armor — never valid
            int sourceRegion = it_playerInvRegion(sourceSlotId);
            if (sourceRegion == 0) return false; // source is crafting/armor — don't handle
            return candidateRegion != sourceRegion;
        } else {
            Slot candidate = handler.slots.get(candidateSlotId);
            boolean sourceIsPlayer = sourceSlot.inventory instanceof net.minecraft.entity.player.PlayerInventory;
            boolean candidateIsPlayer = candidate.inventory instanceof net.minecraft.entity.player.PlayerInventory;
            return sourceIsPlayer != candidateIsPlayer;
        }
    }

    @Unique
    private boolean it_isOtherSide(int sourceSlotId, int candidateSlotId, boolean isPlayerOnlyScreen) {
        if (isPlayerOnlyScreen) {
            int candidateRegion = it_playerInvRegion(candidateSlotId);
            if (candidateRegion == 0) return false; // crafting/armor — never valid
            int sourceRegion = it_playerInvRegion(sourceSlotId);
            if (sourceRegion == 0) return false;
            return candidateRegion != sourceRegion;
        } else {
            Slot source = handler.slots.get(sourceSlotId);
            Slot candidate = handler.slots.get(candidateSlotId);
            boolean sourceIsPlayer = source.inventory instanceof net.minecraft.entity.player.PlayerInventory;
            boolean candidateIsPlayer = candidate.inventory instanceof net.minecraft.entity.player.PlayerInventory;
            return sourceIsPlayer != candidateIsPlayer;
        }
    }
}
