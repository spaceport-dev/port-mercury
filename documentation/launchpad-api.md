# Launchpad (Templating Engine) -- API Reference

## `Launchpad` Class

```groovy
import spaceport.launchpad.Launchpad
```

The main class for assembling and rendering GHTML templates. Typically instantiated once per source module and reused across route handlers.

---

### Constructors

#### `Launchpad()`

Creates a Launchpad instance using the default template directory. Spaceport resolves the directory in this order:

1. `{spaceport root}/{spaceport name}/launchpad/`
2. `{spaceport root}/launchpad/`

Throws an exception if neither directory exists.

```groovy
static Launchpad launchpad = new Launchpad()
```

#### `Launchpad(String sourcePath)`

Creates a Launchpad instance with an explicit template directory. Accepts both absolute and relative paths. Relative paths are resolved against the `spaceport root` configuration value.

| Parameter | Type | Description |
|---|---|---|
| `sourcePath` | `String` | Path to the directory containing `parts/` and `elements/` subdirectories |

```groovy
static Launchpad launchpad = new Launchpad('/opt/app/custom-templates')
```

The specified directory must contain a `parts/` subdirectory for template files. An `elements/` subdirectory is scanned for Server Element definitions if present.

---

### Methods

#### `assemble(List<String> payloadFiles)`

Selects template files that will make up the page content. Returns a **new** `Launchpad` instance configured with the specified parts, leaving the original instance unchanged.

| Parameter | Type | Description |
|---|---|---|
| `payloadFiles` | `List<String>` | Template filenames relative to `{sourcePath}/parts/` |

**Returns:** `Launchpad` -- a new instance ready for `launch()`

```groovy
// Single part
launchpad.assemble(['index.ghtml'])

// Multiple parts, rendered in order
launchpad.assemble(['header.ghtml', 'content.ghtml', 'footer.ghtml'])

// Dynamic assembly
def parts = ['nav.ghtml']
if (showSidebar) parts << 'sidebar.ghtml'
parts << 'main.ghtml'
launchpad.assemble(parts)
```

**Important:** `assemble()` does not modify the Launchpad instance it is called on. It creates and returns a new Launchpad with the same `sourcePath` and a fresh `launchID`. This means calling `assemble()` on the same static Launchpad instance from concurrent requests is safe.

---

#### `launch(HttpResult r, String vessel = null)`

Renders all assembled templates and writes the combined HTML to the HTTP response. This is the terminal method in the assemble/launch chain.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `r` | `HttpResult` | *(required)* | The HTTP result object to write the response to |
| `vessel` | `String` | `null` | Optional wrapper template filename (relative to `parts/`). The wrapper's `<payload/>` tag is replaced with the assembled content. |

```groovy
// Without wrapper
launchpad.assemble(['standalone-page.ghtml']).launch(r)

// With wrapper
launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
```

**Behavior:**

1. Populates the template binding with `r`, `context`, `client`, `data`, `dock`, `cookies`, and internal state
2. Primes (renders) the vessel template first, then each part in order
3. Replaces `<payload/>` in the vessel with the combined parts output
4. Processes any Server Elements found in the output
5. If reactive bindings or server actions were used, injects a WebSocket connection script before `</body>`
6. Writes the final HTML string to the response via `r.writeToClient()`

**Vessel requirements:** If a vessel is specified, it must contain a `<payload/>` tag. If the tag is missing, Launchpad sets the response status to 501.

This method is `synchronized` on the Launchpad instance.

---

## Template Syntax

### Scriptlet Blocks `<% %>`

Execute Groovy code on the server. No output is produced unless you explicitly write to `out`.

```html
<%
    def items = Document.get('items', 'mydb').cargo.getList()
    def total = items.sum { it.price }
%>
```

### Expression Output `<%= %>` and `${ }`

Both syntaxes interpolate a Groovy expression into the HTML output.

```html
<!-- These are equivalent -->
<h1><%= client.userID %></h1>
<h1>${ client.userID }</h1>
```

The `${ }` syntax is preferred as it allows inline usage within HTML attributes and text.

### Reactive Bindings `${{ }}`

Creates a reactive expression that automatically re-evaluates and updates the DOM when its dependencies change. Under the hood, `${{ expr }}` is transformed into `${ _{ _script, _registration -> expr }}`, creating a two-parameter closure that Launchpad treats as a "launch reaction."

