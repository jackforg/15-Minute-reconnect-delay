package bot.reconnect;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReconnectControllerTest {
  @Test
  void establishedSessionWaits900SecondsAndConnectsOnceAtTheDeadline() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    assertEquals(TimeUnit.MINUTES.toNanos(15), fixture.tasks.getFirst().delay);
    fixture.advanceNanos(TimeUnit.MINUTES.toNanos(15) - 1);
    assertEquals(0, fixture.connects);
    fixture.advanceNanos(1);
    assertEquals(1, fixture.connects);
    fixture.advanceSeconds(900);
    assertEquals(1, fixture.connects);
  }

  @Test
  void failedAttemptUsesTheExistingRetryDelay() {
    var fixture = new Fixture();
    fixture.retrySeconds = 7;
    fixture.controller.disconnected(false);
    fixture.advanceSeconds(6);
    assertEquals(0, fixture.connects);
    fixture.advanceSeconds(1);
    assertEquals(1, fixture.connects);
  }

  @Test
  void repeatedFailedAttemptsNeverStartAnother900SecondWait() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(900);
    for (int expected = 2; expected <= 4; expected++) {
      fixture.controller.disconnected(false);
      fixture.advanceSeconds(5);
      assertEquals(expected, fixture.connects);
    }
  }

  @Test
  void nextEstablishedSessionStartsANew900SecondWait() {
    var fixture = new Fixture();
    fixture.controller.disconnected(false);
    fixture.advanceSeconds(5);
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(899);
    assertEquals(1, fixture.connects);
    fixture.advanceSeconds(1);
    assertEquals(2, fixture.connects);
  }

  @Test
  void duplicateFailureCannotShortenOrExtendThe900SecondWait() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(300);
    fixture.controller.disconnected(false);
    fixture.advanceSeconds(599);
    assertEquals(0, fixture.connects);
    fixture.advanceSeconds(1);
    assertEquals(1, fixture.connects);
  }

  @Test
  void aNewEstablishedDropReplacesThePreviousTimer() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(100);
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(800);
    assertEquals(0, fixture.connects);
    fixture.advanceSeconds(100);
    assertEquals(1, fixture.connects);
  }

  @Test
  void earlySchedulerCallbackWaitsForTheRemainder() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    var early = fixture.tasks.getFirst();
    early.ran = true;
    fixture.now += TimeUnit.SECONDS.toNanos(100);
    early.task.run();
    assertEquals(0, fixture.connects);
    assertEquals(TimeUnit.SECONDS.toNanos(800), fixture.tasks.getLast().delay);
    fixture.advanceSeconds(800);
    assertEquals(1, fixture.connects);
  }

  @Test
  void slowDecisionListenersDoNotAddTimeToTheDeadline() {
    var fixture = new Fixture();
    fixture.onAllowed = () -> fixture.now += TimeUnit.SECONDS.toNanos(4);
    fixture.controller.disconnected(true);
    assertEquals(TimeUnit.SECONDS.toNanos(896), fixture.tasks.getFirst().delay);
  }

  @Test
  void disabledReconnectDoesNotSchedule() {
    var fixture = new Fixture();
    fixture.allowed = false;
    fixture.controller.disconnected(true);
    assertTrue(fixture.tasks.isEmpty());
  }

  @Test
  void watchdogOrDisableDuringWaitIsRespectedAtDeadline() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    fixture.allowed = false;
    fixture.advanceSeconds(900);
    assertEquals(0, fixture.connects);
  }

  @Test
  void anAlreadyConnectedOrConnectingBotIsNotConnectedAgain() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    fixture.active = true;
    fixture.advanceSeconds(900);
    assertEquals(0, fixture.connects);
  }

  @Test
  void cancellingInvalidatesEvenAnAlreadyDispatchedCallback() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    var stale = fixture.tasks.getFirst();
    fixture.controller.cancel();
    fixture.now += TimeUnit.SECONDS.toNanos(900);
    stale.task.run();
    assertEquals(0, fixture.connects);
  }

  @Test
  void newDisconnectDuringDecisionInvalidatesTheExpiredTimer() {
    var fixture = new Fixture();
    fixture.controller.disconnected(true);
    fixture.onAllowed = () -> {
      fixture.onAllowed = () -> {};
      fixture.controller.disconnected(true);
    };
    fixture.advanceSeconds(900);
    assertEquals(0, fixture.connects);
    fixture.advanceSeconds(900);
    assertEquals(1, fixture.connects);
  }

  @Test
  void synchronousConnectionExceptionUsesShortRetry() {
    var fixture = new Fixture();
    fixture.onConnect = () -> { throw new IllegalStateException("Connection refused"); };
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(900);
    assertEquals(1, fixture.failures);
    fixture.onConnect = () -> {};
    fixture.advanceSeconds(5);
    assertEquals(2, fixture.connects);
  }

  @Test
  void connectionExceptionDoesNotDuplicateTheFailureEventRetry() {
    var fixture = new Fixture();
    fixture.onConnect = () -> {
      fixture.controller.disconnected(false);
      throw new IllegalStateException("Connection refused");
    };
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(900);
    assertEquals(2, fixture.tasks.size());
    fixture.onConnect = () -> {};
    fixture.advanceSeconds(5);
    assertEquals(2, fixture.connects);
  }

  @Test
  void zeroConfiguredRetryDoesNotAddAFifteenMinuteDelay() {
    var fixture = new Fixture();
    fixture.retrySeconds = 0;
    fixture.controller.disconnected(false);
    fixture.advanceNanos(0);
    assertEquals(1, fixture.connects);
  }

  @Test
  void monotonicClockWraparoundDoesNotShortenTheWait() {
    var fixture = new Fixture();
    fixture.now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1);
    fixture.controller.disconnected(true);
    fixture.advanceSeconds(899);
    assertEquals(0, fixture.connects);
    fixture.advanceSeconds(1);
    assertEquals(1, fixture.connects);
  }

  private static final class Fixture implements ReconnectController.Actions {
    final List<Task> tasks = new ArrayList<>();
    long now;
    int connects;
    int failures;
    int retrySeconds = 5;
    boolean allowed = true;
    boolean active;
    Runnable onConnect = () -> {};
    Runnable onAllowed = () -> {};
    final ReconnectController controller = new ReconnectController(this, (task, delay) -> {
      var scheduled = new Task(task, delay, now + delay);
      tasks.add(scheduled);
      return scheduled.future;
    }, () -> now);

    void advanceSeconds(long seconds) {
      advanceNanos(TimeUnit.SECONDS.toNanos(seconds));
    }

    void advanceNanos(long nanos) {
      now += nanos;
      // Callbacks can enqueue another task, so iterate a snapshot for this instant.
      for (var task : List.copyOf(tasks)) {
        if (!task.ran && !task.future.isCancelled() && task.due - now <= 0) {
          task.ran = true;
          task.task.run();
          task.future.complete(null);
        }
      }
    }

    @Override public boolean allowed() { onAllowed.run(); return allowed; }
    @Override public boolean connectionActive() { return active; }
    @Override public int failedAttemptDelaySeconds() { return retrySeconds; }
    @Override public void connect() { connects++; onConnect.run(); }
    @Override public void scheduled(int delay, boolean failedAttempt) {}
    @Override public void failed(RuntimeException failure) { failures++; }
  }

  private static final class Task {
    final Runnable task;
    final long delay;
    final long due;
    final CompletableFuture<Void> future = new CompletableFuture<>();
    boolean ran;

    Task(Runnable task, long delay, long due) {
      this.task = task;
      this.delay = delay;
      this.due = due;
    }
  }
}
