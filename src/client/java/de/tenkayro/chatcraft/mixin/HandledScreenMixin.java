package de.tenkayro.chatcraft.mixin;

import de.tenkayro.chatcraft.overlay.SlotOverlayRenderer;
import de.tenkayro.chatcraft.overlay.RecipeBookOverlayRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow protected int x;
    @Shadow protected int y;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void chatcraft$renderLabels(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        SlotOverlayRenderer.render((HandledScreen<?>) (Object) this, context, x, y);
        if ((Object) this instanceof InventoryScreen inventory) {
            RecipeBookOverlayRenderer.render(inventory, context);
        }
    }
}
