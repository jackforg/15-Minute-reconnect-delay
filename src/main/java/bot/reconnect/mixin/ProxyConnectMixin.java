package bot.reconnect.mixin;

import bot.reconnect.ConnectionGate;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.injection.CInject;

@CTransformer(name = "com.zenith.Proxy")
public class ProxyConnectMixin {
  @CInject(method = "start()V", target = @CTarget("HEAD"))
  private void fixedReconnect$startup() {
    ConnectionGate.markTransformerLoaded();
  }

  @CInject(method = {"connect()V", "connect(Ljava/lang/String;I)V"},
      target = @CTarget("HEAD"), cancellable = true)
  private void fixedReconnect$connect(InjectionCallback callback) {
    if (ConnectionGate.blockConnect()) callback.setCancelled(true);
  }
}