```html
<span>${{ dock.counter.getDefaulted(0) }}</span>
<span>${{ myVariable }}</span>
<div>${{ items.combine { "<p>${ it.name }</p>" } }}</div>
```

**How it works:**

1. During initial render, the expression is evaluated with a `Catch` proxy intercepting property access
2. The proxy records which properties and Cargo fields were accessed (the "dependencies")
3. The rendered output is wrapped in HTML comment markers: `<!-- START_CHUNK@uuid -->...<!-- END_CHUNK@uuid -->`
4. When a server action modifies one of the tracked dependencies, Launchpad re-evaluates the expression and sends the new content to the client via WebSocket
5. HUD-Core on the client finds the comment markers and replaces the content between them

**Dependencies tracked:**

- Template-level variables defined with `def` (these are automatically converted to reactive properties)
- Cargo field accesses (`dock.counter`, `myCargoInstance.someField`)
- Method calls on tracked objects

**Requirements:** HUD-Core must be loaded on the client for reactive updates to work.

---

### Server Actions `_{ }`

Creates a server-side closure bound to a client event. When used inside `${ }`, it generates a URL that triggers the closure when called.

#### No-parameter form (0 parameters)

Returns a URL string. Typically used with `on-click` and other event attributes that don't need client data:

```html
<button on-click=${ _{ println 'clicked' }}>Click</button>
```

The generated URL points to `/!/lp/bind?launch={launchID}&uuid={bindingUUID}`.

#### Transmission form (1 parameter)

Receives a "transmission" object `t` containing client-side data:

```html
<form on-submit=${ _{ t ->
    def name = t.name.clean()
    return "Hello, ${ name }!"
}}>
    <input name='name' required>
    <button type='submit'>Send</button>
</form>
```

The transmission parameter `t` is a Map enhanced with convenience methods.

---

### Transmission Object (Server Action Parameter)

The transmission object passed to server action closures is the request's `data` Map with additional typed accessor methods:

| Method | Return Type | Description |
|---|---|---|
| `t.fieldName` | `Object` | Direct property access to form fields or element values |
| `t.get('field')` | `Object` | Map-style access |
| `t.getString('field')` | `String` | Coerce value to String |
| `t.getNumber('field')` | `Number` | Parse as Long or Double. Returns `0L` on failure. |
| `t.getBool('field')` | `Boolean` | Coerce value to Boolean |
| `t.getInteger('field')` | `Integer` | Coerce value to Integer |
| `t.getList('field')` | `List` | Parse as List. Handles JSON arrays, comma-separated strings, and single values. |

### Server Action Return Values

The return value of a server action closure determines what happens on the client:

| Return Type | Client Behavior |
|---|---|
| `String` | Replaces the source element's innerHTML (or the element specified by `target`) |
| `Map` | Processed as a set of DOM instructions (see below) |
| `List` | Serialized as JSON and sent to the client |
| Other | Converted to String via `.toString()` |

**Map return instructions:**

| Key Pattern | Description | Example |
|---|---|---|
| `'> .selector'` | Replace innerHTML of matching element | `['> .count': '42']` |
| `'+className'` | Add CSS class to source element | `['+active': true]` |
| `'-className'` | Remove CSS class from source element | `['-hidden': true]` |
| `'@redirect'` | Redirect the browser to a URL | `['@redirect': '/dashboard']` |
| `'@reload'` | Reload the current page | `['@reload']` returned as a List |
| `'@print'` | Trigger browser print dialog | `['@print']` returned as a List |
| `'@remove'` | Remove the source element from the DOM | `['@remove']` returned as a List |

```groovy
// Combined example
_{ t ->
    dock.counter.inc()
    ['> #count': dock.counter.get(), '+updated': true, '-stale': true]
}
```

---

### `on-*` Event Attributes

Server actions are bound to DOM events using `on-*` attributes. HUD-Core intercepts these attributes and wires them to the generated server action URLs.

| Attribute | Triggered By |
|---|---|
| `on-click` | Element click |
| `on-submit` | Form submission |
| `on-change` | Input value change |
| `on-input` | Input event (fires on each keystroke) |
| `on-blur` | Element loses focus |
| `on-focus` | Element gains focus |
| `on-keydown` | Key press |
| `on-keyup` | Key release |
| `on-mouseover` | Mouse enters element |
| `on-mouseout` | Mouse leaves element |
| `on-load` | Element/page load |

