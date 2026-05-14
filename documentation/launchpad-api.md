# Launchpad (Templating Engine) -- API Reference

## `Launchpad` Class

```groovy
import spaceport.launchpad.Launchpad
```

The main class for assembling and rendering GHTML templates. Typically instantiated once per source module and reused across route handlers.

---

### Constructors

The constructor accepts an optional `Map` of named options plus an optional positional `sourcePath`. The supported forms:

```groovy
new Launchpad()                            // → name "global", conventional folder
new Launchpad('/path/to/zeta')             // → name "zeta" (derived from path's last segment)
new Launchpad(name: 'main')                // → name "main", conventional folder
new Launchpad('/path/to/zeta', name: 'z')  // → name "z", custom sourcePath
```

#### `Launchpad()` — the global Launchpad

Creates the canonical Launchpad with name `"global"`, using the default template directory. Spaceport resolves the directory in this order:

1. `{spaceport root}/{spaceport name}/launchpad/`
2. `{spaceport root}/launchpad/`

Throws an exception if neither directory exists.

```groovy
static Launchpad launchpad = new Launchpad()
```

#### `Launchpad(String sourcePath)` — a slice Launchpad

Creates a Launchpad with an explicit template directory ("slice"). Accepts both absolute and relative paths. Relative paths are resolved against the `spaceport root` configuration value. The Launchpad's name defaults to the last segment of the path (`/launchpads/auth/` → `"auth"`).

| Parameter | Type | Description |
|---|---|---|
| `sourcePath` | `String` | Path to the directory containing `parts/` and `elements/` subdirectories |

```groovy
static Launchpad authLaunchpad = new Launchpad('/opt/app/launchpads/auth')
```

The specified directory must contain a `parts/` subdirectory for template files. An `elements/` subdirectory is scanned for Server Element definitions if present.

#### `Launchpad(Map opts)` — named-args form

Same as the no-arg constructor (uses the conventional global folder), but accepts named options:

```groovy
new Launchpad(name: 'main')
```

The only supported option today is `name:`. Without it, the Launchpad is named `"global"`.

#### `Launchpad(Map opts, String sourcePath)` — named args plus path

Combines explicit options with a slice source path:

```groovy
new Launchpad('/slices/auth', name: 'authentication')
```

#### Naming and identity rules

| Form | Resolved name |
|---|---|
| `new Launchpad()` | `"global"` (reserved) |
| `new Launchpad('/path/to/alpha')` | `"alpha"` (last path segment) |
| `new Launchpad(name: 'main')` | `"main"` |
| `new Launchpad('/path/to/x', name: 'beta')` | `"beta"` |

- **`global`** is reserved for the no-arg / null-`sourcePath` form. A slice cannot claim it — the constructor throws `Cannot claim the reserved name 'global' for a sliced Launchpad at: ...`.
- **Without `name:`**, a slice's name is the last segment of the resolved source path.
- **Explicit `name:` always wins.**
- **Duplicate-name detection.** Constructing a second Launchpad with a name that's already registered to a *different* source path is an error:

```groovy
new Launchpad('/slice/a', name: 'shared')
new Launchpad('/slice/b', name: 'shared')   // throws — name 'shared' already used by /slice/a
```

Same name + same source path is allowed; this is the `assemble()` case, where both instances refer to the same logical Launchpad and share its elements map.

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

The assembled instance **inherits the parent's elements map** (rather than re-parsing the on-disk files) because same source path → same name → same logical Launchpad. The on-disk install pass only runs once per source path.

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

#### `resolveElement(String name)`

Resolves an Element class by name from this Launchpad's perspective: checks the per-instance `elements` map first, then falls back to the cross-Launchpad shared pool.

| Parameter | Type | Description |
|---|---|---|
| `name` | `String` | Kebab-case element name (e.g. `"star-rating"`) |

**Returns:** `Class` — the Element class, or `null` if the name isn't reachable from this Launchpad.

#### `resolveForEntry(Map entry)`

Resolves an Element class from a template/stash entry. Honors a `launchpadName` field on the entry (set when the template engine saw a qualified `<g:slice/element>` tag) by reaching directly into that slice's local elements map. Otherwise falls through to `resolveElement(name)`.

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

