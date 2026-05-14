# Source Modules

## What Are Source Modules?

Source modules are the Groovy files that contain your application's server-side logic. They are the classes you write -- route handlers, data models, integrations, utilities -- that Spaceport dynamically compiles and loads at startup. Every Spaceport application is built from source modules.

Unlike traditional Java/Groovy applications where you compile your code ahead of time, Spaceport compiles your source modules on the fly when the server starts. This means there is no separate build step. You write `.groovy` files, point Spaceport at them in your manifest, and the framework handles compilation, class loading, and lifecycle management for you.

Source modules also support **hot-reloading** in debug mode. When you save a file, Spaceport detects the change, tears down the running modules, recompiles everything, and reinitializes -- all without restarting the server.

## Why Source Modules?

The source module system gives you several advantages:

- **No build step.** Write Groovy, save, and it runs. The framework compiles your code at startup.
- **Hot-reload in development.** In debug mode, file changes are detected automatically. The server reloads your modules within milliseconds of saving a file.
- **Convention over configuration.** Place your `.groovy` files in the `modules/` directory, and Spaceport finds and loads them. Subdirectories become Java packages.
- **Cross-module references.** All modules share a single classloader, so any class can reference any other class directly -- no special wiring needed.
- **Lifecycle hooks.** Modules can declare `@Alert` methods that run during initialization and deinitialization, giving you control over setup and teardown.

## Creating Your First Module

A source module is just a Groovy class in your `modules/` directory. Here is the simplest possible module:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class App {

    @Alert('on / hit')
    static _index(HttpResult r) {
        r.writeToClient('<h1>Hello from Spaceport</h1>')
    }

}
```

This single file gives you a working web application. The `@Alert('on / hit')` annotation registers the method as a handler for HTTP requests to the root path `/`. When someone visits your site, Spaceport calls this method and sends back the HTML response.

Save this as `modules/App.groovy`, and your manifest needs just one line to tell Spaceport where to find it:

```yaml
source modules:
  paths:
    - modules/*
```

The `*` at the end of the path tells Spaceport to scan subdirectories recursively, which is the standard configuration for all Spaceport projects.

## A Two-Module Example

Real applications split their logic across multiple files. Here is a minimal two-file application with a route handler and a data model:

**modules/App.groovy** -- the main entry point:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.launchpad.Launchpad

class App {

    static Launchpad launchpad = new Launchpad()

    @Alert('on / hit')
    static _index(HttpResult r) {
        r.setRedirectUrl('index.html')
    }

    @Alert('on /index.html hit')
    static _home(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }

}
```

**modules/documents/Guestbook.groovy** -- a Document class in a subdirectory:

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

}
```

Notice that `Guestbook.groovy` lives in `modules/documents/` and declares `package documents`. When you use the `*` wildcard in your module path, Spaceport scans subdirectories and adds them as classpath roots, so the directory structure maps naturally to Java/Groovy packages. Any other module can then reference this class with `import documents.Guestbook` or simply use it by its fully qualified name.

## Hot-Reloading

When you run Spaceport with `debug: true` in your manifest, the framework watches your module directories for file changes. When you save any `.groovy` file:

1. Spaceport waits 100 milliseconds for any additional changes (debouncing).
2. It fires `on deinitialize` and `on deinitialized` alerts so modules can clean up.
3. All loaded classes, caches, and the classloader are cleared.
4. All modules are recompiled and reloaded from scratch.
5. It fires `on initialize` and `on initialized` alerts so modules can set up again.

This full teardown-and-rebuild cycle ensures a clean state on every reload. The entire process typically completes in under a second.

One important detail: hot-reloading replaces all classes, which means any state stored in static fields is lost. If you need state to survive reloads, use [Cargo](cargo-overview.md) containers, which persist across reload cycles. See [Source Modules Examples](source-modules-examples.md) for patterns on managing state during hot-reload.

## Project Structure Conventions

Every Spaceport project follows a common convention: an `App.groovy` file serves as the primary entry point. This is where you typically define the root route handler and application-level configuration. Beyond that, modules are organized by responsibility:

```
modules/
  App.groovy          # Main entry point, root routes
  Router.groovy       # Additional routing logic
  documents/          # Data model classes (package: documents)
    Guestbook.groovy
```

Larger applications use packages to separate concerns:

```
modules/
  app/                # Core application logic (package: app)
    App.groovy
    Router.groovy
    Notifications.groovy
  data/               # Data models (package: data)
    Job.groovy
    UserManager.groovy
    RateCard.groovy
  integrations/       # Third-party integrations (package: integrations)
    Mailgun.groovy
    R2.groovy
```

For more on project organization patterns, see [Source Modules Examples](source-modules-examples.md).

## Next Steps

- [Source Modules API Reference](source-modules-api.md) -- manifest configuration, lifecycle alerts, and the SourceStore API.
- [Source Modules Internals](source-modules-internals.md) -- how class loading, hot-reload, and file watching work under the hood.
- [Source Modules Examples](source-modules-examples.md) -- real-world patterns from production Spaceport applications.
- [Alerts Overview](alerts-overview.md) -- the event system that powers module lifecycle hooks and route handlers.