### `target` Attribute

Controls which DOM element receives the server action's return value:

| Value | Behavior |
|---|---|
| *(not set)* | The source element that triggered the event |
| `"self"` | Same as default -- the source element |
| `"parent"` | The source element's parent |
| `"#id"` | Element with the specified ID |
| `".class"` | First element matching the CSS class |
| CSS selector | First element matching the selector |

```html
<button target="#output" on-click=${ _{ "Updated!" }}>Update</button>
<div id="output">Waiting...</div>
```

---

## `<g:*>` Generator Tags

Generator tags are preprocessed by `SpaceportTemplateEngine` into Groovy template code before the template is compiled. They provide a declarative syntax for common control structures. Self-closing syntax (`<g:tag />`) is supported.

### `<g:if>`

Conditional rendering. The `condition` attribute accepts a Groovy expression, a `$variable` reference, or a `storeName#flagPath` Cargo reference.

```html
<g:if condition="${ client.authenticated }">
    <p>Welcome back!</p>
</g:if>
```

```html
<g:if condition="myStore#user.isAdmin">
    <p>Admin panel</p>
</g:if>
```

### `<g:elseif>`

Else-if branch. Must follow a `<g:if>` or another `<g:elseif>`.

```html
<g:if condition="${ role == 'admin' }">
    <p>Admin view</p>
</g:if>
<g:elseif condition="${ role == 'editor' }">
    <p>Editor view</p>
</g:elseif>
```

### `<g:else>`

Else branch. Must follow a `<g:if>` or `<g:elseif>`.

```html
<g:if condition="${ items.size() > 0 }">
    <p>Found items</p>
</g:if>
<g:else>
    <p>No items found</p>
</g:else>
```

### `<g:each>`

Iteration. The `in` attribute specifies the collection (as a `$variable`, `${expression}`, or `storeName#path`). The optional `var` attribute names the loop variable (defaults to `it`).

```html
<g:each in="${ items }" var="item">
    <li>${ item.name }</li>
</g:each>

<g:each in="myStore#products" var="product">
    <div>${ product.name } - ${ product.price }</div>
</g:each>
```

### `<g:repeat>`

Repeats the body a fixed number of times.

| Attribute | Type | Description |
|---|---|---|
| `count` | `int` | Number of repetitions |

```html
<g:repeat count="5">
    <div class='star'>&#9733;</div>
</g:repeat>
```

### `<g:flag>`

Outputs the value of a Cargo flag. The `path` attribute uses the `storeName#flagPath` syntax.

```html
<g:flag path="myStore#user.name"></g:flag>
```

This is equivalent to `<%= Cargo.fromStore("myStore").get("user.name") %>`.

### `<g:javascript>`

Alias for `<script type="text/javascript">`. Provides better IDE support in IntelliJ for Groovy-embedded JavaScript.

```html
<g:javascript>
    console.log('Hello from JavaScript');
</g:javascript>
```

### Custom Element Tags

Any `<g:tag-name>` not matching a built-in generator is looked up in the registered Server Elements. If found, it is processed as a Server Element instance. See the [Server Elements documentation](server-elements-overview.md) for details.

---

## Template Composition

### `prime(String filePath)`

Available inside templates as the `prime()` function. Reads, processes, and renders another template file inline, effectively inserting its output at the call site. The primed template shares the same variable binding as the calling template.

| Parameter | Type | Description |
|---|---|---|
| `filePath` | `String` | Template filename relative to `parts/` |

**Returns:** `String` -- the rendered HTML output

```html
<!-- Inline priming via expression -->
<%= prime('partials/navigation.ghtml') %>

<!-- Priming via scriptlet -->
<%
    def navHtml = prime('partials/navigation.ghtml')
%>
${ navHtml }
```

### `<g:prime>` Tag

Declarative syntax for priming. The template is included during preprocessing, before the main template is compiled.

```html
<g:prime src="partials/sidebar.ghtml"/>
```

### Variable Sharing Across Parts

All parts assembled in a single `launch()` share the same `Binding` object. Variables defined in one part are accessible in subsequent parts. Variables defined in the vessel are accessible to all parts.

