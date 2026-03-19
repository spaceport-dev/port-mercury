# Alerts (Event System) — API Reference

## `@Alert` Annotation

```groovy
import spaceport.computer.alerts.Alert
```

Marks a **static method** as an event handler. The method is discovered automatically when source modules are loaded.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `value` | `String` | *(required)* | The event string to match. Prefix with `~` for regex matching. |
| `priority` | `int` | `0` | Execution order. Higher values execute first. |

**Requirements:**
- The annotated method must be `static`
- The method must accept exactly one parameter: a `Result` subclass (`HttpResult`, `SocketResult`, or `Result`)
- The class must be in a configured source module path

**Example:**

```groovy
@Alert(value = 'on /api/data GET', priority = 10)
static _getData(HttpResult r) {
    r.writeToClient([status: 'ok'])
}
```

---

## `Alerts` Class (Central Dispatcher)

```groovy
import spaceport.computer.Alerts
```

Static utility class. You rarely call this directly — the framework invokes it for built-in events. Use it for custom event dispatching.

### `Alerts.invoke(String hookString, def context)`

Broadcasts a named event to all matching handlers.

| Parameter | Type | Description |
|---|---|---|
| `hookString` | `String` | The event name to broadcast |
| `context` | `Object` | Context data passed to handlers via `result.context`. If this object has a `resultType` property set to a `Class`, that class is instantiated as the Result (e.g., `HttpResult`). Otherwise, a base `Result` is used. |

**Returns:** `Result` — The result object after all handlers have executed (or after cancellation).

```groovy
def result = Alerts.invoke('on order placed', [orderId: '123', total: 99.99])
if (!result.cancelled) {
    // Process the order
}
```

### `Alerts.invokeAll(List<String> hookStrings, def context)`

Broadcasts multiple event names in a single pass. Each registered handler is checked against **all** provided event names before moving to the next handler, preserving global priority order.

| Parameter | Type | Description |
|---|---|---|
| `hookStrings` | `List<String>` | Multiple event names to match against |
| `context` | `Object` | Context data passed to handlers |

**Returns:** `Result`

Used internally by the HTTP handler to fire both `'on /path hit'` and `'on /path GET'` in a single priority-ordered pass.

---

## `Result` Class (Base)

```groovy
import spaceport.computer.alerts.results.Result
```

The base result object passed to every alert handler. Mutable — handlers modify it as it flows through the chain.

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `cancelled` | `boolean` | `false` | Set to `true` to stop the handler chain. No further handlers will execute. |
| `called` | `boolean` | `false` | `true` if at least one handler matched and executed. Used internally to detect 404s. |
| `matches` | `List<String>` | `[]` | Regex capture groups from the matching event string. 0-indexed. |
| `context` | `Object` | *(from invoker)* | The context data provided by the event source. Type varies by event. |

---

## `HttpResult` Class

```groovy
import spaceport.computer.alerts.results.HttpResult
```

Extends `Result`. Passed to handlers for HTTP events (`on /path hit`, `on /path METHOD`, `on page hit`). The `context` property is an `HttpContext`.

### Context Properties (`r.context`)

| Property | Type | Description |
|---|---|---|
| `request` | `HttpServletRequest` | The raw servlet request |
| `response` | `HttpServletResponse` | The raw servlet response |
| `method` | `String` | HTTP method: `GET`, `POST`, `PUT`, `DELETE`, etc. |
| `target` | `String` | The URL path (e.g., `/users/123`) |
| `time` | `Long` | Request timestamp |
| `cookies` | `Map` | Request cookies as `Map<String, String>` |
| `headers` | `Map` | Request headers |
| `data` | `Map` | Parsed request body / form data / query parameters. Also serves as a general-purpose data map for passing data to templates. |
| `dock` | `Cargo` | Session-specific Cargo (reactive data container) tied to the client |
| `client` | `Client` | The client/session associated with this request |

### Methods

#### Response Writing

##### `writeToClient(String string)`
Writes a string to the HTTP response body.

```groovy
r.writeToClient('<h1>Hello</h1>')
```

##### `writeToClient(String string, Integer status)`
Writes a string with an explicit HTTP status code.

