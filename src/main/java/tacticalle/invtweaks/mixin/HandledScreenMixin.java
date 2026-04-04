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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;

import tacticalle.invtweaks.ContainerClassifier;
import tacticalle.invtweaks.ContainerClassifier.ContainerCategory;
import tacticalle.invtweaks.InvTweaksConfig;
import tacticalle.invtweaks.InvTweaksOverlay;
import tacticalle.invtweaks.LayoutClipboard;

import net.minecraft.client.gui.Click;

import java.util.HashMap;
import java.util.Map;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");

    @Shadow
    protected AbstractContainerMenu menu;

    @Shadow
    private Slot hoveredSlot;

    // Pre-click snapshot for shift-click tracking
    @Unique private Item it_preClickItem = null;
    @Unique private int it_preClickCount = 0;
    @Unique private Map<Integer, Integer> it_preClickSnapshot = null;
    @Unique private String it_shiftClickMode = null;
    @Unique private int it_shiftClickSlotId = -1;

    // Container classification (cached per screen open)
    @Unique private ContainerCategory it_containerCategory = null;

    // Bundle insert tracking
    @Unique private int it_bundleInsertSlotCount = 0;
    @Unique private boolean it_bundleInsertPending = false;
    @Unique private boolean it_bundleInsertReverse = false;
    @Unique private String it_bundleInsertMode = null;

    // ========== HEAD INJECTION ==========

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V", at = @At("HEAD"), cancellable = true)
    private void beforeOnMouseClick(Slot slot, int slotId, int button, ClickType actionType, CallbackInfo ci) {
        // Block all slot clicks when HalfSelectorOverlay is active
        if (tacticalle.invtweaks.HalfSelectorOverlay.isActive()) {
            ci.cancel();
            return;
        }
        if (slot == null) return;
        InvTweaksConfig config = InvTweaksConfig.get();
        long windowHandle = Minecraft.getInstance().getWindow().getHandle();
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
            if (actionType == ClickType.PICKUP_ALL) {
                ci.cancel();
                return;
            }
        }

        // Throwing tweaks: modifier + Q (THROW action) in GUI
        if (actionType == ClickType.THROW && config.enableThrowHalf) {
            ItemStack slotStack = slot.getStack();
            if (!slotStack.isEmpty() && slotStack.getCount() > 1) {
                boolean throwHalf = config.isThrowHalfKeyPressed();
                boolean throwAB1 = config.isThrowAllBut1KeyPressed();

                if (throwHalf || throwAB1) {
                    ci.cancel();
                    var mc = Minecraft.getInstance();
                    var im = mc.gameMode;
                    var player = mc.player;
                    if (im == null || player == null) return;

                    int count = slotStack.getCount();

                    if (throwAB1) {
                        // Throw all but 1: pick up stack, right-click 1 into source slot, throw cursor
                        // Note: right-click places HALF, not 1. So we use a temp slot.
                        InvTweaksConfig.debugLog("THROW-HALF", "allbut1 | slot=%d | count=%d", slotId, count);
                        int tempSlot = it_findEmptyPlayerSlot(slotId);
                        if (tempSlot < 0) return; // no temp slot available
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        // Place 1 in temp slot
                        im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                        // Throw remaining from cursor
                        im.clickSlot(menu.containerId, -999, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        // Move the 1 from temp back to source
                        im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    } else {
                        // Throw half: right-click to pick up half, then throw cursor
                        InvTweaksConfig.debugLog("THROW-HALF", "half | slot=%d | count=%d | dropping=%d", slotId, count, count / 2);
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, -999, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
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
        if (actionType == ClickType.SWAP) {
            String hotbarMode = config.getActiveMode("hotbarModifiers");
            if (hotbarMode != null) {
                ItemStack sourceStack = slot.getStack();
                if (sourceStack.isEmpty()) return; // nothing to move
                if (sourceStack.getCount() <= 1) return; // only 1 item, just do vanilla swap

                ci.cancel();
                var mc = Minecraft.getInstance();
                var im = mc.gameMode;
                var player = mc.player;
                if (im == null || player == null) return;

                int hotbarIndex = button; // button = 0-8 for hotbar slots
                int hotbarSlotId = it_findHotbarSlotId(hotbarIndex);
                if (hotbarSlotId < 0) return;

                if (config.enableDebugLogging) InvTweaksConfig.debugLog("HOTBAR", "mode=%s | sourceSlot=%d | hotbarSlot=%d | hotbarIndex=%d", hotbarMode, slotId, hotbarSlotId, hotbarIndex);

                ItemStack hotbarStack = menu.slots.get(hotbarSlotId).getStack();

                if (hotbarMode.equals("allbut1")) {
                    // Move all-but-1 from source to hotbar slot
                    if (hotbarStack.isEmpty()) {
                        // Empty hotbar slot: pick up stack, right-click 1 back to source, left-click hotbar
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    } else if (hotbarStack.getItem() == sourceStack.getItem()
                            && ItemStack.isSameItemSameComponents(hotbarStack, sourceStack)) {
                        // Same item in hotbar: pick up source, right-click 1 back, left-click hotbar to stack
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        // If there's overflow on cursor (hotbar full), put it back in source
                        if (!menu.getCarried().isEmpty()) {
                            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        }
                    } else {
                        // Different item in hotbar: swap hotbar item to source, then place N-1 in hotbar
                        // Pick up source stack
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        // Place into hotbar (swaps: cursor gets hotbar item, hotbar gets source)
                        im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        // Cursor now has the old hotbar item. Place it in source.
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        // Now source has old hotbar item, hotbar has full source stack.
                        // We need to pull 1 back from hotbar to source... but source is occupied.
                        // Use a temp slot approach:
                        int tempSlot = it_findEmptyPlayerSlot2(slotId, hotbarSlotId);
                        if (tempSlot >= 0) {
                            // Move old hotbar item from source to temp
                            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                            im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                            // Pick up from hotbar, right-click 1 to source, put rest back in hotbar
                            im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                            im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
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
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    } else if (hotbarStack.getItem() == sourceStack.getItem()
                            && ItemStack.isSameItemSameComponents(hotbarStack, sourceStack)
                            && hotbarStack.getCount() < hotbarStack.getMaxStackSize()) {
                        // Same item, room to stack: pick up source, right-click 1 into hotbar, put rest back
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, hotbarSlotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    }
                    // Different item or full stack: do nothing (don't swap for only1 with incompatible)
                }
                return;
            }
        }

        // Fill Existing Stacks: both modifier keys held + shift + click
        if (shiftPressed && actionType == ClickType.QUICK_MOVE && config.enableFillExisting
                && config.isFillExistingActive()) {
            ci.cancel();
            ItemStack sourceStack = slot.getStack();
            if (sourceStack.isEmpty()) return;

            var mc = Minecraft.getInstance();
            var im = mc.gameMode;
            var player = mc.player;
            if (im == null || player == null) return;

            Item itemType = sourceStack.getItem();
            int sourceCount = sourceStack.getCount();
            boolean isPlayerOnlyScreen = it_isPlayerOnlyScreen();

            // Find all partial stacks of the same item on the other side
            java.util.List<int[]> partialStacks = new java.util.ArrayList<>();
            for (int i = 0; i < menu.slots.size(); i++) {
                if (i == slotId) continue;
                if (!it_isOtherSide(slotId, i, isPlayerOnlyScreen)) continue;
                ItemStack destStack = menu.slots.get(i).getStack();
                if (!destStack.isEmpty() && destStack.getItem() == itemType
                        && ItemStack.isSameItemSameComponents(destStack, sourceStack)
                        && destStack.getCount() < destStack.getMaxStackSize()) {
                    int space = destStack.getMaxStackSize() - destStack.getCount();
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
            it_debugClickSlot(im, menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player, "FILL");

            // Left-click on each partial stack destination — items merge automatically up to max
            for (int[] partial : partialStacks) {
                int destSlotId = partial[0];
                ItemStack cursor = menu.getCarried();
                if (cursor.isEmpty()) break; // nothing left to distribute

                it_debugClickSlot(im, menu.containerId, destSlotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player, "FILL");
            }

            // Put any remainder back in source slot
            if (!menu.getCarried().isEmpty()) {
                it_debugClickSlot(im, menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player, "FILL");
            }

            InvTweaksConfig.debugLog("FILL", "result | sourceSlotNow=%d | cursorNow=%d",
                    slot.getStack().getCount(), menu.getCarried().getCount());
            return;
        }

        // Shift+Click transfer
        if (shiftPressed && actionType == ClickType.QUICK_MOVE && config.enableShiftClickTransfer) {
            String mode = config.getActiveMode("shiftClick");
            if (mode != null) {
                // Guard against macOS Command+Shift+Click bulk-move:
                // macOS fires QUICK_MOVE for ALL slots with matching items, not just the clicked one.
                // Mouse Tweaks also fires QUICK_MOVE for multiple slots (shift-drag), but via reflection.
                // We detect macOS bulk-move by checking:
                // 1. Is this for a different slot than hoveredSlot? (cursor isn't over it)
                // 2. Is the call NOT coming through reflection? (not Mouse Tweaks)
                // If both true, it's a macOS bulk-move ghost — block it.
                if (hoveredSlot == null || hoveredSlot.id != slotId) {
                    // Slot doesn't match cursor — could be Mouse Tweaks or bulk-move.
                    boolean viaReflection = it_isCalledViaReflection();
                    InvTweaksConfig.debugLog("SHIFT", "hoveredSlot mismatch | focused=%d | clicked=%d | viaReflection=%s",
                        hoveredSlot != null ? hoveredSlot.id : -1, slotId, viaReflection);
                    if (!viaReflection) {
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

                    var mc = Minecraft.getInstance();
                    var im = mc.gameMode;
                    var player = mc.player;
                    if (im == null || player == null) return;

                    // Find best destination: partial stack of same item first, then empty slot
                    int destSlot = it_findCompatibleSlotOnOtherSide(sourceStack.getItem(), slotId);
                    if (destSlot < 0) return; // no room

                    if (config.enableDebugLogging) InvTweaksConfig.debugLog("SHIFT", "only1 mode | slot=%d | dest=%d", slotId, destSlot);

                    // Pick up stack, right-click dest (places 1), put rest back
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, destSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
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

                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot s = menu.slots.get(i);
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
        if (!shiftPressed && actionType == ClickType.PICKUP && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            ItemStack cursorStack = menu.getCarried();
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

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V", at = @At("RETURN"))
    private void afterOnMouseClick(Slot slot, int slotId, int button, ClickType actionType, CallbackInfo ci) {
        if (slot == null) return;
        InvTweaksConfig config = InvTweaksConfig.get();

        MultiPlayerGameMode im = Minecraft.getInstance().gameMode;
        if (im == null) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;

        long windowHandle = Minecraft.getInstance().getWindow().getHandle();
        boolean shiftPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                              GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        // Comprehensive RETURN debug logging
        if (config.enableDebugLogging && config.isAnyModifierPressed()) {
            InvTweaksConfig.debugLog("RETURN", "afterOnMouseClick | slot=%d | button=%d | action=%s | shift=%s | cursorStack=%s(%d) | slotStack=%s(%d)",
                slotId, button, actionType, shiftPressed,
                slot.getStack().isEmpty() ? "empty" : slot.getStack().getItem().toString(), slot.getStack().getCount(),
                menu.getCarried().isEmpty() ? "empty" : menu.getCarried().getItem().toString(), menu.getCarried().getCount());
        }

        // Shift+Click Transfer
        if (shiftPressed && actionType == ClickType.QUICK_MOVE
                && it_shiftClickMode != null
                && slotId == it_shiftClickSlotId) {
            if (InvTweaksConfig.get().enableDebugLogging) InvTweaksConfig.debugLog("SHIFT", "handleShiftClick | mode=%s | slot=%d", it_shiftClickMode, slotId);
            it_handleShiftClick(slot, slotId, im, player, it_shiftClickMode);
            return;
        }

        // Non-shift PICKUP actions
        if (!shiftPressed && actionType == ClickType.PICKUP) {
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

    // ========== OVERLAY RENDERING (before tooltip) ==========

    // Inject at TAIL of renderBg rather than INVOKE of renderCursorStack in render().
    // RecipeBookScreen (parent of InventoryScreen) overrides render() and bypasses
    // AbstractContainerScreen.render() — it calls renderBg() then renderCursorStack() directly.
    // By injecting at the end of renderBg, the overlay fires for both code paths:
    // container screens (via AbstractContainerScreen.render()) and player inventory (via RecipeBookScreen.render()).
    @Inject(method = "renderBg", at = @At("TAIL"))
    private void afterRenderMain(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        InvTweaksOverlay.render(context, (AbstractContainerScreen<?>)(Object)this,
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }

    // ========== COPY/PASTE LAYOUT ==========

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        // HalfSelectorOverlay intercepts keys when active
        if (tacticalle.invtweaks.HalfSelectorOverlay.isActive()) {
            if (tacticalle.invtweaks.HalfSelectorOverlay.keyPressed(input.key())) {
                cir.setReturnValue(true);
                return;
            }
            // E key: let it pass through to close inventory (overlay auto-hides)
            if (input.key() == GLFW.GLFW_KEY_E) {
                tacticalle.invtweaks.HalfSelectorOverlay.hide();
                // Don't cancel — let vanilla close the screen
                return;
            }
            // Block all other keys while overlay is active
            cir.setReturnValue(true);
            return;
        }

        InvTweaksConfig config = InvTweaksConfig.get();
        if (!config.enableCopyPaste) return;

        int keyCode = input.key();
        long windowHandle = Minecraft.getInstance().getWindow().getHandle();

        // Clipboard history: configurable key or legacy Shift+Tab
        boolean historyTriggered;
        if (config.clipboardHistoryKey == -1) {
            boolean shiftHeld = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                                GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
            historyTriggered = shiftHeld && keyCode == GLFW.GLFW_KEY_TAB;
        } else {
            historyTriggered = keyCode == config.clipboardHistoryKey;
        }
        if (historyTriggered) {
            boolean isPlayerOnly = it_isPlayerOnlyScreen();
            Minecraft.getInstance().setScreen(new tacticalle.invtweaks.ClipboardHistoryScreen(
                    (AbstractContainerScreen<?>)(Object)this, menu, isPlayerOnly));
            cir.setReturnValue(true);
            return;
        }

        // Copy/Paste/Cut: configurable keys or legacy Ctrl+key / Cmd+key
        boolean copyTriggered;
        if (config.copyLayoutKey == -1) {
            boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                                  GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            boolean superPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                                   GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
            copyTriggered = (ctrlPressed || superPressed) && keyCode == GLFW.GLFW_KEY_C;
        } else {
            copyTriggered = keyCode == config.copyLayoutKey;
        }

        boolean pasteTriggered;
        if (config.pasteLayoutKey == -1) {
            boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                                  GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            boolean superPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                                   GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
            pasteTriggered = (ctrlPressed || superPressed) && keyCode == GLFW.GLFW_KEY_V;
        } else {
            pasteTriggered = keyCode == config.pasteLayoutKey;
        }

        boolean cutTriggered;
        if (config.cutLayoutKey == -1) {
            boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                                  GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            boolean superPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                                   GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
            cutTriggered = (ctrlPressed || superPressed) && keyCode == GLFW.GLFW_KEY_X;
        } else {
            cutTriggered = keyCode == config.cutLayoutKey;
        }

        // Undo: configurable key or legacy Ctrl+Z / Cmd+Z
        boolean undoTriggered;
        if (config.undoKey == -1) {
            boolean ctrlPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                                  GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            boolean superPressed = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                                   GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
            undoTriggered = (ctrlPressed || superPressed) && keyCode == GLFW.GLFW_KEY_Z;
        } else {
            undoTriggered = keyCode == config.undoKey;
        }

        if (!copyTriggered && !pasteTriggered && !cutTriggered && !undoTriggered) return;

        boolean isPlayerOnly = it_isPlayerOnlyScreen();

        // ========== BUNDLE CLIPBOARD HANDLING ==========
        // Bundle operations take priority when hovering a bundle
        boolean hoveringBundle = hoveredSlot != null && !hoveredSlot.getStack().isEmpty()
                && hoveredSlot.getStack().getItem() instanceof BundleItem;

        if (copyTriggered && hoveringBundle) {
            InvTweaksConfig.debugLog("BUNDLE-COPY", "copy triggered on bundle | slot=%d", hoveredSlot.id);
            LayoutClipboard.copyBundleLayout(hoveredSlot.getStack(), hoveredSlot.id);
            cir.setReturnValue(true);
            return;
        }

        if (pasteTriggered && hoveringBundle) {
            InvTweaksConfig.debugLog("BUNDLE-PASTE", "paste triggered on bundle | slot=%d", hoveredSlot.id);
            LayoutClipboard.BundlePasteResult result = LayoutClipboard.pasteBundleLayout(menu, hoveredSlot.id);
            it_showBundlePasteResult(result);
            cir.setReturnValue(true);
            return;
        }

        if (cutTriggered && hoveringBundle) {
            InvTweaksConfig.debugLog("BUNDLE-COPY", "cut triggered on bundle | slot=%d", hoveredSlot.id);
            LayoutClipboard.copyBundleLayout(hoveredSlot.getStack(), hoveredSlot.id, true);
            InvTweaksOverlay.show("Bundle layout copied (cut not supported)", 0xFF55FF55);
            cir.setReturnValue(true);
            return;
        }

        // Check if bundle on cursor (not in a slot) when pasting
        if (pasteTriggered) {
            ItemStack cursorStack = menu.getCarried();
            if (cursorStack != null && !cursorStack.isEmpty() && cursorStack.getItem() instanceof BundleItem) {
                InvTweaksOverlay.show("Place bundle in a slot first", 0xFFFFFF55);
                cir.setReturnValue(true);
                return;
            }
        }

        // ========== CONTAINER CLASSIFICATION + INCOMPATIBLE BLOCKING ==========
        if (!isPlayerOnly) {
            if (it_containerCategory == null) {
                net.minecraft.network.chat.Component screenTitle = ((net.minecraft.client.gui.screen.Screen)(Object)this).getTitle();
                it_containerCategory = ContainerClassifier.classifyContainer(menu, screenTitle);
                InvTweaksConfig.debugLog("CLASSIFY", "%s \u2192 %s",
                        menu.getClass().getSimpleName(),
                        ContainerClassifier.getCategoryName(it_containerCategory));
            }
            if (it_containerCategory == ContainerCategory.INCOMPATIBLE) {
                if (copyTriggered || pasteTriggered || cutTriggered) {
                    InvTweaksOverlay.show("Incompatible", 0xFFFF8800);
                    cir.setReturnValue(true);
                    return;
                }
                // Clipboard history browser is allowed to open in incompatible containers
            }
        }

        // ========== REGULAR CLIPBOARD HANDLING ==========
        if (copyTriggered) {
            // Copy layout — route through container category
            InvTweaksConfig.debugLog("COPY", "copy triggered | playerOnly=%s | category=%s", isPlayerOnly,
                    isPlayerOnly ? "PLAYER_ONLY" : (it_containerCategory != null ? ContainerClassifier.getCategoryName(it_containerCategory) : "null"));
            if (!isPlayerOnly && it_containerCategory != null) {
                tacticalle.invtweaks.LayoutClipboard.copyLayout(menu, isPlayerOnly, false, it_containerCategory);
            } else {
                tacticalle.invtweaks.LayoutClipboard.copyLayout(menu, isPlayerOnly);
            }
            cir.setReturnValue(true);
        } else if (pasteTriggered) {
            // Paste layout — with size-mismatch pre-check and HalfSelectorOverlay support
            InvTweaksConfig.debugLog("PASTE", "paste triggered | playerOnly=%s", isPlayerOnly);

            // If HalfSelectorOverlay is active, ignore paste (don't re-trigger)
            if (tacticalle.invtweaks.HalfSelectorOverlay.isActive()) {
                cir.setReturnValue(true);
                return;
            }

            // Get active clipboard entry to check size mismatch BEFORE calling paste
            tacticalle.invtweaks.LayoutClipboard.HistoryEntry activeEntry;
            if (!isPlayerOnly && it_containerCategory != null) {
                activeEntry = tacticalle.invtweaks.LayoutClipboard.getActiveEntry(isPlayerOnly, it_containerCategory);
            } else {
                activeEntry = tacticalle.invtweaks.LayoutClipboard.getActiveEntry(isPlayerOnly);
            }

            if (activeEntry == null) {
                // Category-specific "no layout" messages
                if (!isPlayerOnly && it_containerCategory != null) {
                    String msg = switch (it_containerCategory) {
                        case GRID9, CRAFTER, CRAFTING_TABLE -> "No grid layout copied";
                        case HOPPER -> "No hopper layout copied";
                        case FURNACE -> "No furnace layout copied";
                        default -> "No layout copied";
                    };
                    tacticalle.invtweaks.InvTweaksOverlay.show(msg, 0xFFFF8800);
                } else {
                    tacticalle.invtweaks.InvTweaksOverlay.show("No layout copied", 0xFFFF5555);
                }
                cir.setReturnValue(true);
                return;
            }

            // For category-aware containers, use category-aware paste directly
            if (!isPlayerOnly && it_containerCategory != null
                    && it_containerCategory != ContainerCategory.STANDARD) {
                LayoutClipboard.captureUndoSnapshot(menu, it_containerCategory, false);
                tacticalle.invtweaks.LayoutClipboard.PasteResult result =
                        tacticalle.invtweaks.LayoutClipboard.pasteLayout(menu, isPlayerOnly, it_containerCategory);
                it_showPasteResult(result);
                cir.setReturnValue(true);
                return;
            }

            // Check type mismatch (for STANDARD and player-only)
            if (activeEntry.snapshot.isPlayerInventory != isPlayerOnly) {
                String clipType = activeEntry.snapshot.isPlayerInventory ? "player inventory" : "container";
                String currentType = isPlayerOnly ? "player inventory" : "container";
                tacticalle.invtweaks.InvTweaksOverlay.show(
                        "Layout was copied from " + clipType + ", can't paste into " + currentType, 0xFFFF5555);
                cir.setReturnValue(true);
                return;
            }

            if (!isPlayerOnly) {
                int clipSize = activeEntry.snapshot.slotCount;
                int containerSize = tacticalle.invtweaks.LayoutClipboard.getContainerSlotCount(menu);

                if (clipSize != containerSize) {
                    // Size mismatch — handle 54↔27 cases with configurable mode
                    if ((clipSize == 54 && containerSize == 27) || (clipSize == 27 && containerSize == 54)) {
                        int pasteMode = config.sizeMismatchPasteMode;
                        boolean is54to27 = (clipSize == 54 && containerSize == 27);
                        String direction = is54to27 ? "54\u219227" : "27\u219254";
                        String modeName = pasteMode == 0 ? "hover" : pasteMode == 1 ? "menu" : "arrow";
                        InvTweaksConfig.debugLog("SIZE-MISMATCH", "direction=%s | mode=%s", direction, modeName);

                        if (is54to27) {
                            // 54→27: always use Menu mode (player needs preview to choose clipboard half)
                            final boolean finalIsPlayerOnly = isPlayerOnly;
                            InvTweaksConfig.debugLog("SIZE-MISMATCH", "54→27 forced menu mode");
                            tacticalle.invtweaks.HalfSelectorOverlay.show(
                                    activeEntry.snapshot.slots, isPlayerOnly, "54to27",
                                    (half) -> {
                                        LayoutClipboard.captureUndoSnapshot(menu, ContainerCategory.STANDARD, false);
                                        Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> sliced =
                                                tacticalle.invtweaks.LayoutClipboard.sliceClipboardHalf(
                                                        activeEntry.snapshot.slots, half);
                                        tacticalle.invtweaks.LayoutClipboard.PasteResult result =
                                                tacticalle.invtweaks.LayoutClipboard.pasteLayout(menu, finalIsPlayerOnly, sliced);
                                        it_showPasteResult(result);
                                    }
                            );
                            cir.setReturnValue(true);
                            return;
                        }

                        // 27→54: respect sizeMismatchPasteMode config
                        if (pasteMode == 0) {
                            // Mode 0: Hover Position — instant paste based on cursor Y
                            String half = it_getHoverHalf((AbstractContainerScreen<?>)(Object)this);
                            InvTweaksConfig.debugLog("SIZE-MISMATCH", "hover selected %s half", half);
                            LayoutClipboard.captureUndoSnapshot(menu, ContainerCategory.STANDARD, false);
                            Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> shifted =
                                    it_shiftClipboardForHalf(activeEntry.snapshot.slots, half);
                            tacticalle.invtweaks.LayoutClipboard.PasteResult result =
                                    tacticalle.invtweaks.LayoutClipboard.pasteLayout(menu, isPlayerOnly, shifted);
                            it_showPasteResultWithHalf(result, half);
                            cir.setReturnValue(true);
                            return;
                        } else if (pasteMode == 1) {
                            // Mode 1: Menu Selection — show HalfSelectorOverlay
                            final boolean finalIsPlayerOnly = isPlayerOnly;
                            tacticalle.invtweaks.HalfSelectorOverlay.show(
                                    activeEntry.snapshot.slots, isPlayerOnly, "27to54",
                                    (half) -> {
                                        LayoutClipboard.captureUndoSnapshot(menu, ContainerCategory.STANDARD, false);
                                        Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> shifted =
                                                it_shiftClipboardForHalf(activeEntry.snapshot.slots, half);
                                        tacticalle.invtweaks.LayoutClipboard.PasteResult result =
                                                tacticalle.invtweaks.LayoutClipboard.pasteLayout(menu, finalIsPlayerOnly, shifted);
                                        it_showPasteResultWithHalf(result, half);
                                    }
                            );
                            cir.setReturnValue(true);
                            return;
                        } else {
                            // Mode 2: Arrow Keys — check Up/Down at paste time
                            boolean upHeld = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS;
                            boolean downHeld = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS;
                            if (upHeld == downHeld) {
                                InvTweaksOverlay.show("Hold \u2191/\u2193 to select half", 0xFFFFFF55);
                                InvTweaksConfig.debugLog("SIZE-MISMATCH", "arrow mode: no valid key held, cancelled");
                                cir.setReturnValue(true);
                                return;
                            }
                            String half = upHeld ? "top" : "bottom";
                            InvTweaksConfig.debugLog("SIZE-MISMATCH", "arrow selected %s half", half);
                            LayoutClipboard.captureUndoSnapshot(menu, ContainerCategory.STANDARD, false);
                            Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> shifted =
                                    it_shiftClipboardForHalf(activeEntry.snapshot.slots, half);
                            tacticalle.invtweaks.LayoutClipboard.PasteResult result =
                                    tacticalle.invtweaks.LayoutClipboard.pasteLayout(menu, isPlayerOnly, shifted);
                            it_showPasteResultWithHalf(result, half);
                            cir.setReturnValue(true);
                            return;
                        }
                    } else {
                        // Other size mismatches still blocked
                        tacticalle.invtweaks.InvTweaksOverlay.show("Layout size incompatible", 0xFFFFFF55);
                        cir.setReturnValue(true);
                        return;
                    }
                }
            }

            // Normal paste (sizes match or player-only)
            ContainerCategory undoCategory = isPlayerOnly ? ContainerCategory.PLAYER_ONLY :
                    (it_containerCategory != null ? it_containerCategory : ContainerCategory.STANDARD);
            LayoutClipboard.captureUndoSnapshot(menu, undoCategory, isPlayerOnly);
            tacticalle.invtweaks.LayoutClipboard.PasteResult result =
                    tacticalle.invtweaks.LayoutClipboard.pasteLayout(menu, isPlayerOnly);
            it_showPasteResult(result);
            cir.setReturnValue(true);
        } else if (cutTriggered) {
            // Cut layout — route through container category
            InvTweaksConfig.debugLog("CUT", "cut triggered | playerOnly=%s | category=%s", isPlayerOnly,
                    isPlayerOnly ? "PLAYER_ONLY" : (it_containerCategory != null ? ContainerClassifier.getCategoryName(it_containerCategory) : "null"));
            if (!isPlayerOnly) {
                ContainerCategory cutCategory = it_containerCategory != null ? it_containerCategory : ContainerCategory.STANDARD;
                LayoutClipboard.captureUndoSnapshot(menu, cutCategory, false);
            }
            if (!isPlayerOnly && it_containerCategory != null) {
                tacticalle.invtweaks.LayoutClipboard.cutLayout(menu, isPlayerOnly, it_containerCategory);
            } else {
                tacticalle.invtweaks.LayoutClipboard.cutLayout(menu, isPlayerOnly);
            }
            cir.setReturnValue(true);
        } else if (undoTriggered) {
            // Undo last paste/cut
            InvTweaksConfig.debugLog("UNDO", "undo triggered");
            if (LayoutClipboard.getUndoSnapshot() == null) {
                InvTweaksOverlay.show("Nothing to undo", 0xFFFF5555);
                cir.setReturnValue(true);
                return;
            }
            LayoutClipboard.UndoResult undoResult = LayoutClipboard.executeUndo(menu);
            if (undoResult == null) {
                InvTweaksOverlay.show("Nothing to undo", 0xFFFF5555);
            } else if (undoResult.slotsTotal == 0) {
                InvTweaksOverlay.show("Paste undone", 0xFF55FF55);
            } else if (undoResult.success) {
                InvTweaksOverlay.show("Paste undone", 0xFF55FF55);
            } else {
                InvTweaksOverlay.show("Paste partially undone (" + undoResult.slotsRestored + "/" + undoResult.slotsTotal + " slots)", 0xFFFFFF55);
            }
            cir.setReturnValue(true);
        }
    }

    // ========== SCREEN CLOSE — UNDO CLEANUP ==========

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        LayoutClipboard.clearUndoSnapshot();
        it_containerCategory = null;
    }

    // ========== PASTE RESULT DISPLAY ==========

    @Unique
    private void it_showPasteResult(tacticalle.invtweaks.LayoutClipboard.PasteResult result) {
        if (result.alreadyMatched) {
            tacticalle.invtweaks.InvTweaksOverlay.show("Layout already matches!", 0xFF55FF55);
            return;
        }
        if (result.noClipboard) {
            tacticalle.invtweaks.InvTweaksOverlay.show("No layout copied", 0xFFFF5555);
            return;
        }
        if (result.noMatchingItems) {
            tacticalle.invtweaks.InvTweaksOverlay.show("No matching items available", 0xFFFFFF55);
            return;
        }
        if (result.sizeMismatch) {
            tacticalle.invtweaks.InvTweaksOverlay.show("Layout size incompatible", 0xFFFFFF55);
            return;
        }
        if (result.typeMismatch) {
            tacticalle.invtweaks.InvTweaksOverlay.show(result.errorMessage, 0xFFFF5555);
            return;
        }

        // Quantity maximization: all types already matched, only topped up stacks
        if (result.quantityMaxOnly) {
            if (result.slotsPlaced > 0) {
                tacticalle.invtweaks.InvTweaksOverlay.show("Stacks topped up", 0xFF55FF55);
            } else {
                tacticalle.invtweaks.InvTweaksOverlay.show("Layout already matches!", 0xFF55FF55);
            }
            InvTweaksConfig.debugLog("PASTE", "quantityMaxOnly result | placed=%d/%d", result.slotsPlaced, result.slotsTotal);
            return;
        }

        // Success case — determine message based on how many slots were placed
        if (result.slotsPlaced == result.slotsTotal) {
            tacticalle.invtweaks.InvTweaksOverlay.show("Layout pasted", 0xFF55FF55);
            if (result.cursorWasOccupied) {
                tacticalle.invtweaks.InvTweaksOverlay.show("Items on cursor held", 0xFFFFFF55);
            }
        } else if (result.slotsPlaced > 0) {
            tacticalle.invtweaks.InvTweaksOverlay.show(
                    "Layout partially pasted (" + result.slotsPlaced + "/" + result.slotsTotal + " slots)", 0xFFFFAA00);
            tacticalle.invtweaks.InvTweaksOverlay.show("No room for remaining items", 0xFFFFFF55);
        } else {
            tacticalle.invtweaks.InvTweaksOverlay.show("Could not paste layout \u2014 no room", 0xFFFF5555);
        }

        if (result.lockedSlotsSkipped > 0) {
            tacticalle.invtweaks.InvTweaksOverlay.show(
                    result.lockedSlotsSkipped + " locked slot" + (result.lockedSlotsSkipped == 1 ? "" : "s") + " skipped", 0xFFFF8800);
        }

        InvTweaksConfig.debugLog("PASTE", "result displayed | placed=%d/%d | cursor=%s",
                result.slotsPlaced, result.slotsTotal, result.cursorWasOccupied);
    }

    // ========== BUNDLE PASTE RESULT DISPLAY ==========

    @Unique
    private void it_showBundlePasteResult(LayoutClipboard.BundlePasteResult result) {
        if (result.noClipboard) {
            InvTweaksOverlay.show("No bundle layout copied", 0xFFFF5555);
            return;
        }
        if (result.bundleOnCursor) {
            InvTweaksOverlay.show("Place bundle in a slot first", 0xFFFFFF55);
            return;
        }
        if (result.itemsInserted == result.itemsTotal && result.itemsTotal > 0) {
            InvTweaksOverlay.show("Bundle layout pasted", 0xFF55FF55);
        } else if (result.itemsInserted > 0) {
            String msg = "Bundle partially filled (" + result.itemsInserted + "/" + result.itemsTotal + " items)";
            if (result.missingItems) {
                msg += " \u2014 missing items";
            }
            InvTweaksOverlay.show(msg, 0xFFFFAA00);
        } else if (result.missingItems) {
            InvTweaksOverlay.show("No matching items available for bundle", 0xFFFFFF55);
        } else if (result.itemsTotal == 0) {
            InvTweaksOverlay.show("Bundle clipboard is empty", 0xFFFFFF55);
        } else {
            InvTweaksOverlay.show("Could not fill bundle \u2014 bundle full or no items", 0xFFFF5555);
        }

        InvTweaksConfig.debugLog("BUNDLE-PASTE", "result displayed | inserted=%d/%d | missing=%s",
                result.itemsInserted, result.itemsTotal, result.missingItems);
    }

    // ========== HOVER HALF DETECTION (Mode 0) ==========

    @Unique
    private String it_getHoverHalf(AbstractContainerScreen<?> screen) {
        tacticalle.invtweaks.mixin.HandledScreenAccessor accessor = (tacticalle.invtweaks.mixin.HandledScreenAccessor) screen;
        int guiY = accessor.getY();

        int containerMinY = Integer.MAX_VALUE;
        int containerMaxY = Integer.MIN_VALUE;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!(slot.inventory instanceof net.minecraft.entity.player.Inventory)) {
                if (slot.y < containerMinY) containerMinY = slot.y;
                if (slot.y > containerMaxY) containerMaxY = slot.y;
            }
        }

        if (containerMinY == Integer.MAX_VALUE) {
            InvTweaksConfig.debugLog("SIZE-MISMATCH", "hover: no container slots found, defaulting to top");
            return "top";
        }

        int slotHeight = 18;
        int containerTopPx = guiY + containerMinY;
        int containerBottomPx = guiY + containerMaxY + slotHeight;
        int midpoint = (containerTopPx + containerBottomPx) / 2;

        Minecraft mc = Minecraft.getInstance();
        double mouseY = mc.mouse.getY() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getHeight();

        InvTweaksConfig.debugLog("SIZE-MISMATCH", "hover: mouseY=%.0f | containerTop=%d | containerBottom=%d | midpoint=%d",
                mouseY, containerTopPx, containerBottomPx, midpoint);

        return mouseY < midpoint ? "top" : "bottom";
    }

    // ========== 27→54 CLIPBOARD SHIFTING ==========

    /**
     * For 27→54 paste: shift clipboard keys so items land in the chosen half of a 54-slot container.
     * "top" = keys stay 0-26, "bottom" = keys become 27-53.
     */
    @Unique
    private static Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> it_shiftClipboardForHalf(
            Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> clipboardSlots, String half) {
        if (half.equals("top")) {
            // Keys 0-26 stay as-is — paste into top rows of 54-slot container
            return clipboardSlots;
        }
        // Shift keys by 27 so they target bottom rows (27-53)
        Map<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> shifted = new HashMap<>();
        for (Map.Entry<Integer, tacticalle.invtweaks.LayoutClipboard.SlotData> entry : clipboardSlots.entrySet()) {
            shifted.put(entry.getKey() + 27, entry.getValue());
        }
        return shifted;
    }

    // ========== SIZE-MISMATCH PASTE RESULT WITH HALF LABEL ==========

    @Unique
    private void it_showPasteResultWithHalf(tacticalle.invtweaks.LayoutClipboard.PasteResult result, String half) {
        String halfLabel = half.equals("top") ? "top half" : "bottom half";
        if (result.quantityMaxOnly) {
            if (result.slotsPlaced > 0) {
                tacticalle.invtweaks.InvTweaksOverlay.show("Stacks topped up", 0xFF55FF55);
            } else {
                tacticalle.invtweaks.InvTweaksOverlay.show("Layout already matches!", 0xFF55FF55);
            }
        } else if (result.alreadyMatched) {
            tacticalle.invtweaks.InvTweaksOverlay.show("Layout already matches!", 0xFF55FF55);
        } else if (result.noMatchingItems) {
            tacticalle.invtweaks.InvTweaksOverlay.show("No matching items available", 0xFFFFFF55);
        } else if (result.slotsPlaced == result.slotsTotal) {
            tacticalle.invtweaks.InvTweaksOverlay.show("Layout pasted (" + halfLabel + ")", 0xFF55FF55);
            if (result.cursorWasOccupied) {
                tacticalle.invtweaks.InvTweaksOverlay.show("Items on cursor held", 0xFFFFFF55);
            }
        } else if (result.slotsPlaced > 0) {
            tacticalle.invtweaks.InvTweaksOverlay.show(
                    "Layout partially pasted (" + result.slotsPlaced + "/" + result.slotsTotal + " slots, " + halfLabel + ")", 0xFFFFAA00);
            tacticalle.invtweaks.InvTweaksOverlay.show("No room for remaining items", 0xFFFFFF55);
        } else {
            tacticalle.invtweaks.InvTweaksOverlay.show("Could not paste layout \u2014 no room", 0xFFFF5555);
        }
    }

    // ========== MOUSE CLICK INTERCEPT FOR HALFSELECTOROVERLAY ==========

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(Click click, boolean focused, CallbackInfoReturnable<Boolean> cir) {
        if (tacticalle.invtweaks.HalfSelectorOverlay.isActive()) {
            double mouseX = click.x();
            double mouseY = click.y();
            if (tacticalle.invtweaks.HalfSelectorOverlay.mouseClicked(mouseX, mouseY, click.button())) {
                cir.setReturnValue(true);
            }
        }
    }

    // ========== SCROLL TRANSFER ==========

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        // Early exit: let vanilla handle bundle scroll selection
        if (this.hoveredSlot != null && this.hoveredSlot.getStack().getItem() instanceof BundleItem) {
            return;
        }

        InvTweaksConfig.debugLog("SCROLL-RAW", "verticalAmount=%s horizontalAmount=%s", verticalAmount, horizontalAmount);
        InvTweaksConfig config = InvTweaksConfig.get();
        if (!config.enableScrollTransfer) return;

        // Creative inventory has its own scroll behavior (tab scrolling) — don't intercept
        if (((Object) this) instanceof net.minecraft.client.gui.screen.ingame.CreativeModeInventoryScreen) return;

        // Player-only inventory (E key): do nothing for now (deferred to v2)
        if (it_isPlayerOnlyScreen()) return;

        // Need a focused slot with items
        if (hoveredSlot == null) return;
        ItemStack hoveredStack = hoveredSlot.getStack();
        if (hoveredStack.isEmpty()) return;

        // Allow scroll even with items on cursor — cursor items stay

        // Determine scroll mode (flush / leave1 / fill-existing / leave1+fill-existing)
        String scrollMode = config.getScrollTransferMode();
        InvTweaksConfig.debugLog("SCROLL", "decision | mode=%s | hoveredSlot=%d | item=%s | count=%d | slotIsPlayer=%s | leave1KeyHeld=%s | verticalAmt=%.1f",
                scrollMode != null ? scrollMode : "none",
                hoveredSlot != null ? hoveredSlot.id : -1,
                hoveredStack.getItem(),
                hoveredStack.getCount(),
                hoveredSlot != null ? String.valueOf(hoveredSlot.inventory instanceof net.minecraft.entity.player.Inventory) : "null",
                String.valueOf(config.isKeyPressed(config.scrollLeave1Key)),
                verticalAmount);
        if (scrollMode == null) return;

        // Determine scroll direction
        boolean scrollUp = verticalAmount > 0;
        boolean scrollDown = verticalAmount < 0;
        if (!scrollUp && !scrollDown) return;

        // Determine which side the hovered slot is on
        boolean hoveredIsPlayer = hoveredSlot.inventory instanceof net.minecraft.entity.player.Inventory;

        // Scroll up = items go UP to chest = scan player side to QUICK_MOVE them
        // Scroll down = items go DOWN to player = scan container side to QUICK_MOVE them
        // This is independent of which side the cursor is hovering
        boolean scanPlayerSide = scrollUp;

        // Cancel vanilla scroll (hotbar scrolling)
        cir.setReturnValue(true);

        var mc = Minecraft.getInstance();
        var im = mc.gameMode;
        var player = mc.player;
        if (im == null || player == null) return;

        Item itemType = hoveredStack.getItem();
        int hoveredSlotId = hoveredSlot.id;

        // Find all slots on the SAME side as the hovered slot that contain the same item type
        java.util.List<Integer> matchingSlots = new java.util.ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.getStack().isEmpty()) continue;
            if (s.getStack().getItem() != itemType) continue;
            if (!ItemStack.isSameItemSameComponents(s.getStack(), hoveredStack)) continue;

            // Must be on the side we're scanning
            boolean slotIsPlayer = s.inventory instanceof net.minecraft.entity.player.Inventory;
            if (slotIsPlayer != scanPlayerSide) continue;

            matchingSlots.add(i);
        }

        if (matchingSlots.isEmpty()) return;

        InvTweaksConfig.debugLog("SCROLL", "%s | item=%s | side=%s | direction=%s | matchingSlots=%d",
                scrollMode, itemType, scanPlayerSide ? "player" : "container",
                scrollUp ? "up" : "down", matchingSlots.size());

        boolean isFillExisting = scrollMode.contains("fill-existing");
        boolean isLeave1 = scrollMode.contains("leave1");

        if (isFillExisting && !isLeave1) {
            // Fill-existing flush: manually merge into partial stacks only, never QUICK_MOVE
            for (int slotId : matchingSlots) {
                ItemStack slotStack = menu.slots.get(slotId).getStack();
                if (slotStack.isEmpty()) continue;

                if (!it_hasExistingPartialStackOnDest(slotStack, scanPlayerSide)) {
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing skip | slot=%d | item=%s | reason=no existing stack on destination",
                            slotId, slotStack.getItem());
                    continue;
                }

                int beforeCount = slotStack.getCount();
                ItemStack sourceRef = slotStack.copy();
                // Pick up the source stack
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);

                // Merge cursor into each partial stack on destination
                for (int di = 0; di < menu.slots.size(); di++) {
                    Slot ds = menu.slots.get(di);
                    boolean dsIsPlayer = ds.inventory instanceof net.minecraft.entity.player.Inventory;
                    if (dsIsPlayer == scanPlayerSide) continue; // skip source side
                    if (ds.getStack().isEmpty()) continue;
                    if (!ItemStack.isSameItemSameComponents(ds.getStack(), sourceRef)) continue;
                    if (ds.getStack().getCount() >= ds.getStack().getMaxStackSize()) continue;
                    im.clickSlot(menu.containerId, di, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing merge | src=%d | dest=%d", slotId, di);
                    if (menu.getCarried().isEmpty()) break;
                }

                // Put remainder back into source slot
                if (!menu.getCarried().isEmpty()) {
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    int remaining = menu.slots.get(slotId).getStack().getCount();
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing merge | src=%d | moved=%d | remaining=%d",
                            slotId, beforeCount - remaining, remaining);
                } else {
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing merge | src=%d | moved=%d | remaining=0",
                            slotId, beforeCount);
                }
            }
        } else if (isFillExisting && isLeave1) {
            // Leave-1 + fill-existing: move all-but-1, merge into partial stacks only
            for (int slotId : matchingSlots) {
                ItemStack slotStack = menu.slots.get(slotId).getStack();
                if (slotStack.isEmpty()) continue;
                if (slotStack.getCount() <= 1) {
                    InvTweaksConfig.debugLog("SCROLL", "leave1 skip | slot=%d | count=1", slotId);
                    continue;
                }

                if (!it_hasExistingPartialStackOnDest(slotStack, scanPlayerSide)) {
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing skip | slot=%d | item=%s | reason=no existing stack on destination",
                            slotId, slotStack.getItem());
                    continue;
                }

                int beforeCount = slotStack.getCount();
                ItemStack sourceRef = slotStack.copy();
                // Pick up source stack, right-click to leave 1 back
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);

                // Merge cursor into each partial stack on destination
                for (int di = 0; di < menu.slots.size(); di++) {
                    Slot ds = menu.slots.get(di);
                    boolean dsIsPlayer = ds.inventory instanceof net.minecraft.entity.player.Inventory;
                    if (dsIsPlayer == scanPlayerSide) continue; // skip source side
                    if (ds.getStack().isEmpty()) continue;
                    if (!ItemStack.isSameItemSameComponents(ds.getStack(), sourceRef)) continue;
                    if (ds.getStack().getCount() >= ds.getStack().getMaxStackSize()) continue;
                    im.clickSlot(menu.containerId, di, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing merge | src=%d | dest=%d", slotId, di);
                    if (menu.getCarried().isEmpty()) break;
                }

                // Put remainder back into source slot (merge with the 1 left behind)
                if (!menu.getCarried().isEmpty()) {
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    int remaining = menu.slots.get(slotId).getStack().getCount();
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing merge | src=%d | moved=%d | remaining=%d",
                            slotId, beforeCount - remaining, remaining);
                } else {
                    InvTweaksConfig.debugLog("SCROLL", "fill-existing merge | src=%d | moved=%d | remaining=1",
                            slotId, beforeCount - 1);
                }
            }
        } else if (!isLeave1) {
            // Flush mode (no fill-existing): shift-click every matching slot to move full stacks
            for (int slotId : matchingSlots) {
                ItemStack slotStack = menu.slots.get(slotId).getStack();
                if (slotStack.isEmpty()) continue;

                InvTweaksConfig.debugLog("SCROLL", "flush QUICK_MOVE | slot=%d | count=%d", slotId, slotStack.getCount());
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.QUICK_MOVE, player);
            }
        } else {
            // Leave-1 mode (no fill-existing): move all but 1 via temp slot + QUICK_MOVE
            for (int slotId : matchingSlots) {
                ItemStack slotStack = menu.slots.get(slotId).getStack();
                if (slotStack.isEmpty()) continue;
                if (slotStack.getCount() <= 1) {
                    InvTweaksConfig.debugLog("SCROLL", "leave1 skip | slot=%d | count=1", slotId);
                    continue;
                }

                InvTweaksConfig.debugLog("SCROLL", "leave1 | slot=%d | count=%d", slotId, slotStack.getCount());

                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                int tempSlot = it_findEmptySlotOnSameSide(slotId, scanPlayerSide);
                boolean tempOnDestSide = false;
                if (tempSlot < 0) {
                    tempSlot = it_findEmptySlotOnSameSide(slotId, !scanPlayerSide);
                    if (tempSlot >= 0) {
                        tempOnDestSide = true;
                        InvTweaksConfig.debugLog("SCROLL", "leave1 temp on dest side | slot=%d", tempSlot);
                    }
                }
                if (tempSlot >= 0) {
                    if (!tempOnDestSide) {
                        im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                        im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.QUICK_MOVE, player);
                    } else {
                        boolean deposited = false;
                        for (int di = 0; di < menu.slots.size(); di++) {
                            Slot ds = menu.slots.get(di);
                            boolean dsIsPlayer = ds.inventory instanceof net.minecraft.entity.player.Inventory;
                            if (dsIsPlayer == scanPlayerSide) continue;
                            if (ds.getStack().isEmpty()) continue;
                            if (ds.getStack().getItem() != itemType) continue;
                            if (ds.getStack().getCount() >= ds.getStack().getMaxStackSize()) continue;
                            im.clickSlot(menu.containerId, di, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                            InvTweaksConfig.debugLog("SCROLL", "leave1 merged into dest slot %d", di);
                            if (menu.getCarried().isEmpty()) {
                                deposited = true;
                                break;
                            }
                        }
                        if (!deposited && !menu.getCarried().isEmpty()) {
                            im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                            InvTweaksConfig.debugLog("SCROLL", "leave1 remainder into empty dest slot %d", tempSlot);
                        }
                    }
                } else {
                    InvTweaksConfig.debugLog("SCROLL", "leave1 no temp slot | slot=%d | putting back", slotId);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                }
            }
        }

        InvTweaksConfig.debugLog("SCROLL", "complete | cursorEmpty=%s", menu.getCarried().isEmpty());
    }

    // ========== SCROLL TRANSFER HELPERS ==========

    /**
     * Find an empty slot on the same side as the source slot (for temp storage during leave-1 scroll).
     */
    @Unique
    private int it_findEmptySlotOnSameSide(int sourceSlotId, boolean sourceIsPlayer) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == sourceSlotId) continue;
            Slot s = menu.slots.get(i);
            if (!s.getStack().isEmpty()) continue;

            boolean slotIsPlayer = s.inventory instanceof net.minecraft.entity.player.Inventory;
            if (slotIsPlayer != sourceIsPlayer) continue;

            // For player inventory, skip crafting/armor slots (indices 0-8 in InventoryScreen)
            // But since we already checked it_isPlayerOnlyScreen() and returned, we're in a container screen
            // where all player inv slots are valid
            return i;
        }
        return -1;
    }

    /**
     * Check if the destination side has any slot containing the same item with room for more.
     * Used by fill-existing scroll mode to skip items with no existing partial stack on destination.
     * scanPlayerSide is the SOURCE side; destination is the opposite.
     */
    @Unique
    private boolean it_hasExistingPartialStackOnDest(ItemStack sourceStack, boolean scanPlayerSide) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            boolean slotIsPlayer = s.inventory instanceof net.minecraft.entity.player.Inventory;
            if (slotIsPlayer == scanPlayerSide) continue;
            ItemStack destStack = s.getStack();
            if (destStack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(destStack, sourceStack)) continue;
            if (destStack.getCount() < destStack.getMaxStackSize()) return true;
        }
        return false;
    }

    // ========== REFLECTION DETECTION ==========
    // Mouse Tweaks calls slotClicked via reflection (GuiContainerHandler.clickSlot -> Method.invoke).
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
    private void it_handlePickup(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player, String mode) {
        var cursorStack = menu.getCarried();
        if (cursorStack.isEmpty()) return;
        int cursorCount = cursorStack.getCount();
        if (slot.getStack().getCount() > 0 || cursorCount <= 1) return;

        if (InvTweaksConfig.get().enableDebugLogging) InvTweaksConfig.debugLog("PICKUP", "%s mode | cursorCount=%d | slotCount=%d", mode, cursorCount, slot.getStack().getCount());

        if (mode.equals("allbut1")) {
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
        } else {
            int tempSlot = it_findEmptyPlayerSlot(slotId);
            if (tempSlot >= 0) {
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            }
        }
    }

    // ========== MACOS CTRL+CLICK ==========

    @Unique
    private void it_handleMacOSCtrlClick(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player, String mode) {
        var cursorStack = menu.getCarried();
        if (cursorStack.isEmpty()) return;
        int cursorCount = cursorStack.getCount();
        int slotCount = slot.getStack().getCount();

        if (cursorCount > 0 && slotCount > 0) {
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            it_handlePickup(slot, slotId, im, player, mode);
        } else if (slotCount == 0 && cursorCount > 1) {
            it_handlePickup(slot, slotId, im, player, mode);
        }
    }

    // ========== SHIFT+CLICK TRANSFER ==========

    @Unique
    private void it_handleShiftClick(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player, String mode) {
        // This menu only processes "allbut1" mode.
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
                ItemStack current = menu.getSlot(idx).getStack();
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
                    im.clickSlot(menu.containerId, destIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                }
            } else if (changedSlots.size() == 1) {
                // Simple case: all items went to one slot. Pick up, right-click 1 back to source, put rest back.
                int destIdx = changedSlots.get(0)[0];
                im.clickSlot(menu.containerId, destIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, destIdx, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
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
                im.clickSlot(menu.containerId, pullSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, pullSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            }
        } else if (remaining.getCount() > 1) {
            // Vanilla couldn't move everything (destination full or partial move)
            Item itemType = remaining.getItem();
            // Pick up remainder, place 1 back, find dest for the rest
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
            int destSlot = it_findCompatibleSlotOnOtherSide(itemType, slotId);
            if (destSlot >= 0) {
                im.clickSlot(menu.containerId, destSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            } else {
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            }
        }

        it_preClickItem = null;
        it_preClickCount = 0;
        it_preClickSnapshot = null;
    }

    // ========== BUNDLE EXTRACT ==========

    @Unique
    private void it_handleBundleExtract(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player, String mode) {
        var cursorStack = menu.getCarried();
        if (cursorStack.isEmpty() || cursorStack.getCount() <= 1) return;

        int tempSlot = it_findEmptyPlayerSlot(slotId);
        if (tempSlot < 0) return;

        if (InvTweaksConfig.get().enableDebugLogging) InvTweaksConfig.debugLog("BUNDLE-EXT", "%s mode | count=%d", mode, cursorStack.getCount());

        if (mode.equals("only1")) {
            im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
        } else {
            int tempSlot2 = it_findEmptyPlayerSlot2(slotId, tempSlot);
            if (tempSlot2 >= 0) {
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            } else {
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            }
        }
    }

    // ========== BUNDLE INSERT ==========

    @Unique
    private void it_handleBundleInsert(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player) {
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
    private void it_handleBundleInsertForward(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player, String mode) {
        int slotCount = slot.getStack().getCount();

        if (slotCount == 0 && it_bundleInsertSlotCount > 1) {
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
            int newSlotCount = slot.getStack().getCount();
            if (newSlotCount <= 1) return;

            int tempSlot = it_findEmptyPlayerSlot(slotId);
            if (tempSlot < 0) return;

            if (mode.equals("allbut1")) {
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
            } else {
                int tempSlot2 = it_findEmptyPlayerSlot2(slotId, tempSlot);
                if (tempSlot2 >= 0) {
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                }
            }
        }
    }

    @Unique
    private void it_handleBundleInsertReverse(Slot slot, int slotId, MultiPlayerGameMode im,
            net.minecraft.entity.player.Player player, String mode) {
        ItemStack cursorNow = menu.getCarried();

        if (cursorNow.isEmpty() && it_bundleInsertSlotCount > 1) {
            im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
            int extractedCount = menu.getCarried().getCount();
            if (extractedCount <= 1) return;

            if (mode.equals("allbut1")) {
                int tempSlot = it_findEmptyPlayerSlot(slotId);
                if (tempSlot >= 0) {
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                }
            } else {
                int tempSlot = it_findEmptyPlayerSlot(slotId);
                int tempSlot2 = it_findEmptyPlayerSlot2(slotId, tempSlot);
                if (tempSlot >= 0 && tempSlot2 >= 0) {
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_RIGHT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, slotId, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                    im.clickSlot(menu.containerId, tempSlot2, GLFW.GLFW_MOUSE_BUTTON_LEFT, ClickType.PICKUP, player);
                }
            }
        }
    }

    // ========== DEBUG-WRAPPED CLICK SLOT ==========

    /**
     * Wrapper for im.clickSlot that logs the synthetic click when debug is enabled.
     */
    @Unique
    private void it_debugClickSlot(MultiPlayerGameMode im, int containerId, int slotId, int button,
            ClickType action, net.minecraft.entity.player.Player player, String tag) {
        InvTweaksConfig.debugLog("CLICK", "synthetic | tag=%s | slot=%d | button=%d | action=%s", tag, slotId, button, action);
        im.clickSlot(containerId, slotId, button, action, player);
    }

    // ========== UTILITY METHODS ==========

    @Unique
    private int it_findHotbarSlotId(int hotbarIndex) {
        // Find the screen menu slot ID that corresponds to a given hotbar index (0-8).
        // In most screens, hotbar slots are the last 9 slots and have inventory index 0-8.
        // In InventoryScreen, hotbar slots are IDs 36-44.
        if (it_isPlayerOnlyScreen()) {
            return 36 + hotbarIndex;
        }
        // For container screens, search for the slot with the matching hotbar inventory index
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.inventory instanceof net.minecraft.entity.player.Inventory && s.getIndex() == hotbarIndex) {
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
                if (i < menu.slots.size() && menu.slots.get(i).getStack().isEmpty()) return i;
            }
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == excludeSlotId) continue;
            Slot s = menu.slots.get(i);
            if (!(s.inventory instanceof net.minecraft.entity.player.Inventory)) continue;
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
                if (i < menu.slots.size() && menu.slots.get(i).getStack().isEmpty()) return i;
            }
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == exclude1 || i == exclude2) continue;
            Slot s = menu.slots.get(i);
            if (!(s.inventory instanceof net.minecraft.entity.player.Inventory)) continue;
            int invIndex = s.getIndex();
            if (invIndex >= 9 && invIndex <= 44 && s.getStack().isEmpty()) return i;
        }
        return -1;
    }

    @Unique
    private int it_findCompatibleSlotOnOtherSide(Item item, int sourceSlotId) {
        boolean isPlayerOnly = it_isPlayerOnlyScreen();
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == sourceSlotId) continue;
            if (!it_isOtherSide(sourceSlotId, i, isPlayerOnly)) continue;
            ItemStack stack = menu.slots.get(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == item && stack.getCount() < stack.getMaxStackSize()) return i;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == sourceSlotId) continue;
            if (!it_isOtherSide(sourceSlotId, i, isPlayerOnly)) continue;
            if (menu.slots.get(i).getStack().isEmpty()) return i;
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
            Slot candidate = menu.slots.get(candidateSlotId);
            boolean sourceIsPlayer = sourceSlot.inventory instanceof net.minecraft.entity.player.Inventory;
            boolean candidateIsPlayer = candidate.inventory instanceof net.minecraft.entity.player.Inventory;
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
            Slot source = menu.slots.get(sourceSlotId);
            Slot candidate = menu.slots.get(candidateSlotId);
            boolean sourceIsPlayer = source.inventory instanceof net.minecraft.entity.player.Inventory;
            boolean candidateIsPlayer = candidate.inventory instanceof net.minecraft.entity.player.Inventory;
            return sourceIsPlayer != candidateIsPlayer;
        }
    }
}
