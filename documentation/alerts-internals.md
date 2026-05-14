# Alerts (Event System) — Internals

This document covers the internal implementation of Spaceport's Alerts system. It is intended for contributors and advanced users who need to understand how the dispatcher works, debug handler registration issues, or extend the system.

## Architecture

The Alerts system has four core components:

```
┌─────────────────────────────────────────────────┐
│                  Alerts (Dispatcher)             │
│  - static List<WeakReference<AlertType>>         │
│  - invoke(hookString, context) → Result          │
│  - invokeAll(hookStrings, context) → Result      │
│  - notifyOf(AlertType) [synchronized]            │
└───────────────┬──────────────────────────────────┘
                │ registers
┌───────────────┴──────────────────────────────────┐
│              AlertType (Abstract Base)            │
│  - hookString, priority, compiledPattern         │
│  - buzz(result) [abstract]                       │
│  - dispose() [abstract]                          │
└───────────────┬──────────────────────────────────┘
                │ extends
┌───────────────┴──────────────────────────────────┐
│           AnnotatedAlert (Runtime Handler)        │
│  - className, methodName, Method m               │
│  - loadStaticHooks() / releaseStaticHooks()      │
│  - buzz(result) → Method.invoke(null, result)    │
└──────────────────────────────────────────────────┘
```

## Handler Discovery and Registration

### Class Scanning

When source modules are loaded, `AnnotatedAlert.loadStaticHooks()` is called. This method:

1. Calls `ClassScanner.getLoadedClasses()` to get **all** classes loaded by Spaceport — both framework classes and user application classes.
2. Iterates every class, checking each method for the `@Alert` annotation.
3. For each annotated method, creates an `AnnotatedAlert` instance with the event string, priority, class name, and method name.

### Self-Registration

The `AlertType` constructor calls `Alerts.notifyOf(this)`, which is `synchronized`. This method:

1. Adds the new handler as a `WeakReference` to the `registered` list.
2. Removes any `null` weak references (handlers that were garbage collected).
3. Sorts the entire list by priority (descending — higher priority values first).

This self-registration pattern means creating an `AlertType` instance automatically registers it. There is no separate "register" call.

### Static Hook Management

`AnnotatedAlert` maintains a static list (`staticHooks`) of all annotation-discovered handlers. This enables:

- `loadStaticHooks()` — Scans classes, creates instances, adds to `staticHooks`
- `releaseStaticHooks()` — Calls `dispose()` on each handler (sets `disabled = true`, nullifies the `Method` reference), then clears the list

These are called during hot-reload to tear down and rebuild the handler registry.

## Event Dispatch

### `Alerts.invoke(hookString, context)`

1. **Result creation:** Checks if `context.resultType` is a `Class`. If so, instantiates that class via `newInstance(context)`. Otherwise, creates a `new Result(context)`.
2. **Handler iteration:** Walks the `registered` list in priority order (already sorted).
3. **Cancellation check:** Before each handler, checks `result.cancelled`. If true, stops immediately.
4. **Matching:**
   - If the handler has a `compiledPattern` (regex hook), uses `Matcher.matches()` against the full hookString. On match, extracts capture groups into `result.matches`.
   - Otherwise, uses `hookString.equalsIgnoreCase()` for case-insensitive string comparison.
5. **Execution:** Calls `handler.buzz(result)`. For `AnnotatedAlert`, this invokes the static method via reflection: `m.invoke(null, result)`.
6. **Error handling:** Each handler invocation is wrapped in try-catch. Exceptions are printed to stderr but do **not** propagate or cancel the chain.
7. **Called flag:** `result.called` is set to `true` when at least one handler matches and executes.
8. **Return:** Returns the mutated Result.

### `Alerts.invokeAll(hookStrings, context)`

Works like `invoke` but for each handler, checks it against **every** hookString in the list. This is used by the HTTP handler to fire both `"on /path hit"` and `"on /path GET"` in a single pass.

The key difference from calling `invoke` twice: the **global priority order is preserved** across both event names. A priority-100 handler for `"on /path hit"` will run before a priority-0 handler for `"on /path GET"`.

### HTTP Request Flow

The `HttpRequestHandler` (Jetty handler) processes each request as:

1. Builds an `HttpContext` with request, response, parsed data, cookies, headers, method, target, dock, client, and `resultType = HttpResult.class`.
2. Calls `Alerts.invokeAll(['on <target> hit', 'on <target> <METHOD>'], context)`.
3. If `result.called` is false (no handler matched), sets response status to 404.
4. Calls `Alerts.invoke('on page hit', context)` — the catch-all that fires on **every** request, even after errors.

### WebSocket Message Flow

The `SocketHandler.onWebSocketText()` method:

1. Parses the incoming text as JSON.
2. Silently drops heartbeat messages (handler-id `"launchpad-socket"` with `"pong"` data).
3. Builds a `SocketContext` with handler, session, data, handlerId, dock, client, and `resultType = SocketResult.class`.
4. **Spawns a new thread** for message processing.
5. In the new thread: calls `Alerts.invoke('on socket data', context)`.
6. If not cancelled, calls `Alerts.invoke('on socket <handler-id>', context)`.

