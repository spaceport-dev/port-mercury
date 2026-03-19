# Routing — API Reference

This document covers the HTTP-specific configuration and utilities that shape how Spaceport handles requests. For the `@Alert` annotation, `HttpResult` methods, and event string syntax, see the [Alerts API Reference](alerts-api.md) — routing handlers are alert handlers, and their full API lives there.

---

## HttpContext Properties

Every HTTP alert handler receives an `HttpResult` whose `context` property is an `HttpContext`. This table summarizes what's available:

| Property | Type | Description |
|---|---|---|
| `request` | `HttpServletRequest` | The raw Jetty servlet request object |
| `response` | `HttpServletResponse` | The raw Jetty servlet response object |
| `method` | `String` | HTTP method in uppercase: `GET`, `POST`, `PUT`, `DELETE`, etc. |
| `target` | `String` | The request path (e.g., `/users/123`). Does not include query string. |
| `time` | `Long` | Timestamp when the request was received (`System.currentTimeMillis()`) |
| `cookies` | `Map<String, String>` | Request cookies, keyed by cookie name |
| `headers` | `Map<String, String>` | Request headers, keyed by header name |
| `data` | `Map` | Parsed request data from all sources (see [Request Data Parsing](#request-data-parsing)) |
| `dock` | `Cargo` | Session-scoped reactive data container tied to the client |
| `client` | `Client` | The client/session object for this request |
| `resultType` | `Class` | Set to `HttpResult` — used internally to instantiate the correct Result subclass |

**Usage:**

```groovy
@Alert('on /profile hit')
static _profile(HttpResult r) {
    def path   = r.context.target       // "/profile"
    def method = r.context.method       // "GET"
    def token  = r.context.headers['Authorization']
    def userId = r.context.data.userId  // from query string or body
    def client = r.context.client       // Client object
}
```

The `data` map is **bidirectional**: you read request data from it, but you can also write to it to pass data forward to templates or downstream handlers.

```groovy
@Alert(value = 'on /profile hit', priority = 10)
static _loadProfile(HttpResult r) {
    r.context.data.user = Document.getIfExists(r.context.data.userId, 'users')
}

@Alert('on /profile hit')
static _renderProfile(HttpResult r) {
    // r.context.data.user is available here, and in Launchpad templates
}
```

---

## Request Data Parsing

Spaceport automatically parses the request body based on the `Content-Type` header and merges the result into `r.context.data`. Query string parameters are always parsed.

| Content-Type | Parsing Behavior |
|---|---|
| `application/json` | JSON body parsed into a Map via `JsonSlurper`. Nested structures preserved. |
| `multipart/form-data` | Each part extracted. File parts stored as the raw `Part` object. Non-file parts stored as strings. Maximum request size: **50 MB**. |
| `application/x-www-form-urlencoded` | Standard form key-value pairs parsed into the data map. |
| *(query string)* | Query parameters parsed from the URL and merged into the data map. Always parsed, regardless of Content-Type. |
| *(other / none)* | No body parsing. Only query string parameters are available. |

**Precedence:** If the same key appears in both the query string and the body, the body value takes precedence.

### File Uploads

For multipart uploads, file parts are available as `javax.servlet.http.Part` objects:

```groovy
@Alert('on /upload POST')
static _upload(HttpResult r) {
    def filePart = r.context.data.file  // Part object
    def fileName = filePart.submittedFileName
    def stream   = filePart.inputStream
    // Process the uploaded file...
}
```

---

## Manifest Configuration

Routing behavior is configured in the Spaceport manifest file. All settings have sensible defaults.

### Server Binding

| Key | Type | Default | Description |
|---|---|---|---|
| `host.address` | `String` | `127.0.0.1` | IP address the server binds to. Use `0.0.0.0` to listen on all interfaces. |
| `host.port` | `Integer` | `10000` | Port the server listens on. |

```yaml
host:
  address: 0.0.0.0
  port: 8080
```

### HTTP Method Whitelist

| Key | Type | Default | Description |
|---|---|---|---|
| `http.methods.allow` | `List<String>` | `['GET', 'POST', 'PUT', 'DELETE', 'PATCH']` | Allowed HTTP methods. Requests with methods not in this list receive a **405 Method Not Allowed** response. |

```yaml
http:
  methods:
    allow:
      - GET
      - POST
      - PUT
      - DELETE
      - PATCH
```

`OPTIONS` and `HEAD` requests are handled automatically regardless of this setting (see [Auto-Processed Methods](#auto-processed-methods)).

### Auto-Processed Methods

| Key | Type | Default | Description |
|---|---|---|---|
| `http.auto process options` | `Boolean` | `true` | When `true`, OPTIONS requests are answered automatically with CORS headers. When `false`, OPTIONS requests pass through to your alert handlers. |

HEAD requests are always auto-processed — they run the normal handler pipeline but suppress the response body.

### Cache Control

| Key | Type | Default | Description |
|---|---|---|---|
| `http.cache control` | `String` | `'no-cache'` | Value of the `Cache-Control` response header. |
| `http.pragma` | `String` | `'no-cache'` | Value of the `Pragma` response header. |
| `http.expires` | `String` | `'0'` | Value of the `Expires` response header. |

```yaml
http:
  cache control: no-store, must-revalidate
  pragma: no-cache
  expires: '0'
```

These headers are set on every HTTP response before your handlers execute.

### CORS Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `http.allow credentials` | `Boolean` | `true` | Sets `Access-Control-Allow-Credentials` header. |
| `http.allow headers` | `String` | `'*'` | Value of `Access-Control-Allow-Headers`. |

```yaml
http:
  allow credentials: true
  allow headers: Content-Type, Authorization, X-Custom
```

**CORS behavior:**
- `Access-Control-Allow-Origin` is set to the request's `Origin` header value (mirrored). This effectively allows all origins while supporting credentialed requests (the `*` wildcard doesn't work with `credentials: true` in browsers).
- `Access-Control-Allow-Methods` mirrors the `Access-Control-Request-Method` from the preflight request.
- When `http.auto process options` is `true` (default), preflight OPTIONS requests are answered automatically with these headers and a 200 status.

### Client Cookie

| Key | Type | Default | Description |
|---|---|---|---|
| `http.spaceport cookie expiration` | `Integer` | `60` | Expiry time in **days** for the `spaceport-uuid` client identification cookie. |

```yaml
http:
  spaceport cookie expiration: 90
```

The `spaceport-uuid` cookie is an HttpOnly cookie set automatically on every response. It identifies the client across requests and ties them to a `Client` session. See [Routing Internals](routing-internals.md) for details on client identification.

### Static Assets

| Key | Type | Default | Description |
|---|---|---|---|
| `static assets.directory` | `String` | `'public'` | Directory (relative to project root) containing static files |
| `static assets.url` | `String` | `'/public'` | URL prefix for serving static files |

```yaml
static assets:
  directory: public
  url: /public
```

Static assets are served directly by Jetty's resource handler, bypassing the alert pipeline entirely. They are handled before HTTP requests reach the `HttpRequestHandler`.

### Default Content Type

The default response `Content-Type` is `text/html;charset=UTF-8`. This is applied to all responses unless explicitly changed by a handler (via `r.setContentType()` or by writing a Map/List, which sets JSON content type automatically).

---

## Outbound HTTP Utility

Spaceport provides a built-in `HTTP` utility class for making outbound HTTP requests from your server-side code. For full documentation, see the [HTTP Client Reference](http-api.md).

```groovy
import spaceport.communications.http.HTTP
```

Quick example:

```groovy
def response = HTTP.get('https://api.example.com/users', [
    queryParams: [page: '2', limit: '10'],
    headers: ['Accept': 'application/json']
])

if (response.statusCode == 200) {
    def users = new groovy.json.JsonSlurper().parseText(response.body)
}
```

---

## HttpResult Quick Reference

The `HttpResult` class (fully documented in [Alerts API Reference](alerts-api.md)) provides the following categories of methods:

| Category | Key Methods |
|---|---|
| **Response writing** | `writeToClient(String)`, `writeToClient(Map)`, `writeToClient(List)`, `writeToClient(File)` |
| **Status & headers** | `setStatus(Integer)`, `setContentType(String)`, `addResponseHeader(String, def)`, `setResponseHeader(String, def)` |
| **Redirects** | `setRedirectUrl(String)` |
| **Cookies** | `addResponseCookie(String, String)`, `addSecureResponseCookie(String, String)`, `removeResponseCookie(String)` |
| **File downloads** | `markAsAttachment(String)` |
| **Auth** | `authorize(Closure)`, `ensure(Closure)`, `getClient()` |
| **Flow control** | `r.cancelled = true` (stop handler chain), `r.called` (check if any handler matched) |

See [Alerts API Reference](alerts-api.md) for full method signatures, parameter types, and examples.
