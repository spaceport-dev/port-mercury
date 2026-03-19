# Launchpad (Templating Engine) -- Internals

This document covers how Launchpad works under the hood: the class architecture, the template compilation pipeline, the reactive dependency tracking system, the assemble/launch execution flow, template caching, wrappers, threading, hot-reload behavior, and Server Element processing. It is intended for contributors, framework developers, and advanced users who need to understand or debug Launchpad's internal behavior.

---

## Architecture Overview

Launchpad's internals are spread across several classes in the `spaceport.launchpad` package, plus a supporting class in `spaceport.engineering`:

| Class | File | Responsibility |
|---|---|---|
| `Launchpad` | `Launchpad.groovy` | Orchestrates assembly, priming, rendering, reactive binding management, WebSocket lifecycle, and Server Element processing |
| `SpaceportTemplateEngine` | `SpaceportTemplateEngine.groovy` | Preprocesses `.ghtml` source text, processes `<g:*>` generator tags, compiles templates into executable Groovy scripts |
| `SpaceportTemplate` | Inner class of `SpaceportTemplateEngine` | Parses template text into a Groovy script string, holds the compiled `Script`, and produces `Writable` instances for rendering |
| `Catch` | `Catch.groovy` | Proxy object that intercepts property access during reactive expression evaluation to discover which variables an expression depends on |
| `GHTMLScriptMetaClass` | `LaunchpadMetaClasses.groovy` | Custom `DelegatingMetaClass` that provides `_source` and `_error` properties on script objects and redirects missing properties to `$`-prefixed binding variables |
| `Beacon` | `engineering/Beacon.groovy` | File-system watcher (Java `WatchService`) used for hot-reload in debug mode |

### How the Classes Relate

```
Launchpad (orchestrator)
    |
    |-- creates --> SpaceportTemplateEngine (per prime() call)
    |                   |
    |                   |-- creates --> SpaceportTemplate (compiled template)
    |                                       |
    |                                       |-- uses --> GHTMLScriptMetaClass (reactive property resolution)
    |
    |-- creates --> Catch (proxy for dependency tracking during ${{ }} evaluation)
    |
    |-- uses --> Beacon (file watcher for hot-reload, debug mode only)
```

These subsystems interact during two distinct phases:

1. **Render phase** -- When `launch()` is called. Templates are preprocessed, compiled, executed, and the final HTML is written to the client. Reactive closures and server action closures are registered during this phase.
2. **Interactive phase** -- After the page is delivered. The client sends events back over HTTP (server action invocations) and maintains a WebSocket connection (for receiving reactive updates and communicating with Server Elements).

---

## Template Compilation Pipeline

When a `.ghtml` file is primed (rendered), it passes through a multi-stage pipeline. Understanding this pipeline is essential for debugging template errors, since error messages and stack traces reference the compiled Groovy script rather than the original template source.

### Stage 1: File Reading

The `prime()` method reads the raw file content from disk:

```groovy
text = new File(sourcePath + 'parts/' + filePath)?.text
```

The file path is relative to the `parts/` subdirectory under the Launchpad source path. A folder traversal check prevents `..` in paths (unless `allowFolderTraversal` is explicitly set to `true`). If the file does not exist, `prime()` returns an HTML comment: `<!-- LaunchPad: Part not found: filename -->`.

### Stage 2: Preprocessing (SpaceportTemplateEngine.createTemplate)

The engine applies a series of regex-based transformations to the raw text, in this order:

**1. Triple-slash comment removal.** Lines matching `^\s*///.*` are stripped entirely:

```groovy
text = text.replaceAll(/(?m)^\s*\/\/\/.*/, '')
```

These comments never appear in the rendered output, unlike HTML comments which are sent to the client.

**2. HUD tag normalization.** `<hud:*>` tags are converted to `<g:*>` tags so they go through the same generator processing pipeline:

```groovy
text = text.replaceAll(/<hud:/, '<g:')
text = text.replaceAll(/<\/hud:/, '</g:')
```

**3. `<g:javascript>` expansion.** Converted to standard script tags for IDE compatibility:

```groovy
text = text.replaceAll(/<g:javascript>/, '<script type="text/javascript">')
text = text.replaceAll(/<\/g:javascript>/, '</script>')
```

**4. CSS class shorthand expansion.** Dot notation like `<div.container.padded>` is converted to `<div class='container padded'>`:

```groovy
def classPattern = /(?s)<(\w+(:\w+)?)((\.[\w|-]+)+)/
text = text.replaceAll(classPattern) { matches ->
    "<${ matches[1] } class='${ matches[3].split('\\.').join(' ')[1..-1] }'"
}
```

