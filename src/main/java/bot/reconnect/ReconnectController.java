package bot.reconnect;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One monotonic deadline, with no polling, sleeping, jitter, or accumulated countdown drift. */
final class ReconnectController {
  static final int DISCONNECT_DELAY_SECONDS = 15 * 60;

  interface Actions {
    boolean allowed();
    boolean connectionActive();
    int failedAttemptDelaySeconds();
    void connect();
    void scheduled(int delaySeconds, boolean failedAttempt);
    void failed(RuntimeException failure);
  }

  @FunctionalInterface
  interface Scheduler {
    Future<?> schedule(Runnable task, long delayNanos);
  }

  private final Actions actions;
  private final Scheduler scheduler;
  private final LongSupplier nanoTime;
  private Future<?> pending;
  private long generation;
  private long deadline;

  ReconnectController(Actions actions, Scheduler scheduler, LongSupplier nanoTime) {
    this.actions = actions;
    this.scheduler = scheduler;
    this.nanoTime = nanoTime;
  }

  synchronized void disconnected(boolean establishedSession) {
    // Duplicate failure notifications must never shorten or restart an existing cooldown.
    if (!establishedSession && pending != null) return;
    long disconnectedAt = nanoTime.getAsLong();
    cancel();
    long expectedGeneration = generation;
    if (!actions.allowed() || expectedGeneration != generation) return;
    int seconds = establishedSession
        ? DISCONNECT_DELAY_SECONDS
        : Math.max(0, actions.failedAttemptDelaySeconds());
    deadline = disconnectedAt + TimeUnit.SECONDS.toNanos(seconds);
    schedule(expectedGeneration);
    actions.scheduled(seconds, !establishedSession);
  }

  synchronized void cancel() {
    generation++;
    if (pending != null) pending.cancel(false);
    pending = null;
  }

  synchronized void resumeCooldown(long remainingNanos) {
    long resumedAt = nanoTime.getAsLong();
    cancel();
    long expectedGeneration = generation;
    if (!actions.allowed() || expectedGeneration != generation) return;
    deadline = resumedAt + Math.max(0, remainingNanos);
    schedule(expectedGeneration);
    long seconds = remainingNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toSeconds(remainingNanos - 1) + 1;
    actions.scheduled((int) Math.min(Integer.MAX_VALUE, seconds), false);
  }

  private void schedule(long expectedGeneration) {
    long remaining = Math.max(0, deadline - nanoTime.getAsLong());
    pending = scheduler.schedule(() -> reconnect(expectedGeneration), remaining);
  }

  private void reconnect(long expectedGeneration) {
    synchronized (this) {
      if (expectedGeneration != generation) return;
      if (deadline - nanoTime.getAsLong() > 0) {
        schedule(expectedGeneration);
        return;
      }
      pending = null;
      if (actions.connectionActive() || !actions.allowed() || expectedGeneration != generation) return;
    }
    try {
      // Proxy.connect's entry guard checks the durable cooldown again. Do not hold
      // this monitor during login: a network disconnect callback must remain free
      // to record a new cooldown while connect waits for the network.
      actions.connect();
    } catch (RuntimeException failure) {
      actions.failed(failure);
      // Most failures produce a disconnect event themselves. Only supply a retry
      // when no newer event/connection has already taken ownership.
      synchronized (this) {
        if (expectedGeneration == generation && pending == null
            && !actions.connectionActive()) disconnected(false);
      }
    }
  }
}
