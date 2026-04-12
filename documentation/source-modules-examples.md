# Source Modules Examples

Practical patterns and real-world examples drawn from production Spaceport applications.

## Project Structure Patterns

### Small Project: Guestbook

The Guestbook application uses two modules with a single subdirectory for document classes.

```
modules/
  App.groovy                 # Routes and Launchpad rendering
  documents/
    Guestbook.groovy         # Document class (package: documents)
```

```yaml
source modules:
  paths:
    - modules/*
```

This is the simplest useful structure. `App.groovy` has no package declaration (it sits at the modules root). `Guestbook.groovy` declares `package documents` to match its subdirectory.

### Medium Project: Port-Mercury

The Port-Mercury starter kit adds a `Router.groovy` alongside `App.groovy`, separating routing concerns from application logic.

```
modules/
  App.groovy                 # Entry point, page routing, admin panel
  Router.groovy              # Error handling, auth middleware, static resources
```

```yaml
source modules:
  paths:
    - modules/*
```

Both files sit at the root of `modules/` with no package declarations. At this scale, packages are not yet necessary.

### Large Project: MadAve-Collab

A production application with 12 modules organized into three packages by responsibility.

```
modules/
  app/                       # Core application logic (package: app)
    App.groovy               # Main routing and authorization
    Router.groovy            # Error handling, static assets, middleware
    Notifications.groovy     # Email and in-app notification logic
    Invoices.groovy          # Invoice generation and management
    Assets.groovy            # Asset/file management routes
  data/                      # Data models (package: data)
    Job.groovy               # Base job document class
    VoiceJob.groovy          # Specialized job type
    UserManager.groovy       # User lookup and permissions
    ContextManager.groovy    # Application context/state
    RateCard.groovy          # Pricing data model
  integrations/              # Third-party services (package: integrations)
    Mailgun.groovy           # Email delivery via Mailgun API
    R2.groovy                # Cloudflare R2 object storage
```

```yaml
source modules:
  paths:
    - modules/*
```

The `*` wildcard scans all three subdirectories. Each subdirectory becomes a Java package. Cross-package references use standard imports:

```groovy
package app

import data.Job
import data.RateCard
import data.UserManager
import integrations.R2
```

---

## The App.groovy Convention

Every Spaceport project has an `App.groovy` file as its primary entry point. While Spaceport does not enforce this convention (any class can handle any alert), it provides a consistent starting point for anyone reading the codebase.

A minimal `App.groovy` typically handles:

- The root route (`on / hit`)
- Main page routing
- Application-level Launchpad instance
- Database initialization (in small projects)

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.computer.alerts.results.Result
import spaceport.launchpad.Launchpad
import spaceport.Spaceport

class App {

    static Launchpad launchpad = new Launchpad()

