package bot.reconnect.mixin;

import bot.reconnect.ConnectionGate;
import net.kyori.adventure.text.Component;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.injection.CInject;

@CTransformer(name = "com.zenith.network.client.ClientSession")
public class ClientDisconnectMixin {
  @CInject(method = "callDisconnected(Lnet/kyori/adventure/text/Component;Ljava/lang/Throwable;)V",
      target = @CTarget("HEAD"))
  private void fixedReconnect$disconnect(Component reason, Throwable cause) {
    // Start the cooldown before Zenith tears down the event loop or another module
    // can see a terminal session and start a replacement connection.
    ConnectionGate.beforeDisconnect(this, reason);
  }
}
