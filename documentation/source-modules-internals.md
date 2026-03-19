# Source Modules Internals

This document describes how Spaceport's source module system works under the hood. It covers the class loading mechanism, the scan-and-load pipeline, hot-reload lifecycle, file watching, and cache management. This is intended for contributors and advanced users who need to debug, extend, or deeply understand the framework.

## Class Loading Architecture

Source modules are compiled and loaded through a two-level classloader hierarchy.

### SpaceportClassLoader

`SpaceportClassLoader` extends Groovy's `GroovyClassLoader` and serves as the primary classloader for all user source modules. It is created fresh on every load cycle (initial startup and every hot-reload).

```
Spaceport.classLoader (parent)
  └── SpaceportClassLoader (source modules)
        └── SpaceportInnerLoader (class definitions)
```

The parent classloader is `Spaceport.classLoader`, which is the classloader that loaded the Spaceport framework itself. This parent relationship means source modules can reference all framework classes (anything in the `spaceport` package) via standard imports.

Key design decisions in `SpaceportClassLoader`:

**Single shared classloader.** All source modules are compiled into one `SpaceportClassLoader` instance. There is no per-module isolation. This is intentional -- it allows any module to reference any other module's classes without explicit dependency wiring or import resolution. Cross-module references are resolved at class loading time through the standard Java classloader delegation model.

**Duplicate class handling.** When a class has already been defined (a `LinkageError`), the classloader catches the error and returns the previously cached version. This can happen when multiple files define the same class name, or during hot-reload edge cases. The recovery logic extracts the class name from the error message and searches both `SpaceportClassLoader.loadedClasses` and `SpaceportInnerLoader.loadedClasses` for a match.

**Template exclusion.** Files with `_ghtml` in their name (compiled Launchpad templates) are never cached in the source cache. They use a separate code path that always recompiles the template, ensuring that template changes are reflected immediately without requiring a full module reload.

### SpaceportInnerLoader

`SpaceportInnerLoader` extends `GroovyClassLoader.InnerLoader` and handles the actual class definition (bytecode to `Class` object). A single static instance is shared across all source module compilations within a load cycle.

```groovy
class SpaceportInnerLoader extends GroovyClassLoader.InnerLoader {
    static InnerLoaders = [:]
    SpaceportClassLoader delegate
    long timeStamp
}
```

The `InnerLoader` pattern is inherited from Groovy's classloader design. In standard `GroovyClassLoader`, a new `InnerLoader` is created for each compilation unit, which enables class unloading (since a class can only be garbage collected when its defining classloader is unreachable). Spaceport modifies this by reusing a single `SpaceportInnerLoader` instance, prioritizing simplicity over fine-grained class unloading. Full classloader disposal happens during hot-reload instead.

During a reload, `SpaceportClassLoader.innerLoader` is set to `null`, and the entire `SpaceportClassLoader` is replaced, allowing both the outer and inner loaders (and all their defined classes) to be garbage collected.

---

## The `scan()` Pipeline

`SourceStore.scan()` is the central method that orchestrates module loading. It runs during initial startup (called from `Spaceport.main()`) and on every hot-reload. The pipeline has two major phases: teardown (only on reload) and build.

### Phase 1: Teardown (reload only)

When `SourceStore.hasLoaded` is `true`, a full teardown occurs before loading new classes:

```
1. Fire 'on deinitialize' alert
2. Fire 'on deinitialized' alert
3. AnnotatedAlert.releaseStaticHooks()    -- remove all @Alert registrations from user modules
4. classLoader.clearCache()               -- purge class cache + metaclass registrations
5. SpaceportClassLoader.innerLoader = null -- release inner loader reference
6. classLoader = null                     -- release outer loader reference
7. SpaceportTemplateEngine.templateCache.clear()  -- purge compiled template cache
8. Launchpad.elements.clear()             -- purge server element cache
9. CouchHandler.cachedDocuments.clear()   -- purge document cache
```

Steps 1-2 give user modules a chance to clean up (close connections, cancel timers, release resources). Steps 3-6 dismantle the classloader hierarchy. Steps 7-9 clear caches that may hold references to old class definitions.

