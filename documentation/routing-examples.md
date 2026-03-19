# Routing — Examples

Real-world routing patterns drawn from Spaceport applications. Each example is adapted from working code in the Guestbook, port-mercury, and MadAve-Collab projects.

---

## Basic Page Routes

The simplest Spaceport application is a handful of routes in a single file. From the Guestbook project:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.launchpad.Launchpad

class GuestbookRouter {

    static Launchpad launchpad = new Launchpad()

    @Alert('on / hit')
    static _home(HttpResult r) {
        launchpad.assemble(['guestbook.ghtml']).launch(r, '_wrapper.ghtml')
    }

    @Alert('on /sign hit')
    static _sign(HttpResult r) {
        launchpad.assemble(['sign.ghtml']).launch(r, '_wrapper.ghtml')
    }

    @Alert('on /entries hit')
    static _entries(HttpResult r) {
        def results = View.get('views', 'all-entries', 'guestbooks')
        r.writeToClient([success: true, entries: results.rows])
    }
}
```

Three routes, one file, no configuration. The `hit` suffix handles all HTTP methods. Pages render Launchpad templates; the API endpoint returns JSON.

---

## GET and POST Separation

When a route needs different behavior per method, listen for methods explicitly. From port-mercury's login flow:

```groovy
@Alert('on /login GET')
static _loginPage(HttpResult r) {
    launchpad.assemble(['login.ghtml']).launch(r, 'wrapper.ghtml')
}

