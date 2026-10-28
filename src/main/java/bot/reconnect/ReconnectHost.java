package bot.reconnect;

import com.github.rfresh2.SimpleEventBus;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

interface ReconnectHost {
  record Session(Object identity, boolean online, boolean wasOnline, boolean inQueue, boolean terminal) {}
  SimpleEventBus events();
  ComponentLogger log();
  Session currentSession();
  Session session(Object identity);
  boolean connectionActive();
  boolean reconnectEnabled();
  int failedAttemptDelaySeconds();
  void stopBuiltinReconnect();
  void connect();
  void disconnect(Object identity, String reason);
  String disconnectReason(Object reason);
}
