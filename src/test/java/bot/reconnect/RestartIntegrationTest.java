package bot.reconnect;

import static org.junit.jupiter.api.Assertions.*;

import com.github.rfresh2.EventConsumer;
import com.github.rfresh2.SimpleEventBus;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.module.AutoReconnectDecisionEvent;
import com.zenith.event.queue.QueueStartEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Zenith event types and event bus, with an isolated destination/session adapter. */
class RestartIntegrationTest {
  @TempDir Path directory;
  @AfterEach void detachGate() { ConnectionGate.install(null); }

  @Test void screenshotReconfigurationClosesTheSessionThenWaits900Seconds() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.events.post(new ClientConfigurationEvent.Entering());
    assertEquals(1, fixture.host.disconnects);
    assertEquals(FixedReconnectModule.RECONFIGURED, fixture.host.lastCloseReason);
    assertTrue(ConnectionGate.blockConnect());
    fixture.advance(899);
    assertEquals(0, fixture.host.attempts);
    fixture.advance(1);
    assertEquals(1, fixture.host.attempts);
  }

  @Test void kickedToQueueWithoutAReconfigurationEventStillStartsTheCooldown() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.client.online = false;
    fixture.host.client.queue = true;
    fixture.host.events.post(new QueueStartEvent(true, Duration.ofHours(17)));
    assertEquals(FixedReconnectModule.KICKED_TO_QUEUE, fixture.host.lastCloseReason);
    fixture.advance(899);
    assertEquals(0, fixture.host.attempts);
    fixture.advance(1);
    assertEquals(1, fixture.host.attempts);
  }

  @Test void reconfigurationQueueAndDisconnectNotificationsDoNotExtendTheSameWait() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.events.post(new ClientConfigurationEvent.Entering());
    fixture.advance(60);
    fixture.host.events.post(new QueueStartEvent(true, Duration.ofHours(17)));
    fixture.host.events.post(new ClientDisconnectEvent("Server restarting"));
    fixture.advance(840);
    assertEquals(1, fixture.host.disconnects);
    assertEquals(1, fixture.host.attempts);
  }

  @Test void initialLoginReconfigurationIsNotMistakenForAServerRestart() {
    var fixture = fixture();
    fixture.host.client = new Client();
    fixture.host.events.post(new ClientConfigurationEvent.Entering());
    assertEquals(0, fixture.host.disconnects);
    assertFalse(ConnectionGate.blockConnect());
    assertTrue(fixture.tasks.isEmpty());
  }

  @Test void firstAutomaticConnectionIsAllowedWithoutSavedSessionHistory() {
    var fixture = new Fixture(directory.resolve("state"), 1_800_000_000_000L);
    fixture.host.connect();
    assertEquals(1, fixture.host.attempts);
    assertTrue(fixture.tasks.isEmpty());
  }

  @Test void initialQueueToWorldReconfigurationCanCompleteThenItsRealDisconnectWaits() {
    var fixture = fixture();
    fixture.host.connect();
    var login = fixture.host.client;
    login.queue = true;
    fixture.host.events.post(new QueueStartEvent(false, Duration.ZERO));
    fixture.host.events.post(new ClientConfigurationEvent.Entering());
    assertEquals(0, fixture.host.disconnects);
    assertFalse(login.terminal);
    assertTrue(fixture.tasks.isEmpty());
    login.queue = false;
    login.online = true;
    login.wasOnline = true;
    fixture.host.events.post(new ClientOnlineEvent());
    fixture.host.disconnect(login, "Server restart after login");
    fixture.advance(899);
    assertEquals(1, fixture.host.attempts);
    fixture.advance(1);
    assertEquals(2, fixture.host.attempts);
  }

  @Test void initialQueueEntryIsAllowedAndItsLaterDisconnectWaits900Seconds() {
    var fixture = fixture();
    fixture.host.client = new Client();
    fixture.host.client.queue = true;
    fixture.host.events.post(new QueueStartEvent(false, Duration.ZERO));
    assertEquals(0, fixture.host.disconnects);
    fixture.host.disconnect(fixture.host.client, "Connection reset");
    fixture.advance(899);
    assertEquals(0, fixture.host.attempts);
    fixture.advance(1);
    assertEquals(1, fixture.host.attempts);
  }

  @Test void realDisconnectStartsBlockingBeforeTheLaterDisconnectEventArrives() {
    var fixture = fixture();
    fixture.worldOnline();
    ConnectionGate.beforeDisconnect(fixture.host.client, "Timed out");
    fixture.host.client.terminal = true;
    fixture.host.connect(); // Another module notices the terminal socket before event-loop teardown ends.
    assertEquals(0, fixture.host.attempts);
    fixture.advance(20);
    fixture.host.events.post(new ClientDisconnectEvent("Timed out"));
    fixture.advance(880);
    assertEquals(1, fixture.host.attempts);
  }

  @Test void directManualOrOtherModuleConnectCallsCannotBypassTheCooldown() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.disconnect(fixture.host.client, "Restart");
    fixture.host.connect();
    fixture.advance(400);
    fixture.host.connect();
    assertEquals(0, fixture.host.attempts);
    fixture.advance(500);
    assertEquals(1, fixture.host.attempts);
  }

  @Test void restartDuringCooldownBlocksStartupAndWaitsOnlyTheRemaining600Seconds() {
    var first = fixture();
    first.worldOnline();
    first.host.disconnect(first.host.client, "Restart");
    first.advance(300);
    first.module.onDisable();
    var restarted = new Fixture(directory.resolve("state"), first.millis);
    restarted.host.connect(); // Zenith startup autoConnect and update reconnect both enter this guard.
    assertEquals(0, restarted.host.attempts);
    restarted.advance(599);
    assertEquals(0, restarted.host.attempts);
    restarted.advance(1);
    assertEquals(1, restarted.host.attempts);
  }

  @Test void abruptProcessRestartAfterAnOnlineSessionWaits900Seconds() {
    var first = fixture();
    first.worldOnline();
    first.module.onDisable(); // No disconnect callback: simulate a crash or VPS power loss.
    var restarted = new Fixture(directory.resolve("state"), first.millis);
    restarted.host.connect();
    restarted.advance(899);
    assertEquals(0, restarted.host.attempts);
    restarted.advance(1);
    assertEquals(1, restarted.host.attempts);
  }

  @Test void anExpiredSavedCooldownReconnectsEvenWhenStartupAutoConnectIsOff() {
    var first = fixture();
    first.worldOnline();
    first.host.disconnect(first.host.client, "Restart");
    first.module.onDisable();
    var restarted = new Fixture(directory.resolve("state"), first.millis + 901_000);
    assertEquals(0, restarted.host.attempts);
    restarted.advance(0);
    assertEquals(1, restarted.host.attempts);
  }

  @Test void failedAttemptsAfterTheCooldownUseFiveSecondRetries() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.disconnect(fixture.host.client, "Restart");
    fixture.advance(900);
    assertEquals(1, fixture.host.attempts);
    fixture.host.disconnect(fixture.host.client, "Connection refused");
    fixture.advance(4);
    assertEquals(1, fixture.host.attempts);
    fixture.advance(1);
    assertEquals(2, fixture.host.attempts);
    fixture.host.disconnect(fixture.host.client, "Login rejected");
    fixture.advance(5);
    assertEquals(3, fixture.host.attempts);
  }

  @Test void restartingDuringFailedAttemptsDoesNotCreateAnotherFifteenMinuteWait() {
    var first = fixture();
    first.host.connect();
    first.host.disconnect(first.host.client, "Connection refused");
    first.module.onDisable();
    var restarted = new Fixture(directory.resolve("state"), first.millis);
    restarted.host.connect();
    assertEquals(1, restarted.host.attempts);
  }

  @Test void anotherPluginsReconnectVetoIsRetainedAfterAQueueKick() {
    var fixture = fixture();
    fixture.host.events.subscribe(new Object(), AutoReconnectDecisionEvent.class, event -> event.setCancelled(true));
    fixture.worldOnline();
    fixture.host.events.post(new QueueStartEvent(true, Duration.ofHours(1)));
    assertEquals(1, fixture.host.disconnects);
    fixture.advance(900);
    assertEquals(0, fixture.host.attempts);
  }

  @Test void disablingAutoReconnectDuringTheWaitPreventsTheScheduledAttempt() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.disconnect(fixture.host.client, "Restart");
    fixture.host.enabled = false;
    fixture.advance(900);
    assertEquals(0, fixture.host.attempts);
  }

  @Test void nativeReconnectDecisionCannotStartACompetingTimer() {
    var fixture = fixture();
    var event = new AutoReconnectDecisionEvent(new ClientDisconnectEvent("Timed out"));
    fixture.host.events.post(event);
    assertTrue(event.isCancelled());
  }

  @Test void staleConnectedEventCannotClearTheNewCooldown() {
    var fixture = fixture();
    fixture.worldOnline();
    fixture.host.events.post(new ClientConfigurationEvent.Entering());
    fixture.host.events.post(new ClientOnlineEvent());
    assertTrue(ConnectionGate.blockConnect());
    fixture.advance(899);
    assertEquals(0, fixture.host.attempts);
  }

  private Fixture fixture() { return new Fixture(directory.resolve("state"), 1_800_000_000_000L); }

  private static final class Fixture {
    final FakeHost host = new FakeHost();
    final List<Task> tasks = new ArrayList<>();
    final FixedReconnectModule module;
    long millis;
    long nanos;

    Fixture(Path file, long millis) {
      this.millis = millis;
      module = new FixedReconnectModule(host, new CooldownStore(file), () -> this.millis, () -> nanos,
          (action, delay) -> {
            var task = new Task(action, nanos + delay);
            tasks.add(task);
            return task.future;
          });
      host.events.subscribe(module, module.registerEvents().toArray(new EventConsumer<?>[0]));
      module.onEnable();
    }

    void worldOnline() {
      host.client = new Client();
      host.client.online = true;
      host.client.wasOnline = true;
      host.events.post(new ClientOnlineEvent());
    }

    void advance(long seconds) {
      millis += TimeUnit.SECONDS.toMillis(seconds);
      nanos += TimeUnit.SECONDS.toNanos(seconds);
      for (var task : List.copyOf(tasks)) {
        if (!task.ran && !task.future.isCancelled() && task.due - nanos <= 0) {
          task.ran = true;
          task.action.run();
          task.future.complete(null);
        }
      }
    }
  }

  private static final class Client {
    boolean online, wasOnline, queue, terminal;
  }

  private static final class FakeHost implements ReconnectHost {
    final SimpleEventBus events = new SimpleEventBus();
    Client client;
    boolean enabled = true;
    int attempts, disconnects;
    String lastCloseReason;
    @Override public SimpleEventBus events() { return events; }
    @Override public ComponentLogger log() { return ComponentLogger.logger("ReconnectIntegrationTest"); }
    @Override public Session currentSession() { return session(client); }
    @Override public Session session(Object identity) {
      if (!(identity instanceof Client current)) return null;
      return new Session(current, current.online, current.wasOnline, current.queue, current.terminal);
    }
    @Override public boolean connectionActive() { return client != null && !client.terminal; }
    @Override public boolean reconnectEnabled() { return enabled; }
    @Override public int failedAttemptDelaySeconds() { return 5; }
    @Override public void stopBuiltinReconnect() {}
    @Override public void connect() {
      if (ConnectionGate.blockConnect()) return;
      attempts++;
      client = new Client();
    }
    @Override public void disconnect(Object identity, String reason) {
      var closing = (Client) identity;
      disconnects++;
      lastCloseReason = reason;
      ConnectionGate.beforeDisconnect(closing, reason);
      closing.terminal = true;
      closing.online = false;
      events.post(new ClientDisconnectEvent(reason));
    }
    @Override public String disconnectReason(Object reason) { return String.valueOf(reason); }
  }

  private static final class Task {
    final Runnable action;
    final long due;
    final CompletableFuture<Void> future = new CompletableFuture<>();
    boolean ran;
    Task(Runnable action, long due) { this.action = action; this.due = due; }
  }
}