@Alert('on /login POST')
static _loginSubmit(HttpResult r) {
    def username = r.context.data.username
    def password = r.context.data.password

    def authorized = Client.getAuthenticatedClient(username, password)
    if (authorized) {
        authorized.attachCookie(r.context.cookies.'spaceport-uuid' as String)
        r.setRedirectUrl('/dashboard')
    } else {
        r.context.data.error = 'Invalid credentials'
        launchpad.assemble(['login.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

GET serves the form. POST processes the submission. Both share the same path. Spaceport fires `on /login GET` or `on /login POST` depending on the method — they never conflict because they listen to different event strings.

---

## Regex Routes with Captures

Dynamic path segments use regex event strings (prefixed with `~`). Capture groups are available in `r.matches`:

```groovy
@Alert('~on /users/([^/]+) hit')
static _userProfile(HttpResult r) {
    def userId = r.matches[0]
    def user = Document.getIfExists(userId, 'users')
    if (!user) {
        r.writeToClient('User not found', 404)
        return
    }
    r.context.data.user = user
    launchpad.assemble(['user-profile.ghtml']).launch(r, 'wrapper.ghtml')
}
```

Multiple capture groups:

```groovy
@Alert('~on /projects/([^/]+)/tasks/([^/]+) hit')
static _task(HttpResult r) {
    def projectId = r.matches[0]
    def taskId    = r.matches[1]
    // ...
}
```

---

## Route Alternation

Regex alternation serves multiple paths with one handler. From MadAve-Collab, where several auth-related pages share the same rendering pattern:

```groovy
@Alert('~on /(login|register|forgot-password|reset-password) hit')
static _authPages(HttpResult r) {
    def page = r.matches[0]  // "login", "register", etc.
    launchpad.assemble(["auth/${page}.ghtml"]).launch(r, 'wrapper.ghtml')
}
```

This replaces four separate handlers with one. The captured group tells you which page was requested.

---

## JSON API Endpoints

The convention across Spaceport applications is to return a Map with a `success` boolean:

```groovy
@Alert('on /api/messages POST')
static _createMessage(HttpResult r) {
    def text = r.context.data.text
    if (!text) {
        r.writeToClient([success: false, error: 'Message text is required'])
        return
    }

    def message = Document.getNew('messages')
    message.type = 'message'
    message.fields.text = text
    message.fields.authorId = r.client.userID
    message.save()

    r.writeToClient([success: true, id: message._id])
}

@Alert('on /api/messages GET')
static _listMessages(HttpResult r) {
    def results = View.get('views', 'all-messages', 'messages')
    r.writeToClient([success: true, messages: results.rows])
}
```

When you pass a `Map` to `writeToClient`, Spaceport automatically serializes it as JSON and sets the `Content-Type` to `application/json;charset=UTF-8`.

---

## Authorization Plug Pattern

Spaceport's `authorize` and `ensure` methods support a pluggable authorization model. The simplest approach is a closure that checks authentication:

### Simple Authentication Check

```groovy
class Auth {
    static requireLogin = { HttpResult r ->
        if (!r.client.authenticated) {
            r.setRedirectUrl('/login')
            r.cancelled = true
            return false
        }
        return true
    }
}
```

Use it in route handlers:

```groovy
@Alert('on /dashboard hit')
static _dashboard(HttpResult r) {
    if (!r.authorize(Auth.requireLogin)) return

    launchpad.assemble(['dashboard.ghtml']).launch(r, 'wrapper.ghtml')
}
```

### Priority-Based Middleware

For broader protection, use a high-priority handler that covers a path prefix:

```groovy
@Alert(value = '~on /admin/(.*) hit', priority = 100)
static _requireAdmin(HttpResult r) {
    if (!r.client.authenticated) {
        r.setRedirectUrl('/login')
        r.cancelled = true
        return
    }
    // Optionally check permissions
    if (!r.client.document?.hasPermission('admin')) {
        r.setRedirectUrl('/login')
        r.cancelled = true
    }
}

@Alert('on /admin/settings hit')
static _adminSettings(HttpResult r) {
    // This only runs if _requireAdmin didn't cancel
    launchpad.assemble(['admin/settings.ghtml']).launch(r, 'wrapper.ghtml')
}
```

### Filesystem-Driven Role Mapping

MadAve-Collab uses a more advanced pattern where authorization rules are loaded from configuration files, mapping URL patterns to required roles:

```groovy
@Alert(value = '~on /(.*) hit', priority = 50)
static _roleCheck(HttpResult r) {
    def path = r.matches[0]
    def requiredPermission = RoleMap.getRequiredPermission(path)
    if (requiredPermission && !r.client.document?.hasPermission(requiredPermission)) {
        r.setRedirectUrl('/unauthorized')
        r.cancelled = true
    }
}
```

This pattern scales well for applications with many protected routes — add a route to the role map configuration instead of adding authorization code to each handler.

---

## Custom Error Pages

Use `on page hit` to catch unhandled routes and errors:

```groovy
@Alert('on page hit')
static _errorPage(HttpResult r) {
    if (!r.called) {
        r.context.data.requestedPath = r.context.target
        launchpad.assemble(['errors/404.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

The `r.called` property is `false` when no handler matched the request. Since `on page hit` fires after all route handlers (and after error handling), you can also use it to catch 500 errors:

```groovy
@Alert('on page hit')
static _errorHandler(HttpResult r) {
    def status = r.context.response.status
    if (!r.called) {
        launchpad.assemble(['errors/404.ghtml']).launch(r, 'wrapper.ghtml')
    } else if (status >= 500) {
        launchpad.assemble(['errors/500.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

---

## Dynamic Page Routing with Wildcards

For content management or wiki-style applications where pages map to database entries or files:

```groovy
@Alert('~on /pages/(.*)\\.html hit')
static _dynamicPage(HttpResult r) {
    def slug = r.matches[0]  // e.g., "about-us" from "/pages/about-us.html"
    def page = Document.getIfExists(slug, 'pages')
    if (page) {
        r.context.data.page = page
        launchpad.assemble(['page-template.ghtml']).launch(r, 'wrapper.ghtml')
    } else {
        r.writeToClient('Page not found', 404)
    }
}
```

---

## Bidirectional `r.context.data`

The `data` map on `HttpContext` serves double duty: it holds incoming request data (query params, form data, JSON body) and can carry data forward to templates and downstream handlers.

```groovy
@Alert(value = 'on /profile hit', priority = 10)
static _loadProfileData(HttpResult r) {
    // Read from request data
    def userId = r.context.data.userId ?: r.client.userID

    // Write to data for downstream handlers and templates
    r.context.data.user = Document.getIfExists(userId, 'users')
    r.context.data.pageTitle = 'User Profile'
}

@Alert('on /profile hit')
static _renderProfile(HttpResult r) {
    // r.context.data now contains user, pageTitle,
    // AND any original request params — all available in the template
    launchpad.assemble(['profile.ghtml']).launch(r, 'wrapper.ghtml')
}
```

In Launchpad templates, everything in `r.context.data` is available directly:

```html
<h1>${pageTitle}</h1>
<p>Welcome, ${user.name}</p>
```

---

## Client-Side Navigation

Spaceport is not a single-page application framework. Navigation triggers full page loads. The client-side library (hud-core.js) provides several navigation mechanisms:

### Standard Links

Any element (not just `<a>` tags) can trigger navigation using the `href` attribute processed by hud-core.js:

```html
<button href="/dashboard">Go to Dashboard</button>
<div href="/settings" class="nav-item">Settings</div>
```

Hud-core.js sets up click handlers and keyboard accessibility (Enter/Space) on elements with `href`.

### Server-Driven Navigation via Transmissions

Server action return values can trigger navigation using special map keys:

```groovy
// In a server action closure
_{ ['@redirect': '/dashboard'] }       // Navigate to a new page
_{ ['@reload'] }                       // Refresh the current page
_{ ['@back'] }                         // Browser back
_{ ['@forward'] }                      // Browser forward
```

These instructions are returned from server actions and executed by HUD-Core on the client. See the [Transmissions Overview](transmissions-overview.md) for the full list of Transmission instructions.

---

## Debug-Only Routes

Guard development and diagnostic routes behind the debug flag:

```groovy
@Alert('on /debug/clients hit')
static _debugClients(HttpResult r) {
    if (!Spaceport.config.'debug') {
        r.cancelled = true
        return
    }

    r.writeToClient([success: true, message: 'Debug mode active'])
}

@Alert('on /debug/routes hit')
static _debugRoutes(HttpResult r) {
    if (!Spaceport.config.'debug') {
        r.cancelled = true
        return
    }
    // List all registered alert handlers
    r.writeToClient([success: true, alerts: Alerts.getRegisteredHooks()])
}
```

These routes are accessible during development but effectively disabled in production — the `cancelled = true` stops processing and the default 404 behavior takes over.

---

## File Downloads

Combine `markAsAttachment` with `writeToClient(File)` to trigger browser downloads:

```groovy
@Alert('~on /downloads/([^/]+) hit')
static _download(HttpResult r) {
    def filename = r.matches[0]
    def file = new File("/var/app/exports/${filename}")

    if (!file.exists()) {
        r.writeToClient('File not found', 404)
        return
    }

    r.markAsAttachment(filename)
    r.writeToClient(file)
}
```

For generated files (e.g., CSV exports):

```groovy
@Alert('on /export/users.csv hit')
static _exportCsv(HttpResult r) {
    def csv = new StringBuilder('Name,Email\n')
    def results = View.get('views', 'all-users', 'users')
    results.rows.each { row ->
        csv.append("${row.value.name},${row.value.email}\n")
    }

    r.setContentType('text/csv')
    r.markAsAttachment('users.csv')
    r.writeToClient(csv.toString())
}
```

---

## File Uploads

Multipart form data is parsed automatically. File parts arrive as `javax.servlet.http.Part` objects:

```groovy
@Alert('on /upload POST')
static _handleUpload(HttpResult r) {
    def filePart = r.context.data.file  // matches the form field name
    if (!filePart) {
        r.writeToClient([success: false, error: 'No file provided'])
        return
    }

    def fileName = filePart.submittedFileName
    def destFile = new File("/var/app/uploads/${fileName}")
    filePart.inputStream.withStream { input ->
        destFile.withOutputStream { output ->
            output << input
        }
    }

    r.writeToClient([success: true, fileName: fileName])
}
```

The corresponding HTML form:

```html
<form action="/upload" method="POST" enctype="multipart/form-data">
    <input type="file" name="file" />
    <button type="submit">Upload</button>
</form>
```

Maximum upload size is **50 MB** (configured at the Jetty multipart level).

---

## Outbound HTTP Requests

Use the `HTTP` utility to call external APIs from your route handlers:

```groovy
import spaceport.communications.http.HTTP

@Alert('on /api/weather hit')
static _weather(HttpResult r) {
    def city = r.context.data.city ?: 'New York'
    def response = HTTP.get('https://api.weather.example.com/current', [
        queryParams: [city: city, units: 'imperial'],
        headers: ['X-API-Key': Spaceport.config.'weather.api.key'],
        connectTimeout: 5000,
        socketTimeout: 10000
    ])

    if (response.statusCode == 200) {
        def weather = new groovy.json.JsonSlurper().parseText(response.body)
        r.writeToClient([success: true, weather: weather])
    } else {
        r.writeToClient([success: false, error: 'Weather service unavailable'])
    }
}
```

For POST requests with a JSON body:

```groovy
def response = HTTP.post('https://api.example.com/notify', [
    body: [event: 'order_placed', orderId: '12345'],
    headers: ['Authorization': 'Bearer ' + token]
])
```

---

## Organizing Routes Across Files

As applications grow, split routes across multiple classes by domain. From MadAve-Collab's structure:

```
src/madave/
    ├── routes/
    │     ├── PageRoutes.groovy       # HTML page routes
    │     ├── ApiRoutes.groovy        # JSON API routes
    │     ├── AuthRoutes.groovy       # Login/logout/register
    │     └── AdminRoutes.groovy      # Admin panel routes
    ├── middleware/
    │     ├── AuthMiddleware.groovy   # Authentication checks (high priority)
    │     └── LogMiddleware.groovy    # Request logging (on page hit)
    └── plugs/
          └── Plugs.groovy           # Reusable authorization closures
```

Each file contains `@Alert`-annotated static methods. Spaceport discovers them all at startup — no imports or registration needed. Priority controls execution order across files:

```groovy
// AuthMiddleware.groovy — runs first
class AuthMiddleware {
    @Alert(value = '~on /admin/(.*) hit', priority = 100)
    static _requireAuth(HttpResult r) { /* ... */ }
}

// AdminRoutes.groovy — runs after middleware
class AdminRoutes {
    @Alert('on /admin/dashboard hit')  // priority 0
    static _dashboard(HttpResult r) { /* ... */ }
}
```

No coordination is needed between the files. The alert system handles ordering globally.
