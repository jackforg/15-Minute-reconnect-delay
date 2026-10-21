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
    var original = clock.load(store, false);
    original.begin("Server restarting");
    clock.advance(300);
    clock.nanos = 0; // A new JVM has an unrelated monotonic-clock origin.
    var restarted = clock.load(store, true);
    assertEquals(TimeUnit.SECONDS.toNanos(600), restarted.remainingNanos());
    clock.advance(599);
    assertTrue(restarted.blocked());
    clock.advance(1);
    assertFalse(restarted.blocked());
  }

  @Test void repeatedRestartsDoNotResetTheSameDeadline() {
    var clock = new Clock();
    var store = store();
    clock.load(store, false).begin("Kick");
    clock.advance(100);
    assertEquals(TimeUnit.SECONDS.toNanos(800), clock.load(store, true).remainingNanos());
    clock.advance(200);
    assertEquals(TimeUnit.SECONDS.toNanos(600), clock.load(store, true).remainingNanos());
  }

  @Test void crashOfAnEstablishedSessionStartsAFullWaitAtStartup() {
    var clock = new Clock();
    var store = store();
    clock.load(store, false).connected();
    var restarted = clock.load(store, true);
    assertTrue(restarted.blocked());
    assertEquals(TimeUnit.MINUTES.toNanos(15), restarted.remainingNanos());
  }

  @Test void restartingAfterAFailedAttemptDoesNotAddFifteenMinutes() {
    var clock = new Clock();
    var store = store();
    assertTrue(clock.load(store, false).attemptStarting());
    assertFalse(clock.load(store, true).blocked());
  }

  @Test void expiredSavedDeadlinePermitsAnAttemptImmediately() {
    var clock = new Clock();
    var store = store();
    clock.load(store, false).begin("Restart");
    clock.advance(901);
    var restarted = clock.load(store, true);
    assertFalse(restarted.blocked());
    assertTrue(restarted.attemptStarting());
  }

  @Test void duplicateTransitionDoesNotMoveTheDeadline() {
    var clock = new Clock();
    var state = clock.load(store(), false);
    state.begin("Reconfiguring");
    clock.advance(30);
    assertFalse(state.begin("Kicked to queue"));
    assertEquals(TimeUnit.SECONDS.toNanos(870), state.remainingNanos());
  }

  @Test void delayedConnectedNotificationCannotEraseAnActiveCooldown() {
    var clock = new Clock();
    var store = store();
    var state = clock.load(store, false);
    state.begin("Restart");
    state.connected();
    assertEquals(TimeUnit.MINUTES.toNanos(15), clock.load(store, true).remainingNanos());
  }

  @Test void liveWallClockChangesCannotShortenTheTimer() {
    var clock = new Clock();
    var state = clock.load(store(), false);
    state.begin("Restart");
    clock.millis += TimeUnit.DAYS.toMillis(1);
    assertEquals(TimeUnit.MINUTES.toNanos(15), state.remainingNanos());
  }

  @Test void firstUpgradeWithStartupAutoConnectWaits() {
    assertTrue(new Clock().load(store(), true).blocked());
  }

  @Test void firstManualLoginWithoutStartupAutoConnectIsAllowed() {
    assertFalse(new Clock().load(store(), false).blocked());
  }

  @Test void invalidSavedStateDoesNotBecomeAnImmediateConnection() throws IOException {
    Files.writeString(directory.resolve("cooldown.properties"), "version=1\nmode=WAITING\n");
    assertThrows(UncheckedIOException.class, () -> new Clock().load(store(), true));
  }

  @Test void saveFailureKeepsConnectionsBlockedEvenAfterTheDeadline() throws IOException {
    Files.writeString(directory.resolve("not-a-directory"), "x");
    var clock = new Clock();
    var state = clock.load(new CooldownStore(directory.resolve("not-a-directory/state")), false);
    assertThrows(UncheckedIOException.class, () -> state.begin("Restart"));
    clock.advance(901);
    assertTrue(state.blocked());
  }

  private CooldownStore store() { return new CooldownStore(directory.resolve("cooldown.properties")); }

  private static final class Clock {
    long millis = 1_800_000_000_000L;
    long nanos;
    PersistentCooldown load(CooldownStore store, boolean startup) {
      return new PersistentCooldown(store, () -> millis, () -> nanos, startup);
    }
    void advance(long seconds) {
      millis += TimeUnit.SECONDS.toMillis(seconds);
      nanos += TimeUnit.SECONDS.toNanos(seconds);
    }
  }
}
