# FixedReconnectDelay 1.0.2

Standalone ZenithProxy plugin that waits **15 minutes (900 seconds)** after an established session is lost. Failed connection attempts use the normal retry delay instead.

## Download and upgrade

Download [FixedReconnectDelay-1.0.2.jar](https://github.com/jackforg/15-Minute-reconnect-delay/releases/download/v1.0.2/FixedReconnectDelay-1.0.2.jar) from the [v1.0.2 release](https://github.com/jackforg/15-Minute-reconnect-delay/releases/tag/v1.0.2).

1. Stop ZenithProxy.
2. Remove any older `FixedReconnectDelay` JAR from the Zenith `plugins` folder and put the new JAR there. Keep only one version installed.
3. Keep the `plugins/fixed-reconnect-delay` state folder when upgrading or restarting.
4. Start ZenithProxy through its normal Java launcher, with plugins enabled and `autoReconnect on`.

Built against **ZenithProxy 3.7.0+1.21.4**, for **Minecraft 1.21.4**. It requires that version or a compatible Java release with the `ProxyLaunchWrapper` plugin transformation support. Plugin classes target Java 21; building against the pinned dependency uses Java 25. An older/direct `com.zenith.Proxy` entrypoint cannot install the new connection guard and reports a load error. Use a compatible Zenith release with its normal launcher.

## What starts the wait

- A real disconnect after reaching the Minecraft world or queue: server restart/shutdown, kick, timeout, reset, or manual disconnect.
- A session that has reached the world entering reconfiguration. The plugin explicitly closes that connection instead of following the backend transfer immediately.
- Being kicked from the world back to queue, including the **Reconfiguring -> Started Queuing / Kicked to queue** restart sequence that v1.0.0 missed.

Reconfiguration, queue notifications, and the resulting physical disconnect count as one incident. They do not restart or extend the same timer.

**The first connection is allowed immediately when there is no saved previous session or disconnect.** Normal login configuration, initial queue entry, and the queue-to-world reconfiguration are allowed to finish. Version 1.0.1 incorrectly closed that queue-to-world transition, which could prevent the bot from ever reaching the world.

All Zenith connection entrypoints check the active cooldown **before authentication or connection work**. Startup auto-connect, update reconnect, other modules, and manual `connect`/`reconnect` commands cannot jump ahead of that deadline.

## Zenith/VPS restarts

The state is saved atomically in `plugins/fixed-reconnect-delay/cooldown.properties`.

- Restart during a cooldown: resume its **remaining time**, including time spent offline. Repeated restarts do not reset the same deadline.
- Crash/restart after an established session, without a disconnect callback: start a full 900-second wait at the next startup. The plugin cannot know the precise instant an ungraceful process died.
- First installation with no saved history: allow the initial automatic or manual connection immediately. A reconnect cooldown requires a previous session or disconnect.
- Restart during failed connection attempts: retain the failed-attempt exception, without adding another 15 minutes.

Upgrading from v1.0.1 automatically clears its erroneous `Initial startup with automatic connection requested` timer. Other saved deadlines remain in force; keep the state folder.

A running process uses a monotonic clock, so changing its wall clock does not shorten an active wait. Wall-clock time is used to recover the saved deadline after a process restart.

## Failed-attempt exception and reconnect controls

Refused connections, authentication failures, and rejected login/handshake attempts use `autoReconnect delay <seconds>` (Zenith's default is 5 seconds). They do not start a new 900-second timer. After a successful world/queue session, its next loss starts another full cooldown.

There are no plugin commands or delay settings. `autoReconnect off` prevents the scheduled automatic attempt. Other plugins' reconnect vetoes remain in force; this plugin does not reset or bypass an ObsidianFarmWatchdog emergency trip.

The attempt becomes due at 900 seconds; JVM/OS scheduling, authentication, network conditions, and server queues can make the actual login later. This is not a hard real-time guarantee. State-storage errors are logged and block new connections rather than discarding the cooldown.

## Build and validation

With Java 25 installed:

```powershell
.\gradlew.bat clean test shadowJar
```

Linux/macOS: `bash ./gradlew clean test shadowJar`.

The result is `build/libs/FixedReconnectDelay-1.0.2.jar`. All **61 tests passed**. The regression suite exercises actual Zenith event types/event bus, its configuration packet handler with an isolated session, durable restart recovery, and the connection transformers against the pinned Zenith classes and shared application classloader. See [BUILD-RESULT.txt](BUILD-RESULT.txt) for the result and checksum. No live Minecraft/2b2t session is used by those tests.

The `release/` directory contains the tested JAR, checksum and release notes. The release workflow checks the checksum before publishing the downloads.
