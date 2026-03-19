# Routing — Overview

Spaceport does not have a router. There is no route table, no route registration step, and no routing middleware. Instead, **routing is the Alerts system**. Every HTTP request becomes an event, and your code listens for that event with the `@Alert` annotation.

If you've read the [Alerts Overview](alerts-overview.md), you already know how routing works. This document focuses on the HTTP-specific patterns that make up day-to-day route handling.

## Your First Route

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class Pages {

    @Alert('on / hit')
    static _home(HttpResult r) {
        r.writeToClient('<h1>Welcome</h1>')
    }
}
```

Place this class in any configured source module directory. Spaceport discovers the `@Alert` annotation at startup, and from that moment forward, HTTP requests to `/` are handled by `_home`. There is nothing else to configure.

The event string `'on / hit'` means: "when `/` is hit by any HTTP method." The `HttpResult` parameter gives you access to everything about the request and the tools to build a response.

## How a Request Becomes Events

When an HTTP request arrives (say, a `POST` to `/login`), Spaceport fires **three events** in a specific sequence:

1. **`on /login hit`** — matches any handler listening for this path, regardless of HTTP method
2. **`on /login POST`** — matches any handler listening for this path with the POST method specifically
3. **`on page hit`** — a global catch-all that fires after every request, regardless of path

Events 1 and 2 fire together in a single priority-ordered pass using `Alerts.invokeAll()`. This means a handler for `on /login hit` at priority 10 will run before a handler for `on /login POST` at priority 5, even though they listen to different event strings. They share the same priority queue.

Event 3 (`on page hit`) fires separately afterward, in its own priority pass. It always fires — even if no route matched, even if an error occurred.

```
Request: POST /login
    │
    ├─ Combined pass (priority-ordered):
    │     @Alert('on /login hit')         → runs first if higher priority
    │     @Alert('on /login POST')        → runs in its priority position
    │
    └─ Separate pass:
          @Alert('on page hit')           → always runs last
```

## Listening by Method

Most routes use `hit` to handle all methods:

```groovy
@Alert('on /dashboard hit')
static _dashboard(HttpResult r) {
    r.writeToClient('<h1>Dashboard</h1>')
}
```

When you need method-specific behavior, listen for the method directly:

```groovy
@Alert('on /login GET')
static _loginPage(HttpResult r) {
    r.writeToClient('<form>...</form>')
}

@Alert('on /login POST')
static _loginSubmit(HttpResult r) {
    def username = r.context.data.username
    def password = r.context.data.password
    // authenticate...
}
```

Both patterns work. In practice, most Spaceport applications use `hit` for the majority of routes and only split by method when the behavior is fundamentally different (like serving a form vs. processing a submission).

## Dynamic Routes with Regex

For routes with variable segments, prefix the event string with `~` to enable regex matching:

```groovy
@Alert('~on /users/([^/]+) hit')
static _userProfile(HttpResult r) {
    def userId = r.matches[0]
    r.writeToClient("Profile for user: ${userId}")
}
```

The `~` prefix tells Spaceport to treat the event string as a regex pattern. Capture groups are available via `r.matches` (0-indexed). See the [Alerts API Reference](alerts-api.md) for full regex syntax details.

## Priority and Middleware

The `@Alert` annotation accepts a `priority` parameter. Higher values execute first. This is how you build middleware — authentication checks, logging, and other cross-cutting concerns:

```groovy
@Alert(value = 'on /admin hit', priority = 100)
static _requireAdmin(HttpResult r) {
    if (!r.client.authenticated) {
        r.setRedirectUrl('/login')
        r.cancelled = true  // stops the handler chain
    }
}

@Alert('on /admin hit')  // default priority 0
static _adminPage(HttpResult r) {
    r.writeToClient('<h1>Admin Panel</h1>')
}
```

Because the authentication handler runs at priority 100 (higher = first), it intercepts unauthenticated requests before the page handler ever executes. Setting `r.cancelled = true` halts the chain — no further handlers for this event will fire. (Note: `on page hit` still fires, because it runs in a separate pass.)

## The `on page hit` Catch-All

`on page hit` fires after every HTTP request. It's ideal for:

- **Error pages** — check if no route matched and serve a 404
- **Logging** — record every request
- **Response headers** — add global headers after route processing

```groovy
@Alert('on page hit')
static _notFound(HttpResult r) {
    if (!r.called) {
        r.writeToClient('Page not found', 404)
    }
}
```

The `r.called` property is `false` when no handler matched the request. This is how Spaceport detects 404s internally, and you can use the same mechanism for custom error pages.

## JSON APIs

Passing a `Map` or `List` to `writeToClient` automatically serializes it as JSON and sets the correct Content-Type:

```groovy
@Alert('on /api/users hit')
static _apiUsers(HttpResult r) {
    r.writeToClient([
        success: true,
        users: [[id: 1, name: 'Alice'], [id: 2, name: 'Bob']]
    ])
}
```

This is the standard pattern across Spaceport applications — return a map with a `success` boolean and any associated data.

## Request Data

All parsed request data — query parameters, form submissions, JSON bodies, and multipart uploads — is available in `r.context.data` as a flat `Map`:

```groovy
@Alert('on /search hit')
static _search(HttpResult r) {
    def query = r.context.data.q       // from ?q=something
    def page = r.context.data.page     // from ?page=2
    // ...
}
```

Spaceport automatically parses the request body based on Content-Type. JSON bodies, form-urlencoded data, multipart form data, and query string parameters all end up in the same `data` map.

## What's Next

- **[Routing API Reference](routing-api.md)** — Complete reference for `HttpContext` properties, manifest configuration, CORS, caching, and the outbound HTTP utility.
- **[Routing Internals](routing-internals.md)** — How Jetty processes requests, the full handler pipeline, and the threading model.
- **[Routing Examples](routing-examples.md)** — Real-world patterns from Spaceport applications: authorization, file uploads, API design, and more.
- **[Alerts Overview](alerts-overview.md)** — The underlying event system that powers routing.
- **[Alerts API Reference](alerts-api.md)** — Full documentation for `@Alert`, `HttpResult`, regex patterns, and all built-in events.
