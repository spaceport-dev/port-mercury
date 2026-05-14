# Logging Overview

## Current Status: Not Yet Implemented

Logging configuration keys exist in Spaceport's default manifest configuration, but the logging subsystem is **not yet implemented** in the current source code. The configuration values are defined but never consumed by any logging infrastructure.

### Configuration Keys (Defined but Unused)

The following keys appear in Spaceport's default configuration:

```yaml
logging:
  enabled: false
  path: "log/"
```

These keys are recognized by the configuration parser and can be set in your manifest file, but they have no effect on application behavior at this time.

### Current Output Behavior

Spaceport currently outputs all diagnostic and status messages to **stdout and stderr** through the `Command` class (`spaceport.bridge.Command`). This class provides several output methods used throughout the framework:

- `Command.debug(message)` -- Debug-level messages (visible when `debug: true` is set in the manifest)
- `Command.error(message)` -- Error messages written to stderr
- `Command.success(message)` -- Success/status messages
- `Command.println(message)` -- General output
- `Command.printHeader(message)` -- Section headers during startup
- `Command.printParagraph(message)` -- Formatted paragraph output

All output goes directly to the console. There is no file-based logging, log rotation, or log level filtering beyond the debug flag.

### Planned for Future Implementation

File-based logging with configurable paths is planned for a future Spaceport release. When implemented, the `logging.enabled` and `logging.path` configuration keys will activate the feature. Until then, you can capture Spaceport output using standard OS-level redirection:

```bash
java -jar spaceport.jar --start config.spaceport > app.log 2>&1
```

Or to keep output visible while also writing to a file:

```bash
java -jar spaceport.jar --start config.spaceport 2>&1 | tee app.log
```

## Related Documentation

- [Manifest Configuration](manifest-overview.md) -- All configuration options including the logging keys
- [Source Modules](source-modules-overview.md) -- Where most application-level logging would occur
