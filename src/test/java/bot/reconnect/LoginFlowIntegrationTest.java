package bot.reconnect;

import static org.junit.jupiter.api.Assertions.*;

import com.github.rfresh2.SimpleEventBus;
import com.zenith.Globals;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.queue.QueueStartEvent;
import com.zenith.network.client.ClientSession;
import com.zenith.network.client.handler.incoming.CStartConfigurationHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.PalettedWorldState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundConfigurationAcknowledgedPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes Zenith's real configuration handler and session state with no network login. */
class LoginFlowIntegrationTest {
  @TempDir Path directory;
  private final TestSession session = new TestSession();
  private final Host host = new Host();
  private final List<Task> tasks = new ArrayList<>();
  private FixedReconnectModule module;
  private long nanos;

  @AfterEach void close() {
    if (module != null) module.disable();
    ConnectionGate.install(null);
    session.channel.finishAndReleaseAll();
    session.getClientEventLoop().shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
  }

  @Test void queuedFirstLoginReconfiguresAndSendsItsAcknowledgementWithoutStartingACooldown() {
    enable();
    session.setInQueue(true);
    host.events().post(new QueueStartEvent(false, Duration.ZERO));
    var packet = new ClientboundStartConfigurationPacket();
    assertNull(new CStartConfigurationHandler().apply(packet, session));
    assertEquals(ProtocolState.CONFIGURATION, session.getPacketProtocol().getInboundState());
    assertInstanceOf(ServerboundConfigurationAcknowledgedPacket.class, session.sent.getFirst());
    assertEquals(0, host.closes);
    assertFalse(session.isTerminalState());
    assertTrue(tasks.isEmpty());
    assertFalse(ConnectionGate.blockConnect());
  }

  @Test void theSameHandlerAfterWorldPlayStillClosesAndWaitsTheFull900Seconds() {
    enable();
    session.setWasOnline(true);
    new CStartConfigurationHandler().apply(new ClientboundStartConfigurationPacket(), session);
    assertEquals(1, host.closes);
    assertTrue(ConnectionGate.blockConnect());
    advance(899);
    assertEquals(0, host.attempts);
    advance(1);
    assertEquals(1, host.attempts);
  }

  private void enable() {
    module = new FixedReconnectModule(host, new CooldownStore(directory.resolve("state")),
        () -> 1_800_000_000_000L + TimeUnit.NANOSECONDS.toMillis(nanos), () -> nanos,
        (action, delay) -> {
          var task = new Task(action, nanos + delay, new CompletableFuture<>());
          tasks.add(task);
          return task.future();
        });
    // Exercise the actual Zenith module lifecycle and global event bus used by its handler.
    module.enable();
    assertTrue(module.isEnabled());
  }

  private void advance(int seconds) {
    nanos += TimeUnit.SECONDS.toNanos(seconds);
    for (var task : List.copyOf(tasks)) {
      if (!task.future().isDone() && task.due() <= nanos) {
        task.action().run();
        task.future().complete(null);
      }
    }
  }

  private final class Host implements ReconnectHost {
    int closes, attempts;
    @Override public SimpleEventBus events() { return Globals.EVENT_BUS; }
    @Override public ComponentLogger log() { return ComponentLogger.logger("LoginFlowIntegrationTest"); }
    @Override public Session currentSession() { return session(session); }
    @Override public Session session(Object identity) {
      if (identity != session) return null;
      return new Session(session, session.isOnline(), session.isWasOnline(), session.isInQueue(),
          session.isTerminalState());
    }
    @Override public boolean connectionActive() { return !session.isTerminalState(); }
    @Override public boolean reconnectEnabled() { return true; }
    @Override public int failedAttemptDelaySeconds() { return 5; }
    @Override public void stopBuiltinReconnect() {}
    @Override public void connect() { if (!ConnectionGate.blockConnect()) attempts++; }
    @Override public void disconnect(Object identity, String reason) {
      assertSame(session, identity);
      closes++;
      ConnectionGate.beforeDisconnect(identity, reason);
      session.setDisconnected(true);
      events().post(new ClientDisconnectEvent(reason));
    }
    @Override public String disconnectReason(Object reason) { return String.valueOf(reason); }
  }

  private record Task(Runnable action, long due, CompletableFuture<Void> future) {}

  private static final class TestSession extends ClientSession {
    final EmbeddedChannel channel = new EmbeddedChannel();
    final List<Packet> sent = new ArrayList<>();
    TestSession() { super("no-network.invalid", 25565, "0.0.0.0", new MinecraftProtocol("Test"), null); }
    // Configuration does not use a world palette. Avoid loading world registry data here.
    @Override public PalettedWorldState createPalettedWorldState(int sections) { return null; }
    @Override public ChannelFuture send(Packet packet) {
      sent.add(packet);
      return channel.newSucceededFuture();
    }
    @Override public void connect(boolean wait) { throw new AssertionError("A test must not open a socket"); }
  }
}
