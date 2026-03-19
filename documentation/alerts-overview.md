# Alerts (Event System) — Overview

Alerts are the backbone of Spaceport. Every HTTP request, every WebSocket message, every document save, and every application lifecycle event flows through the Alerts system. If something happens in a Spaceport application, an Alert made it happen.

## What Are Alerts?

Alerts are Spaceport's publish-subscribe event system. The framework broadcasts named events (like `"on /login hit"` or `"on document saved"`), and your code listens for them using the `@Alert` annotation on static methods.

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class MyRouter {

    @Alert('on /hello hit')
    static _hello(HttpResult r) {
        r.writeToClient('Hello, world!')
    }
}
```

That's it — no registration, no configuration files, no router setup. Place this class in a source module, and Spaceport discovers it automatically.

## Why Alerts Instead of a Router?

Most web frameworks separate routing from event handling. Spaceport unifies them. HTTP routing, WebSocket messaging, document lifecycle hooks, and custom application events all use the same mechanism: `@Alert`.

This means:

- **One pattern to learn.** Whether you're handling a GET request, reacting to a document save, or running code at startup, the syntax is identical.
- **Decoupled architecture.** Multiple handlers can listen to the same event. A notification module can react to document saves without the document module knowing it exists.
- **Priority control.** Every handler has a priority. Authentication middleware runs before route handlers. Logging runs after. You control the order.

## The Alert Lifecycle

1. **Discovery** — When your application starts (or hot-reloads in debug mode), Spaceport scans all source module classes for methods annotated with `@Alert`.
2. **Registration** — Each discovered handler is registered with the central dispatcher, sorted by priority.
3. **Dispatch** — When an event occurs, the dispatcher iterates all matching handlers in priority order, passing a shared Result object.
4. **Response** — Handlers read from and write to the Result object. For HTTP alerts, this means reading request data and writing the response.

## Event Strings

Every alert matches on an **event string** — a human-readable description of what happened:

| Event String | What It Matches |
|---|---|
| `'on / hit'` | HTTP request to `/` |
| `'on /login POST'` | POST request to `/login` |
| `'on initialized'` | Application finished starting up |
| `'on document saved'` | Any document was saved to CouchDB |
| `'on page hit'` | Any HTTP request (catch-all, runs after specific routes) |
| `'on socket connect'` | A WebSocket connection was opened |

Event string matching is **case-insensitive**. `'on /Login HIT'` matches the same events as `'on /login hit'`.

## Regex Matching

For dynamic routes, prefix the event string with `~` to enable regex matching:

```groovy
@Alert('~on /users/(.*) hit')
static _userProfile(HttpResult r) {
    def userId = r.matches[0]
    // userId contains the captured group
}
```

Capture groups are available in `r.matches` (0-indexed). This is how you handle parameterized URLs.

## Result Types

The parameter type on your handler method determines what API you have access to:

| Result Type | Used For | Key Capabilities |
|---|---|---|
| `HttpResult` | HTTP route handlers | Read request data, write response, set headers, cookies, redirects |
| `SocketResult` | WebSocket message handlers | Read message data, send messages back, close connection |
| `Result` | Lifecycle events, document events | Access event context, cancel the event chain |

## Priority

Handlers execute in priority order (highest first). Default priority is `0`.

```groovy
@Alert(value = 'on /admin hit', priority = 100)
static _authCheck(HttpResult r) {
    if (!r.client.authenticated) {
        r.setRedirectUrl('/login')
        r.cancelled = true  // Stop further handlers from running
    }
}

@Alert('on /admin hit')  // priority = 0 (default)
static _adminPage(HttpResult r) {
    // Only runs if auth check above didn't cancel
    launchpad.assemble(['admin.ghtml']).launch(r)
}
```

**Suggested priority ranges:**

| Range | Purpose |
|---|---|
| 100+ | Security / authentication middleware |
| 1–99 | Pre-processing (logging, CORS headers) |
| 0 | Normal route handlers (default) |
| -1 to -99 | Post-processing |
| -100 or below | Fallback / catch-all handlers |

## Cancellation

Setting `r.cancelled = true` stops the handler chain. No further handlers for that event will execute. This is the primary mechanism for authentication guards and middleware short-circuiting.

## Common Patterns

### Route Handling

```groovy
@Alert('on /index.html hit')
static _index(HttpResult r) {
    launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
}
```

### Application Initialization

```groovy
@Alert('on initialized')
static _init(Result r) {
    // Set up CouchDB databases and views
    if (!Spaceport.main_memory_core.containsDatabase('myapp'))
        Spaceport.main_memory_core.createDatabase('myapp')
}
```

### Reacting to Document Saves

```groovy
@Alert('on document saved')
static _onSaved(Result r) {
    if (r.context.document.type != 'order') return
    // Send notification email when an order is saved
}
```

### Catch-All Error Pages

```groovy
@Alert('on page hit')
static _errorPages(HttpResult r) {
    if (r.context.response.status >= 400) {
        launchpad.assemble(['error.ghtml']).launch(r)
    }
}
```

## What's Next

- **[Alerts API Reference](alerts-api.md)** — Complete method signatures for `HttpResult`, `SocketResult`, `Result`, and the `@Alert` annotation.
- **[Alerts Internals](alerts-internals.md)** — How the dispatcher works, threading model, weak references, and hot-reload behavior.
- **[Alerts Examples](alerts-examples.md)** — Real-world patterns from production Spaceport applications.