**5. Self-closing `<g:*>` tag expansion.** Tags like `<g:flag path="store#key"/>` are expanded to `<g:flag path="store#key"></g:flag>`:

```groovy
def selfClosingPattern = Pattern.compile(/<(g:[\w-]+)(?:\s+([^>]*?))?\s*\/>/, Pattern.DOTALL)
text = selfClosingMatcher.replaceAll('<$1$2></$1>')
```

**6. Generator tag processing.** All `<g:*>` tags are processed from innermost to outermost. The engine locates the *last* `<g:` occurrence in the text, processes it, then repeats until no more remain. This inside-out approach ensures nested generators are resolved before their parents.

For each tag, the engine:

1. Parses the tag name, attributes (handling quoted values, unquoted values, and values containing `{ }` brackets), and body content
2. Dispatches to either:
   - A **built-in generator** (defined in `SpaceportTemplateEngine.generators`) -- the generator closure transforms the tag into Groovy template code
   - A **registered Server Element** -- the tag is replaced with comment-based placeholder markers for later element processing
   - If neither matches, an HTML comment is produced: `<!-- SpaceportTemplateEngine: Unknown tag: tagName -->`

**Built-in generators and their transformations:**

| Generator | Input | Output |
|---|---|---|
| `<g:if condition="expr">body</g:if>` | Condition + body | `<% if(expr) { %> body <% } %>` |
| `<g:else>body</g:else>` | Body only | `<% else { %> body <% } %>` |
| `<g:elseif condition="expr">body</g:elseif>` | Condition + body | `<% else if(expr) { %> body <% } %>` |
| `<g:each in="source" var="x">body</g:each>` | Source collection + optional var name | `<% for (def x in source) { %> body <% } %>` |
| `<g:repeat count="n">body</g:repeat>` | Count attribute | Literal string repetition of body `n` times |
| `<g:flag path="store#path">` | Path in `storeName#flagPath` format | `<hud-item><%= Cargo.fromStore("store").get("path") %></hud-item>` |

Conditions and iteration sources accept three syntaxes for their value attributes:
- `storeName#flagPath` -- expanded to `Cargo.fromStore("storeName").get("flagPath")`
- `${expression}` -- the `${ }` wrapper is stripped, leaving the raw expression
- `$variable` -- the `$` prefix is stripped

When a `<g:*>` tag matches a registered Server Element instead of a built-in generator, it is replaced with placeholder markers:

```html
<!-- id-styles --><id originalAttributes><!-- id-body -->body<!-- END id-body --></id><!-- id-handler -->
```

These markers are resolved later during the `parseElements()` phase of `launch()`.

**7. `@Provided` annotation stripping.** Lines containing `@Provided` are wrapped in block comments:

```groovy
text = text.replaceAll(/(@Provided.*)/, '/* $1 */')
```

`@Provided` is purely an IDE/documentation hint -- it has no runtime effect.

**8. Action delegate injection.** Inside server action closures (`${ _{ ... }}`), closure calls like `$closureName(args)` have delegate assignment code injected. This ensures that closures called within server actions use the `Catch` proxy as their delegate, enabling recursive reactive dependency tracking:

```groovy
// Before preprocessing
${ _{ $myHandler(data) }}

// After preprocessing (conceptual)
${ _{ ;try{$myHandler.delegate=actionDelegate;$myHandler.resolveStrategy=Closure.DELEGATE_FIRST;}catch(e){};$myHandler(data) }}
```

This transformation also applies to regular script-scoped closure assignments (`= { ... }`) that contain `$closureName()` calls.

**9. Root-level `def` removal.** The `removeDefsOutsideBraces()` method strips `def` keywords from variable declarations at the root scope (not inside `{ }` blocks). It uses brace-depth tracking to determine whether each `def` is at the top level:

```groovy
text = removeDefsOutsideBraces(text)
```

This converts root-level local variables into binding properties, enabling:
- **Cross-part variable sharing** -- all parts in a single `launch()` share the same `Binding`
- **Reactive tracking** -- binding properties can be intercepted by the `Catch` proxy

Variables inside closures retain their `def` and remain local to that closure scope.

**10. Reactive expression sugar.** The `${{ expr }}` syntax is transformed into a two-parameter closure call:

```groovy
text = text.replaceAll(/\$\{\{\s*/, '\\${ _{ _script, _registration -> ')
```

So `${{ dock.counter.get() }}` becomes `${ _{ _script, _registration -> dock.counter.get() }}`. The two parameters (`_script`, `_registration`) signal to the `bind()` method that this is a "launch reaction" rather than a "launch binding" (server action).

