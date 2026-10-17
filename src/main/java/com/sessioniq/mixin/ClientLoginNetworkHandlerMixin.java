package com.sessioniq.mixin;

import com.sessioniq.SessionIQ;
import com.sessioniq.track.ConnectionEvidence;
import com.sessioniq.util.Safe;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLoginNetworkHandler.class)
public abstract class ClientLoginNetworkHandlerMixin {
	@Shadow
	@Final
	private ClientConnection connection;

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void sessioniq$serverRefusedLogin(LoginDisconnectS2CPacket packet, CallbackInfo ci) {
		ClientConnection c = connection;
		Safe.run("login disconnect evidence", () -> ConnectionEvidence.serverDisconnectPacket(c, "login", packet.reason()));
	}

	@Inject(method = "onDisconnected", at = @At("HEAD"))
	private void sessioniq$loginDisconnected(DisconnectionInfo info, CallbackInfo ci) {
		ClientConnection c = connection;
		Safe.run("login disconnect handling", () -> {
			ConnectionEvidence.listenerDisconnected(c, info, "login");
			SessionIQ siq = SessionIQ.getOrNull();
			if (siq != null) {
				siq.tracker.onListenerDisconnected(c, info, "login");
			}
		});
	}
}
