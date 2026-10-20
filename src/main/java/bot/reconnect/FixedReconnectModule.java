package bot.reconnect;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.EVENT_BUS;
import static com.zenith.Globals.EXECUTOR;
import static com.zenith.Globals.MODULE;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.module.AutoReconnectDecisionEvent;
import com.zenith.event.module.AutoReconnectEvent;
import com.zenith.module.api.Module;
import com.zenith.module.impl.AutoReconnect;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public final class FixedReconnectModule extends Module {
  private final ComponentLogger log;
  private final AutoReconnect builtin = MODULE.get(AutoReconnect.class);
  private final ThreadLocal<AutoReconnectDecisionEvent> ownDecision = new ThreadLocal<>();
  private final ReconnectController controller;
  private final SessionHistory sessions = new SessionHistory();
  private ClientDisconnectEvent lastDisconnect;

  FixedReconnectModule(ComponentLogger log) {
    this.log = log;
    controller = new ReconnectController(new ReconnectController.Actions() {
      @Override
      public boolean allowed() {
        return reconnectAllowed();
      }

      @Override
      public boolean connectionActive() {
        var proxy = Proxy.getInstance();
        var client = proxy.getClient();
        return proxy.getLoggingIn().get() || (client != null && !client.isTerminalState());
      }

      @Override
      public int failedAttemptDelaySeconds() {
        return CONFIG.client.extra.autoReconnect.delaySeconds;
      }

      @Override
      public void connect() {
        log.info("Reconnect delay elapsed; connecting now");
        Proxy.getInstance().connect();
      }

      @Override
      public void scheduled(int seconds, boolean failedAttempt) {
        log.info("{}: automatic reconnect in {} seconds",
            failedAttempt ? "Connection attempt failed" : "Session disconnected", seconds);
        EVENT_BUS.postAsync(new AutoReconnectEvent(seconds));
      }

      @Override
      public void failed(RuntimeException failure) {
        log.error("Reconnect attempt failed", failure);
      }
    }, (task, delay) -> EXECUTOR.schedule(task, delay, TimeUnit.NANOSECONDS), System::nanoTime);
  }

  @Override
  public boolean enabledSetting() {
    return true;
  }

  @Override
  public void onEnable() {
    // Leave the enabled preference intact so `autoReconnect off` and other plugins'
    // emergency stops still control whether reconnect is permitted.
    builtin.disable();
  }

  @Override
  public void onDisable() {
    controller.cancel();
  }

  @Override
  public List<EventConsumer<?>> registerEvents() {
    return List.of(
        // Take ownership before the built-in jittered scheduler can start, including
        // after a command has re-enabled its module during this process.
        of(AutoReconnectDecisionEvent.class, Integer.MAX_VALUE, event -> {
          if (event != ownDecision.get()) event.setCancelled(true);
        }),
        // Run after protection modules have processed the actual disconnect.
        of(ClientDisconnectEvent.class, Integer.MIN_VALUE, this::disconnected));
  }

  private synchronized void disconnected(ClientDisconnectEvent event) {
    var client = Proxy.getInstance().getClient();
    // ClientConnectEvent only means TCP connected, before Minecraft login succeeds.
    // A session that reached the world or queue is an established connection. A
    // refused TCP connection, rejected handshake/login, or authentication failure is
    // a failed attempt. Consuming each session once also handles synthetic auth
    // failures, which can still refer to the previous, already disconnected client.
    boolean established = sessions.disconnected(client,
        client != null && client.isWasOnline(),
        event.wasInQueue() || (client != null && client.isInQueue()));
    lastDisconnect = event;
    builtin.disable();
    controller.disconnected(established);
  }

  private boolean reconnectAllowed() {
    builtin.disable();
    if (!CONFIG.client.extra.autoReconnect.enabled) return false;
    var decision = new AutoReconnectDecisionEvent(lastDisconnect);
    ownDecision.set(decision);
    try {
      // The built-in reason blacklist is unsubscribed; all established-session
      // reasons receive 900 seconds. Other plugins can still veto reconnect.
      EVENT_BUS.post(decision);
      return !decision.isCancelled() && CONFIG.client.extra.autoReconnect.enabled;
    } finally {
      ownDecision.remove();
    }
  }
}