**11. `<g:prime>` inline inclusion.** Tags like `<g:prime src="partial.ghtml"/>` are replaced with the rendered content of the referenced file. This inclusion happens during preprocessing, before the template is compiled, so the primed content becomes part of the same compiled script.

**12. Import conversion.** JSP/GSP-style import directives are extracted, converted to Groovy import statements, and prepended to the template text:

```html
<%@ page import="spaceport.Spaceport; documents.MyDoc" %>
```

Becomes:

```groovy
<% import spaceport.Spaceport; import documents.MyDoc; %>
```

Multiple `<%@ page import="..." %>` directives are collected and combined into a single import block.

### Stage 3: Template Parsing (SpaceportTemplate.parse)

After preprocessing, the text is passed to `SpaceportTemplate.parse()`, which converts the mixed HTML/Groovy text into a pure Groovy script string. The parser is a character-level state machine that processes the input one character at a time:

- **Literal text** is accumulated and wrapped in `out.print("""...""")` statements
- **`<% ... %>`** scriptlet blocks are extracted as raw Groovy code. The parser closes the current `out.print(""")` call, inserts the code, then opens a new `out.print("""`
- **`<%= expr %>`** expression blocks are converted to `${expr}` inside the print statement
- **`${ ... }`** GString expressions are passed through, with nested brace tracking to handle expressions like `${ map.collect { it.key } }`
- **Double quotes** in literal text are escaped with backslashes (since the output is wrapped in triple-quoted strings)
- **Line endings** are normalized to `\n`

The resulting script starts with `out.print("""` and ends with `""");\n/* Generated by SpaceportTemplateEngine */`.

### Stage 4: Script Compilation

The parsed script string is compiled into a `Script` class using a `GroovyShell` that shares the application's classloader (`SourceStore.classLoader`):

```groovy
def n = name.replace('-','_').replace('.','_') + '_' + counter++ + ".ghtml.groovy"
template.script = this.groovyShell.parse(script, n)
```

The script is given a generated name like `index_ghtml_42.ghtml.groovy` for stack trace readability. The monotonically increasing counter ensures unique class names even when the same template is recompiled. Compilation errors at this stage indicate syntax problems in the template's Groovy code (after all preprocessing transformations).

### Stage 5: Caching

The compiled `SpaceportTemplate` is stored in a static `ConcurrentHashMap` keyed by template file path:

```groovy
templateCache.put(name, [template: template, hash: originalText.hashCode()])
```

On subsequent requests, if the template name exists in the cache and the text hash matches, the cached template is returned without recompilation:

```groovy
if (templateCache.containsKey(name) && templateCache.get(name).get('hash') == text.hashCode()) {
    return templateCache.get(name).get('template')  // Cache hit
}
```

This means:
- **First request** -- Full preprocessing, parsing, and compilation. Result is cached.
- **Subsequent requests with unchanged file** -- Cache hit. Only the file read occurs; all processing is skipped.
- **Requests after file modification** -- Hash mismatch triggers full recompilation and cache update.

### Stage 6: Execution (SpaceportTemplate.make)

When `prime()` calls `template.make(scriptBinding)`, the template creates a `Writable` that performs the actual rendering:

1. Creates a fresh `Script` instance from the compiled class via `InvokerHelper.createScript()`. This ensures each rendering gets its own script object even though the compiled class is shared.
2. Attaches a `GHTMLScriptMetaClass` to the script for reactive property resolution and error tracking.
3. Sets up the script's `PrintWriter` (bound to `out`), `_self` reference, `_b` binding reference, and `_source` (for debugging).
4. Registers the script in the binding's `_scripts` map by class name.
5. Executes `scriptObject.run()`, which writes rendered HTML to the writer.
6. If execution throws, captures the error with line-number context, surrounding source lines, and a collapsible full stack trace.

---

## The Binding System

All templates rendered in a single `launch()` call share a `Binding` object. When `launch()` begins, it populates the binding with request-scoped variables:

```groovy
binding.putAll([
    r        : r,                    // HttpResult
    context  : r.context,            // HttpContext
    client   : r.context.client,     // Client
    data     : r.context.data,       // Request data Map
    dock     : r.context.dock,       // Session Cargo
    cookies  : r.context.cookies,    // Cookies
    prime    : this.&prime,          // The prime() method reference
    _        : this.&bind,           // The bind() method reference (server actions/reactions)
    __       : this.&parseElements,  // Internal element parsing
    '_reactions' : new ConcurrentHashMap(),
    '_bindings'  : new ConcurrentHashMap(),
    '_launchID'  : '',
    '_scripts'   : [:]
])
```