The order matters. Alert handlers run first (while classes are still loaded and functional), then hooks are released (so the alert system forgets about user module methods), and finally the classloader and caches are cleared.

### Phase 2: Build

A new classloader is created and populated:

```
1.  classLoader = new SpaceportClassLoader()
2.  hasLoaded = true
3.  Add stowaway JAR URLs to classLoader (if configured)
4.  Parse 'source modules.paths' from manifest
5.  For each path entry:
      a. Check for '*' suffix (subdirectory scanning)
      b. Resolve '/' to spaceport root
      c. Resolve relative paths against spaceport root
      d. Add to 'class paths' list
      e. Add to 'include paths' list
      f. If wildcard: scan for subdirectories, add each to 'include paths'
6.  Add all class paths to classLoader.addClasspath()
7.  For each include path:
      a. List all files in directory (non-recursive)
      b. For each .groovy file: classLoader.parseClass(file)
8.  AnnotatedAlert.loadStaticHooks()      -- discover @Alert methods in loaded classes
9.  Fire 'on initialize' alert
10. Fire 'on initialized' alert
```

### Path Resolution Detail

Step 5 transforms the user-facing `paths` configuration into two internal lists:

- **`class paths`**: The root directories where packages begin. Added to the classloader with `addClasspath()` so that `package data` in a file at `modules/data/Job.groovy` resolves correctly. Each unique root path appears once.

- **`include paths`**: All directories to scan for `.groovy` files. When the `*` wildcard is used, this includes the root path plus every subdirectory found by `FileUtils.listFilesAndDirs()`. Without the wildcard, only the root path itself is included.

The subdirectory scan uses Apache Commons IO `FileUtils.listFilesAndDirs()` with a custom `IOFileFilter` that accepts only directories. The `null` parameter for the directory filter means only the immediate children are returned (no recursive directory filter), but the `IOFileFilter.accept(File)` method checks `file.isDirectory()`, which along with the way `listFilesAndDirs` works, captures the full directory tree.

### File Compilation

For each `.groovy` file in each include path, `classLoader.parseClass(file)` is called. This triggers Groovy's compilation pipeline:

1. The file is read and parsed into a Groovy AST.
2. The AST is compiled to JVM bytecode.
3. `SpaceportClassLoader.createCollector()` is called, which creates a `ClassCollector` backed by the shared `SpaceportInnerLoader`.
4. The `ClassCollector` calls `defineClass()` on the `SpaceportInnerLoader`, producing a `Class` object.
5. The class is added to the classloader's class cache and source cache.

If compilation fails (syntax errors, missing imports), the error is printed to the console and that file is skipped. Other files continue to load. The `errors` flag is set, and a warning message is displayed after all files have been processed.

`LinkageError` exceptions (duplicate class definitions) are caught and silently ignored -- this handles cases where a class is defined by multiple files or re-encountered during compilation of dependent classes.

### No Dependency Resolution

Spaceport does not perform dependency resolution between source modules. Files are loaded in the order returned by the filesystem listing (`FileUtils.listFiles()`), which is typically alphabetical but is not guaranteed by the operating system.

Cross-module references are resolved lazily by the classloader at the point of first use, not at compile time. When class `A` references class `B`, and `B` has not yet been compiled, the classloader's `findClass()` or `loadClass()` method will search the source cache and class cache. If `B` was already compiled (because its file appeared earlier in the filesystem listing), it is found immediately. If not, Groovy's compilation pipeline may trigger on-demand compilation.

In practice, this means the load order does not matter for correctness. However, if a module's `@Alert('on initialize')` handler depends on another module's class being fully loaded, both classes must be compiled before the alert fires -- and since all compilation happens before any alerts fire, this is always the case.

---

## Hot-Reload Lifecycle

Hot-reload is enabled when `debug: true` is set in the manifest. The mechanism involves two components: the `Beacon` file watcher and the `SourceStore.scan()` method.

### Trigger Chain