```groovy
r.writeToClient('Not Found', 404)
```

##### `writeToClient(Map map)`
Serializes a Map as JSON and writes it to the response. Automatically sets `Content-Type: application/json;charset=UTF-8`.

```groovy
r.writeToClient([success: true, data: [name: 'Alice']])
// Response: {"success":true,"data":{"name":"Alice"}}
```

##### `writeToClient(List list)`
Serializes a List as a JSON array and writes it to the response.

```groovy
r.writeToClient([[id: 1], [id: 2]])
// Response: [{"id":1},{"id":2}]
```

##### `writeToClient(File file)`
Writes file bytes to the response. Content-Type is probed from the file.

```groovy
r.writeToClient(new File('/path/to/image.png'))
```

##### `writeToClient(File file, String contentType)`
Writes file bytes with an explicit Content-Type.

```groovy
r.writeToClient(new File('/path/to/data.csv'), 'text/csv')
```

#### Response Configuration

##### `setContentType(String contentType)`
Sets the response Content-Type header. Returns `this` for chaining.

```groovy
r.setContentType('text/plain')
```

##### `setStatus(Integer status)`
Sets the HTTP response status code. Returns `this` for chaining.

```groovy
r.setStatus(201)
```

##### `addResponseHeader(String name, def value)`
Adds a response header. Supports `String`, `Boolean`, `Date`, `Long`, and `Integer` value types. Returns `this` for chaining.

```groovy
r.addResponseHeader('X-Custom', 'value')
```

##### `setResponseHeader(String name, def value)`
Sets (replaces) a response header. Same type support as `addResponseHeader`. Returns `this` for chaining.

##### `setRedirectUrl(String url)`
Sends a 302 redirect to the specified URL.

```groovy
r.setRedirectUrl('/dashboard')
```

##### `markAsAttachment(String fileName)`
Sets the `Content-Disposition` header to trigger a file download. Returns `this` for chaining.

```groovy
r.markAsAttachment('report.pdf')
r.writeToClient(new File('/reports/latest.pdf'))
```

#### Cookies

##### `addResponseCookie(String name, String value)`
Adds a cookie with a 7-day expiry and path `/`. Returns `this` for chaining.

##### `addSecureResponseCookie(String name, String value)`
Adds an HttpOnly, Secure cookie (except in debug mode where Secure is omitted). Returns `this` for chaining.

##### `addResponseCookie(Cookie cookie)`
Adds a raw `Cookie` object for full control. Returns `this` for chaining.

##### `removeResponseCookie(String name)`
Removes a cookie by setting its max-age to 0. Returns `this` for chaining.

#### Authentication Helpers

##### `getClient()`
Returns the `Client` associated with this request.

```groovy
if (r.client.authenticated) {
    // Serve protected content
}
```

##### `authorize(Closure plug)`
Calls the provided closure with `this` (the `HttpResult`). Returns the closure's boolean result. Used for pluggable authorization checks.

```groovy
if (r.authorize(myAuthClosure)) {
    // Authorized
}
```

##### `ensure(Closure plug)`
Calls the provided closure with `this`. No return value. Used for running side-effect authorization checks that handle their own redirects.

---

## `SocketResult` Class

```groovy
import spaceport.computer.alerts.results.SocketResult
```

Extends `Result`. Passed to handlers for WebSocket events (`on socket connect`, `on socket data`, `on socket <handler-id>`, `on socket closed`). The `context` property is a `SocketContext` or `SocketLifecycleContext`.

### Context Properties (`r.context`)

| Property | Type | Description |
|---|---|---|
| `request` | `UpgradeRequest` | The WebSocket upgrade request |
| `session` | `Session` | The Jetty WebSocket session |
| `handler` | `SocketHandler` | The Spaceport socket handler instance |
| `handlerId` | `String` | The `handler-id` from the incoming JSON message (not present on lifecycle events) |
| `time` | `Long` | Message timestamp |
| `headers` | `Map` | Request headers from the upgrade request |
| `cookies` | `Map` | Cookies from the upgrade request |
| `data` | `Map` | Parsed JSON message data (not present on lifecycle events) |
| `dock` | `Cargo` | Session-specific Cargo |
| `client` | `Client` | The client associated with this connection |

