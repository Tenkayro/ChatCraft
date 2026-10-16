package de.tenkayro.chatcraft.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mouse.class)
public interface MouseAccessor {
    @Accessor("x")
    void chatcraft$setX(double x);

    @Accessor("y")
    void chatcraft$setY(double y);
}
