package bot.reconnect;

/** Shared by the transformed Zenith classes and the plugin, before authentication or TCP login. */
public final class ConnectionGate {
  public interface Listener {
    boolean blockConnect();
    void beforeDisconnect(Object client, Object reason);
  }

  private static volatile Listener listener;
  private static volatile boolean transformerLoaded;

  private ConnectionGate() {}

  public static void markTransformerLoaded() { transformerLoaded = true; }
  public static boolean transformerLoaded() { return transformerLoaded; }
  public static void install(Listener replacement) { listener = replacement; }
  public static boolean blockConnect() {
    var current = listener;
    return current != null && current.blockConnect();
  }
  public static void beforeDisconnect(Object client, Object reason) {
    var current = listener;
    if (current != null) current.beforeDisconnect(client, reason);
  }
}
