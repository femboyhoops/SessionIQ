package com.sessioniq.mixin;

import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {
	@Accessor("connectingCancelled")
	boolean sessioniq$isCancelled();
}
