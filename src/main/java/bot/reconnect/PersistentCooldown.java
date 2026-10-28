package bot.reconnect;

import static bot.reconnect.CooldownStore.Mode;
import static bot.reconnect.CooldownStore.Snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Wall-clock deadlines survive restarts; a running process uses only its monotonic clock. */
final class PersistentCooldown {
  static final long DELAY_MILLIS = TimeUnit.MINUTES.toMillis(15);
  private final CooldownStore store;
  private final LongSupplier millis;
  private final LongSupplier nanos;
  private Snapshot state;
  private long deadlineNanos;
  private boolean timed;
  private boolean failed;

  PersistentCooldown(CooldownStore store, LongSupplier millis, LongSupplier nanos) {
    this.store = store;
    this.millis = millis;
    this.nanos = nanos;
    try { state = store.load(); }
    catch (IOException failure) { throw new UncheckedIOException(failure); }
    // v1.0.1 created a cooldown without any prior session on a fresh install.
    // Remove only that synthetic wait; preserve real saved disconnect deadlines.
    if (state.mode() == Mode.WAITING
        && state.reason().equals("Initial startup with automatic connection requested"))
      persist(new Snapshot(Mode.EMPTY, 0, "First connection ready"));
    if (state.mode() == Mode.WAITING) {
      long remaining = Math.max(0, Math.min(DELAY_MILLIS, state.deadlineEpochMillis() - millis.getAsLong()));
      deadlineNanos = nanos.getAsLong() + TimeUnit.MILLISECONDS.toNanos(remaining);
      timed = remaining > 0;
    } else if (state.mode() == Mode.CONNECTED) {
      begin("Zenith restarted after an established session");
    }
  }

  synchronized boolean begin(String reason) {
    if (blocked()) return false;
    deadlineNanos = nanos.getAsLong() + TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS);
    timed = true;
    persist(new Snapshot(Mode.WAITING, millis.getAsLong() + DELAY_MILLIS, reason));
    return true;
  }

  synchronized boolean blocked() { return failed || remainingNanos() > 0; }
  synchronized long remainingNanos() {
    return timed ? Math.max(0, deadlineNanos - nanos.getAsLong()) : 0;
  }
  synchronized String reason() { return state.reason(); }
  synchronized boolean hasReconnectToResume() { return state.mode() == Mode.WAITING; }

  synchronized void connected() {
    if (!blocked()) {
      timed = false;
      persist(new Snapshot(Mode.CONNECTED, 0, "Established world or queue session"));
    }
  }

  synchronized boolean attemptStarting() {
    if (blocked()) return false;
    timed = false;
    if (state.mode() != Mode.RETRYING)
      persist(new Snapshot(Mode.RETRYING, 0, "Connection attempt; failed attempts use normal retries"));
    return true;
  }

  private void persist(Snapshot next) {
    try {
      store.save(next);
      state = next;
    } catch (IOException failure) {
      failed = true;
      throw new UncheckedIOException("Cannot save cooldown; new connections remain blocked", failure);
    }
  }
}