```html
<!-- wrapper.ghtml -->
<% def theme = 'light' %>
<html>
<body class="${ theme }">
    <payload/>
</body>
</html>

<!-- content.ghtml (can read and modify 'theme') -->
<% theme = 'dark' %>
<p>Theme is: ${ theme }</p>
```

The `@Provided` annotation is a documentation hint for variables expected from outer scopes. It has no runtime effect -- the annotation is stripped during preprocessing.

```html
<%
    @Provided def theme  // Documenting that 'theme' comes from wrapper
%>
```

---

## Imports in Templates

Use JSP/GSP-style page imports. Multiple classes can be separated by semicolons.

```html
<%@ page import="spaceport.computer.memory.physical.Document" %>
<%@ page import="spaceport.computer.memory.virtual.Cargo; spaceport.Spaceport" %>
```

These are converted to Groovy `import` statements during preprocessing.

---

## Comments

Lines starting with `///` (triple slash, optionally preceded by whitespace) are stripped during preprocessing. They do not appear in the rendered HTML output. Unlike HTML comments (`<!-- -->`), triple-slash comments work universally inside HTML, embedded JavaScript, embedded CSS, and Groovy scriptlet blocks.

```html
/// This comment is removed entirely from the output
<div>
    /// Even works inside elements
    <p>Visible content</p>
</div>

<script>
    /// Works in embedded JavaScript too
    var x = 42;
</script>
```

---

## CSS Class Shorthand

Tags support a dot-notation shorthand for CSS classes, similar to Emmet syntax:

```html
<!-- These are equivalent -->
<div.container.padded>Content</div>
<div class='container padded'>Content</div>

<!-- Works with any tag -->
<section.hero.centered>
    <h1.title>Hello</h1>
</section>
```

---

## Dollar Sign Escaping

Since Groovy uses `$` for expressions, literal dollar signs in GHTML templates must be escaped with a backslash: `\$`. This applies to inline CSS and JavaScript within the template. External linked files do not require escaping.

```html
<span>\$19.99</span>
<script>
    var price = '\$' + amount;
</script>
```

---

## `def` Variables in Templates

Template-level `def` variables (those outside closures) have their `def` keyword stripped during preprocessing. This converts them from local variables into binding properties, making them:

1. **Shared across parts** -- accessible in any template within the same `launch()` call
2. **Reactive** -- can be tracked by `${{ }}` expressions for automatic updates
3. **Prefixed with `$`** -- internally stored with a `$` prefix in the binding (transparent to templates)

Variables declared with `def` inside closures retain their `def` and remain local to that closure.

```html
<%
    def counter = 0     // Becomes a reactive binding property (def stripped)
    def handler = {
        def temp = 0    // Stays local to this closure (def preserved)
        counter++       // Modifies the reactive property
    }
%>

<p>Count: ${{ counter }}</p>
<button on-click=${ _{ counter++ }}>Increment</button>
```

**Note:** Template-level `def` variables should be initialized to non-null values because the reactive system may not handle null properly. Use typed declarations (`String name = null`) if null is needed.

---

## Static Properties

### `Launchpad.elements`

```groovy
static def elements = new ConcurrentHashMap()
```

A shared registry of Server Element class definitions, keyed by kebab-case tag name. Populated automatically from `.groovy` files in the `elements/` directory. Cleared on hot-reload in debug mode.

### `Launchpad.bindingSatellites`

```groovy
static final bindingSatellites = new ConcurrentHashMap()
```

Global map of `launchID` to template binding state. Stores reactive closures, server action closures, and WebSocket references for all active Launchpad pages. Entries are automatically cleaned up when WebSocket connections close.

### `Launchpad.reloading`

```groovy
static def reloading = false
```

Flag indicating whether templates are currently being reloaded (debug mode only). During reload, the template cache and element registry are cleared.

---

## Instance Properties

| Property | Type | Description |
|---|---|---|
| `launchID` | `String` | Unique identifier for this Launchpad instance, generated as a UUID. Used to associate WebSocket connections with their template bindings. |
| `binding` | `ConcurrentHashMap` | The shared binding map containing template variables, reactions, and internal state. |
| `allowFolderTraversal` | `boolean` | Default `false`. When `false`, template paths containing `..` are rejected to prevent directory traversal. |
