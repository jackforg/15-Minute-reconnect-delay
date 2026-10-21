package bot.reconnect;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;

@Plugin(
    id = "fixed-reconnect-delay",
    version = "1.0.1",
    description = "Wait 15 minutes after a disconnect; failed connection attempts use the normal retry delay",
    authors = {"FixedReconnectDelay"},
    mcVersions = {"1.21.4"},
    mixins = {"bot.reconnect.mixin.ProxyConnectMixin", "bot.reconnect.mixin.ClientDisconnectMixin"})
public final class FixedReconnectDelayPlugin implements ZenithProxyPlugin {
  @Override
  public void onLoad(PluginAPI api) {
    if (!ConnectionGate.transformerLoaded())
      throw new IllegalStateException("The connection guard was not loaded. Use ZenithProxy 3.7.0+1.21.4 "
          + "or a compatible release through its ProxyLaunchWrapper entrypoint.");
    // Keep new connections blocked while loading durable state. A damaged state file
    // must not silently turn a pending cooldown into an immediate startup login.
    ConnectionGate.install(new ConnectionGate.Listener() {
      @Override public boolean blockConnect() { return true; }
      @Override public void beforeDisconnect(Object client, Object reason) {}
    });
    var module = new FixedReconnectModule(new ZenithHost(api.getLogger()));
    api.registerModule(module);
    if (!module.isEnabled()) throw new IllegalStateException("Reconnect delay module failed to enable");
    api.getLogger().info(
        "Loaded v1.0.1: disconnects, reconfiguration and kicks to queue start a saved 900-second cooldown. "
            + "All connection attempts respect that cooldown. Failed attempts use autoReconnect delay.");
  }
}