Generator tags are preprocessed by `SpaceportTemplateEngine` into Groovy template code before the template is compiled. They provide a declarative syntax for common control structures. All generator tags require explicit opening and closing tags.

For conditional rendering, use standard Groovy template scriptlets (`<% if (...) { %> ... <% } %>`).

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

### `<g:javascript>`

Alias for `<script type="text/javascript">`. Provides better IDE support in IntelliJ for Groovy-embedded JavaScript.

```html
<g:javascript>
    console.log('Hello from JavaScript');
</g:javascript>
```

### Custom Element Tags

Any `<g:tag-name>` not matching a built-in generator is resolved against the rendering Launchpad's local element map first, then against the cross-Launchpad shared pool. If found, the tag is processed as a Server Element instance. See the [Server Elements documentation](server-elements-overview.md) for details.

### Qualified `<g:slice/element>` Syntax

When you need to reach into a specific slice's local elements — typically because the shared pool dropped a name due to slice-vs-slice collision, or because you want to be explicit about which slice's variant to use — use the qualified form. The first segment is the **Launchpad name**, the second is the element name within that Launchpad's local map:

```html
<g:alpha/hud-user>...</g:alpha/hud-user>
```

Resolution rules:

- **Bypasses both** the rendering Launchpad's local map and the shared pool — resolves directly to `Launchpad.byName['alpha'].elements['hud-user']`.
- Works at template compile time (in `.ghtml` files) and at runtime inside an Element's `prerender()` return string.
- Unknown slice → `<!-- SpaceportTemplateEngine: Unknown tag: nosuch/foo -->` (or, at runtime, `<!-- Unknown tag: nosuch/foo -->`).
- Known slice, unknown element → `<!-- Unknown tag: alpha/missing -->`.
- From inside the slice itself, the qualified form is allowed but redundant — `<g:hud-user>` already resolves locally.

The separator is `/` (chosen over `:` which collides with the `<g:` prefix, `-` which would conflict with kebab-case element names, and `.` which is unusual in HTML attribute contexts).

---

## Multi-Launchpad Element Resolution

Vertical slicing — running multiple Launchpads in one Spaceport, each with their own `parts/` and `elements/` — is a first-class capability. Element resolution honors both per-Launchpad local definitions and a cross-Launchpad shared pool.

### Concepts

- **Global Launchpad**: the canonical `new Launchpad()` (no `sourcePath`). Always has the name `"global"`. Conceptually owns the shared design-system kit.
- **Slice Launchpad**: any `new Launchpad('/path/...')`. Owns Elements scoped to that slice.
- **Local elements** (`launchpad.elements`): per-Launchpad instance map. Always checked first.
- **Shared pool** (`Launchpad.sharedPool` + `Launchpad.poolOwners`): the cross-Launchpad namespace. Slices and global all contribute. Resolution falls back to the pool when a name isn't in the rendering Launchpad's local map.

### Resolution order

From any Launchpad rendering a template:

1. `this.elements[name]` — the rendering Launchpad's own local map. If found, use it.
2. `Launchpad.sharedPool[name]` — the cross-Launchpad pool. If found, use it.
3. Otherwise: `<!-- Unknown tag: name -->`.

A local definition always shadows the pool for the Launchpad that defines it, but the pool still serves any Launchpad that doesn't have a local definition.

### Pool ownership rules

When a Launchpad registers an Element by name into the pool:

| Current state | Action |
|---|---|
| Pool slot empty | This Launchpad claims it |
| Slot owned by `global`, this Launchpad is not global | No-op (global cannot be displaced) |
| Slot owned by another slice, this Launchpad is global | Overwrite — global wins |
| Slot owned by slice A, this is slice B (neither is global) | **Evict** — remove the name from the pool entirely; each slice still has it locally |
| Slot owned by this Launchpad re-installing | No-op |

### Worked examples

**Local override**: `global` defines `<g:header>`; `alpha` also defines `<g:header>`. Rendering from `alpha` uses alpha's version (local wins). Rendering from `beta` (no local) uses global's version via the pool.

**Global wins on collision**: `alpha` registers `<g:header>` first (pool entry: alpha). Then `global` registers `<g:header>` — pool entry overwrites to global. Beta, gamma, etc. now see global's version. Alpha still uses its own locally.

