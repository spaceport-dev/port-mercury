# Alerts (Event System) — Examples

Real-world patterns drawn from production Spaceport applications, including the Guestbook, port-mercury, and MadAve-Collab projects.

## Basic Route Handling

### Root Redirect

Every Spaceport app starts with a root route. The simplest pattern redirects to a named page:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class App {

    @Alert('on / hit')
    static _root(HttpResult r) {
        r.setRedirectUrl('/index.html')
    }

    @Alert('on /index.html hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

*From: port-mercury — `modules/App.groovy`*

### Conditional Root Based on Authentication

A more sophisticated pattern redirects based on the client's authentication state:

```groovy
@Alert('on / hit')
static _root(HttpResult r) {
    if (r.client.authenticated) {
        r.setRedirectUrl('/home/dashboard')
    } else {
        r.setRedirectUrl('/login')
    }
}
```

*From: MadAve-Collab — `modules/app/Router.groovy`*

## Regex Routes with Captures

### Single Capture Group

Extract a path segment using `(.*)` or `([^/]+)`:

```groovy
@Alert('~on /g/(.*) hit')
static _event(HttpResult r) {
    // r.matches[0] contains the captured segment
    launchpad.assemble(['guestbook.ghtml']).launch(r, '_wrapper.ghtml')
}
```

*From: Guestbook — `modules/App.groovy`*

### Wildcard HTML Pages

Match any `.html` page and route to corresponding templates:

```groovy
@Alert('~on /([^/]+)\\.html hit')
static _pages(HttpResult r) {
    def pageName = r.matches[0]
    if (pageName == 'index') return  // Already handled. Alerts can stack!
    launchpad.assemble(["pages/${pageName}.ghtml"]).launch(r, 'wrapper.ghtml')
}
```

*From: port-mercury — `modules/App.groovy`*

The comment "Alerts can stack!" is key — multiple handlers can match the same URL. The `return` prevents double-rendering for `/index.html` (which has its own dedicated handler).

### Multiple Capture Groups

Extract multiple path segments for complex URLs:

```groovy
@Alert('~on /download/(.+)/(.+) hit')
static _downloadFile(HttpResult r) {
    def jobNumber = r.matches[0]
    def fileName = r.matches[1]
    // Fetch and serve the file
}
```

*From: MadAve-Collab — `modules/app/Assets.groovy`*

### Route Alternation

Handle multiple related routes with a single handler using regex alternation:

```groovy
@Alert('~on /(login|logout|reset|forgot) hit')
static _login(HttpResult r) {
    if (r.matches[0] != 'logout' && r.client.authenticated) {
        r.setRedirectUrl(r.context.data.'redirect-url' ?: '/')
        return
    }
    launchpad.assemble([
        "auth/_header.ghtml",
        "auth/${r.matches[0]}.ghtml",
        "auth/_footer.ghtml"
    ]).launch(r, '_wrapper.ghtml')
}
```

*From: MadAve-Collab — `modules/app/Router.groovy`*

This handles `/login`, `/logout`, `/reset`, and `/forgot` with a single handler. The captured match selects the appropriate template file.

## HTTP Method-Specific Routes

### Login Form (GET and POST)

Separate handlers for displaying a form vs. processing its submission:

```groovy
@Alert('on /login GET')
static _loginPage(HttpResult r) {
    launchpad.assemble(['admin/login.ghtml']).launch(r)
}

@Alert('on /login POST')
static _login(HttpResult r) {
    String username = r.context.data.username
    String password = r.context.data.password
    def authorized = Client.getAuthenticatedClient(username, password)
    if (authorized) {
        authorized.attachCookie(r.context.cookies.'spaceport-uuid' as String)
        r.setRedirectUrl('/a/')
    } else {
        r.setRedirectUrl('/login?message=Invalid+credentials')
    }
}
```

*From: port-mercury — `modules/Router.groovy`*

## JSON API Endpoints

### REST-Style API with Authentication

```groovy
@Alert('on /api/search-jobs hit')
static _searchJobs(HttpResult r) {
    if (!r.client.authenticated) {
        r.writeToClient([success: false, error: 'Not authenticated'])
        return
    }
    def query = r.context.data.q?.toString()?.trim()?.toLowerCase()
    // ... search logic ...
    r.writeToClient([success: true, results: results])
}
```

*From: MadAve-Collab — `modules/app/App.groovy`*

`writeToClient(Map)` automatically sets the Content-Type to `application/json` and serializes the map.

## Authorization Guard Pattern

### Using `authorize()` with a Closure

The `authorize` method accepts a closure that performs the auth check. If it returns false, the handler typically redirects:

```groovy
static authPlug = { HttpResult r ->
    if (!r.client.authenticated) {
        r.setRedirectUrl('/login?redirect-url=' + r.context.target.encode())
        r.cancelled = true
        return false
    }
    r.context.data.group = r.client.document?.fields?.group
    return true
}

@Alert('~on /home/([^/]+) hit')
static _home(HttpResult r) {
    r.context.data.part = r.matches[0].toLowerCase()
    if (r.authorize(authPlug)) {
        launchpad.assemble([
            'secure/_header.ghtml',
            'secure/_sidebar.ghtml',
            "secure/${r.context.data.part}.ghtml",
            'secure/_footer.ghtml'
        ]).launch(r, '_wrapper.ghtml')
    }
}
```

*From: MadAve-Collab — `modules/app/App.groovy`*

The `authPlug` closure is reusable across multiple route handlers. It enriches `r.context.data` with user information while checking authentication.

## Application Initialization

### Database and View Setup

The `on initialized` event is the standard place to ensure databases exist and CouchDB views are registered:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.Spaceport

class Job {

    @Alert('on initialized')
    static _init(Result r) {
        Spaceport.main_memory_core.createDatabaseIfNotExists('jobs')

        ViewDocument.get('jobs', 'jobs').setViewIfNeeded(
            'list-jobs', /* language=javascript */ '''
                function(doc) {
                    if (doc.type === 'job') {
                        emit(doc.states.created, {
                            jobId: doc.jobId,
                            client: doc.client,
                            status: doc.status
                        })
                    }
                }
            '''
        )
    }
}
```

*From: MadAve-Collab — `modules/data/Job.groovy`*

This pattern appears in nearly every document class. `setViewIfNeeded` only updates the view if the map function has changed, avoiding unnecessary writes to CouchDB.

## Document Lifecycle Hooks

### Pre-Save Notifications with Dedup

The most sophisticated alert usage in the example projects. Multiple handlers listen to `on document save` to trigger notifications when document state changes:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result

class Notifications {

    @Alert('on document save')
    static _notifyTalentOnAssignment(Result r) {
        if (r.context.document.type != 'job') return

        def doc = r.context.document
        if (doc.cargo.'notifications-sent'?.'send-to-talent') return  // Already sent
        if (doc.status != 'Assigned to Talent') return

        // Set dedup flag BEFORE save completes (atomic with the save)
        doc.cargo.'notifications-sent'.set('send-to-talent', System.currentTimeMillis())

        // Send the notification email...
    }

    @Alert('on document save')
    static _notifyOnRevisionRequested(Result r) {
        if (r.context.document.type != 'job') return

        def doc = r.context.document
        if (doc.cargo.'notifications-sent'?.'send-revision-requested') return
        if (doc.status != 'Revision Requested') return

        // Reset the engineering notification cycle
        doc.cargo.'notifications-sent'.set('send-revision-requested', System.currentTimeMillis())
        doc.cargo.'notifications-sent'.delete('send-to-engineers')

        // Send revision request email...
    }
}
```

*From: MadAve-Collab — `modules/app/Notifications.groovy`*

**Why `on document save` (pre-save) instead of `on document saved` (post-save)?** The dedup flags are set on the document's Cargo *before* the save is committed to CouchDB. This means the flag is persisted atomically with the save that triggered the notification. If multiple rapid `save()` calls occur, only the first triggers the notification because subsequent pre-save hooks see the flag already set.

### Post-Save Handlers

Use `on document saved` when the handler needs to re-save the document:

```groovy
@Alert('on document saved')
static _notifyNewUserAccount(Result r) {
    if (r.context.document.database != 'users') return

    def userDoc = r.context.document
    if (userDoc.cargo.'welcome-email-sent') return

    // Send welcome email...

    userDoc.cargo.'welcome-email-sent' = System.currentTimeMillis()
    userDoc.save()  // Safe to re-save here (post-save handler)
}
```

*From: MadAve-Collab — `modules/app/Notifications.groovy`*

`on document saved` runs asynchronously in a new thread, so calling `save()` again won't cause infinite recursion — the dedup flag prevents re-triggering.

## Error Page Catch-All

### `on page hit` for Status Code Handling

`on page hit` fires after every HTTP request. Use it to render error pages based on the response status code:

```groovy
@Alert('on page hit')
static _errorPages(HttpResult r) {
    if (r.context.target == '/favicon.ico') {
        r.setRedirectUrl('/assets/img/favicon.svg')
        return
    }
    def statusCode = r.context.response.status
    if (statusCode >= 500) {
        launchpad.assemble(['errors/5XX.ghtml']).launch(r, '_wrapper.ghtml')
    } else if (statusCode >= 400) {
        launchpad.assemble(['errors/4XX.ghtml']).launch(r, '_wrapper.ghtml')
    }
}
```

*From: MadAve-Collab — `modules/app/Router.groovy`*

## Debug-Only Routes

### Conditional Route with Debug Check

```groovy
@Alert('~on /test/(.*) hit')
static _test(HttpResult r) {
    if (!Spaceport.config.'debug') return
    def testName = r.matches[0]
    launchpad.assemble(["test/${testName}.ghtml"]).launch(r, '_wrapper.ghtml')
}
```

*From: MadAve-Collab — `modules/app/Router.groovy`*

This exposes test pages only when the application is running in debug mode.

## Pattern Summary

| Pattern | Event String | Key Technique |
|---|---|---|
| Root redirect | `'on / hit'` | `r.setRedirectUrl()` |
| Static route | `'on /path hit'` | `launchpad.assemble().launch(r)` |
| Parameterized route | `'~on /path/(.*) hit'` | `r.matches[0]` for captures |
| Method-specific | `'on /path POST'` | Separate GET/POST handlers |
| JSON API | `'on /api/endpoint hit'` | `r.writeToClient(Map)` |
| Auth guard | Closure + `r.authorize()` | `r.cancelled = true` to block |
| DB init | `'on initialized'` | Create databases, register views |
| Pre-save hook | `'on document save'` | Atomic dedup flags on Cargo |
| Post-save hook | `'on document saved'` | Async, safe to re-save |
| Error pages | `'on page hit'` | Check `r.context.response.status` |
| Debug route | `'~on /test/(.*) hit'` | `Spaceport.config.'debug'` guard |

## Conventions

Based on the example projects:

- **Handler method names** are prefixed with underscore (`_init`, `_home`, `_root`). This appears to be a convention, not a requirement.
- **Handler methods are always `static`** — the `@Alert` annotation only works on static methods.
- **One import per result type** — use `HttpResult` for HTTP routes, `Result` for everything else.
- **Filter by type early** — document event handlers should `return` immediately if `r.context.document.type` doesn't match the expected type, since all document saves fire the same event.
- **Organize by concern** — put routes in a Router class, notifications in a Notifications class, data setup in document classes. The alert system's decoupled nature encourages this separation.
