package bot.reconnect;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionHistoryTest {
  @Test
  void worldSessionCountsAsEstablished() {
    assertTrue(new SessionHistory().disconnected(new Object(), true, false));
  }

  @Test
  void queueSessionCountsAsEstablished() {
    assertTrue(new SessionHistory().disconnected(new Object(), false, true));
  }

  @Test
  void aTcpConnectionWithoutSuccessfulLoginIsOnlyAFailedAttempt() {
    assertFalse(new SessionHistory().disconnected(new Object(), false, false));
  }

  @Test
  void authenticationFailureWithNoClientIsOnlyAFailedAttempt() {
    assertFalse(new SessionHistory().disconnected(null, false, false));
  }

  @Test
  void authenticationFailureRetainingThePreviousClientIsNotAnotherSessionDrop() {
    var history = new SessionHistory();
    var oldClient = new Object();
    assertTrue(history.disconnected(oldClient, true, false));
    assertFalse(history.disconnected(oldClient, true, false));
  }

  @Test
  void eachNewSuccessfulSessionCountsEvenAfterSeveralFailedAttempts() {
    var history = new SessionHistory();
    assertTrue(history.disconnected(new Object(), true, false));
    assertFalse(history.disconnected(new Object(), false, false));
    assertFalse(history.disconnected(new Object(), false, false));
    assertTrue(history.disconnected(new Object(), false, true));
  }
}
