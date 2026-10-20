# Changelog

## 1.0.0 - 2026-10-20

- Publish a standalone ZenithProxy plugin for Minecraft 1.21.4.
- Schedule automatic reconnect 900 seconds after an established world or queue session disconnects, regardless of the disconnect reason.
- Use the normal configured retry delay for failed connection, authentication, and login attempts.
- Use a monotonic timer without the built-in reconnect jitter.
- Respect AutoReconnect being disabled and reconnect vetoes from other plugins, including watchdog emergency stops.
- Verify timing, duplicate events, failed-attempt handling, cancellation, and session classification with 23 passing tests.