**Slice-vs-slice eviction**: `alpha` defines `<g:hud-user>`, `zeta` also defines `<g:hud-user>`, neither is global. Pool drops the name. Both alpha and zeta continue to render their own locally. A third slice rendering unqualified `<g:hud-user>` gets `<!-- Unknown tag: hud-user -->`. To disambiguate, the third slice uses the qualified form (`<g:alpha/hud-user>`) or defines the Element locally.

### Why this model

It maps to natural intent:

- "I'm using a widget everyone shares" → global owns it; nobody collides.
- "I have a slice-local variant" → slice owns its local; global pool still serves the canonical version to everyone else.
- "Two slices both implemented the same name and neither is canonical" → the model surfaces the ambiguity loudly rather than picking a silent winner.

### Known limitation

`SpaceportTemplateEngine.templateCache` is keyed on template name/hash, not on Launchpad. If the *same template path* gets compiled from two different Launchpads with diverging element-visibility views, the cache could return a placeholder-baked version meant for the other Launchpad. In practice each Launchpad has its own source path (so template file paths naturally differ), but it's worth knowing if include/share patterns cross Launchpad boundaries.

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

**Null values are supported.** The per-Launchpad `binding` is a `Collections.synchronizedMap` (not a `ConcurrentHashMap`), so writing `null` through `Binding.setVariable` is safe:

```html
<% def foo = something?.maybeMissing %>
```

When `something` is `null`, `foo` becomes `null` in the binding without raising. (Earlier versions used a `ConcurrentHashMap`, which rejected null values and forced workarounds like `def foo = something ?: ''` or a typed declaration that opted the variable out of reactivity.)

---

## Static Properties

### `Launchpad.byName`

```groovy
static def byName = new ConcurrentHashMap()
```

Registry of Launchpad instances by their resolved name. Used to find the right local elements map for qualified `<g:slice/element>` lookups. Keyed on `launchpad.name` (e.g. `"global"`, `"alpha"`, `"zeta"`).

### `Launchpad.sharedPool`

```groovy
static def sharedPool = new ConcurrentHashMap()
```

Cross-Launchpad element pool. Slices and global all contribute. Resolution falls back to this pool when a name isn't in the rendering Launchpad's local map. See [Multi-Launchpad Element Resolution](#multi-launchpad-element-resolution).

### `Launchpad.poolOwners`

```groovy
static def poolOwners = new ConcurrentHashMap()
```

Parallel ownership tracking for `sharedPool` entries. Maps element name → owning Launchpad's name. `"global"` is the sentinel for the no-arg Launchpad.

### `Launchpad.bindingSatellites`

```groovy
static final bindingSatellites = new ConcurrentHashMap()
```

Global map of `launchID` to template binding state. Stores reactive closures, server action closures, and WebSocket references for all active Launchpad pages. Entries are automatically cleaned up when WebSocket connections close.

### `Launchpad.reloading`

```groovy
static def reloading = false
```

Flag indicating whether templates are currently being reloaded (debug mode only). During reload, the template cache and all per-instance element maps are cleared.

### `Launchpad.clearAllElements()` (static method)

Clears all Launchpad-owned element state: iterates `byName.values()` and clears each Launchpad's `elements` map, then clears `sharedPool` and `poolOwners`. Used on full reloads.

---

## Instance Properties

| Property | Type | Description |
|---|---|---|
| `name` | `String` | The Launchpad's resolved name. `"global"` for the no-arg form; derived from the path's last segment or set explicitly via the `name:` opt. Used as the slice identifier in qualified `<g:slice/element>` tags. |
| `sourcePath` | `String` | The Launchpad's source directory (always normalized to end with `/`). Contains `parts/` and optionally `elements/`. |
| `launchID` | `String` | Unique identifier for this Launchpad instance, generated as a UUID. Used to associate WebSocket connections with their template bindings. |
| `binding` | `Map` | Per-Launchpad script-binding map (`Collections.synchronizedMap`). Holds template variables, reactions, server-action bindings, and internal state. Accepts `null` values (the `def`-strip mechanism may legitimately write nulls). |
| `elements` | `ConcurrentHashMap<String, Class>` | Per-Launchpad local element map, keyed by kebab-case tag name. Always checked first during element resolution. Replaces the old static `Launchpad.elements`. |
| `allowFolderTraversal` | `boolean` | Default `false`. When `false`, template paths containing `..` are rejected to prevent directory traversal. |
