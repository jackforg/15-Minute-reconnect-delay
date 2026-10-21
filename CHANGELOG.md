# Changelog

## 1.0.1 - 2026-10-21

- Fix the server restart path that reconfigures an established session and kicks it directly back to queue without a network disconnect.
- Close that session and start the 900-second cooldown; duplicate reconfiguration/queue/close events keep the original deadline.
- Persist cooldown deadlines and established/failed-attempt state across Zenith/VPS restarts.
- Gate all Zenith connection entrypoints before authentication/network work, including startup, direct module requests and manual connects during a cooldown.
- Observe established-session disconnects before event-loop teardown so another reconnect path cannot race ahead of the timer.
- Preserve the normal retry interval for failed attempts and existing emergency reconnect vetoes.
- Pass all 57 tests, including event-bus restart regressions and transformation checks against the actual pinned Zenith classes and application classloader.

## 1.0.0 - 2026-10-20

- Publish a standalone ZenithProxy plugin for Minecraft 1.21.4.
- Schedule automatic reconnect 900 seconds after an established world or queue session disconnects, regardless of the disconnect reason.
- Use the normal configured retry delay for failed connection, authentication, and login attempts.
- Use a monotonic timer without the built-in reconnect jitter.
- Respect AutoReconnect being disabled and reconnect vetoes from other plugins, including watchdog emergency stops.
- Verify timing, duplicate events, failed-attempt handling, cancellation, and session classification with 23 passing tests.
