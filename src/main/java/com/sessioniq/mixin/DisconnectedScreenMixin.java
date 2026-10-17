package com.sessioniq.mixin;

import com.sessioniq.SessionIQ;
import com.sessioniq.util.Safe;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin {
	@Inject(method = "<init>(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/text/Text;Lnet/minecraft/network/DisconnectionInfo;Lnet/minecraft/text/Text;)V",
		at = @At("TAIL"))
	private void sessioniq$shown(Screen parent, Text title, DisconnectionInfo info, Text buttonLabel, CallbackInfo ci) {
		Safe.run("disconnected screen", () -> {
			SessionIQ siq = SessionIQ.getOrNull();
			if (siq != null) {
				siq.tracker.onDisconnectedScreen(title, info);
			}
		});
	}
}
