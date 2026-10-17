package com.sessioniq.mixin;

import com.sessioniq.SessionIQ;
import com.sessioniq.track.ConnectionEvidence;
import com.sessioniq.util.Safe;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {
	@Shadow
	@Final
	protected ClientConnection connection;

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void sessioniq$serverSentDisconnect(DisconnectS2CPacket packet, CallbackInfo ci) {
		String phase = ((Object) this) instanceof ClientPlayNetworkHandler ? "play" : "configuration";
		ClientConnection c = connection;
		Safe.run("server disconnect evidence", () -> ConnectionEvidence.serverDisconnectPacket(c, phase, packet.reason()));
	}

	@Inject(method = "onDisconnected", at = @At("HEAD"))
	private void sessioniq$disconnected(DisconnectionInfo info, CallbackInfo ci) {
		String phase = ((Object) this) instanceof ClientPlayNetworkHandler ? "play" : "configuration";
		ClientConnection c = connection;
		Safe.run("disconnect handling", () -> {
			ConnectionEvidence.listenerDisconnected(c, info, phase);
			SessionIQ siq = SessionIQ.getOrNull();
			if (siq != null) {
				siq.tracker.onListenerDisconnected(c, info, phase);
			}
		});
	}
}
