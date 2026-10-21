package bot.reconnect;

import static com.github.rfresh2.EventConsumer.of;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.module.AutoReconnectDecisionEvent;
import com.zenith.event.module.AutoReconnectEvent;
import com.zenith.event.queue.QueueStartEvent;
import com.zenith.module.api.Module;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class FixedReconnectModule extends Module implements ConnectionGate.Listener {
  static final String RECONFIGURED = "FixedReconnectDelay: established session reconfigured";
  static final String KICKED_TO_QUEUE = "FixedReconnectDelay: kicked from world to queue";
  private final ReconnectHost host;
  private final PersistentCooldown cooldown;
  private final ThreadLocal<AutoReconnectDecisionEvent> ownDecision = new ThreadLocal<>();
  private final ReconnectController controller;
  private final SessionHistory sessions = new SessionHistory();
  private volatile ClientDisconnectEvent lastDisconnect;

  FixedReconnectModule(ReconnectHost host) {
    this(host, new CooldownStore(Path.of("plugins", "fixed-reconnect-delay", "cooldown.properties")),
        System::currentTimeMillis, System::nanoTime,
        (task, delay) -> com.zenith.Globals.EXECUTOR.schedule(task, delay, TimeUnit.NANOSECONDS));
  }

  FixedReconnectModule(ReconnectHost host, CooldownStore store, LongSupplier millis,
      LongSupplier nanos, ReconnectController.Scheduler scheduler) {
    this.host = host;
    cooldown = new PersistentCooldown(store, millis, nanos, host.startupConnectRequested());
    lastDisconnect = new ClientDisconnectEvent(cooldown.reason());
    controller = new ReconnectController(new ReconnectController.Actions() {
      @Override public boolean allowed() { return reconnectAllowed(); }
      @Override public boolean connectionActive() { return host.connectionActive(); }
      @Override public int failedAttemptDelaySeconds() { return host.failedAttemptDelaySeconds(); }
      @Override public void connect() {
        if (cooldown.blocked() || !reconnectAllowed()) return;
        host.log().info("Reconnect delay elapsed; connecting now");
        host.connect();
      }
      @Override public void scheduled(int seconds, boolean failedAttempt) {
        host.log().info("{}: automatic reconnect in {} seconds; reason: {}",
            failedAttempt ? "Connection attempt failed" : "Cooldown active", seconds, lastDisconnect.reason());
        host.events().postAsync(new AutoReconnectEvent(seconds));
      }
      @Override public void failed(RuntimeException failure) { host.log().error("Reconnect attempt failed", failure); }
    }, scheduler, nanos);
  }

  @Override public boolean enabledSetting() { return true; }
  @Override public void onEnable() {
    host.stopBuiltinReconnect();
    ConnectionGate.install(this);
    if (cooldown.hasReconnectToResume()) controller.resumeCooldown(cooldown.remainingNanos());
  }
  @Override public void onDisable() { controller.cancel(); }

  @Override public List<EventConsumer<?>> registerEvents() {
    return List.of(
        of(AutoReconnectDecisionEvent.class, Integer.MAX_VALUE, event -> {
          if (event != ownDecision.get()) event.setCancelled(true);
        }),
        of(ClientConfigurationEvent.Entering.class, Integer.MAX_VALUE, event -> reconfigured()),
        of(QueueStartEvent.class, Integer.MIN_VALUE, this::queueStarted),
        of(ClientOnlineEvent.class, Integer.MIN_VALUE, event -> established()),
        of(ClientDisconnectEvent.class, Integer.MIN_VALUE, this::disconnected));
  }

  private void reconfigured() {
    var client = host.currentSession();
    if (client != null && !client.terminal() && (client.wasOnline() || client.online() || client.inQueue()))
      transitionLost(client, RECONFIGURED);
  }

  private void queueStarted(QueueStartEvent event) {
    var client = host.currentSession();
    if (client == null || client.terminal()) return;
    if (event.wasOnline()) transitionLost(client, KICKED_TO_QUEUE);
    else established();
  }

  private void established() {
    var client = host.currentSession();
    if (client == null || client.terminal() || cooldown.blocked()) return;
    try { cooldown.connected(); }
    catch (RuntimeException failure) { storageFailed(failure); }
  }

  private void transitionLost(ReconnectHost.Session client, String reason) {
    if (!sessions.disconnected(client.identity(), true, client.inQueue())) return;
    host.log().info("{}; closing the destination connection for the 900-second cooldown", reason);
    beginCooldown(reason);
    host.stopBuiltinReconnect();
    host.disconnect(client.identity(), reason);
  }

  @Override public void beforeDisconnect(Object identity, Object reason) {
    var client = host.session(identity);
    if (client == null) return;
    if (sessions.disconnected(identity, client.wasOnline() || client.online(), client.inQueue()))
      beginCooldown(host.disconnectReason(reason));
  }

  private void disconnected(ClientDisconnectEvent event) {
    var client = host.currentSession();
    boolean established = sessions.disconnected(client == null ? null : client.identity(),
        client != null && (client.wasOnline() || client.online()),
        event.wasInQueue() || (client != null && client.inQueue()));
    host.stopBuiltinReconnect();
    if (established) beginCooldown(event.reason());
    else if (!cooldown.blocked()) {
      lastDisconnect = event;
      controller.disconnected(false);
    }
  }

  private void beginCooldown(String reason) {
    try {
      if (!cooldown.begin(reason)) return;
      lastDisconnect = new ClientDisconnectEvent(reason);
      controller.resumeCooldown(cooldown.remainingNanos());
    } catch (RuntimeException failure) { storageFailed(failure); }
  }

  @Override public boolean blockConnect() {
    try {
      if (cooldown.blocked()) {
        host.log().info("Connection blocked by saved cooldown; {} seconds remain",
            (cooldown.remainingNanos() + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
        return true;
      }
      return !host.connectionActive() && !cooldown.attemptStarting();
    } catch (RuntimeException failure) {
      storageFailed(failure);
      return true;
    }
  }

  private boolean reconnectAllowed() {
    host.stopBuiltinReconnect();
    if (!host.reconnectEnabled()) return false;
    var decision = new AutoReconnectDecisionEvent(lastDisconnect);
    var previous = ownDecision.get();
    ownDecision.set(decision);
    try {
      host.events().post(decision);
      return !decision.isCancelled() && host.reconnectEnabled();
    } finally {
      if (previous == null) ownDecision.remove(); else ownDecision.set(previous);
    }
  }

  private void storageFailed(RuntimeException failure) {
    controller.cancel();
    host.log().error("Cooldown storage failed; new connections remain blocked", failure);
  }
}
