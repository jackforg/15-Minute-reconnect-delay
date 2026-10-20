package bot.reconnect;

/** Session identity avoids mistaking a stale client after an auth failure for a new drop. */
final class SessionHistory {
  private Object lastDisconnected;

  synchronized boolean disconnected(Object session, boolean wasOnline, boolean wasInQueue) {
    boolean established = session != null && session != lastDisconnected && (wasOnline || wasInQueue);
    lastDisconnected = session;
    return established;
  }
}
