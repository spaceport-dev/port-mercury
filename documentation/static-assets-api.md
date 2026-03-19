# Static Assets — API Reference

This document covers the configuration options and classes involved in serving static files in Spaceport. For a conceptual introduction, see the [Static Assets Overview](static-assets-overview.md).

---

## Manifest Configuration

Static asset behavior is configured in the `static assets` section of `config.spaceport`.

### `static assets.paths`

| Key | Type | Default | Description |
|---|---|---|---|
| `static assets.paths` | `Map<String, String>` | `{ '/assets/' : 'assets/*' }` | Maps URL path prefixes to filesystem directories. Each entry creates a separate Jetty `ServletContextHandler`. |

**Map entries:**

- **Key** — The URL path prefix (e.g., `/assets/`). This becomes the Jetty context path. Requests matching this prefix are routed to the corresponding filesystem directory.
- **Value** — The filesystem directory path, optionally suffixed with `*` to enable recursive subdirectory serving.

```yaml
static assets:
  paths:
    /assets/ : assets/*
    /fonts/  : resources/fonts/*
    /vendor/ : lib/vendor/
```

#### Wildcard (`*`) Behavior

Appending `*` to the **filesystem path** (the value) enables recursive serving:

| Value | Subdirectories Served | `FlatResourceServlet.allowSubdirs` |
|---|---|---|
| `assets/*` | Yes | `true` |
| `assets/` | No | `false` |

When subdirectories are disallowed, any request containing a path separator beyond the context path returns a **404 Not Found**.

#### Path Resolution

Filesystem paths are resolved as follows:

1. If the path is **absolute** (e.g., `/var/www/assets/`), it is used directly.
2. If the path is **relative** (e.g., `assets/`), it is resolved against the `spaceport root` configuration value.
3. If the path is exactly `/`, it resolves to the `spaceport root` directory.

### `static assets.buffer`

| Key | Type | Default | Description |
|---|---|---|---|
| `static assets.buffer` | `Boolean` | `null` (auto) | Controls Jetty's `useFileMappedBuffer` setting for memory-mapped file serving. |

**Auto behavior** (when not set):

- `debug: true` — Buffer is **disabled**. Files are read from disk on each request, allowing live editing.
- `debug: false` — Buffer is **enabled**. Files are memory-mapped for high-throughput serving.

**Explicit override:**

```yaml
# Force buffering off even in production
static assets:
  buffer: false
  paths:
    /assets/ : assets/*
```

Setting `buffer: true` in debug mode has no effect -- the implementation disables it whenever debug mode is active.

---

## FlatResourceServlet

**Package:** `spaceport.engineering`

**Extends:** `org.eclipse.jetty.servlet.DefaultServlet`

A servlet that serves static files with configurable subdirectory access control. One instance is created per static asset path mapping.

### Constructor

