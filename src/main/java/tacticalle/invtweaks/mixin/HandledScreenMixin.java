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

        // Debug logging
        boolean allBut1Down = InvTweaksConfig.isKeyPressed(config.allBut1Key);
        boolean only1Down = InvTweaksConfig.isKeyPressed(config.only1Key);
        if (config.enableDebugLogging && (allBut1Down || only1Down)) {
            String screenType = ((Object) this).getClass().getSimpleName();
            LOGGER.info("IT HEAD: screen={}, slot={}, button={}, action={}, allBut1Key={}({}), only1Key={}({}), shift={}",
                screenType, slotId, button, actionType,
                InvTweaksConfig.getKeyName(config.allBut1Key), allBut1Down,
                InvTweaksConfig.getKeyName(config.only1Key), only1Down,
                shiftPressed);
        }

        // Block vanilla PICKUP_ALL (double-click gather) when modifier keys are held.
        // This prevents double-clicking from collecting matching items from all slots.
        // Note: THROW (Ctrl+Q drop) is intentionally NOT blocked — players need it.
        if (allBut1Down || only1Down) {
            if (actionType == SlotActionType.PICKUP_ALL) {
                ci.cancel();
                return;
            }
        }

        // Shift+Click transfer
        if (shiftPressed && actionType == SlotActionType.QUICK_MOVE && config.enableShiftClickTransfer) {
            String mode = config.getActiveMode(config.flipShiftClickTransfer);
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

                    if (config.enableDebugLogging) LOGGER.info("IT: only1 shift-click - slot={}, dest={}", slotId, destSlot);

                    // Pick up stack, right-click dest (places 1), put rest back
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, destSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, SlotActionType.PICKUP, player);
                    im.clickSlot(handler.syncId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, SlotActionType.PICKUP, player);
                    return;
                }

                // "allbut1" mode: take snapshot and let vanilla proceed, fix in RETURN
                it_shiftClickMode = mode;
                it_shiftClickSlotId = slotId;
                ItemStack sourceStack = slot.getStack();
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
                String mode = config.getActiveMode(config.flipBundleInsertCursorBundle);
                if (mode != null) {
                    it_bundleInsertSlotCount = slotStack.getCount();
                    it_bundleInsertPending = true;
                    it_bundleInsertMode = mode;
                }
            } else if (slotStack.getItem() instanceof BundleItem && !cursorStack.isEmpty()
                       && !(cursorStack.getItem() instanceof BundleItem)
                       && config.enableBundleInsertCursorItems) {
                String mode = config.getActiveMode(config.flipBundleInsertCursorItems);
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

        // Shift+Click Transfer
        if (shiftPressed && actionType == SlotActionType.QUICK_MOVE
                && it_shiftClickMode != null
                && slotId == it_shiftClickSlotId) {
            if (InvTweaksConfig.get().enableDebugLogging) LOGGER.info("IT: handleShiftClick - mode={}, slot={}", it_shiftClickMode, slotId);
            it_handleShiftClick(slot, slotId, im, player, it_shiftClickMode);
            return;
        }

        // Non-shift PICKUP actions
        if (!shiftPressed && actionType == SlotActionType.PICKUP) {
            // Bundle extract: right-click on bundle
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && slot.getStack().getItem() instanceof BundleItem
                    && config.enableBundleExtract) {
                String mode = config.getActiveMode(config.flipBundleExtract);
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
                String mode = config.getActiveMode(config.flipClickPickup);
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

        if (InvTweaksConfig.get().enableDebugLogging) LOGGER.info("IT: handlePickup - mode={}, cursorCount={}", mode, cursorCount);

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

        if (InvTweaksConfig.get().enableDebugLogging) LOGGER.info("IT: Bundle extract ({}) - count={}", mode, cursorStack.getCount());

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

    // ========== UTILITY METHODS ==========

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