When templates execute, their root-level variables (with `def` stripped by preprocessing) become additional binding properties. After all templates render, `launch()` renames user-defined variables by prefixing them with `$`:

```groovy
def modifiedMap = variables.collectEntries { key, value ->
    if (key.startsWith('_') || ['out','client','data','prime','cookies','r','context','dock'].contains(key)) {
        [(key): value]           // Reserved names stay as-is
    } else {
        [('$' + key): value]     // User variables get $ prefix
    }
}
```

This separates user-defined variables from framework internals and reserved names. The `$` prefix is transparent to templates thanks to `GHTMLScriptMetaClass`.

### GHTMLScriptMetaClass

Each template script object receives a custom `DelegatingMetaClass` that:

- Provides the `_source` property (a `StringBuilder` holding the compiled script source for error reporting)
- Provides the `_error` property (a `StringBuilder` for capturing rendering errors)
- Falls back to `$`-prefixed binding variables when a property is not found on the script object directly:

```groovy
Object getProperty(Object object, String property) {
    if (property == '_source') return source
    if (property == '_error')  return error
    try {
        return super.getProperty(object, property)
    } catch (MissingPropertyException e) {
        return object.getBinding().variables.get('$' + property)
    }
}
```

When a template references `myVar`, the metaclass first tries a normal property lookup. If that fails with `MissingPropertyException`, it looks for `$myVar` in the binding. This is how the `def`-stripping and `$`-prefixing remain invisible to template authors while enabling reactive tracking.

---

## Reactive Dependency Tracking (The Catch Proxy)

The `Catch` class is the core mechanism for reactive dependency tracking. It acts as a transparent proxy that records which properties are accessed during expression evaluation.

### Catch Class Implementation

```groovy
class Catch {
    Object through     // The real object being proxied
    def caught = new HashSet()  // Set of property/method names accessed

    def getProperty(String name) {
        if (name == 'caught') return caught
        caught << name                    // Record the access
        return through."$name"            // Delegate to real object
    }

    void setProperty(String name, value) {
        caught << name                    // Record the write
        through."$name" = value           // Delegate to real object
    }

    def invokeMethod(String name, args) {
        caught << name                    // Record the call
        // If the property is a Closure, clone it with this Catch as delegate
        if (through."$name" instanceof Closure) {
            def copy = through."$name".clone()
            copy.resolveStrategy = Closure.DELEGATE_ONLY
            copy.delegate = this
            return copy(*args)
        }
        return through.invokeMethod(name, args)
    }
}
```

Key behaviors:

- **Property reads** record the property name and return the real value from the proxied object.
- **Property writes** record the property name and set the real value.
- **Method calls** record the method name. Critically, if the method resolves to a Closure property on the underlying object, the Closure is cloned with `Catch` set as its delegate. This means nested closures within a reactive expression also have their dependencies tracked recursively.
- The `caught` property itself is exempted from tracking (returns the raw set).

### How Reactive Bindings (Launch Reactions) Work

When `bind()` receives a two-parameter closure (indicating a reactive binding from `${{ }}`), the following sequence occurs:

1. A `Catch` proxy is created, wrapping the closure's owner (the template script object).
2. The closure's resolve strategy is set to `DELEGATE_ONLY` and its delegate is set to the `Catch` proxy.
3. The closure is called with `(closure.owner, uuid)` to get the initial rendered value. During execution, every property access goes through the `Catch` proxy, which records the names in its `caught` set.
4. The closure's delegate and resolve strategy are restored to their originals.
5. A reaction record is stored in `binding._reactions`:

```groovy
closestScript._reactions.put(uuid, [
    'created'  : System.currentTimeMillis(),
    'reaction' : closure,          // The original closure (for re-evaluation)
    '_source'  : sourceText,       // Source line for debugging
    '_current' : returnString,     // Current rendered value (for change detection)
    '_triggers': delegate.caught   // Set of variable names this expression depends on
])
```

6. The rendered output is wrapped in HTML comment markers:

```html
<!-- START_CHUNK@abc123def456 -->rendered content<!-- END_CHUNK@abc123def456 -->
```

These markers allow the client-side code to locate and replace the specific DOM region when updates arrive.

### How Server Actions (Launch Bindings) Work

When `bind()` receives a one-parameter closure (indicating a server action from `_{ t -> ... }`), it:

1. Stores the closure in `binding._bindings` with a UUID key:
   ```groovy
   closestScript._bindings.put(uuid, [
       'created' : System.currentTimeMillis(),
       'action'  : closure,
       'script'  : closestScript,
       '_source' : sourceText
   ])
   ```

