Standalone ZenithProxy plugin that schedules automatic reconnect **15 minutes (900 seconds)** after an established Minecraft world or queue session disconnects. This covers server restarts, kicks, timeouts, connection resets, and manual disconnects.

**Failed connection attempts are the delay exception:** refused connections, authentication failures, and rejected logins use the existing `autoReconnect delay` setting instead (5 seconds by default). Failed attempts do not restart the 15-minute timer.

### Install

1. Download `FixedReconnectDelay-1.0.0.jar` below.
2. Stop ZenithProxy, put the JAR in its `plugins` folder, and start ZenithProxy.
3. Keep `autoReconnect on`. The plugin's 900-second session-disconnect delay has no extra configuration.

Built against **ZenithProxy 3.7.0+1.21.4**, for **Minecraft 1.21.4**. Output targets **Java 21**. All **23 tests passed** locally; no live Minecraft server test was performed.

Existing reconnect vetoes and watchdog emergency stops remain in force. Timers run while Zenith is running and are not saved across a Zenith process restart. An explicit manual connect or another module can bypass the automatic timer. The attempt is scheduled for 900 seconds; actual login can be later due to scheduling, authentication, network conditions, or the server queue.

SHA-256 for the JAR:

```text
243eaff3ec339b7378cbb5c73b20ad5db87187ba4e59253229fb0b460d8b0e01
```