WebSocket lifecycle events (`on socket connect`, `on socket closed`) use `SocketLifecycleContext` (no `handlerId` or `data`) and run on the WebSocket event thread (no separate thread).

## Threading Model

| Event Type | Thread | Notes |
|---|---|---|
| HTTP alerts | Jetty handler thread | Synchronous. Handler blocks until all alerts complete. |
| `on page hit` | Jetty handler thread | Same thread, runs after route-specific alerts. |
| WebSocket message alerts | New thread per message | `new Thread({ ... }).start()`. Each message gets its own thread. |
| WebSocket lifecycle | WebSocket event thread | `on socket connect` and `on socket closed` run directly on the event thread. |
| `on document save` | Caller's thread | Synchronous. Runs before the save is committed. |
| `on document saved` | New thread | `Thread.start { ... }`. Runs after save completes. |
| `on document modified` | Caller's thread | Synchronous. Runs after save/remove. |
| Lifecycle events | Main/scanner thread | `on initialize`, `on deinitialize`, etc. |

**Important:** `Alerts.invoke()` and `Alerts.invokeAll()` are **not synchronized**. They read the `registered` list without locking. Only `Alerts.notifyOf()` (registration) is synchronized. This means handler registration during dispatch is theoretically unsafe, but in practice, registration only happens at startup and during hot-reload (which tears everything down first).

## Weak References

Handlers are stored as `WeakReference<AlertType>` in the `registered` list. This design allows handlers to be garbage collected if nothing else references them.

**Implications:**

- If you create an `AlertType` subclass manually (not via `@Alert` annotation), you must keep a strong reference to it — otherwise it may be GC'd and silently stop working.
- `AnnotatedAlert.staticHooks` holds strong references to all annotation-discovered handlers, preventing them from being collected during normal operation.
- During `releaseStaticHooks()`, the strong references are cleared, allowing GC. The weak references in `Alerts.registered` will become null and be cleaned up on the next `notifyOf()` call.
- Null weak references are cleaned up during registration (`notifyOf`), not during dispatch. Stale entries in the list during dispatch are effectively no-ops.

## Regex Compilation

When an `AlertType` is created with a hookString starting with `~`:

1. The `~` prefix is stripped.
2. All spaces in the pattern are replaced with `\s`.
3. The pattern is compiled as a case-insensitive `java.util.regex.Pattern` (`Pattern.CASE_INSENSITIVE`).
4. The compiled pattern is stored in `compiledPattern` (final field).

During dispatch, `compiledPattern.matcher(hookString).matches()` is called. This is a **full-string match** — the pattern must match the entire event string, not just a substring.

Patterns are compiled once at registration time. There is no additional caching layer.

## Hot-Reload Lifecycle

In debug mode, Spaceport watches source module files for changes via `Beacon` (a file watcher). When a change is detected:

1. `Alerts.invoke('on deinitialize', [:])` — Handlers can clean up resources.
2. `Alerts.invoke('on deinitialized', [:])` — Final teardown notification.
3. `AnnotatedAlert.releaseStaticHooks()` — All annotation-discovered handlers are disposed (disabled, method references nullified) and the static hooks list is cleared.
4. Class loaders and caches are cleared.
5. Source files are re-parsed into new class loaders.
6. `AnnotatedAlert.loadStaticHooks()` — Classes are re-scanned for `@Alert` annotations.
7. `Alerts.invoke('on initialize', [:])` — Handlers run initialization logic.
8. `Alerts.invoke('on initialized', [:])` — Application is ready again.

This means the **entire alert registry is rebuilt** on every hot-reload. There is no incremental update.

## Framework-Internal Handlers

These `@Alert` handlers are defined within the Spaceport framework itself:

| Class | Event | Purpose |
|---|---|---|
| `SourceStore` | `on initialized` | Sets up file watchers for hot-reload (debug mode) |
| `Beacon` | `on deinitialized` | Stops all file watchers |
| `Launchpad` | `on initialize` | Sets up template file watchers (debug mode) |
| `Launchpad` | `on /!/lp/bind hit` | Handles Launchpad reactive binding HTTP endpoints |
| `Launchpad` | `on /!/lp/bind/u/ hit` | Handles Launchpad binding unregistration |
| `Launchpad` | `on socket launchpad-socket` | Handles Launchpad WebSocket registration and reactive element updates |
| `ClientRegistry` | `on initialize` | Registers CouchDB views for authentication |
| `Cargo` | `on document saved` | Synchronizes document changes to in-memory Cargo objects |

These handlers use default priority (0), so user handlers with higher priority will run before them.

## Error Handling

Exceptions thrown inside a handler's `buzz()` method are caught and printed to stderr. They do **not**:

- Propagate to the caller
- Cancel the handler chain
- Affect subsequent handlers

This means a buggy handler cannot break the dispatch loop. However, it also means errors can be silently swallowed if you're not watching stderr.

`AnnotatedAlert.buzz()` specifically catches `IllegalArgumentException` separately (for method signature mismatches) and prints a distinct error message.