```
File saved on disk
  → OS notifies WatchService (ENTRY_MODIFY event)
    → Beacon.watch() loop receives WatchKey
      → Beacon calls action closure with (path, kind)
        → SourceStore._init closure checks reloading flag
          → If not already reloading:
            → Set reloading = true
            → Start new thread
              → Sleep 100ms (debounce)
              → Call SourceStore.scan()
              → Set reloading = false
```

### Debounce Mechanism

The 100-millisecond debounce prevents cascading reloads when multiple files are saved in quick succession (for example, when an IDE reformats several files, or when a git checkout modifies many files at once).

The `reloading` flag is a simple boolean guard. If a file change event arrives while `reloading` is `true`, the event is silently discarded. This means that changes occurring during the 100ms window are not tracked individually -- instead, the subsequent `scan()` call reloads all files from disk, picking up whatever state they are in at that moment.

### Concurrency

The reload runs on a new `Thread.start{}` thread, separate from both the Beacon watcher thread and the HTTP server threads. During reload:

- HTTP requests that are currently being processed continue on their existing threads with their existing class references.
- New HTTP requests arriving during reload will use whatever classes are available in the classloader at the time. If the classloader has been nulled but not yet replaced, this may cause errors. In practice, the reload completes quickly enough that this is rarely observed.
- The `reloading` flag is not synchronized. It uses simple volatile-like semantics from Groovy's `static def`, which is sufficient for the single-writer (watcher thread) pattern.

### What Gets Cleared

During a hot-reload, five distinct caches are cleared:

| Cache | Location | Contents |
|---|---|---|
| Class cache | `SpaceportClassLoader.classCache` | Compiled class objects keyed by name |
| Source cache | `SpaceportClassLoader.sourceCache` | Compiled class objects keyed by source file |
| Template cache | `SpaceportTemplateEngine.templateCache` | Compiled `.ghtml` template classes |
| Element cache | `Launchpad.elements` | Server element instances |
| Document cache | `CouchHandler.cachedDocuments` | Document objects loaded from CouchDB |

All five caches must be cleared because they may hold references to old class definitions. If the template cache retained a compiled template that references `documents.Guestbook`, and the `Guestbook` class was reloaded into a new classloader, the template would hold a stale class reference, leading to `ClassCastException` at runtime.

### Metaclass Cleanup

`SpaceportClassLoader.clearCache()` calls `InvokerHelper.removeClass()` on every class being removed from the class cache. This purges Groovy's metaclass registry, which maintains per-class metaclass objects used for dynamic method dispatch. Without this cleanup, metaclass entries from the old classloader would accumulate, creating a memory leak.

---

## Beacon File Watcher

`Beacon` wraps Java NIO's `WatchService` API to detect file system changes.

### Architecture

Each `Beacon` instance manages:

- A `WatchService` obtained from `FileSystems.getDefault().newWatchService()`
- A `Map<WatchKey, Path>` mapping registered watch keys to their directories
- A `Closure` action to invoke on file events
- A background thread running the `watch()` loop
- A `done` flag for clean shutdown

### Directory Registration

On construction, `Beacon.scan(Path)` walks the directory tree using `Files.walkFileTree()` with a `SimpleFileVisitor`. Each directory encountered (including the root) is registered with the `WatchService` for `ENTRY_CREATE`, `ENTRY_DELETE`, and `ENTRY_MODIFY` events. The resulting `WatchKey` is stored in the `keys` map.

This means the watcher covers the root directory and all subdirectories at the time of construction. However, new subdirectories created after construction are not automatically watched. For source module hot-reload, this is acceptable because `SourceStore.scan()` re-scans the full directory tree on every reload.

### Watch Loop

The `watch()` method runs in a blocking loop:

```
while (!done):
  key = watcher.take()           // Blocks until an event occurs
  dir = keys.get(key)            // Look up the directory for this key
  if dir is null: continue       // Stale key, skip

  for event in key.pollEvents():
    if event.kind == OVERFLOW: continue
    child = dir.resolve(event.context())
    action(child, event.kind)    // Invoke the callback

  if !key.reset():               // Re-arm the watch key
    keys.remove(key)             // Key is no longer valid (directory deleted?)
    if keys.isEmpty(): break     // No directories left to watch
```

