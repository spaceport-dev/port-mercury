# Stowaway JARs API Reference

## Configuration

Stowaways are configured in the Spaceport manifest file (`.spaceport`) under the `stowaways` key.

### `stowaways.enabled`

| Property | Value |
|---|---|
| Type | `boolean` |
| Default | `true` |
| Required | No |

Enables or disables stowaway JAR loading. When `false`, the entire stowaway system is skipped at startup.

```yaml
stowaways:
  enabled: false
```

### `stowaways.paths`

| Property | Value |
|---|---|
| Type | `List<String>` |
| Default | `["stowaways/*"]` |
| Required | No (when `enabled` is `true`) |

A list of directory paths to scan for JAR files. Each path can be either relative (resolved from the Spaceport project root) or absolute.

```yaml
stowaways:
  enabled: true
  paths:
    - "stowaways/*"
    - "lib/drivers/"
    - "/opt/shared-jars/*"
```

#### Wildcard Scanning

Paths ending with `*` enable recursive subdirectory scanning. Paths without `*` scan only the immediate directory.

| Path Pattern | Behavior |
|---|---|
| `stowaways/*` | Scans `stowaways/` and all nested subdirectories recursively |
| `lib/external/` | Scans only the `lib/external/` directory (no subdirectories) |
| `/opt/jars/*` | Scans the absolute path `/opt/jars/` and all subdirectories |

JAR files are identified by the `.jar` extension (case-insensitive).


## Runtime Configuration (Internal)

### `stowaways.'jar urls'`

| Property | Value |
|---|---|
| Type | `URL[]` |
| Set by | `Stowaways.load()` |
| Consumed by | `SourceStore` |

After loading, the Stowaways system stores the resolved JAR URLs in `Spaceport.config.stowaways.'jar urls'`. This array is consumed by `SourceStore` when it creates the `GroovyClassLoader` for source module compilation, ensuring that stowaway classes are available for imports in source modules.

This is an internal configuration value. Application code should not read or modify it.


## Classes

### `spaceport.engineering.Stowaways`

The main class responsible for discovering and loading stowaway JAR files.

**Package:** `spaceport.engineering`

#### `static void load()`

Entry point for the stowaway loading process. Called during Spaceport startup, after configuration is loaded and before source modules are compiled.

**Behavior:**

1. Initializes `Spaceport.classLoader` if it has not been set (uses the current thread's context classloader or the Stowaways class's own classloader as a fallback).
2. Checks `stowaways.enabled` in config. Returns immediately if disabled.
3. Checks `stowaways.paths` in config. Returns immediately if no paths are defined.
4. Determines whether the configuration is the default (`['stowaways/*']`) to control error handling behavior.
5. Iterates over each path in `stowaways.paths`, calling `scanForJars()` to collect JAR files.
6. Passes the collected JAR files to `loadJarFiles()`.

**Configuration required:**
- `Spaceport.config.stowaways.enabled` must be `true`
- `Spaceport.config.stowaways.paths` must contain at least one path

#### `private static List<File> scanForJars(String pathPattern, boolean isDefaultConfig)`

Scans a single directory path for JAR files.

| Parameter | Type | Description |
|---|---|---|
| `pathPattern` | `String` | Directory path to scan. May end with `*` for recursive scanning. |
| `isDefaultConfig` | `boolean` | Whether the current configuration is the default. Controls error handling. |

**Returns:** `List<File>` -- all JAR files found in the specified directory.

**Behavior:**

1. If the path ends with `*`, strips the `*` and enables recursive scanning.
2. Converts relative paths to absolute paths using `Spaceport.config.'spaceport root'` as the base.
3. Checks if the directory exists:
   - Default config: silently returns an empty list if missing.
   - Custom config: prints an error and calls `System.exit(1)`.
4. Validates the path is a directory (not a file). Exits on failure regardless of config type.
5. Scans for files with a `.jar` extension (case-insensitive):
   - Recursive mode: uses `eachFileRecurse` to traverse subdirectories.
   - Non-recursive mode: uses `eachFile` for the immediate directory only.

#### `private static void loadJarFiles(List<File> jarFiles, boolean isDefaultConfig)`

Loads discovered JAR files into the classloader chain.

| Parameter | Type | Description |
|---|---|---|
| `jarFiles` | `List<File>` | JAR files to load. |
| `isDefaultConfig` | `boolean` | Whether the current configuration is the default. |

**Behavior:**

1. If the list is empty, prints a message and returns.
2. Converts all `File` objects to `URL` objects via `file.toURI().toURL()`.
3. Stores the URL array in `Spaceport.config.stowaways.'jar urls'` for later use by `SourceStore`.
4. Creates a new `URLClassLoader` with all JAR URLs, using the current `Spaceport.classLoader` as the parent.
5. Replaces `Spaceport.classLoader` with the new `URLClassLoader`.


## Classloader Architecture

Stowaways use a layered classloader design to ensure JARs persist across source module reloads:

```
Thread Context ClassLoader (or Stowaways.class ClassLoader)
  └── URLClassLoader (stowaway JARs)        <-- Spaceport.classLoader
        └── GroovyClassLoader (source modules)   <-- Created by SourceStore
```

When source modules are reloaded, `SourceStore` creates a new `GroovyClassLoader` that uses `Spaceport.classLoader` (the stowaway URLClassLoader) as its parent. This means:

- Stowaway classes are always available to source modules via standard `import` statements.
- Stowaway JARs do not need to be reloaded when source modules change.
- The stowaway classloader is created once at startup and remains for the application lifecycle.

Additionally, `SourceStore` adds the stowaway JAR URLs directly to the `GroovyClassLoader` via `addURL()`. This ensures that the Groovy compiler can resolve classes from stowaway JARs during compilation, not just at runtime.


## Error Behavior Summary

| Condition | Default Config | Custom Config |
|---|---|---|
| Directory does not exist | Debug log, continue | Error, `System.exit(1)` |
| Path is not a directory | Error, `System.exit(1)` | Error, `System.exit(1)` |
| No JARs found in directories | Prints message, continue | Prints message, continue |
| Feature disabled | Debug log, skip | Debug log, skip |
| No paths defined | Debug log, skip | Debug log, skip |
