package tacticalle.invtweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

// Empty accessor - keeping for compatibility, actual access is done in HandledScreenMixin
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
}
