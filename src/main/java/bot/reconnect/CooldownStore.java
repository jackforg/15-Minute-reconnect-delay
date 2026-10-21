package bot.reconnect;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

final class CooldownStore {
  enum Mode { EMPTY, CONNECTED, WAITING, RETRYING }
  record Snapshot(Mode mode, long deadlineEpochMillis, String reason) {}

  private final Path path;

  CooldownStore(Path path) { this.path = path; }

  Snapshot load() throws IOException {
    if (!Files.exists(path)) return new Snapshot(Mode.EMPTY, 0, "First installation");
    var properties = new Properties();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
      if (!"1".equals(properties.getProperty("version"))) throw new IOException("Unknown cooldown state version");
      var mode = Mode.valueOf(properties.getProperty("mode", ""));
      long deadline = Long.parseLong(properties.getProperty("deadlineEpochMillis", ""));
      String reason = properties.getProperty("reason", "");
      if (deadline < 0 || (mode == Mode.WAITING ? deadline == 0 : deadline != 0) || reason.isBlank())
        throw new IOException("Invalid cooldown state");
      return new Snapshot(mode, deadline, reason);
    } catch (IllegalArgumentException failure) {
      throw new IOException("Invalid cooldown state", failure);
    }
  }

  void save(Snapshot state) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    var properties = new Properties();
    properties.setProperty("version", "1");
    properties.setProperty("mode", state.mode().name());
    properties.setProperty("deadlineEpochMillis", Long.toString(state.deadlineEpochMillis()));
    properties.setProperty("reason", state.reason());
    var text = new StringWriter();
    properties.store(text, "FixedReconnectDelay durable state");
    var temporary = path.resolveSibling(path.getFileName() + ".tmp");
    try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      var bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) file.write(bytes);
      file.force(true);
    }
    try {
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
