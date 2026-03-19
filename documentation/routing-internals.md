# Routing — Internals

This document explains how HTTP requests flow through Spaceport, from the moment a TCP connection arrives at Jetty to the moment the response is sent. It covers the server architecture, the request processing pipeline, client identification, error handling, and the threading model.

---

## Server Architecture

Spaceport runs on **Eclipse Jetty**. The server is configured in `CommunicationsArray`, which sets up three handlers in a specific order:

```
Jetty Server
  └─ HandlerList
       ├─ 1. WebSocket Handler
       ├─ 2. Static Asset Handler (FlatResourceServlet)
       └─ 3. HttpRequestHandler
```

Jetty evaluates handlers in order. The first handler that claims the request wins:

1. **WebSocket Handler** — Intercepts WebSocket upgrade requests (`ws://` or `wss://`). If the request is a WebSocket upgrade, it's handled here and never reaches the other handlers.

2. **Static Asset Handler** — Serves files from the configured static assets directory (default: `public/` at the URL prefix `/public`). Implemented via `FlatResourceServlet`, a standard Jetty `DefaultServlet` pointed at the assets folder. If the URL matches a static file, the response is sent directly. No alerts fire.

3. **HttpRequestHandler** — Processes everything else. This is where routing (via alerts) happens.

This ordering means WebSocket connections and static files are resolved before any application code runs. Only requests that don't match WebSocket upgrades or static files reach your alert handlers.

### Server Binding

The server binds to the address and port specified in the manifest:

| Config Key | Default |
|---|---|
| `host.address` | `127.0.0.1` |
| `host.port` | `10000` |

Setting `host.address` to `0.0.0.0` makes the server accessible on all network interfaces (required for production deployment behind a reverse proxy or direct external access).

---

## The HttpRequestHandler Pipeline

`HttpRequestHandler` is the core of HTTP processing. Every non-static, non-WebSocket request passes through this pipeline in exact order:

### Step 1: Cache Headers

Response headers are set immediately from manifest configuration:

```
Cache-Control: <http.cache control>     (default: "no-cache")
Pragma: <http.pragma>                   (default: "no-cache")
Expires: <http.expires>                 (default: "0")
```

These are set before any handler code runs. Your handlers can override them with `r.setResponseHeader()` or `r.addResponseHeader()`.

### Step 2: CORS Headers

CORS headers are set on every response (not just preflight):

| Header | Value |
|---|---|
| `Access-Control-Allow-Origin` | Mirrors the `Origin` request header |
| `Access-Control-Allow-Credentials` | From `http.allow credentials` (default: `true`) |
| `Access-Control-Allow-Headers` | From `http.allow headers` (default: `*`) |
| `Access-Control-Allow-Methods` | Mirrors the `Access-Control-Request-Method` request header |

**Why mirror Origin?** The HTTP spec does not allow `Access-Control-Allow-Origin: *` when `Access-Control-Allow-Credentials: true`. By mirroring the request's Origin, Spaceport effectively allows all origins while maintaining credential support. This means CORS is permissive by default — if you need to restrict origins, you'll need to handle it in a high-priority `on page hit` handler or via a reverse proxy.

### Step 3: OPTIONS / HEAD Auto-Response

If the request method is `OPTIONS` and `http.auto process options` is `true` (default):
- The CORS headers (already set in step 2) are sufficient.
- A **200 OK** status is returned immediately.
- No alerts fire. Processing stops.

If the request method is `HEAD`:
- The pipeline continues normally (alerts fire, handlers execute).
- The response body is suppressed at the end. Only headers and status are sent.

### Step 4: Method Whitelist

