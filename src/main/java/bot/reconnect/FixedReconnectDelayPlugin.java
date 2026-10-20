package bot.reconnect;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;

@Plugin(
    id = "fixed-reconnect-delay",
    version = "1.0.0",
    description = "Wait 15 minutes after a disconnect; failed connection attempts use the normal retry delay",
    authors = {"FixedReconnectDelay"},
    mcVersions = {"1.21.4"})
public final class FixedReconnectDelayPlugin implements ZenithProxyPlugin {
  @Override
  public void onLoad(PluginAPI api) {
    api.registerModule(new FixedReconnectModule(api.getLogger()));
    api.getLogger().info(
        "Loaded: established sessions wait exactly 900 seconds before automatic reconnect. "
            + "Failed connection attempts use autoReconnect delay. Keep autoReconnect on.");
  }
}
