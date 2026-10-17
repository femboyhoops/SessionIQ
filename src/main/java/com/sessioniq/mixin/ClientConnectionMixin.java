package com.sessioniq.mixin;

import com.sessioniq.track.ConnectionEvidence;
import com.sessioniq.util.Safe;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionInfo;)V", at = @At("HEAD"))
	private void sessioniq$recordDisconnectReason(DisconnectionInfo info, CallbackInfo ci) {
		ClientConnection self = (ClientConnection) (Object) this;
		if (self.getSide() == NetworkSide.CLIENTBOUND) {
			Safe.run("disconnect evidence", () -> ConnectionEvidence.disconnectCalled(self, info));
		}
	}
}
