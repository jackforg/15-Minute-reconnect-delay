Fixes the restart sequence **Reconfiguring -> Started Queuing / Kicked to queue**. Version 1.0.0 only handled actual network disconnects, so that backend transfer could bypass its timer.

### Fixed behavior

- Close an established session when it reconfigures or is kicked from the world back into queue, then wait **900 seconds (15 minutes)** before connecting again.
- Keep one deadline for the reconfiguration/queue/disconnect sequence.
- Save the cooldown across Zenith/VPS restarts and resume the remaining time. A crash of a previously established session starts a full wait at the next startup.
- Block startup auto-connect, update reconnect, direct module connects and manual connects while the cooldown is active, before authentication/network work.
- Keep failed connection attempts on the normal `autoReconnect delay` interval (5 seconds by default).

### Upgrade

1. Stop ZenithProxy.
2. Replace `FixedReconnectDelay-1.0.0.jar` with `FixedReconnectDelay-1.0.1.jar` in its `plugins` folder. Keep only the new version installed.
3. Preserve the `plugins/fixed-reconnect-delay` state folder on future upgrades/restarts.
4. Start ZenithProxy through its normal Java launcher and keep `autoReconnect on`.

Targets **Minecraft 1.21.4**, built against **ZenithProxy 3.7.0+1.21.4**. Requires compatible `ProxyLaunchWrapper` transformation support; output classes target Java 21. The first upgrade with startup auto-connect enabled waits 15 minutes because v1.0.0 did not save session history.

Existing watchdog/emergency reconnect vetoes remain in force. All **57 tests passed**, including real Zenith events and shared-classloader transformation checks. See the attached `BUILD-RESULT.txt` for the recorded results. No live Minecraft/2b2t restart was performed during validation.

JAR SHA-256:

```text
eef99aea08bec052f2a9e7040919f5fa965a6589d630f0665a9cc10376ede88f
```