The `take()` call blocks the thread until an event is available, so the watcher thread consumes no CPU while idle.

### Shutdown

`Beacon.stop()` performs three actions:

1. Cancels all registered `WatchKey` instances.
2. Sets `done = true` to exit the watch loop.
3. Closes the `WatchService`, which causes any blocked `take()` call to throw `ClosedWatchServiceException`, caught by the watch loop to exit cleanly.

All active beacons are stopped during `on deinitialized` by `Beacon._deinit()`, which iterates `Beacon.watchers` and calls `stop()` on each.

### Beacon Lifecycle

Beacons for source module watching are created by `SourceStore._init()`, which is itself an `@Alert('on initialized')` handler. This means beacons are recreated after every hot-reload:

1. Old beacons stopped during `on deinitialized` (before reload)
2. Modules reloaded
3. `on initialized` fires
4. `SourceStore._init()` creates new beacons for each class path

This ensures that if the module path configuration changes, the new beacons watch the correct directories.

---

## ClassScanner and Alert Discovery

`ClassScanner.getLoadedClasses()` returns a unified list of all classes available in the system -- both framework classes and user source modules. This list is consumed by `AnnotatedAlert.loadStaticHooks()` to discover `@Alert`-annotated static methods.

### How It Works

```groovy
static List<Class> getLoadedClasses() {
    List<Class> classes = []
    def loader = Thread.currentThread().getContextClassLoader()
    classes.addAll(
        ClassPath.from(loader)
            .getTopLevelClassesRecursive('spaceport')
            .toList().collect({ item -> item.load() })
    )
    classes.addAll(SourceStore.classLoader.loadedClasses)
    return classes
}
```

Two sources are combined:

1. **Framework classes**: Google Guava's `ClassPath.from()` scans the context classloader for all classes in the `spaceport` package. This picks up framework-defined `@Alert` handlers (like `SourceStore._init` and `Beacon._deinit`).

2. **User module classes**: `SourceStore.classLoader.loadedClasses` returns all classes compiled by the `SpaceportClassLoader`. This is a method inherited from `GroovyClassLoader` that returns classes loaded through `parseClass()`.

The combined list is passed to `AnnotatedAlert.loadStaticHooks()`, which uses reflection to find every static method annotated with `@Alert`, extract the alert string, and register it with the alert system.

### Timing

`ClassScanner.getLoadedClasses()` is called during step 8 of the build phase (after all files are compiled, before any alerts fire). This means:

- All user classes are available for scanning.
- No `@Alert` methods have been registered yet (for user modules).
- Framework `@Alert` methods from the `spaceport` package are re-registered alongside user methods.

---

## Garbage Collection and Memory

### Classloader Disposal

When `SourceStore.scan()` nulls out `classLoader` and `innerLoader`, the old classloader and all its defined classes become eligible for garbage collection -- provided no other references exist. The cache-clearing steps (template cache, element cache, document cache) are specifically designed to break references that would prevent GC.

However, if user code stores class references in non-module locations (for example, in a `Cargo` container or a framework-managed cache), those references will keep the old classloader alive, creating a classloader leak. Each leaked classloader retains all its classes and their static fields in memory.

### Weak References

The standard `GroovyClassLoader.InnerLoader` uses weak references for class lookups to enable individual class GC. Spaceport's `SpaceportInnerLoader` inherits this behavior but effectively bypasses it by reusing a single inner loader instance. Class-level GC is not supported; instead, the entire classloader is replaced as a unit during hot-reload.

### Practical Implications

- **Development (debug mode)**: Each hot-reload creates a new classloader. Frequent reloads over long development sessions can increase memory pressure if classloader leaks exist. Restarting the server periodically is a practical mitigation.
- **Production**: The classloader is created once at startup and never replaced. Memory characteristics are stable and predictable.

---

## See Also

- [Source Modules Overview](source-modules-overview.md) -- high-level introduction
- [Source Modules API Reference](source-modules-api.md) -- configuration and public APIs
- [Source Modules Examples](source-modules-examples.md) -- real-world patterns
- [Alerts Internals](alerts-internals.md) -- how `AnnotatedAlert.loadStaticHooks()` works
