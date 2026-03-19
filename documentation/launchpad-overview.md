# Launchpad (Templating Engine) -- Overview

Launchpad is Spaceport's server-side templating engine. It reads `.ghtml` template files (Groovy-embedded HTML), combines them into pages, and writes the rendered output to the HTTP response. Beyond basic templating, Launchpad provides two features that distinguish it from traditional engines: **reactive bindings** that automatically push DOM updates when server-side data changes, and **server actions** that let you bind Groovy closures directly to client-side events like clicks and form submissions.

## Why Launchpad?

Most web frameworks require you to build a separate API layer between your server logic and your UI. You write route handlers that return JSON, then write client-side JavaScript that fetches that JSON and updates the DOM. Launchpad eliminates this middleman. Your templates run Groovy code on the server with full access to your database, business logic, and session state -- and the results render directly as HTML. When you need interactivity, reactive bindings and server actions handle the communication transparently over WebSocket.

The result is a development model where a single `.ghtml` file can contain the markup, the data queries, the form handling logic, and the reactive update rules for an entire page section. There is no separate frontend codebase to maintain.

## Core Concepts

### GHTML Templates

Templates are HTML files with a `.ghtml` extension stored in a `launchpad/parts/` directory. They use Groovy's template syntax to embed server-side code:

```html
<!-- launchpad/parts/greeting.ghtml -->
<%@ page import="spaceport.computer.memory.physical.Document" %>
<%
    def user = Document.get(client.userID, 'users')
%>

<h1>Hello, ${ user.fields.name }!</h1>
<p>You last visited ${ user.fields.lastVisit?.relativeTime() ?: 'never' }.</p>
```

The `<% %>` blocks execute Groovy code on the server. The `${ }` expressions interpolate values into the HTML output. The client never sees the Groovy code -- they receive plain HTML.

### The Assemble/Launch Pattern

Every Launchpad page follows a two-step pattern:

1. **Assemble** -- Choose which template files make up the page
2. **Launch** -- Render those templates and write the output to the HTTP response

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.launchpad.Launchpad

class Router {

    static Launchpad launchpad = new Launchpad()

    @Alert('on / hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

The `assemble()` method takes a list of template filenames (relative to `launchpad/parts/`). The `launch()` method renders them, optionally wrapping the combined output in a "vessel" template. The vessel uses a `<payload/>` tag to mark where the assembled content goes:

```html
<!-- launchpad/parts/wrapper.ghtml -->
<!DOCTYPE html>
<html>
<head>
    <title>My App</title>
    <script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
</head>
<body>
    <payload/>
</body>
</html>
```

### Reactive Bindings

Wrap an expression in `${{ }}` (double curly braces) to create a reactive binding. Launchpad tracks which variables and Cargo fields the expression depends on, and when those values change, it pushes a DOM update to the client over WebSocket -- automatically.

```html
<p>Messages: ${{ dock.messages.getList().size() }}</p>
<button on-click=${ _{ dock.messages.push('New message') }}>
    Add Message
</button>
```

When the button is clicked, the server action adds a message to the session-scoped Cargo. Launchpad detects that `dock.messages` changed, re-evaluates the `${{ }}` expression, and sends the updated count to the browser. The paragraph updates without a page reload.

### Server Actions

The `_{ }` syntax inside a `${ }` expression creates a server action -- a Groovy closure that executes on the server when a client-side event fires. Server actions are bound to DOM events using `on-*` attributes:

```html
<button on-click=${ _{ println 'Button clicked on server!' }}>Click Me</button>

<form on-submit=${ _{ t ->
    def name = t.name.clean()
    def email = t.email.clean()
    Document.get('contacts', 'mydb').cargo.setNext([name: name, email: email])
    "<p>Thanks, ${ name }!</p>"
}}>
    <input name='name' required>
    <input name='email' type='email' required>
    <button type='submit'>Submit</button>
</form>
```

The closure's parameter `t` (the "transmission") carries client-side data -- form fields, element values, event details. The closure's return value is sent back to the client. Returning a String replaces the source element's content. Returning a Map can target multiple DOM elements or trigger special behaviors like redirects.

## Built-in Template Variables

Every GHTML template has access to these variables without any imports:

| Variable | Type | Description |
|---|---|---|
| `r` | `HttpResult` | The current HTTP request/response object |
| `data` | `Map` | Request parameters (GET query string, POST form data) plus any data set by the route handler |
| `client` | `Client` | The session/user object. Check `client.authenticated` for login status |
| `dock` | `Cargo` | Session-scoped reactive data container tied to the user's `spaceport-uuid` cookie |
| `cookies` | `Map` | Request cookies |
| `context` | `HttpContext` | HTTP context with `method`, `target`, `headers`, `request`, `response` |

## HUD-Core Requirement

For reactive bindings and server actions to work, the client page must include the HUD-Core JavaScript library. This lightweight library (~23KB minified) handles WebSocket connections, event binding for `on-*` attributes, and DOM updates from server reactions.

```html
<script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
```

HUD-Core is not required for purely static server-rendered pages -- only when you use `${{ }}` reactive bindings or `_{ }` server actions.

## A Complete Example

This example from the Guestbook project shows all the major Launchpad features working together. The route handler in the source module:

```groovy
static def launchpad = new Launchpad()

@Alert('on /index.html hit')
static _index(HttpResult r) {
    launchpad.assemble(['index.ghtml', 'history.ghtml']).launch(r, '_wrapper.ghtml')
}
```

And a template that uses reactive bindings and server actions to display and manage guestbook entries:

```html
<%@ page import="spaceport.computer.memory.physical.Document; documents.Guestbook" %>
<%
    def guestbook_id = r.matches[0]
    def gb = Document.get(guestbook_id, 'guestbooks') as Guestbook
    def cookie = cookies.'spaceport-uuid'
%>

<strong>Guestbook for <span>${{ gb.info.name }}</span></strong>

<% if (!gb.hasParticipant(cookie) && gb.isOpen()) { %>

<form on-submit='${ _{ t ->
    gb.addParticipant(t.name, t.email, t.getBool('public'), t.message, cookie)
    [ '@reload' ]
}}'>
    <input name='name' required type='text' placeholder='Enter your name'>
    <input name='email' type='email' placeholder='Email (optional)'>
    <textarea name='message' placeholder='Leave a message' rows='4'></textarea>
    <button type='submit'>Sign</button>
</form>

<% } %>

${{ gb.participants.combine { p -> """
    <div>
        <strong>${ p.name }</strong>
        ${ p.time_signed.relativeTime() }
    </div>
""" } }}
```

This single file queries the database, checks the user's session, renders a conditional form with server-side validation, and displays a reactive list of participants that updates automatically when new signatures are added.

## What's Next

- **[Launchpad API Reference](launchpad-api.md)** -- Complete method signatures for the `Launchpad` class, template syntax reference, `<g:*>` generators, and transmission API.
- **[Launchpad Internals](launchpad-internals.md)** -- How template preprocessing, reactive binding tracking, the Catch proxy, and WebSocket updates work under the hood.
- **[Launchpad Examples](launchpad-examples.md)** -- Real-world patterns from production Spaceport applications including page composition, form handling, reactive lists, and nested templates.
