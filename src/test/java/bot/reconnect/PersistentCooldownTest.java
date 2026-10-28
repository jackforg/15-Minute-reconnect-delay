package bot.reconnect;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentCooldownTest {
  @TempDir Path directory;

  @Test void fullCooldownIsDurableAndResumesOnlyTheRemainder() {
    var clock = new Clock();
    var store = store();
    var original = clock.load(store);
    original.begin("Server restarting");
    clock.advance(300);
    clock.nanos = 0; // A new JVM has an unrelated monotonic-clock origin.
    var restarted = clock.load(store);
    assertEquals(TimeUnit.SECONDS.toNanos(600), restarted.remainingNanos());
    clock.advance(599);
    assertTrue(restarted.blocked());
    clock.advance(1);
    assertFalse(restarted.blocked());
  }

  @Test void repeatedRestartsDoNotResetTheSameDeadline() {
    var clock = new Clock();
    var store = store();
    clock.load(store).begin("Kick");
    clock.advance(100);
    assertEquals(TimeUnit.SECONDS.toNanos(800), clock.load(store).remainingNanos());
    clock.advance(200);
    assertEquals(TimeUnit.SECONDS.toNanos(600), clock.load(store).remainingNanos());
  }

  @Test void crashOfAnEstablishedSessionStartsAFullWaitAtStartup() {
    var clock = new Clock();
    var store = store();
    clock.load(store).connected();
    var restarted = clock.load(store);
    assertTrue(restarted.blocked());
    assertEquals(TimeUnit.MINUTES.toNanos(15), restarted.remainingNanos());
  }

  @Test void restartingAfterAFailedAttemptDoesNotAddFifteenMinutes() {
    var clock = new Clock();
    var store = store();
    assertTrue(clock.load(store).attemptStarting());
    assertFalse(clock.load(store).blocked());
  }

  @Test void expiredSavedDeadlinePermitsAnAttemptImmediately() {
    var clock = new Clock();
    var store = store();
    clock.load(store).begin("Restart");
    clock.advance(901);
    var restarted = clock.load(store);
    assertFalse(restarted.blocked());
    assertTrue(restarted.attemptStarting());
  }

  @Test void duplicateTransitionDoesNotMoveTheDeadline() {
    var clock = new Clock();
    var state = clock.load(store());
    state.begin("Reconfiguring");
    clock.advance(30);
    assertFalse(state.begin("Kicked to queue"));
    assertEquals(TimeUnit.SECONDS.toNanos(870), state.remainingNanos());
  }

  @Test void delayedConnectedNotificationCannotEraseAnActiveCooldown() {
    var clock = new Clock();
    var store = store();
    var state = clock.load(store);
    state.begin("Restart");
    state.connected();
    assertEquals(TimeUnit.MINUTES.toNanos(15), clock.load(store).remainingNanos());
  }

  @Test void liveWallClockChangesCannotShortenTheTimer() {
    var clock = new Clock();
    var state = clock.load(store());
    state.begin("Restart");
    clock.millis += TimeUnit.DAYS.toMillis(1);
    assertEquals(TimeUnit.MINUTES.toNanos(15), state.remainingNanos());
  }

  @Test void firstInstallationHasNoDisconnectToWaitFor() {
    var state = new Clock().load(store());
    assertFalse(state.blocked());
    assertFalse(state.hasReconnectToResume());
    assertTrue(state.attemptStarting());
  }

  @Test void upgradeClearsOnlyTheSyntheticStartupWaitSavedByVersion101() throws IOException {
    var clock = new Clock();
    var store = store();
    store.save(new CooldownStore.Snapshot(CooldownStore.Mode.WAITING,
        clock.millis + 900_000, "Initial startup with automatic connection requested"));
    var state = clock.load(store);
    assertFalse(state.blocked());
    assertFalse(state.hasReconnectToResume());
    assertEquals(CooldownStore.Mode.EMPTY, store.load().mode());
    assertTrue(state.attemptStarting());
  }

  @Test void invalidSavedStateDoesNotBecomeAnImmediateConnection() throws IOException {
    Files.writeString(directory.resolve("cooldown.properties"), "version=1\nmode=WAITING\n");
    assertThrows(UncheckedIOException.class, () -> new Clock().load(store()));
  }

  @Test void saveFailureKeepsConnectionsBlockedEvenAfterTheDeadline() throws IOException {
    Files.writeString(directory.resolve("not-a-directory"), "x");
    var clock = new Clock();
    var state = clock.load(new CooldownStore(directory.resolve("not-a-directory/state")));
    assertThrows(UncheckedIOException.class, () -> state.begin("Restart"));
    clock.advance(901);
    assertTrue(state.blocked());
  }

  private CooldownStore store() { return new CooldownStore(directory.resolve("cooldown.properties")); }

  private static final class Clock {
    long millis = 1_800_000_000_000L;
    long nanos;
    PersistentCooldown load(CooldownStore store) {
      return new PersistentCooldown(store, () -> millis, () -> nanos);
    }
    void advance(long seconds) {
      millis += TimeUnit.SECONDS.toMillis(seconds);
      nanos += TimeUnit.SECONDS.toNanos(seconds);
    }
  }
}
