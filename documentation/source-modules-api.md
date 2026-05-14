# Source Modules API Reference

## Manifest Configuration

Source modules are configured in the `source modules` section of your Spaceport manifest file (`config.spaceport`).

### `source modules.paths`

**Type:** `List<String>`
**Default:** `['modules/*']`

A list of directory paths where Spaceport should look for `.groovy` source files. Each path is processed according to the path syntax rules described below.

```yaml
source modules:
  paths:
    - modules/*
```

Multiple paths can be specified:

```yaml
source modules:
  paths:
    - modules/*
    - lib/*
    - shared/utilities
```

### Path Syntax

Each entry in `paths` supports three features: wildcard scanning, root references, and relative/absolute resolution.

#### Wildcard Suffix (`*`)

Appending `*` to a path enables recursive subdirectory scanning. Without it, only files in the specified directory itself are loaded.

```yaml
# Scans modules/ AND all subdirectories (modules/data/, modules/app/, etc.)
source modules:
  paths:
    - modules/*
```

```yaml
# Scans ONLY modules/ — subdirectories are ignored
source modules:
  paths:
    - modules
```

When the wildcard is used, each subdirectory is added to the classloader's classpath, allowing files within subdirectories to declare Java package names matching their directory names. For example, a file at `modules/data/Job.groovy` can declare `package data`.

#### Root Reference (`/`)

A path of exactly `/` resolves to the Spaceport root directory (the directory containing the manifest file).

```yaml
# Load modules from the project root itself
source modules:
  paths:
    - /
```

#### Relative vs. Absolute Paths

Relative paths are resolved against the Spaceport root directory. Absolute paths are used as-is.

```yaml
source modules:
  paths:
    # Relative: resolved to <spaceport-root>/modules/
    - modules/*
    # Absolute: used verbatim
    - /opt/shared-modules/*
```

### Path Processing Details

Internally, each path entry produces two derived lists:

| Derived List | Purpose |
|---|---|
| `class paths` | Root directories added to the classloader's classpath. Enables package resolution. |
| `include paths` | All directories (root + subdirectories when `*` is used) scanned for `.groovy` files. |

For a path like `modules/*`, the class path is `modules/` and the include paths are `modules/`, `modules/app/`, `modules/data/`, and so on for every subdirectory found.

---

## Lifecycle Alerts

Source modules participate in a four-phase lifecycle managed by the [Alerts system](alerts-overview.md). These alerts fire during initial startup and on every hot-reload cycle.

### `on initialize`

Fires after all source module classes have been compiled and loaded, and after `@Alert` methods have been discovered and registered. Use this for setup tasks that other modules may depend on -- database creation, configuration validation, global state initialization.

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result

class App {