    @Alert('on initialized')
    static _init(Result r) {
        Spaceport.main_memory_core.createDatabaseIfNotExists('myapp')
    }

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

In larger projects, `App.groovy` may live inside a package (like `app.App` in MadAve-Collab), with database initialization delegated to individual document classes.

---

## Module Patterns

### Static Module

A module where all methods and state are static. This is the most common pattern for route handlers and service classes. The class is never instantiated -- Spaceport calls its static `@Alert` methods directly.

```groovy
class HitCounter {

    static int count = 0

    @Alert('on / hit')
    static _index(HttpResult r) {
        count++
        r.writeToClient("<p>This page has been visited ${count} times.</p>")
    }

}
```

**Caveat:** Static state (like `count` above) is lost on hot-reload because the class is replaced. See [Hot-Reload State Management](#hot-reload-state-management) below for how to persist state across reloads.

### Document Module

A module that extends `Document` to define a data model. Document modules typically use `@Alert('on initialized')` to create databases and register CouchDB views, then provide static factory methods for creating and fetching instances.

```groovy
package documents

import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.computer.memory.physical.Document
import spaceport.computer.memory.physical.ViewDocument

class Guestbook extends Document {

    def type = 'guestbook'
    def customProperties = ['info', 'participants']

    List participants = []

    @Alert('on initialized')
    static _view(Result r) {
        ViewDocument.get('views', 'guestbooks')
            .setViewIfNeeded('all', '''
                function(doc) {
                    if (doc.type == 'guestbook') {
                        emit(doc._id, { info: doc.info });
                    }
                }
            ''')
    }

    void addParticipant(String name, String message, String cookie) {
        participants.add([name: name.clean(), message: message.clean(), cookie: cookie])
        save()
        this._update()
    }

}
```

Key characteristics:

- Uses `@Alert('on initialized')` (not `on initialize`) because view registration can safely happen after other modules have initialized.
- The `customProperties` list declares which instance properties should be serialized to CouchDB.
- `save()` persists to the database; `_update()` triggers reactive updates in connected Launchpad templates.

### Service Module

A module that wraps a third-party integration or shared utility. These modules typically initialize connections or credentials during `on initialize` and expose static methods for other modules to call.

```groovy
package integrations

import spaceport.Spaceport
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result

class Mailgun {

    static String apiKey
    static String domain

    @Alert('on initialize')
    static _init(Result r) {
        apiKey = Spaceport.config.mailgun?.apiKey
        domain = Spaceport.config.mailgun?.domain
    }

    static boolean sendEmail(String to, String subject, String body) {
        // HTTP call to Mailgun API using apiKey and domain
        // ...
    }

}
```

---

## Cross-Module References

All source modules share a single classloader, so cross-module references work through standard Groovy/Java imports. No special wiring is needed.

### Same Package (No Import Needed)

Classes in the same package can reference each other directly:

```groovy
// modules/data/Job.groovy
package data

class Job extends Document {
    // ...
}
```

```groovy
// modules/data/VoiceJob.groovy
package data

class VoiceJob extends Job {
    // Job is in the same package, no import needed
}
```

### Cross-Package (Import Required)

Classes in different packages require an import statement:

```groovy
// modules/app/App.groovy
package app

import data.Job
import data.RateCard
import data.UserManager
import integrations.R2

class App {

    @Alert('~on /v/job/([^/]+) hit')
    static _jobView(HttpResult r) {
        def jobId = r.matches[0]
        r.context.data.job = VoiceJob.getIfExists(jobId)
        // ...
    }

}
```

### Top-Level Classes (No Package)

Classes at the `modules/` root have no package declaration and are accessible by name from any module without an import:

```groovy
// modules/App.groovy (no package)
class App {
    static Launchpad launchpad = new Launchpad()
}
```

```groovy
// modules/documents/Guestbook.groovy
package documents

class Guestbook extends Document {
    // Can reference App directly -- it has no package
    // But in practice, top-level classes are rarely referenced from subdirectories
}
```

---

## Hot-Reload State Management

When hot-reload occurs, all classes are replaced and static fields are reinitialized to their default values. Any state stored in static fields is lost. This section describes patterns for preserving state across reloads.

### Use Cargo for Persistent State

[Cargo](cargo-overview.md) containers survive hot-reload because they are managed by the framework, not by user module classes. Store state in a Cargo instance instead of a static field:

```groovy
import spaceport.computer.memory.virtual.Cargo

class HitCounter {

    // This Cargo instance persists across hot-reloads
    static Cargo hits = new Cargo()

    @Alert('on initialize')
    static _init(Result r) {
        hits.set('count', 0)  // Only sets if not already set
    }

    @Alert('on / hit')
    static _index(HttpResult r) {
        hits.inc('count')
        r.writeToClient("<p>Visits: ${hits.get('count')}</p>")
    }

}
```

The `Cargo` object reference in the static field is lost on reload, but the underlying Cargo data may persist depending on how it is managed. For development purposes, this pattern is more resilient than raw static fields.

### Avoid ClassCastException with `def`

A common pitfall during hot-reload: if you store a typed object in a static field, and the class is reloaded into a new classloader, the old object's class is not the same as the new class -- even if they have the same name. This causes `ClassCastException`.

```groovy
// PROBLEM: ClassCastException after hot-reload
class Cache {
    static List<MyObject> items = []  // Old MyObject != new MyObject after reload
}
```

Use `def` (dynamic typing) to avoid this:

```groovy
// SOLUTION: Use def to avoid type mismatch
class Cache {
    static def items = []  // No type constraint, no ClassCastException
}
```

With `def`, Groovy uses duck typing -- it accesses properties and methods by name rather than by class identity, bypassing the classloader mismatch.

### Reinitialize Gracefully

Design your `@Alert('on initialize')` handlers to be idempotent. They run on first startup and on every hot-reload, so they should check existing state before overwriting it:

```groovy
@Alert('on initialize')
static _init(Result r) {
    // Good: check before creating
    Spaceport.main_memory_core.createDatabaseIfNotExists('myapp')

    // Good: setViewIfNeeded only writes if the view doesn't exist
    ViewDocument.get('views', 'mydb')
        .setViewIfNeeded('all', '...')
}
```

---

## Launchpad Integration

Each module that renders templates creates its own `Launchpad` instance as a static field:

```groovy
class App {
    static Launchpad launchpad = new Launchpad()

    @Alert('on /index.html hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

Multiple modules can each have their own `Launchpad` instance. The Launchpad instance is lightweight -- it primarily serves as an entry point for template assembly and rendering, not as a heavy stateful object.

---

## Error Handling Pattern

A common pattern across projects is to separate error handling into a dedicated module or section:

```groovy
class Router {

    static Launchpad launchpad = new Launchpad()

    // Catch-all handler for unmatched routes
    @Alert('on page hit')
    static _errors(HttpResult r) {
        if (r.context.response.status == 404) {
            launchpad.assemble(['error-pages/404.ghtml']).launch(r, 'wrapper.ghtml')
        } else if (r.context.response.status >= 400) {
            launchpad.assemble(['error-pages/error.ghtml']).launch(r, 'wrapper.ghtml')
        }
    }

}
```

The `on page hit` alert runs after all route-specific handlers, making it a natural place for fallback error rendering. See [Alerts Overview](alerts-overview.md) for details on alert priority and execution order.

---

## Authorization Pattern

Larger projects extract authorization logic into reusable closures (called "plugs") stored as static fields:

```groovy
package app

class Router {

    // Reusable authorization plug
    static def administratorAuthPlug = { HttpResult r ->
        if (r.client.authenticated && r.client.document?.hasPermission('admin')) {
            return true
        } else {
            r.setRedirectUrl('/login?redirect-url=' + r.context.target)
            return false
        }
    }

}
```

```groovy
package app

class App {

    @Alert('on /a/ hit')
    static _admin(HttpResult r) {
        if (r.authorize(Router.administratorAuthPlug)) {
            launchpad.assemble(['admin/overview.ghtml']).launch(r)
        }
    }

}
```

This pattern separates the authorization decision from the route handler, allowing the same plug to protect multiple routes across different modules.

---

## See Also

- [Source Modules Overview](source-modules-overview.md) -- what source modules are and why they exist
- [Source Modules API Reference](source-modules-api.md) -- configuration and lifecycle alerts
- [Source Modules Internals](source-modules-internals.md) -- how class loading and hot-reload work
- [Alerts Overview](alerts-overview.md) -- the event system powering `@Alert` annotations
- [Alerts Examples](alerts-examples.md) -- more patterns for route handling and lifecycle hooks
