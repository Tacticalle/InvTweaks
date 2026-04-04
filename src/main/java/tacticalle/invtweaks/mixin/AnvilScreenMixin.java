package tacticalle.invtweaks.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.KeyEvent;

import tacticalle.invtweaks.InvTweaksConfig;
import tacticalle.invtweaks.InvTweaksOverlay;

@Mixin(AnvilScreen.class)
public class AnvilScreenMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void it_interceptClipboardKeys(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        InvTweaksConfig config = InvTweaksConfig.get();
        if (!config.enableCopyPaste) return;

        int keyCode = input.key();
        long windowHandle = Minecraft.getInstance().getWindow().handle();

        boolean ctrlOrSuper = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                              GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS ||
                              GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                              GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;

        boolean copyTriggered = config.copyLayoutKey == -1
                ? ctrlOrSuper && keyCode == GLFW.GLFW_KEY_C
                : keyCode == config.copyLayoutKey;
        boolean pasteTriggered = config.pasteLayoutKey == -1
                ? ctrlOrSuper && keyCode == GLFW.GLFW_KEY_V
                : keyCode == config.pasteLayoutKey;
        boolean cutTriggered = config.cutLayoutKey == -1
                ? ctrlOrSuper && keyCode == GLFW.GLFW_KEY_X
                : keyCode == config.cutLayoutKey;

        boolean triggered = copyTriggered || pasteTriggered || cutTriggered;

        if (triggered) {
            InvTweaksConfig.debugLog("CLASSIFY", "AnvilScreen → INCOMPATIBLE (text field bypass)");
            InvTweaksOverlay.show("Incompatible", 0xFFFF8800);
            cir.setReturnValue(true);
        }
    }
}
