package ru.cherdakmd.chrdkregister.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import ru.cherdakmd.chrdkregister.RegisterMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void chrdkRegister$addRegistrationLinks(SocketAddress address, @Coerce Object profile,
                                                     CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original != null && RegisterMod.isVanillaWhitelistDenial(original)) {
            cir.setReturnValue(RegisterMod.registrationLoginMessage(profile, original));
        }
    }
}