The request method is checked against `http.methods.allow` (default: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`).

If the method is not in the whitelist:
- A **405 Method Not Allowed** response is sent.
- Processing stops. No alerts fire.

`OPTIONS` and `HEAD` are exempt from this check — they're handled in step 3 regardless of the whitelist.

### Step 5: Parse Request Headers

Request headers are extracted from the servlet request into a `Map<String, String>`.

### Step 6: Parse Cookies

Cookies are extracted from the request into a `Map<String, String>` keyed by cookie name.

### Step 7: Parse Request Data

The request body and query string are parsed into a `Map` based on Content-Type:

| Content-Type | Parsing |
|---|---|
| `application/json` | `JsonSlurper.parseText(body)` |
| `multipart/form-data` | Each `Part` extracted; file parts stored as `Part` objects, non-file parts as strings. Max size: 50 MB. |
| `application/x-www-form-urlencoded` | Standard form parsing. |
| *(query string)* | Always parsed. Merged into the same map. |

If no data is present, the map is empty. All data sources merge into a single `data` map on the context.

### Step 8: Client Identification

The `spaceport-uuid` cookie is read from the request:

- **Cookie present:** The existing `Client` object is looked up by UUID.
- **Cookie absent:** A new UUID is generated, a new `Client` is created, and a `Set-Cookie` header is added to the response.

The cookie is **HttpOnly** (not accessible to JavaScript) with a configurable expiry (default: 60 days, set via `http.spaceport cookie expiration`). The path is `/`.

This means every browser that visits a Spaceport application gets a persistent client identity, even before authentication. The `Client` object holds session state, the session `Cargo` (dock), and authentication status.

### Step 9: Build HttpContext

An `HttpContext` object is assembled with all parsed data:

```groovy
[
    request:    servletRequest,
    response:   servletResponse,
    method:     'GET',
    target:     '/users/123',
    time:       System.currentTimeMillis(),
    cookies:    [spaceport_uuid: 'abc-123', ...],
    headers:    ['Content-Type': 'application/json', ...],
    data:       [userId: '123', ...],
    dock:       client.dock,
    client:     client,
    resultType: HttpResult
]
```

The `resultType: HttpResult` property tells `Alerts.invoke()` / `Alerts.invokeAll()` to instantiate an `HttpResult` (rather than the base `Result`) for the handler chain.

### Step 10: Dispatch Route Alerts

Two event strings are constructed from the request:

```
"on <target> hit"         →  "on /users/123 hit"
"on <target> <METHOD>"    →  "on /users/123 GET"
```

These are dispatched together via `Alerts.invokeAll()`:

```groovy
Alerts.invokeAll(['on /users/123 hit', 'on /users/123 GET'], httpContext)
```

`invokeAll` iterates all registered handlers once, in global priority order. For each handler, it checks whether the handler's event string matches **any** of the provided strings. This means:

- A handler for `'on /users/123 hit'` at priority 10 and a handler for `'on /users/123 GET'` at priority 5 will run in that order (10, then 5).
- A handler matching both strings (e.g., a regex like `'~on /users/.* hit'`) is only invoked once, not twice.
- If any handler sets `result.cancelled = true`, no further handlers execute in this pass.

After `invokeAll` returns, if `result.called` is `false` (no handler matched), the response status is set to **404**.

### Step 11: Dispatch `on page hit`

Regardless of what happened in step 10 — whether a route matched, whether an error occurred, whether the result was cancelled — a separate alert pass fires:

```groovy
Alerts.invoke('on page hit', httpContext)
```

This is a completely independent dispatch. It has its own set of registered handlers with their own priority order. The `HttpResult` from step 10 is reused (same context, same state), so `on page hit` handlers can see whether a route matched (`r.called`), what status was set, and any data written to the context.

Common uses:
- Serve custom 404 pages when `!r.called`
- Global logging
- Response header injection

### Step 12: Error Handling

If an exception occurs during steps 10 or 11:
- The response status is set to **500**.
- The exception is logged.
- `on page hit` still fires (if the error was in step 10). This means your error page handler can catch 500s.

If the error occurs during `on page hit` itself, it's logged but there is no further recovery — the 500 status stands.

### Step 13: Logging

After the response is sent, the request is logged with the method, path, status code, and timing information.

---

## Static Asset Serving

The `FlatResourceServlet` (extending Jetty's `DefaultServlet`) serves static files from a configured directory.

| Config Key | Default | Description |
|---|---|---|
| `static assets.directory` | `public` | Filesystem directory (relative to project root) |
| `static assets.url` | `/public` | URL prefix |

A request to `/public/css/style.css` maps to the file `public/css/style.css` on disk. Content-Type is determined by Jetty's MIME type detection.

Static file serving is handled at the Jetty level, before `HttpRequestHandler`. No alerts fire. No client identification occurs. No CORS headers are added. If you need authentication or CORS for static files, use a different serving strategy (e.g., serve them through an alert handler, or configure a reverse proxy).

---

## Error Responses

| Scenario | Status | Behavior |
|---|---|---|
| Method not in whitelist | **405** | Immediate response. No alerts fire. |
| No handler matched | **404** | Set after `invokeAll` returns. `on page hit` still fires. |
| Handler exception | **500** | Logged. `on page hit` still fires (for errors in route handlers). |
| `on page hit` exception | **500** | Logged. No further recovery. |

The **default content type** for all responses is `text/html;charset=UTF-8`, set at the start of processing. JSON responses override this when you call `writeToClient(Map)` or `writeToClient(List)`.

---

## Threading Model

Spaceport uses Jetty's default thread pool. Each incoming HTTP request is handled on a Jetty thread. The full pipeline (steps 1-13) runs synchronously on that thread.

Key implications:

- **Alert handlers run on the request thread.** A slow handler blocks the response. Long-running operations should be offloaded (e.g., to a separate thread or async mechanism).
- **Handlers share the same `HttpResult` instance.** The result is mutable and passed sequentially through the handler chain. There is no copy or isolation between handlers.
- **`on page hit` runs on the same thread** as the route handlers, after they complete.
- **Static files and WebSocket upgrades** are handled on Jetty threads before reaching application code.

There is no async handler support — all handlers are synchronous. If a handler needs to do background work, it should use standard Java/Groovy concurrency (threads, executors, etc.) and ensure the response is written before the handler returns.

---

## Debug vs. Production

Spaceport's debug mode (configured in the manifest) affects routing behavior in several ways:

| Behavior | Debug Mode | Production Mode |
|---|---|---|
| **Hot reload** | Source modules are reloaded on change. Alert handlers are re-registered. | Classes loaded once at startup. |
| **Secure cookies** | `addSecureResponseCookie` omits the `Secure` flag (allowing HTTP in development). | `Secure` flag is set (requires HTTPS). |
| **Error detail** | Exception stack traces may be more visible in logs. | Standard error logging. |
| **`Spaceport.config.'debug'`** | Evaluates to `true`. Routes can check this to enable debug-only endpoints. | Evaluates to `false`. |

Applications commonly guard debug-only routes:

```groovy
@Alert('on /debug/info hit')
static _debugInfo(HttpResult r) {
    if (!Spaceport.config.'debug') {
        r.cancelled = true
        return
    }
    r.writeToClient([
        clients: spaceport.personnel.ClientRegistry.clients.size(),
        uptime: System.currentTimeMillis() - startTime
    ])
}
```

---

## Request Flow Diagram

```
                    Incoming HTTP Request
                           │
                    ┌──────▼──────┐
                    │   Jetty     │
                    │  Server     │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         WebSocket     Static       HttpRequest
         Handler?      Asset?       Handler
              │            │            │
         (upgrade)    (serve file)      │
                                        ▼
                                 Set cache headers
                                        │
                                 Set CORS headers
                                        │
                              OPTIONS? ──▶ 200 + stop
                                        │
                              Method OK? ──▶ 405 + stop
                                        │
                                 Parse headers
                                 Parse cookies
                                 Parse request data
                                        │
                                 Identify client
                                 (spaceport-uuid)
                                        │
                                 Build HttpContext
                                        │
                              ┌─────────▼─────────┐
                              │   invokeAll(       │
                              │     "on /path hit",│
                              │     "on /path GET" │
                              │   )                │
                              └─────────┬──────────┘
                                        │
                              No match? → status 404
                                        │
                              ┌─────────▼─────────┐
                              │   invoke(          │
                              │     "on page hit"  │
                              │   )                │
                              └─────────┬──────────┘
                                        │
                                 Log request
                                        │
                                 Send response
```
