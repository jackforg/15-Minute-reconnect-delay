Fixes v1.0.1 interrupting the first connection when the bot reconfigures from queue into the world. That ordinary login transition was being treated as a lost session.

### Fixed behavior

- Allow normal initial configuration, initial queue entry, and the queue-to-world handoff to finish.
- Allow the first automatic or manual connection immediately when there is no saved previous session or disconnect.
- Automatically clear the synthetic first-start timer written by v1.0.1. Keep real saved cooldowns and their remaining time.
- Continue waiting **900 seconds (15 minutes)** after actual world/queue disconnects, world-to-queue restart transfers, and reconfiguration after world play.
- Preserve cooldowns across Zenith/VPS restarts. Failed connection attempts still use the normal retry interval.

### Upgrade

1. Stop ZenithProxy.
2. Remove the older `FixedReconnectDelay` JAR and put `FixedReconnectDelay-1.0.2.jar` in its `plugins` folder. Keep only one version installed.
3. Preserve the `plugins/fixed-reconnect-delay` state folder.
4. Start ZenithProxy through its normal Java launcher with `autoReconnect on`.

Targets **Minecraft 1.21.4**, built against **ZenithProxy 3.7.0+1.21.4**. Requires compatible `ProxyLaunchWrapper` transformation support; output classes target Java 21.

Existing watchdog/emergency reconnect vetoes remain in force. The tests include Zenith's actual configuration handler, session state, event bus, module lifecycle and connection transformations. See the attached `BUILD-RESULT.txt` and `SHA256SUMS` for validation and the artifact checksum. No live Minecraft/2b2t restart was performed during validation.
