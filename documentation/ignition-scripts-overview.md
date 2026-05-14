# Ignition Scripts Overview

## Current Status: Not Yet Implemented

Ignition scripts are referenced in Spaceport documentation as a planned feature, but they are **not yet implemented** in the current source code. There is no source code, class, or module in the Spaceport codebase that handles ignition scripts.

### No Configuration Key Defined

Unlike logging (which has defined but unused configuration keys), ignition scripts do not have a corresponding configuration key in Spaceport's default configuration. There is no `ignition.path` or similar entry in the default manifest. The feature has not yet reached the configuration stage of development.

### What Ignition Scripts Are Intended to Be

Based on documentation references, ignition scripts are envisioned as startup scripts that run during the Spaceport initialization sequence. They would allow developers to execute custom setup logic -- such as seeding databases, initializing caches, or performing environment checks -- as part of the application boot process.

### Current Alternatives

Until ignition scripts are implemented, you can achieve similar startup behavior through these approaches:

- **Source module initialization**: Place startup logic in a source module that runs its setup code when the module is first loaded. Source modules are compiled and loaded during startup, so any static initialization blocks or constructor logic will execute at boot time.
- **External scripts**: Run setup scripts before starting Spaceport, either as part of a shell script that wraps the `java -jar` command or through your deployment automation.

## Related Documentation

- [Source Modules](source-modules-overview.md) -- Application logic that loads at startup
- [Manifest Configuration](manifest-overview.md) -- Spaceport configuration options
