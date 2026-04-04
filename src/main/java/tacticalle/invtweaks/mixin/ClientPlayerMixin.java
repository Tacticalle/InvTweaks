package tacticalle.invtweaks.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import tacticalle.invtweaks.InvTweaksConfig;

/**
 * Mixin to intercept item dropping for the "Throw Half" tweak outside of GUI screens.
 * When the player presses Q (drop) with an InvTweaks modifier held, and no screen is open,
 * this drops half the stack instead of 1 or all.
 */
@Mixin(LocalPlayer.class)
public class ClientPlayerMixin {
    @Unique
    private boolean it_throwHalfActive = false;

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void onDropSelectedItem(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        // Guard against recursion from our own drop calls
        if (it_throwHalfActive) return;

        InvTweaksConfig config = InvTweaksConfig.get();

        // Only intercept single-item drop (Q, not Ctrl+Q)
        if (entireStack) return;

        boolean throwHalf = config.enableThrowHalf && config.isThrowHalfKeyPressed();
        boolean throwAB1 = config.enableThrowAllBut1 && config.isThrowAllBut1KeyPressed();
        if (!throwHalf && !throwAB1) return;

        LocalPlayer self = (LocalPlayer) (Object) this;
        ItemStack held = self.getMainHandItem();
        if (held.isEmpty()) return;

        int count = held.getCount();
        if (count <= 1) {
            cir.setReturnValue(false);
            return;
        }

        int toDrop;
        if (throwAB1) {
            toDrop = count - 1; // drop all but 1
            InvTweaksConfig.debugLog("THROW", "allbut1 non-GUI | count=%d | dropping=%d", count, toDrop);
        } else {
            toDrop = count / 2; // drop half
            InvTweaksConfig.debugLog("THROW", "half non-GUI | count=%d | dropping=%d", count, toDrop);
        }

        cir.setReturnValue(true);
        it_throwHalfActive = true;
        try {
            for (int i = 0; i < toDrop; i++) {
                self.drop(false);
            }
        } finally {
            it_throwHalfActive = false;
        }
    }
}
