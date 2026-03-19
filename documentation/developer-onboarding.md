# Developer Onboarding

This guide takes you from zero to a running Spaceport application. By the end, you will have installed the prerequisites, created a project, written your first route handler, rendered a template, and understood the core concepts you will use every day as a Spaceport developer.


## Prerequisites

Spaceport needs three things on your system:

| Prerequisite | Minimum Version | Recommended |
|---|---|---|
| Java (JDK or JRE) | Java 8 SE | Java 11 (Azul Zulu or Amazon Corretto) |
| Apache CouchDB | 2.0 | 3.5.x or later |
| Spaceport JAR | Latest | Latest |

You will also need a terminal, a web browser, and a text editor. Any editor works -- Spaceport has no build step, so there is no IDE dependency. If you want syntax highlighting for `.ghtml` template files, Sublime Text with the [Spaceport plugin](https://github.com/aufdemrand/spaceport-slt) or JetBrains IntelliJ IDEA Ultimate with the Grails plugin are good choices.

### Groovy Knowledge

Spaceport server code is written in Groovy, a JVM language that is fully compatible with Java. If you know Java, you already know most of Groovy. The key differences you will encounter immediately are:

- **Optional semicolons and return statements** -- the last expression in a method is its return value
- **`def` keyword** for dynamic typing alongside standard Java types
- **GStrings** -- double-quoted strings support `${ expression }` interpolation
- **Closures** -- `{ args -> body }` blocks that can be passed around like values
- **Maps and lists** -- `[key: value]` for maps, `[a, b, c]` for lists

You do not need to be a Groovy expert to start. Spaceport source modules are plain Groovy classes, and the framework handles compilation for you.

> For a more thorough Groovy primer tailored to Spaceport patterns, see [Groovy Luminary](groovy-luminary.md).

### Editor and IDE Setup

Spaceport has no build step and no IDE requirement — any text editor works. However, a few tools make the experience significantly better:

**JetBrains IntelliJ IDEA** (recommended)

IntelliJ IDEA provides excellent Groovy support out of the box: code completion, refactoring, debugging, and error detection. The **Ultimate** edition adds HTML, CSS, and JavaScript support that is valuable for full-stack Spaceport development. The Community edition is free and handles Groovy well but lacks front-end tooling. Ultimate is free for students and open-source projects.

- Download from the [IntelliJ IDEA Downloads Page](https://www.jetbrains.com/idea/download/)
- Install the **Grails plugin** for `.ghtml` template support:
  1. Go to `File > Settings > Plugins` (or `IntelliJ IDEA > Preferences > Plugins` on macOS)
  2. Search the Marketplace for `Grails` and install it
  3. Restart the IDE
  4. Associate `.ghtml` files with Groovy Server Pages: `Preferences > Editor > File Types`, find Groovy Server Pages, and add `*.ghtml` to its patterns

> The Grails/GSP association is not a perfect match for Spaceport's template syntax, but it provides reasonable syntax highlighting and basic editing features while dedicated Spaceport tooling is in development.

**IntelliJ IDEA with JetBrains Gateway** (remote development)

If your Spaceport application runs on a remote server, JetBrains Gateway lets you develop remotely with a local IDE experience. Enable SSH on your server, install Gateway locally, and connect — all file operations happen on the remote machine with hot-reload working as normal.

**Sublime Text**

A lightweight alternative with Spaceport-specific support. Install the [Spaceport plugin](https://github.com/aufdemrand/spaceport-slt) for `.ghtml` syntax highlighting.

**IDE Tip: Language Injection**

In IntelliJ, you can get syntax highlighting inside embedded strings by using language injection comments. Spaceport's real-world code uses this pattern for CouchDB view functions and Server Element JavaScript:

```groovy
// JavaScript highlighting inside a Groovy string
@Javascript String constructed = /* language=javascript */ """
    function(element) {
        element.listen('click', function(e) {
            // IntelliJ highlights this as JavaScript
        })
    }
"""

// JavaScript highlighting for CouchDB map functions
ViewDocument.get('jobs', 'jobs').setViewIfNeeded(
    'list-jobs', /* language=javascript */ '''
        function(doc) {
            if (doc.type === 'job') emit(doc.created, doc);
        }
    '''
)
```

The `/* language=javascript */` comment is stripped during processing — it exists purely for IDE support.


## Installing Java

Check if Java is already installed:

```bash
java -version
```

If you see a version number (1.8 or higher), you are set. If not, install a JDK. For development, any distribution works. For production, an LTS distribution avoids frequent upgrades:

- **[Azul Zulu](https://www.azul.com/downloads/)** -- Free, open-source OpenJDK with LTS for Java 8, 11, and 17
- **[Amazon Corretto](https://aws.amazon.com/corretto/)** -- Free LTS OpenJDK from Amazon
- **[OpenJDK](https://openjdk.org/)** -- Rolling releases, no LTS

On Debian/Ubuntu, the quickest path is:

```bash
sudo apt install default-jre
```

For Java version compatibility details beyond Java 11, see [Compatibility Notes](compatibility-notes.md).


## Installing CouchDB

CouchDB is the database that backs all persistent data in Spaceport. Install it from the [CouchDB downloads page](https://couchdb.apache.org/#download) or through your system package manager.

During installation:

1. Choose **standalone** mode (not clustered) for local development
2. Set the bind address to `127.0.0.1` on Linux or `localhost` on Windows
3. Set an admin password and note it down -- you will need it when configuring Spaceport

After installation, verify CouchDB is running by visiting `http://localhost:5984/_utils/` in your browser. You should see the Fauxton web interface. This admin panel is useful for inspecting your databases during development, but Spaceport handles all database operations for you through its [Documents API](documents-overview.md).


## Getting the Spaceport JAR

Spaceport is distributed as a single JAR file. Download the latest version from the [Spaceport builds repository](/builds) and place it in a directory of your choice. All Spaceport commands are run through this JAR:

```bash
java -jar spaceport.jar --help
```

If you want a shorter command, create a shell alias or wrapper script:

```bash
alias spaceport='java -jar /path/to/spaceport.jar'
```

For full CLI documentation, see [CLI Overview](cli-overview.md).


## Creating a Project

You have two paths to create a new Spaceport project: the interactive CLI wizard, or downloading a starter kit.

### Option A: The --create-port Wizard

The CLI wizard walks you through an interactive setup process that creates your project structure, writes a manifest configuration file, configures your database connection, and creates an administrator account.

```bash
java -jar spaceport.jar --create-port
```

The wizard offers three scaffold types:

| Scaffold | What You Get | Best For |
|---|---|---|
| **Mercury** | A single `App.groovy` in `modules/` | APIs, microservices, learning |
| **Pioneer** | Source modules + Launchpad templates | Sites with pages, no user accounts |
| **Voyager** | Full app with login, registration, admin panel | Production web applications |

For your first project, **Mercury** is the right choice. It gives you the minimum needed to start, and you can add structure as you go. See [Scaffolds Overview](scaffolds-overview.md) for details on what each scaffold creates.

### Option B: Download a Starter Kit

If you prefer starting from a pre-built project you can inspect and modify:

- **[Port-Echo](https://github.com/spaceport-dev/port-echo)** -- Minimal starter with a manifest and a single source module
- **[Port-Mercury](https://github.com/spaceport-dev/port-mercury)** -- Full-featured starter with routing, authentication, templates, and inline documentation

Clone the repository, update the manifest with your CouchDB credentials, and you are ready to go.

### Option C: Zero-Config Start

For the fastest possible start with no setup at all:

```bash
mkdir my-app && cd my-app
mkdir modules
```

Create `modules/App.groovy` (we will write the contents in a moment), then start Spaceport without a manifest:

```bash
java -jar spaceport.jar --start --no-manifest
```

Spaceport binds to `127.0.0.1:10000`, looks for source modules in `modules/`, and connects to CouchDB at the default address. This mode is great for learning, but use a manifest file for anything beyond quick experiments.


## Project Directory Structure

A typical Spaceport project looks like this:

```
my-app/
  config.spaceport          # Manifest configuration (YAML)
  modules/                  # Groovy source modules (your server code)
    App.groovy
    Router.groovy
    documents/              # Subdirectories become Java packages
      Message.groovy
  launchpad/                # Launchpad template files
    parts/                  # Page templates (.ghtml files)
      index.ghtml
      about.ghtml
    elements/               # Reusable template components
  assets/                   # Static files (CSS, JS, images)
    css/
    js/
    images/
  stowaways/                # Third-party JAR libraries
  migrations/               # Database migration scripts
```

The key directories are:

- **`modules/`** -- Where all your server-side Groovy code lives. Spaceport compiles these automatically at startup. Subdirectories map to Java packages, so `modules/documents/Message.groovy` has `package documents`.
- **`launchpad/parts/`** -- Where your `.ghtml` template files live. These are Groovy-embedded HTML files that Spaceport renders on the server.
- **`assets/`** -- Static files served directly to the browser at the `/assets/` URL path.
- **`config.spaceport`** -- Your application's configuration file. See [Manifest Overview](manifest-overview.md) for all options.

The manifest tells Spaceport where to find everything. A minimal `config.spaceport` looks like:

```yaml
host:
  port: 10000

memory cores:
  main:
    username: admin
    password: your-couchdb-password

source modules:
  paths:
    - modules/*

debug: true
```

You only need to specify what differs from the defaults. Everything else is filled in automatically. See [Manifest Overview](manifest-overview.md) for the full default configuration and all available keys.


## Your First Route: Hello World

Create `modules/App.groovy` with the following content:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class App {

    @Alert('on / hit')
    static _index(HttpResult r) {
        r.writeToClient('<h1>Hello from Spaceport!</h1>')
    }

}
```

Start the server:

```bash
java -jar spaceport.jar --start --no-manifest
```

Open `http://localhost:10000` in your browser. You should see "Hello from Spaceport!" rendered as a heading.

Here is what happened:

1. Spaceport scanned the `modules/` directory and found `App.groovy`
2. It compiled the class and discovered the `@Alert('on / hit')` annotation
3. It registered the `_index` method as a handler for HTTP requests to `/`
4. When your browser sent a GET request to `/`, Spaceport dispatched the event and called your method
5. `r.writeToClient()` wrote the HTML string directly to the HTTP response

The `@Alert` annotation is the core of Spaceport's event system. The string `'on / hit'` is an **event string** that matches HTTP requests to the root path. Every route, every lifecycle hook, and every event listener in Spaceport uses this same annotation. See [Alerts Overview](alerts-overview.md) for the full story.


## Adding a Second Route

Add another method to `App.groovy`:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class App {

    @Alert('on / hit')
    static _index(HttpResult r) {
        r.writeToClient('<h1>Hello from Spaceport!</h1><p><a href="/about">About</a></p>')
    }

    @Alert('on /about hit')
    static _about(HttpResult r) {
        r.writeToClient('<h1>About</h1><p>This is my first Spaceport app.</p>')
    }

}
```

If you started with `debug: true` (which is the default in no-manifest mode), save the file and Spaceport will **hot-reload** your modules automatically. No restart needed. Visit `http://localhost:10000/about` to see the new page.

Hot-reloading compiles and reloads all source modules within milliseconds of a file save. This tight feedback loop is one of Spaceport's core development advantages. See [Source Modules Overview](source-modules-overview.md) for details on how it works.


## Rendering Templates with Launchpad

Writing HTML inside `writeToClient()` strings is fine for simple responses, but real pages need Launchpad templates. Launchpad is Spaceport's server-side templating engine. Templates are `.ghtml` files -- HTML with embedded Groovy code -- stored in `launchpad/parts/`.

Create the template directory and two files:

**`launchpad/parts/wrapper.ghtml`** -- a layout wrapper that wraps every page:

```html
<!DOCTYPE html>
<html>
<head>
    <title>My First App</title>
</head>
<body>
    <nav>
        <a href="/">Home</a> |
        <a href="/about">About</a>
    </nav>
    <main>
        <payload/>
    </main>
</body>
</html>
```

The `<payload/>` tag marks where assembled page content goes.

**`launchpad/parts/index.ghtml`** -- the home page content:

```html
<%
    def greeting = 'Hello from Launchpad!'
    def timestamp = new Date().format('h:mm a')
%>

<h1>${ greeting }</h1>
<p>Server time: ${ timestamp }</p>
```

Now update `App.groovy` to use Launchpad:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.launchpad.Launchpad

class App {

    static Launchpad launchpad = new Launchpad()

    @Alert('on / hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }

}
```

The pattern is always the same: `assemble()` selects which template files to combine, and `launch()` renders them into the HTTP response. The second argument to `launch()` is the wrapper template.

Save all three files. With hot-reload active, visit `http://localhost:10000` and you will see your templated page with the nav bar, greeting, and server time.

For the full templating system including reactive bindings, server actions, and template composition, see [Launchpad Overview](launchpad-overview.md).


## Key Concepts

Now that you have a running application, here is a quick map of the major systems you will work with as you build out your project.

### Alerts -- The Event System

Alerts are Spaceport's unified event system. Every HTTP request, WebSocket message, document save, and lifecycle event is dispatched as an Alert. Your code listens with the `@Alert` annotation.

```groovy
@Alert('on /login POST')           // Handle POST to /login
@Alert('on initialized')           // Run code at startup
@Alert('on document saved')        // React when any document is saved
@Alert('on socket connect')        // Handle new WebSocket connections
```

Alerts support priorities for ordering (authentication checks before route handlers) and cancellation for short-circuiting (redirect unauthenticated users before the page renders). One annotation, one pattern, for everything.

Read more: [Alerts Overview](alerts-overview.md)

### Source Modules -- Your Server Code

Source modules are the Groovy classes in your `modules/` directory. Spaceport compiles them at startup -- no build step required. In debug mode, saving a file triggers automatic hot-reload.

Subdirectories map to Java packages. All modules share one classloader, so any class can reference any other. Structure your code however makes sense for your project.

Read more: [Source Modules Overview](source-modules-overview.md)

### Launchpad -- Server-Side Templating

Launchpad renders `.ghtml` templates on the server with full access to your Groovy code, database, and session state. Beyond basic templating, it provides **reactive bindings** (`${{ }}`) that push live DOM updates when server data changes, and **server actions** (`_{ }`) that bind Groovy closures to client-side events like clicks and form submissions.

Read more: [Launchpad Overview](launchpad-overview.md)

### Cargo -- Reactive Data Containers

Cargo is a reactive key-value store. When you update a Cargo value, any Launchpad template bound to it re-renders automatically on the client via WebSocket. Cargo comes in three modes: in-memory, server-wide shared, and CouchDB-backed persistent. Every user session also has a per-session Cargo called the **dock**.

Read more: [Cargo Overview](cargo-overview.md)

### Documents -- CouchDB ORM

Documents are Spaceport's data layer. The `Document` class maps Groovy objects to CouchDB JSON documents with automatic serialization, revision tracking, and lifecycle events. You can use the base `Document` class for simple key-value storage or extend it to create typed data models with custom properties and business logic.

Read more: [Documents Overview](documents-overview.md)

### Manifest -- Configuration

The `config.spaceport` file (YAML format) controls how your application starts: host, port, database connection, source module paths, static asset paths, debug mode, and any custom keys your application needs. Spaceport deep-merges your configuration over sensible defaults, so you only specify what you want to change.

Read more: [Manifest Overview](manifest-overview.md)


## The Development Loop

With your project running in debug mode, the development loop is:

1. **Edit** a `.groovy` source module or `.ghtml` template
2. **Save** the file
3. **Refresh** your browser (or watch reactive bindings update automatically)

There is no compile step, no build command, no restart. Spaceport watches for file changes and hot-reloads within milliseconds. This feedback loop lets you iterate rapidly and experiment freely.


## Quick Start Checklist

- [ ] Install Java 8+ (Java 11 LTS recommended)
- [ ] Install CouchDB and note the admin credentials
- [ ] Download the Spaceport JAR
- [ ] Create a project (`--create-port`, starter kit, or manual)
- [ ] Write a source module with an `@Alert` route handler
- [ ] Start Spaceport and verify your route works in the browser
- [ ] Add a Launchpad template and render it with `assemble().launch()`
- [ ] Explore the documentation links below


## Next Steps

Once you are comfortable with the basics, pick a path:

**Hands-on tutorials:**
- [Tic-Tac-Toe Tutorial](tic-tac-toe.md) -- Build an interactive game covering routes, templates, and server actions
- [Meeting Room Booker Tutorial](meeting-room.md) -- Build a real-time booking system with Documents and Cargo

**Deep dives into core systems:**
- [Alerts Overview](alerts-overview.md) -- The event system behind routes, lifecycle hooks, and inter-module communication
- [Source Modules Overview](source-modules-overview.md) -- How Spaceport compiles, loads, and hot-reloads your code
- [Launchpad Overview](launchpad-overview.md) -- Templating, reactive bindings, and server actions
- [Cargo Overview](cargo-overview.md) -- Reactive data containers for session state, caching, and persistence
- [Documents Overview](documents-overview.md) -- The CouchDB ORM for persistent data
- [Scaffolds Overview](scaffolds-overview.md) -- Project structure and the `--create-port` wizard
- [Manifest Overview](manifest-overview.md) -- Configuration file format and all available keys
- [CLI Overview](cli-overview.md) -- All Spaceport command-line commands

**Starter projects to explore:**
- [Port-Echo](https://github.com/spaceport-dev/port-echo) -- Minimal starter kit
- [Port-Mercury](https://github.com/spaceport-dev/port-mercury) -- Full-featured starter with authentication and templates
- [Guestbook](https://github.com/aufdemrand/guestbook.ing) -- A real-world example application
