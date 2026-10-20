# FixedReconnectDelay 1.0.0

**The delay is 15 minutes (900 seconds).** The repository's `15-Second-reconnect-delay` name does not change the plugin's behavior.

Standalone ZenithProxy plugin for Minecraft **1.21.4**, built against ZenithProxy **3.7.0+1.21.4**. Install this JAR separately from ObsidianFarmWatchdog.

After a session that reached the Minecraft world or queue disconnects, the plugin schedules the next automatic connection attempt for **900 seconds (15 minutes)** later. The reason does not change the delay: server restart/shutdown, kick, timeout, connection reset, manual disconnect, and maximum playtime all use the same timer.

**Failed connection attempts are the only delay exception.** Refused connections, failed authentication, and rejected login/handshake attempts use the existing `autoReconnect delay` setting instead (Zenith's default is 5 seconds). They do not start another 15-minute wait. A successful session's next disconnect starts a fresh 15 minutes.

## Install

Download [FixedReconnectDelay-1.0.0.jar](https://github.com/jackforg/15-Second-reconnect-delay/releases/download/v1.0.0/FixedReconnectDelay-1.0.0.jar) from the [v1.0.0 release](https://github.com/jackforg/15-Second-reconnect-delay/releases/tag/v1.0.0).

1. Stop ZenithProxy.
2. Copy `FixedReconnectDelay-1.0.0.jar` into its `plugins` folder.
3. Start ZenithProxy with plugins enabled and run `autoReconnect on` if needed.

There are no plugin commands or settings. The 900-second session-disconnect delay is fixed. `autoReconnect delay <seconds>` only controls failed-attempt retries while this plugin is installed. `autoReconnect off` disables automatic reconnects. Remove the JAR and restart Zenith to restore its built-in scheduler.

The plugin replaces the built-in reconnect timer in memory, avoiding its extra random jitter. Scheduling uses a monotonic clock and a single nonblocking timer. The connection attempt is due at 900 seconds; JVM/OS scheduling, authentication, network latency, server availability, and queue time can make the actual login later. This is not a hard real-time guarantee.

Timers run while the Zenith process is running; they are not persisted across a Zenith process restart. Manually issuing a connect command or another module explicitly initiating a connection can bypass the automatic timer. A Minecraft server restart is covered through its disconnect event.

Intentional reconnect controls remain in force: disabling AutoReconnect or another plugin vetoing reconnect prevents the attempt. In particular, this plugin does not reset or bypass an ObsidianFarmWatchdog emergency trip. Use the watchdog's normal recovery procedure before enabling reconnect again.

## Build

Java 25 is used to compile against the pinned Zenith dependency; the resulting plugin targets Java 21. From this folder:

```powershell
.\gradlew.bat clean test shadowJar
```

The installable file is `build/libs/FixedReconnectDelay-1.0.0.jar`.

On Linux/macOS, run `bash ./gradlew clean test shadowJar` with Java 25 installed. Validation for v1.0.0 passed all 23 tests; see [BUILD-RESULT.txt](BUILD-RESULT.txt). No live server session was used for validation.

The `release/` directory contains the tested JAR, its SHA-256 checksum, and release notes. The GitHub Actions release workflow verifies that checksum before attaching the assets and publishing v1.0.0.