### Methods

##### `getHandler()`
Returns the `SocketHandler` from context.

##### `getClient()`
Returns the `Client` associated with the WebSocket connection (resolved via `Client.getClientByHandler()`).

##### `writeToRemote(String string)`
Sends a string message over the WebSocket connection (async via `sendStringByFuture`).

```groovy
r.writeToRemote('pong')
```

##### `writeToRemote(Map map)`
Sends a Map as pretty-printed JSON over the WebSocket connection.

```groovy
r.writeToRemote([type: 'update', data: [count: 42]])
```

##### `close()`
Closes the WebSocket session.

##### `close(String reason)`
Closes the WebSocket session with status code 1000 and the provided reason string.

---

## Built-in Events Reference

### Application Lifecycle

| Event String | Context | When It Fires |
|---|---|---|
| `on initialize` | `[:]` (empty map) | After classes loaded, before application is ready |
| `on initialized` | `[:]` (empty map) | After `on initialize` completes; application is ready |
| `on deinitialize` | `[:]` (empty map) | Before classes unloaded during hot-reload |
| `on deinitialized` | `[:]` (empty map) | After deinitialize, before class unloading |

### HTTP Events

| Event String | Result Type | When It Fires |
|---|---|---|
| `on <path> hit` | `HttpResult` | HTTP request to `<path>` (any method) |
| `on <path> <METHOD>` | `HttpResult` | HTTP request to `<path>` with specific method (GET, POST, etc.) |
| `on page hit` | `HttpResult` | After every HTTP request, including 404s and errors. Has its own priority queue. |

Both `on <path> hit` and `on <path> <METHOD>` are fired together via `invokeAll`, sharing a single priority-ordered pass. `on page hit` fires separately afterward.

### WebSocket Events

| Event String | Result Type | When It Fires |
|---|---|---|
| `on socket connect` | `SocketResult` | WebSocket connection opened |
| `on socket data` | `SocketResult` | Any WebSocket message received |
| `on socket <handler-id>` | `SocketResult` | After `on socket data`, routed by the `handler-id` field in the JSON message |
| `on socket closed` | `SocketResult` | WebSocket connection closed |

WebSocket messages must be JSON containing a `handler-id` field. If `on socket data` sets `result.cancelled = true`, the handler-id-specific alert is skipped.

### Document Events

| Event String | Context Properties | When It Fires |
|---|---|---|
| `on document created` | `id`, `database`, `doc`, `classType` | New document created in CouchDB |
| `on document save` | `type`, `old`, `document`, `difference` | Before document is persisted (synchronous) |
| `on document saved` | `type`, `old`, `document`, `difference`, `operation` | After document is persisted (asynchronous, new thread) |
| `on document modified` | `type`, `action`, `old`/`document`, `difference`, `operation` | After save or remove (synchronous) |
| `on document remove` | `type`, `document` | Before document removal |
| `on document removed` | `type`, `document`, `operation` | After document removal |
| `on document conflict` | `type`, `action`, `document`, `conflicted`, `changes`, `differences` | CouchDB revision conflict detected |

### Authentication Events

| Event String | Context Properties | When It Fires |
|---|---|---|
| `on client auth` | `userID`, `client` | After successful authentication. Set `r.cancelled = true` to reject. |
| `on client auth failed` | `userID`, `exists` | Authentication failed (wrong password or user not found) |

---

## Regex Pattern Reference

Prefix an event string with `~` to enable regex matching.

| Feature | Syntax | Example |
|---|---|---|
| Single capture | `(.*)` or `([^/]+)` | `'~on /users/([^/]+) hit'` |
| Multiple captures | Multiple `()` groups | `'~on /files/(.+)/(.+) hit'` |
| Alternation | `(a\|b\|c)` | `'~on /(login\|logout\|reset) hit'` |
| Wildcard path | `(.*)` | `'~on /api/(.*) GET'` |

**Behavior notes:**
- Spaces in the pattern are automatically converted to `\s`
- Matching is always case-insensitive
- Uses full-string matching (`Matcher.matches()`), not partial matching
- Capture groups are stored in `r.matches` (0-indexed: `r.matches[0]` is the first group)
