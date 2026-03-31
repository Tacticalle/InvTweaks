package tacticalle.invtweaks.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");

    @Unique
    private boolean it_throwHalfActive = false;

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void onDropSelectedItem(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        // Guard against recursion from our own drop calls
        if (it_throwHalfActive) return;

        InvTweaksConfig config = InvTweaksConfig.get();

        // Only intercept single-item drop (Q, not Ctrl+Q)
        if (entireStack) return;

        boolean throwHalf = config.enableThrowHalf && config.isThrowHalfKeyPressed();
        boolean throwAB1 = config.enableThrowAllBut1 && config.isThrowAllBut1KeyPressed();
        if (!throwHalf && !throwAB1) return;

        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        ItemStack held = self.getMainHandStack();
        if (held.isEmpty()) return;

        int count = held.getCount();
        if (count <= 1) {
            cir.setReturnValue(false);
            return;
        }

        int toDrop;
        if (throwAB1) {
            toDrop = count - 1; // drop all but 1
            if (config.enableDebugLogging) LOGGER.info("[IT:THROW] allbut1 non-GUI | count={} | dropping={}", count, toDrop);
        } else {
            toDrop = count / 2; // drop half
            if (config.enableDebugLogging) LOGGER.info("[IT:THROW] half non-GUI | count={} | dropping={}", count, toDrop);
        }

        cir.setReturnValue(true);
        it_throwHalfActive = true;
        try {
            for (int i = 0; i < toDrop; i++) {
                self.dropSelectedItem(false);
            }
        } finally {
            it_throwHalfActive = false;
        }
    }
}