```groovy
FlatResourceServlet(boolean allowSubdirs = false)
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `allowSubdirs` | `boolean` | `false` | When `true`, requests for files in subdirectories are allowed. When `false`, only files at the root of the mapped directory are served. |

### Properties

| Property | Type | Description |
|---|---|---|
| `allowSubdirs` | `boolean` | Whether subdirectory access is permitted. Set via constructor. |

### Resource Resolution

The servlet overrides `getResource(String pathInContext)` to enforce access rules:

**When `allowSubdirs` is `false` (flat mode):**

| Request Path | Result |
|---|---|
| `/logo.svg` | Served normally |
| `/css/styles.css` | Returns `null` (404) — contains a path separator |
| `/` (root, debug on) | Directory listing shown |
| `/` (root, debug off) | Returns `null` (404) |
| `/subdir/` | Returns `null` (404) — trailing slash blocked |

**When `allowSubdirs` is `true` (recursive mode):**

All paths are passed through to Jetty's `DefaultServlet` for standard resource resolution.

### Error Handling

`FlatResourceServlet` overrides the default Jetty error page behavior. All error responses are returned as **plain text** in the format:

```
404 Not Found
```

This applies to all HTTP error codes (400, 401, 403, 404, 405, 500, etc.). The servlet wraps the response in a `PlainErrorWrapper` that intercepts `sendError()` calls and writes plain text instead of Jetty's default HTML error pages.

### HTTP Methods

The servlet handles two HTTP methods:

| Method | Behavior |
|---|---|
| `GET` | Serves the requested file, or returns a plain text error |
| `HEAD` | Same as GET but suppresses the response body (standard HTTP HEAD semantics) |

---

## Handler Chain Setup

Static asset handlers are configured in `CommunicationsArray` (`spaceport.communications`), which builds the Jetty server's handler chain.

### Handler Order

Handlers are added to Jetty's `HandlerCollection` in this order:

| Order | Handler | Purpose |
|---|---|---|
| 1 | `SocketConnector` | WebSocket connections (context path: `/`) |
| 2 | Static asset servlets | One `ServletContextHandler` per `static assets.paths` entry |
| 3 | `HttpRequestHandler` | Dynamic HTTP routes via the Alert system (context path: `/`) |

Jetty evaluates handlers in order. When a static asset handler matches a request, the response is served and subsequent handlers are not consulted. This is why static assets take precedence over dynamic routes with the same URL path.

### Servlet Configuration

For each static asset path mapping, `CommunicationsArray` creates a `ServletContextHandler` with the following Jetty init parameters:

| Init Parameter | Debug Mode | Production Mode | Description |
|---|---|---|---|
| `useFileMappedBuffer` | `false` | `true` (unless `buffer: false`) | Memory-mapped file serving |
| `dirAllowed` | `true` | `false` | Directory listing at the context root |

The servlet is mapped to `/*` within its context, with `FlatResourceServlet` enforcing subdirectory rules independently of Jetty's default behavior.

### Resource Base

The `resourceBase` for each servlet context is set to the resolved filesystem path. For relative paths, this is the absolute path computed by joining the `spaceport root` with the configured directory.

---

## Directory Listing

When `debug` mode is enabled, browsing to the root of a static asset context path (e.g., `/assets/`) shows a directory listing of the mapped filesystem directory. This is controlled by two mechanisms:

1. **Jetty's `dirAllowed` parameter** — Set to `true` in debug mode, `false` in production.
2. **FlatResourceServlet's `getResource`** — In flat mode, root directory access is only allowed when `Spaceport.store._debug` is `true`.

In production, directory listing is disabled and requests to directory paths return a 404.

---

## MIME Type Handling

MIME types are handled automatically by Jetty's `DefaultServlet`. The `Content-Type` response header is set based on the file extension using Jetty's built-in MIME type mappings. Common mappings include:

| Extension | Content-Type |
|---|---|
| `.html` | `text/html` |
| `.css` | `text/css` |
| `.js` | `application/javascript` |
| `.json` | `application/json` |
| `.svg` | `image/svg+xml` |
| `.png` | `image/png` |
| `.jpg` / `.jpeg` | `image/jpeg` |
| `.gif` | `image/gif` |
| `.woff` / `.woff2` | `font/woff` / `font/woff2` |
| `.ttf` | `font/ttf` |
| `.ico` | `image/x-icon` |

No additional configuration is needed -- Jetty detects and sets MIME types automatically.

---

## Default Configuration

When no `static assets` section is present in the manifest, Spaceport applies this default:

```groovy
'static assets' : [
    'paths' : [ '/assets/' : 'assets/*' ]
]
```

This creates a single recursive handler serving the `assets/` directory at the `/assets/` URL prefix. The `buffer` setting defaults to automatic (disabled in debug, enabled in production).

---

## See Also

- **[Static Assets Overview](static-assets-overview.md)** — Conceptual introduction, patterns, and real-world examples.
- **[Routing API Reference](routing-api.md)** — HTTP request handling configuration, including how static assets interact with the handler chain.
- **[Manifest Configuration](manifest-configuration.md)** — Full `config.spaceport` reference.