2. Returns a URL string: `/!/lp/bind?launch={launchID}&uuid={uuid}`. HUD-Core on the client parses `on-*` attributes, extracts these URLs, and calls them when the corresponding DOM event fires.

### How Server Actions Trigger Reactive Re-evaluation

When a server action is invoked via HTTP to `/!/lp/bind`, the `serveBinding()` handler:

1. Retrieves the binding from `bindingSatellites` by launch ID and the action closure by UUID. If not found in the main bindings, it checks Server Element bindings.
2. Validates that the requesting client matches the binding's client (security check -- returns 405 on mismatch).
3. Enhances the request data map `r.context.data` with typed accessor methods (`getNumber`, `getBool`, `getString`, `getList`, `getInteger`).
4. Creates a new `Catch` proxy wrapping the action's script, sets it as the action closure's delegate, and calls the action with the enhanced data map.
5. After action execution, iterates all registered reactions for this launch:

```groovy
for (def reaction in bindingSatellites.get(launch)?._reactions) {
    // Skip reactions whose triggers don't overlap with accessed variables
    if ((reaction.value._triggers as List).intersect(delegate.caught).size() == 0) continue
    // Skip Cargo-backed reactions (Cargo handles its own reactivity)
    if (binding.script."$reaction.key" instanceof Cargo) continue

    def payload = reaction.value.reaction(action.owner, reaction.key).toString()
    payload = bindingSatellites.get(launch).__(payload) // Process Server Elements in the new content

    if (reaction.value._current != payload) {
        // Send updated content via WebSocket
        bindingSatellites.get(launch)._socket.session?.remote?.sendStringByFuture(
            new JsonBuilder([
                'action' : 'applyReaction',
                'uuid'   : reaction.key,
                'payload': payload
            ]).toString())
        reaction.value._current = payload
    }
}
```

6. The action closure's return value is processed based on its type:
   - `Map` or `List` -- serialized to JSON
   - `String` or `GString` -- passed through `parseElements()` for Server Element processing, then returned as-is
   - Other types -- converted to String via `.toString()`

---

## The Assemble/Launch Flow

### assemble()

`assemble()` creates a brand-new `Launchpad` instance with the same source path but a fresh `launchID`, binding, and script binding:

```groovy
Launchpad assemble(List<String> payloadFiles) {
    def newConfiguration = new Launchpad(sourcePath)
    newConfiguration.parts = payloadFiles
    return newConfiguration
}
```

This design is critical for thread safety: every request gets its own Launchpad instance with its own isolated binding state. The original static Launchpad instance (typically declared as `static Launchpad launchpad = new Launchpad()` in a source module) is never modified by request processing.

### launch()

`launch()` is `synchronized` on the Launchpad instance. Since each request gets a new instance from `assemble()`, this synchronization does not create contention between concurrent requests. The full execution sequence:

**1. Populate the binding** with request-scoped variables (as shown in the Binding System section above).

**2. Prime the vessel** (wrapper template) first:

```groovy
def vtext = vessel ? prime(vessel) : null
```

The vessel is primed before any parts so that variables it defines are available to the parts.

**3. Prime each part** in order, concatenating their output:

```groovy
for (def part in parts) {
    if (part.toString().endsWith('.ghtml')) {
        html = html + prime(part.toString())
    }
}
```

**4. Insert parts into the vessel** by replacing the `<payload/>` tag:

```groovy
html = vtext.replace('<payload/>', html)
```

If the vessel is missing `<payload/>`, the response status is set to 501.

**5. Rename binding variables** with `$` prefix for reactive property resolution (described in the Binding System section).

**6. Process Server Elements** by calling `parseElements(html)`, which instantiates element classes, calls their `prerender()` and `initialize()` methods, and injects their styles, handlers, and content into the HTML.

**7. Inject WebSocket script** if reactive features were used. The framework detects this by checking `binding._socket`:

```groovy
if (binding._socket) {
    if (!(binding._socket instanceof SocketContext)) {
        binding._socket = launchID
        binding._launchId = launchID
        bindingSatellites.put(launchID, binding)
    }
    // Inject <script> before </body>
}
```

The injected script opens a WebSocket, registers with the server, and handles incoming `applyReaction`, `elementResponse`, and `ping` messages.

**8. Write the final HTML** to the client and log timing information:

```groovy
r.writeToClient(html)
```

### prime()

The `prime()` method renders a single template file:

1. Checks for folder traversal in the path
2. Reads the file from `sourcePath + 'parts/' + filePath`
3. Creates a `SpaceportTemplateEngine` and calls `createTemplate(filePath, text)` (which runs the full preprocessing pipeline or returns a cached template)
4. Saves the current `out` writer and `_self` script reference from the binding (since prime can be called recursively from within templates)
5. Calls `template.make(scriptBinding)` to execute the template and capture output as a String
6. Checks for errors in `binding._self._error`
7. If the template contained Server Elements, creates element instances and registers them in `binding._elements` (or stashes them for later processing)
8. Restores the previous writer and script reference
9. Returns the rendered HTML string

Recursive priming works because the writer and script context are saved and restored on each call. All primed templates share the same binding, so variables defined in one template are visible in all others within the same `launch()`.

---

## How Wrappers (Vessels) Work Internally

A wrapper (vessel) is simply a regular `.ghtml` template that contains a `<payload/>` tag. There is no special class or configuration -- any template with `<payload/>` in it can serve as a vessel.

The internal process:

1. The vessel is primed **first** (before any content parts), giving it the opportunity to define variables, set up closures, or establish shared state.
2. All content parts are primed in order and their output is concatenated.
3. The vessel's rendered output has its `<payload/>` tag replaced with the concatenated parts HTML:
   ```groovy
   html = vtext.replace('<payload/>', html)
   ```
4. If `<payload/>` is missing from the vessel, the response status is set to 501 and a debug message is logged.

Because the vessel shares the same binding with parts:
- Variables defined in the vessel's `<% %>` blocks are accessible to all parts.
- Parts can modify variables that the vessel defined.
- However, since the vessel's HTML is already rendered by the time parts execute, modifications to variables in parts do **not** retroactively change the vessel's rendered output. They only affect reactive expressions (`${{ }}`) that are re-evaluated later via WebSocket, and they affect any parts that execute after the variable is modified.

---

## Binding Satellites and WebSocket Lifecycle

When a launch uses reactive features (any `${{ }}` or `_{ }`), the binding is registered as a "binding satellite" in a global static map:

```groovy
static final bindingSatellites = new ConcurrentHashMap()  // launchID -> Binding
```

### Registration Flow

1. During template rendering, when `bind()` encounters a reactive closure or server action, it walks up the closure's owner chain to find the owning `Script` or `Element` object. If `_socket` is not already a `SocketContext` (i.e., this is the first binding in this launch), it sets `_socket = true` to signal that a WebSocket connection will be needed.

2. At the end of `launch()`, if `binding._socket` is truthy, the binding is stored in `bindingSatellites` keyed by `launchID`, and a client-side WebSocket script is injected before `</body>`.

3. When the page loads in the browser, the injected script opens a WebSocket to `/!/lp/socket` and sends a registration message:
   ```json
   { "handler-id": "launchpad-socket", "action": "register", "uuid": "<launchID>" }
   ```

4. The `@Alert('on socket launchpad-socket')` handler receives the registration, retrieves the binding satellite by UUID, and stores the `SocketContext`:
   ```groovy
   Binding binding = bindingSatellites.get(uuid)
   binding._socket = r.context
   ```

### Keep-Alive and Cleanup

After registration, a `Timer` is scheduled with a `TimerTask` that runs every 10 seconds:

```groovy
timer.schedule(task, 10000, 10000)
```

On each tick:
- If the WebSocket session is still open, a `ping` message is sent. The client responds with a `pong`.
- If the session is closed (the user navigated away or the connection dropped), the binding satellite is removed from `bindingSatellites` and the timer is cancelled. This prevents memory leaks from accumulated stale bindings.

### Unbinding