    @Alert('on initialize')
    static _init(Result r) {
        // Create databases, register views, set up integrations
    }

}
```

**Parameter type:** `Result`

### `on initialized`

Fires after `on initialize` has completed across all modules. Use this for setup that depends on other modules having already initialized -- querying views that were created during `on initialize`, building caches from data set up by other modules.

```groovy
@Alert('on initialized')
static _ready(Result r) {
    // Safe to assume all modules have completed 'on initialize'
}
```

**Parameter type:** `Result`

### `on deinitialize`

Fires at the beginning of a hot-reload cycle, before any classes are unloaded. Use this for teardown tasks -- closing connections, canceling timers, releasing resources.

```groovy
@Alert('on deinitialize')
static _shutdown(Result r) {
    // Clean up resources before reload
}
```

**Parameter type:** `Result`

This alert only fires during hot-reload (debug mode). It does not fire during initial startup, since there is nothing to deinitialize.

### `on deinitialized`

Fires after `on deinitialize` has completed across all modules, just before classes and classloaders are discarded. This is the last opportunity to perform cleanup.

```groovy
@Alert('on deinitialized')
static _cleanedUp(Result r) {
    // Final cleanup before classloader is released
}
```

**Parameter type:** `Result`

### Lifecycle Order

**Startup sequence:**

1. `.groovy` files compiled and loaded into `SpaceportClassLoader`
2. `AnnotatedAlert.loadStaticHooks()` discovers `@Alert` methods
3. `on initialize` fires
4. `on initialized` fires

**Hot-reload sequence (debug mode only):**

1. `on deinitialize` fires
2. `on deinitialized` fires
3. `AnnotatedAlert.releaseStaticHooks()` removes alert registrations
4. Classloader and all caches cleared
5. New `SpaceportClassLoader` created
6. `.groovy` files recompiled and loaded
7. `AnnotatedAlert.loadStaticHooks()` discovers `@Alert` methods
8. `on initialize` fires
9. `on initialized` fires

---

## SourceStore

`spaceport.engineering.SourceStore`

The `SourceStore` class manages the compilation and loading of source modules. It is an internal framework class, but understanding its public surface is useful for debugging and advanced use cases.

### Properties

#### `SourceStore.classLoader`

**Type:** `SpaceportClassLoader` (extends `GroovyClassLoader`)
**Access:** `static`

The classloader instance that holds all compiled source module classes. This is the primary way the framework accesses user-defined classes at runtime.

```groovy
// Access loaded classes programmatically
def classes = SourceStore.classLoader.loadedClasses
```

After a hot-reload, this property points to a new classloader instance. The previous classloader and all its classes are discarded.

#### `SourceStore.hasLoaded`

**Type:** `boolean`
**Access:** `static`
**Default:** `false`

Indicates whether source modules have been loaded at least once. Set to `true` after the first call to `scan()`. Used internally to determine whether a deinitialize cycle is needed before loading.

### Methods

#### `SourceStore.scan()`

```groovy
static void scan()
```

Compiles and loads all source modules. If modules have already been loaded (`hasLoaded == true`), performs a full deinitialize-and-reload cycle:

1. Fires `on deinitialize` and `on deinitialized` alerts
2. Releases all alert hooks from the previous load
3. Clears the classloader cache, template cache, element cache, and document cache
4. Creates a new `SpaceportClassLoader`
5. Processes module paths from the manifest configuration
6. Compiles all `.groovy` files found in include paths
7. Adds stowaway JAR URLs to the classloader (if configured)
8. Runs `AnnotatedAlert.loadStaticHooks()` to discover `@Alert` methods
9. Fires `on initialize` and `on initialized` alerts

This method is called once during `Spaceport.main()` startup, and again by the file watcher on every hot-reload in debug mode.

**Errors:** Compilation errors are printed to the console but do not halt the server. Modules that fail to compile are skipped, and the remaining modules continue to load.

---

## Beacon (File Watcher)

`spaceport.engineering.Beacon`

The `Beacon` class provides file system watching for hot-reload support. It wraps Java NIO's `WatchService` to monitor directories for changes. Beacons are created automatically by `SourceStore` when running in debug mode.

### Constructor

```groovy
Beacon(Path dir, Closure action)
```

Creates a new file watcher on the given directory and all its subdirectories.

| Parameter | Type | Description |
|---|---|---|
| `dir` | `java.nio.file.Path` | Root directory to watch. All subdirectories are registered recursively. |
| `action` | `Closure` | Called on file events. Receives `(Path path, WatchEvent.Kind kind)`. |

The watcher runs on a background thread and begins watching immediately after construction. The `Beacon` instance is automatically added to the static `watchers` list.

**Throws:** `IOException` if the directory does not exist or cannot be watched. `IllegalArgumentException` if the path does not exist.

**Watched events:** `ENTRY_CREATE`, `ENTRY_DELETE`, `ENTRY_MODIFY`. `OVERFLOW` events are ignored.

### Static Properties

#### `Beacon.watchers`

**Type:** `List<Beacon>`
**Access:** `static`

All active Beacon instances. Used internally to stop all watchers during deinitialization.

### Instance Methods

#### `stop()`

```groovy
void stop()
```

Stops the file watcher. Cancels all registered watch keys and closes the underlying `WatchService`. The watcher thread exits after this call. Called automatically during `on deinitialized` for all active beacons.

### Automatic Lifecycle

Beacons are self-managing:

- **Created** by `SourceStore._init()` during `on initialized`, one per class path in the manifest configuration. Only created when `Spaceport.inDebugMode` is `true`.
- **Stopped** by `Beacon._deinit()` during `on deinitialized`. All watchers in the static `watchers` list are stopped.

When a Beacon detects a file change, it triggers `SourceStore.scan()` after a 100-millisecond debounce. Multiple rapid file changes (such as saving several files at once) are coalesced into a single reload.

---

## Debug Mode

Debug mode enables hot-reloading and is controlled by the manifest:

```yaml
debug: true
```

When debug mode is active:

- `Beacon` file watchers are created for each module path.
- File changes trigger automatic `SourceStore.scan()` calls.
- The debounce interval is 100 milliseconds.
- A `reloading` flag prevents concurrent reload operations.

When debug mode is inactive (the default for production):

- No file watchers are created.
- Modules are loaded once at startup and never reloaded.
- `SourceStore.scan()` can still be called programmatically, but this is not standard practice.

---

## SpaceportClassLoader

`spaceport.engineering.SpaceportClassLoader`

A customized `GroovyClassLoader` that handles source module compilation. Key behaviors:

| Behavior | Description |
|---|---|
| Duplicate class handling | Catches `LinkageError` for duplicate class definitions and returns the cached version instead of failing. |
| Template file handling | Files containing `_ghtml` in their name are never cached in the source cache, ensuring templates are always recompiled on access. |
| Shared classloader | All modules are compiled into a single classloader, enabling cross-module references without explicit dependency wiring. |
| Stowaway JAR support | Stowaway JAR URLs from the manifest are added to the classloader, making their classes available to source modules via standard `import` statements. |

### `clearCache()`

```groovy
void clearCache()
```

Clears the class cache and source cache. Also calls `InvokerHelper.removeClass()` on each cleared class to purge Groovy metaclass registrations. Called by `SourceStore.scan()` during the reload cycle.

---

## ClassScanner

`spaceport.computer.utils.ClassScanner`

Aggregates classes from both the Spaceport framework and user source modules.

### `getLoadedClasses()`

```groovy
static List<Class> getLoadedClasses()
```

Returns a combined list of all loaded classes:

1. All classes in the `spaceport` package (framework classes, loaded via the context classloader using Guava's `ClassPath`).
2. All classes loaded by `SourceStore.classLoader` (user source modules).

This method is used internally by `AnnotatedAlert.loadStaticHooks()` to discover `@Alert`-annotated methods across both framework and user code.

---

## See Also

- [Source Modules Overview](source-modules-overview.md) -- high-level introduction
- [Source Modules Internals](source-modules-internals.md) -- implementation deep-dive
- [Source Modules Examples](source-modules-examples.md) -- real-world patterns
- [Alerts API Reference](alerts-api.md) -- the `@Alert` annotation and event system
