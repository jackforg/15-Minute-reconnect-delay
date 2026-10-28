package bot.reconnect;

import static com.zenith.Globals.*;

import com.github.rfresh2.SimpleEventBus;
import com.zenith.Proxy;
import com.zenith.module.impl.AutoReconnect;
import com.zenith.network.client.ClientSession;
import com.zenith.util.ComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

final class ZenithHost implements ReconnectHost {
  private final ComponentLogger log;
  ZenithHost(ComponentLogger log) { this.log = log; }
  @Override public SimpleEventBus events() { return EVENT_BUS; }
  @Override public ComponentLogger log() { return log; }
  @Override public Session currentSession() { return session(Proxy.getInstance().getClient()); }
  @Override public Session session(Object identity) {
    if (!(identity instanceof ClientSession client)) return null;
    return new Session(client, client.isOnline(), client.isWasOnline(), client.isInQueue(), client.isTerminalState());
  }
  @Override public boolean connectionActive() {
    var client = Proxy.getInstance().getClient();
    return Proxy.getInstance().getLoggingIn().get() || (client != null && !client.isTerminalState());
  }
  @Override public boolean reconnectEnabled() { return CONFIG.client.extra.autoReconnect.enabled; }
  @Override public int failedAttemptDelaySeconds() { return CONFIG.client.extra.autoReconnect.delaySeconds; }
  @Override public void stopBuiltinReconnect() {
    var builtin = MODULE.get(AutoReconnect.class);
    builtin.cancelAutoReconnect();
    builtin.disable();
  }
  @Override public void connect() { Proxy.getInstance().connect(); }
  @Override public void disconnect(Object identity, String reason) { ((ClientSession) identity).disconnect(reason); }
  @Override public String disconnectReason(Object reason) {
    return reason instanceof Component component ? ComponentSerializer.serializePlain(component) : String.valueOf(reason);
  }
}