When client-side elements are removed from the DOM (e.g., navigating away from a section using HUD-Core's SPA-like behavior), HUD-Core sends unbind requests to `/!/lp/bind/u/` with a list of binding UUIDs. The `unbind()` handler removes these specific closures from the binding satellite, freeing memory incrementally rather than waiting for the WebSocket to close.

### WebSocket Message Types

The injected client-side script handles three types of incoming messages:

| Action | Description |
|---|---|
| `ping` | Server keep-alive check. Client responds with `pong`. |
| `applyReaction` | Reactive update. Contains `uuid` and `payload`. The client finds the `START_CHUNK@uuid` / `END_CHUNK@uuid` comment markers in the DOM, creates a `Range` between them, deletes the old content, and inserts the new HTML from `payload`. |
| `elementResponse` | Server Element callback response. Contains `function`, `payload`, and optional `requestId`. If `requestId` is present, the client invokes the stored callback function from `window.spaceportCallbacks`. |

---

## Thread Safety and Concurrency

- **`launch()` synchronization** -- `launch()` is `synchronized` on the Launchpad instance. Since `assemble()` creates a new instance per call, concurrent requests to the same route handler do not block each other. The synchronization exists to prevent accidental concurrent use of the same assembled instance.

- **`bindingSatellites`** is a `ConcurrentHashMap`, providing safe concurrent read/write from HTTP request threads and WebSocket handler threads.

- **`SpaceportTemplateEngine.templateCache`** is a `ConcurrentHashMap`. If two requests compile the same template simultaneously, both complete and the last write wins. Since both produce identical output from the same source text, this race is harmless.

- **`Launchpad.elements`** is a `ConcurrentHashMap`, safe for concurrent Server Element registration and lookup.

- **Per-request isolation** -- Each request gets its own `Binding` object (created in the `Launchpad` constructor) with its own `_reactions` and `_bindings` maps (both `ConcurrentHashMap`). There is no shared mutable state between concurrent requests during the render phase.

- **Server action dispatch** -- When server actions execute via `serveBinding()`, they access the shared binding satellite. The reaction iteration and WebSocket sending are not explicitly synchronized beyond the ConcurrentHashMap guarantees. In practice, HUD-Core serializes actions from a single browser tab, so concurrent actions on the same launch ID are rare.

---

## Hot-Reload Behavior

Hot-reload is enabled only when `Spaceport.inDebugMode` is `true`. It is initialized during the `on initialize` Alert lifecycle event.

### How It Works

1. The `_init()` method locates the launchpad directory (checking `{root}/{name}/launchpad` first, then `{root}/launchpad`).
2. A `Beacon` is created for that directory. `Beacon` uses Java's `WatchService` API and recursively registers watches on all subdirectories via `Files.walkFileTree()`.
3. A background thread continuously calls `watcher.take()` to block until a file event occurs.
4. When any file event fires (ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE), the Beacon's callback is invoked.
5. The callback uses a debounce pattern:

```groovy
if (!reloading) {
    reloading = true
    Thread.start {
        Thread.sleep(100)
        SpaceportTemplateEngine.templateCache.clear()
        elements.clear()
        reloading = false
    }
}
```

The 100ms sleep allows a batch of file changes (e.g., an IDE saving multiple files) to trigger only one cache clear rather than one per file.

### What Gets Reloaded

| Changed File | Effect |
|---|---|
| `.ghtml` templates | Recompiled on next access (template cache was cleared) |
| `.groovy` Server Elements | Re-parsed and re-registered on next Launchpad instantiation (elements map was cleared, and the constructor re-scans when the map is empty) |
| Other files in the launchpad directory | Cache is still cleared (the Beacon watches all file types) |

### Limitations

- **Active connections are not updated.** Clients with open WebSocket connections retain their old binding satellites and closures. Only new page loads pick up template changes.
- **Source modules are separate.** Changes to `.groovy` files in the `modules/` directory are handled by Spaceport's separate source-reloading mechanism, not by Launchpad's Beacon.
- **Production mode has no watching.** When debug mode is off, templates are compiled once and cached for the lifetime of the JVM. Template changes require a server restart.
- **Beacon lifecycle.** Beacons are deactivated during server de-initialization via the `on deinitialized` Alert, which cancels all watch keys and closes the WatchService.

---

## Server Element Processing Internals

Server Elements are custom `<g:tag-name>` components defined as Groovy classes in the `elements/` directory. Their lifecycle is deeply integrated with the Launchpad pipeline.

### Registration

During `Launchpad` construction, if the static `elements` map is empty, all `.groovy` files in `sourcePath + 'elements/'` are loaded:

```groovy
for (def element in new File(sourcePath + 'elements').listFiles()) {
    if (element.name.endsWith('.groovy')) {
        def text = element.text
        // Apply ${{ }} syntactic sugar
        text = text.replaceAll(/\$\{\{\s*/, '\\${ _{ _script, _registration -> ')
        def clazz = new GroovyClassLoader(SourceStore.classLoader).parseClass(text)
        elements.put(element.name.replace('.groovy', '').kebab(), clazz)
    }
}
```

The filename is converted to kebab-case for the tag name (e.g., `StatCard.groovy` becomes `stat-card`, `TagInput.groovy` becomes `tag-input`).

### Tag Processing in SpaceportTemplateEngine

When a `<g:tag-name>` is encountered during preprocessing and the tag name matches a registered element (but not a built-in generator), the engine generates placeholder markers with a unique ID:

```html
<!-- id-styles --><id originalAttributes><!-- id-body -->body content<!-- END id-body --></id><!-- id-handler -->
```

The element's ID and name are stored in the template's `elements` map for later resolution.

### Element Instantiation in prime()

After a template is rendered, if it contained Server Element tags, `prime()` scans the rendered HTML for handler markers (`<!-- id-handler -->`) and creates element instances:

```groovy
Element newElement = Launchpad.elements[e.name].newInstance()
newElement.launchpad = this
newElement.client = binding.client
newElement.dock = binding.dock
newElement._id = 6.randomID()
```

Elements are added to `binding._elements`. Elements that appear inside reactive closures may be "stashed" in `binding._stashed_elements` for later processing during `parseElements()`.

### Element Rendering in parseElements()

The `parseElements()` method runs after all templates are primed and the vessel is assembled. It processes each registered element:

1. **Attribute extraction** -- Parses HTML attributes from the element's rendered tag using regex matching.
2. **Server-side attribute resolution** -- Attributes whose values are server action URLs (starting with `/!/`) but are not `on-*` event attributes are resolved immediately by calling the associated binding closure. The result replaces the URL.
3. **Typed accessor injection** -- The attributes map is enhanced with `getNumber`, `getBool`, `getString`, `getList`, and `getInteger` methods (same as the transmission object).
4. **Prerender** -- `element.prerender(body, attributes)` is called. The element transforms its body content based on attributes.
5. **Initialize** -- `element.initialize()` is called. The element builds its CSS, JavaScript event handlers, and any other runtime setup.
6. **Style injection** -- The `<!-- id-styles -->` comment is replaced with the element's `_style` (global CSS) and `_scopedStyle` (instance-scoped CSS).
7. **Handler injection** -- The `<!-- id-handler -->` comment is replaced with the element's `_handler` (JavaScript).
8. **Tag replacement** -- The placeholder tag `<id>` is replaced with the actual element tag `<tag-name element-id="rid">`.
9. **Prepend/append** -- Global prepend content is inserted before `</head>` (or at the start of the HTML). Global append content is inserted after `</body>`. Scoped prepend/append content is inserted adjacent to individual element instances.

The processing loop runs repeatedly until no more replacements occur, handling the case where element prerendering introduces new elements (nested elements).

### Element WebSocket Communication

Server Elements can expose methods that are callable from client-side JavaScript via WebSocket. When the client sends an `element-call` message:

```json
{
    "handler-id": "launchpad-socket",
    "action": "element-call",
    "function": "methodName",
    "element": "elementId",
    "uuid": "launchId",
    "args": [...],
    "request-id": "callbackId"
}
```

The server looks up the element in the binding satellite, finds the method in the element's `_handlerMap`, calls it with the provided arguments, and sends the return value back to the client. If a `request-id` was included, it is echoed back so the client can match the response to a pending callback.

---

## Error Handling

Template errors are handled at multiple levels, with increasing specificity:

### Preprocessing / Compilation Errors

If `SpaceportTemplateEngine.createTemplate()` throws during preprocessing or script compilation, `prime()` catches the exception and returns an HTML error fragment:

```html
<fatal class="error">Error creating template: filename<br><tt>error message</tt></fatal>
```

The `GroovyRuntimeException` thrown by the engine includes a descriptive message about the nature of the failure.

### Runtime Errors

If the compiled template script throws during execution, the `SpaceportTemplate.make()` writable catches the exception and:

1. Extracts the line number from the stack trace by matching entries with `.ghtml.groovy` filenames
2. Reads the compiled source from the `_source` StringBuilder
3. Writes an HTML diagnostic block to the `_error` StringBuilder:

```html
<strong>Oops!</strong> script_name has encountered errors. <br>
Found problem in line: [ 42 ] <br>
Error message<br>
<tt>
  37  line of code...
  38  line of code...
--> 42  the problematic line
  43  line of code...
</tt>
<details><summary>Full stacktrace:</summary>
    ...full stack trace...
</details>
```

The `prime()` method then wraps this in a `<fatal class="error">` element.

### Binding / Security Errors

| Condition | Response |
|---|---|
| Binding UUID not found | HTTP 404 |
| Client mismatch (different user trying to invoke another user's server action) | HTTP 405 |
| Unknown `<g:*>` tag | HTML comment: `<!-- SpaceportTemplateEngine: Unknown tag: name -->` |
| Missing `<payload/>` in vessel | HTTP 501 status, debug log message |
| Folder traversal detected in template path | HTML comment: `<!-- LaunchPad: Folder traversal detected -->` |
| Template file not found | HTML comment: `<!-- LaunchPad: Part not found: filename -->` |
