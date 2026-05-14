# Server Elements -- Internals

This document covers the implementation details of the Server Elements system: the trait architecture, how annotations work internally, how template tags become rendered components, how CSS and JavaScript are aggregated and injected, the reactive binding system, element lifecycle, and hot-reload behavior.

## Architecture Overview

Server Elements span three layers of the Spaceport stack:

1. **Template Engine** (`SpaceportTemplateEngine`) -- parses `<g:element-name>` tags from `.ghtml` files and replaces them with placeholder markers during template compilation
2. **Launchpad** (`Launchpad`) -- discovers element classes at startup, instantiates them per-use during rendering, calls `prerender()` and `initialize()`, and injects the resulting CSS/JS/HTML into the final page output
3. **HUD-Core** (client-side JavaScript) -- manages element lifecycle on the client, including `constructed`/`deconstructed` hooks and listener cleanup on DOM removal

## The `Element` Trait Architecture

### Why a Trait?

Server Elements are built on a Groovy trait (`spaceport.launchpad.element.Element`), not an abstract class. A trait in Groovy is similar to an interface with default implementations -- any class that declares `implements Element` gains all of the trait's properties and methods without requiring inheritance. This allows element classes to remain simple, self-contained `.groovy` files while gaining a rich set of internal infrastructure.

### Internal Properties

The trait defines underscore-prefixed properties that the framework manages. Element authors should not modify these directly:

```groovy
trait Element {
    def _bindings = [:]        // Reactive bindings registered by this element
    def _source   = ''         // Reserved for debugging (not yet implemented)
    def _handler  = ''         // Aggregated JavaScript block for client-side behavior
    def _handlerMap = [:]      // Maps function IDs to @Bind method closures
    def _id = 'uninitialized'  // Unique instance ID (6-char random), set by Launchpad
    def _reactions = [:]       // Reactive expression subscriptions
    def _style = ''            // Aggregated CSS from @CSS fields
    def _scopedStyle = ''      // Aggregated CSS from @ScopedCSS fields
    def _initialized = false   // Guard against double initialization
    def _prepend = ''          // HTML from @Prepend fields
    def _scopedPrepend = ''    // HTML from @ScopedPrepend fields
    def _append = ''           // HTML from @Append fields
    def _scopedAppend = ''     // HTML from @ScopedAppend fields

    Client client              // Current session's Client object (set by Launchpad)
    Cargo  dock                // Session-scoped Cargo store (set by Launchpad)
    Launchpad launchpad        // Parent Launchpad instance (set by Launchpad)
}
```

### Methods Provided by the Trait

| Method | Purpose |
|---|---|
| `getTagName()` | Converts the class name to kebab-case using `.kebab()` (e.g., `StarRating` becomes `star-rating`) |
| `initialize()` | Processes all annotations to build aggregated CSS, JavaScript, and binding code. Called once per instance. |
| `grab(String)` | Retrieves a reactive variable from the parent template's binding context by name |
| `_(Closure)` | Creates a Launchpad server action binding (delegates to `launchpad.bind()`) |
| `prerender(String, Map)` | Default implementation returns `body` unchanged. Element classes override this. |
| `getName()` (static) | Returns the lowercase class name |

The `grab()` method works by accessing the template script's binding directly:

```groovy
def grab(String reactiveVariableName) {
    return launchpad.binding._self."${reactiveVariableName}"
}
```

This allows elements to participate in the parent template's reactive data flow, reading variables that were defined at the template level.

---

## How Annotations Work Internally

All eight element annotations are defined in `spaceport.launchpad.element` as simple marker annotations with `@Retention(RetentionPolicy.RUNTIME)`. They carry no attributes -- their presence on a field or method is what matters.

```groovy
package spaceport.launchpad.element

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

@Retention(RetentionPolicy.RUNTIME)
@interface CSS {}
```

All annotations follow this identical pattern: `@CSS`, `@ScopedCSS`, `@Javascript`, `@Bind`, `@Prepend`, `@ScopedPrepend`, `@Append`, `@ScopedAppend`.

The annotations themselves are inert markers. All processing logic lives in the `initialize()` method of the `Element` trait, which uses Java reflection (`field.getAnnotation(CSS)`, `method.getAnnotation(Bind)`) to scan for annotated fields and methods at runtime.

### @CSS -- How Global Styles Are Built

During `initialize()`, all fields annotated with `@CSS` are iterated via reflection:

```groovy
this.class.declaredFields.each { field ->
    field.setAccessible(true)
    if (field.getAnnotation(CSS)) {
        _style = _style + field.get(this).toString()
    }
}
```

After aggregation, if the CSS starts with `&`, the first `&` is replaced with the element's tag name:

```groovy
if (_style.length() > 0 && _style.trim().startsWith('&'))
    _style = _style.replaceFirst('&', getTagName())
```

This is designed for the common pattern of `& { ... }` becoming `my-element { ... }`. Only the first occurrence is replaced -- additional `&` references inside the block are left alone for CSS nesting. The result is wrapped in `<style>` tags:

```groovy
if (_style.length() > 0) _style = "<style>${_style}</style>\n"
```

### @ScopedCSS -- How Instance-Scoped Styles Work

Scoped CSS uses regex to find every CSS selector (text before a `{`) and prepend the instance scope:

```groovy
def scopedSelector = "[element-id=${ _id }] > "
def regex = /([^\{\}]+)\s*\{/
cssString = cssString.replaceAll(regex) { match, selector ->
    def scopedSelectors = selector.split(',').collect { s ->
        scopedSelector + s.trim()
    }.join(', ')
    return "\n ${scopedSelectors} {"
}
```

Comma-separated selectors are individually prefixed. A separate pass handles the `&` self-reference by converting `[element-id=<id>] > &` into `[element-id=<id>]` (removing the child combinator when self-referencing):

```groovy
_scopedStyle = _scopedStyle.replace(
    '[element-id=' + _id + '] > &',
    '[element-id=' + _id + '] ')
```

### @Bind -- How Server Methods Become Client Functions

For each method annotated with `@Bind`, the framework:

1. Generates a unique random 10-character function ID
2. Creates a server-side closure wrapper that invokes the method and then checks all registered reactions for changes
3. Stores the wrapper in `_handlerMap` keyed by the function ID
4. Generates a client-side JavaScript proxy function appended to `_handler`

**Zero-parameter methods** generate a simpler proxy:

```javascript
element_<id>.methodName = function() {
    var args = Array.from(arguments);
    socket.send(JSON.stringify({
        "handler-id": "launchpad-socket",
        "action": "element-call",
        "function": "<function-id>",
        "element": "<element-id>",
        "uuid": "<launchpad-launch-id>",
        "args": args
    }));
}
```

**Methods with parameters** generate a proxy that supports an optional trailing callback function. If the last argument is a function, it is extracted and stored in `window.spaceportCallbacks` under a unique request ID:

```javascript
element_<id>.methodName = function() {
    var args = Array.from(arguments);
    var callback = null;
    if (typeof args[args.length - 1] === 'function') {
        callback = args.pop();
    }
    var requestId = 'req-' + Math.random().toString(36).substring(2, 15);
    if (callback) {
        window.spaceportCallbacks = window.spaceportCallbacks || {};
        window.spaceportCallbacks[requestId] = callback;
    }
    socket.send(JSON.stringify({
        "handler-id": "launchpad-socket",
        "action": "element-call",
        "function": "<function-id>",
        "element": "<element-id>",
        "uuid": "<launchpad-launch-id>",
        "args": args,
        "request-id": requestId
    }));
}
```

The server-side closure wraps the method call with reaction checking:

```groovy
{ args ->
    def returned = element.invokeMethod(method.name, args)
    // Check all reactive expressions for changes
    for (def reaction in _reactions) {
        def payload = reaction.value.reaction(element, reaction.key).toString()
        if (reaction.value._current != payload) {
            // Push updated payload via WebSocket
            launchpad.binding._socket.session?.remote?.sendStringByFuture(
                new JsonBuilder([
                    'action': 'applyReaction',
                    'uuid': reaction.key,
                    'payload': payload
                ]).toString())
            reaction.value._current = payload
        }
    }
    return returned
}
```

This means that reactive expressions (`${{ }}`) inside `prerender()` automatically update after any `@Bind` call that modifies the underlying data.

### @Javascript -- How Client-Side Code Is Processed

JavaScript fields are processed after `@Bind` methods. The behavior differs based on field content:

**Function properties** (value starts with `function(`): The field value is assigned as a method on the element:

```javascript
element_<id>.fieldName = function(...) { ... }
```

Inside these functions, `this` refers to the DOM element because the function is a property of the element object.

**Inline code** (value does not start with `function(`): References to `this` are rewritten to the element reference variable:

```groovy
source.replaceAll('\\sthis\\s', ' element_' + _id + ' ')
      .replaceAll('this\\.', 'element_' + _id + '.')
      .replaceAll('this\\[', 'element_' + _id + '[')
```

This replacement allows inline JavaScript to use `this` as a convenient reference to the element, even though it is not executing within a method context.

**Special fields `constructed` and `deconstructed`:** These are not treated differently by the annotation processor -- they become function properties like any other. The special behavior comes from the script wrapper: after all JavaScript is assembled, the script checks for and calls `constructed`:

```javascript
if (element_<id>.constructed) {
    element_<id>.constructed(element_<id>)
}
```

The `deconstructed` function is called by HUD-Core when the element is removed from the DOM.

### @Prepend, @ScopedPrepend, @Append, @ScopedAppend

These annotations are straightforward -- during `initialize()`, all fields with each annotation are concatenated into their respective `_prepend`, `_scopedPrepend`, `_append`, and `_scopedAppend` properties. No transformation is applied to the content; it is injected as-is during the final HTML assembly step.

---

## Element Discovery and Registration

### Startup Scan

When a `Launchpad` instance is constructed, it scans its own `elements/` subdirectory for `.groovy` files. Each Launchpad gets:

- A **per-instance** element map (`launchpad.elements`) keyed by kebab-case tag name
- A contribution to the **static cross-Launchpad pool** (`Launchpad.sharedPool`) governed by ownership rules (see the [multi-Launchpad model](#multi-launchpad-element-resolution) below)

All `.groovy` files for one Launchpad are parsed through a **single shared `GroovyClassLoader`**, so sibling Element classes are mutually visible by class name:

```groovy
// In Launchpad constructor (paraphrased)
Map elements = new ConcurrentHashMap()      // per-instance, not static
static def sharedPool = new ConcurrentHashMap()  // cross-Launchpad pool
static def poolOwners = new ConcurrentHashMap()

def elementLoader = new GroovyClassLoader(SourceStore.classLoader)
for (def element in new File(this.sourcePath + 'elements').listFiles()) {
    if (element.name.endsWith('.groovy')) {
        def text = element.text
        // Transform reactive syntax: ${{ expr }} -> ${ _{ _script, _registration -> expr }}
        text = text.replaceAll(/\$\{\{\s*/, '\\${ _{ _script, _registration -> ')
        def clazz = elementLoader.parseClass(text)
        def tagName = element.name.replace('.groovy', '').kebab()
        this.elements.put(tagName, clazz)            // always populate local map
        contributeToSharedPool(tagName, clazz)       // ownership-aware
    }
}
// Templates compiled against an older view of the registry must be re-scanned.
SpaceportTemplateEngine.templateCache.clear()
```

Key details:

- **Reactive syntax preprocessing.** The `${{ }}` reactive expression syntax is transformed into Launchpad's internal closure format before parsing. The same transformation is applied to `.ghtml` templates, so reactive expressions work identically in Elements and templates.
- **Shared classloader per Launchpad.** All Element classes for a single Launchpad load into one classloader. `Page.prerender()` may reference `Sidebar.renderHtml(...)` directly — no helper module required. Earlier versions used a throwaway loader per file, which made sibling classes invisible to each other.
- **Per-instance map.** `launchpad.elements` is now an instance field, not the static `Launchpad.elements` of earlier versions. Two Launchpads constructed for the **same source path** share the same element map (the `assemble()` case), so the install pass runs once per source path.
- **Path normalization.** The directory lookup uses the trailing-slash-normalized `this.sourcePath`. Earlier code used the constructor's raw `sourcePath` argument, which silently missed the directory when callers passed a path without a trailing slash.
- **Template cache invalidation.** Installing a new Element clears `SpaceportTemplateEngine.templateCache`. Without this, templates compiled before the new Element was registered would still report `<!-- SpaceportTemplateEngine: Unknown tag: ... -->` until something else forced a recompile.

### Multi-Launchpad Element Resolution

A Spaceport can have multiple Launchpads running side-by-side — typically a canonical "global" Launchpad plus one or more **slice** Launchpads with `sourcePath` set to a sub-directory. Each slice has its own `parts/` and `elements/`, and Element resolution honors both local definitions and a shared pool.

**Naming and identity:**

| Form | Resolved name |
|---|---|
| `new Launchpad()` (no args) | `"global"` (reserved) |
| `new Launchpad('/path/to/alpha')` | `"alpha"` (last path segment) |
| `new Launchpad(name: 'main')` | `"main"` |
| `new Launchpad('/path/to/z', name: 'zeta')` | `"zeta"` |

A slice cannot claim the reserved name `"global"`; the constructor throws if you try. Reusing an existing name with a *different* source path also throws. Same name + same source path is allowed (this is the `assemble()` case — both instances refer to the same logical Launchpad and share the elements map).

**Resolution order from any rendering Launchpad:**

1. `this.elements[name]` — the rendering Launchpad's own local map. If found, use it.
2. `Launchpad.sharedPool[name]` — the cross-Launchpad pool. If found, use it.
3. Otherwise: `<!-- Unknown tag: name -->`.

A local definition always shadows the pool **for the Launchpad that defines it locally**, but the pool still serves any Launchpad that doesn't have a local definition.

**Pool ownership rules (in `contributeToSharedPool`):**

| State | Action |
|---|---|
| Pool slot empty | This Launchpad claims it |
| Slot owned by `global`, this Launchpad is not global | No-op (global cannot be displaced) |
| Slot owned by another slice, this Launchpad is global | Overwrite — global wins |
| Slot owned by slice A, this is slice B (neither is global) | **Evict** — remove the name from the pool entirely. Both slices keep it locally; nobody else sees it through the pool. |
| Slot owned by this Launchpad re-installing | No-op |

The eviction case is intentional: the framework surfaces ambiguous slice-vs-slice collisions loudly rather than picking a silent winner.

**Qualified `<g:slice/element>` syntax** bypasses both local and pool resolution and reaches directly into the named slice's local map. See the [Launchpad API reference](launchpad-api.md) for the user-facing description; in the engine, it's recognized at compile time (a `launchpadName` field is stashed on `template.elements` entries) and at runtime in `processNestedElements`.

---

## The Compilation Pipeline: From `<g:tag>` to Rendered HTML

The transformation of a `<g:tag>` in a template to rendered HTML in the browser involves three phases: template compilation, element instantiation, and element processing.

### Phase 1: Template Compilation (SpaceportTemplateEngine)

When a `.ghtml` template is compiled, the `SpaceportTemplateEngine` processes all `<g:tag>` occurrences. The engine works from the **last** `<g:` tag backwards (innermost elements first), which correctly handles nested elements:

```groovy
while (text.contains('<g:')) {
    def start = text.lastIndexOf('<g:')
    def tagName = text.substring(start + 3, indexOfFirstOf(text, [' ', '>'], start + 3))
    def end = text.indexOf('</g:' + tagName + '>', start)
    def outerTag = text.substring(start, end + 5 + tagName.length())
    def processed = processTag(tagName, outerTag, template)
    text = text.substring(0, start) + processed + text.substring(end + 5 + tagName.length())
}
```

The `hud:` prefix is aliased to `g:` for backward compatibility.

In `processTag()`, the engine checks two registries:
1. **Built-in generators** (`generators` map) -- handles tags like `<g:javascript>` and `<g:prime>`
2. **Registered elements** — resolved via the engine's bound `Launchpad` (`launchpad.resolveElement(name)` for local + shared-pool lookup), or via the qualified slice's local map when the tag uses `<g:slice/element>` syntax

For user-defined elements, the tag is replaced with placeholder markers:

```groovy
if (knownElement) {
    def id = 10.randomID()
    def entry = [name: localName]
    if (qualifiedSlice != null) entry.launchpadName = qualifiedSlice  // for render-time resolution
    template.elements.put(id, entry)
    result = """<!-- ${id}-styles --><${id} ${originalAttributeString}><!-- ${id}-body -->${body}<!-- END ${id}-body --></${id}><!-- ${id}-handler -->"""
}
```

The engine receives a Launchpad via its constructor (`new SpaceportTemplateEngine(launchpad)`), so each slice's templates see its own local Elements plus the shared pool. The no-arg constructor still works for callers without a Launchpad context — those fall back to the shared pool only, matching the legacy single-Launchpad behavior.

This produces intermediate HTML with:
- `<!-- id-styles -->` -- placeholder for CSS injection
- A temporary tag using the random 10-character ID as the tag name (not the real element name)
- `<!-- id-body -->` / `<!-- END id-body -->` -- markers around the body content
- `<!-- id-handler -->` -- placeholder for JavaScript injection

The element class is **not** instantiated at this point. The element metadata (name, ID) is stored in `template.elements` for later processing.

### Phase 2: Element Instantiation (Launchpad.prime)

After the template engine produces the intermediate HTML and the Groovy scriptlets execute (producing the final template output), Launchpad's `prime()` method scans the output for handler markers:

```groovy
def pattern = Pattern.compile(/<!-- (\w*)-handler -->/)
def matcher = pattern.matcher(primed)
while (matcher.find()) {
    def elementId = matcher.group(1)
    def e = template.elements.get(elementId)
    if (e == null) continue

    // Resolve from this Launchpad's perspective; honors qualified <g:slice/elem>.
    Class elementClass = resolveForEntry(e)
    if (elementClass == null) continue
    Element newElement = elementClass.newInstance()
    newElements.add([name: e.name, replacementId: elementId, element: newElement])

    // Context injection
    newElement.launchpad = this
    newElement.client = binding.client ?: binding.'$client'
    newElement.dock   = binding.dock ?: binding.'$dock'
    newElement._id = 6.randomID()
}

// Stash the recipe for EVERY compile-time element id (not just the ones
// that didn't appear in the primed output). parseElements consults these
// recipes to spin up extra instances when a reactive update produces more
// placeholder copies than the original render registered.
if (binding._stashed_elements == null) binding._stashed_elements = [:]
for (def element in template.elements) {
    binding._stashed_elements.put(element.key, element.value)
}
```

Each element tag gets a fresh class instance with:
- A unique 6-character `_id` (used as the `element-id` attribute in the DOM)
- References to the parent Launchpad, client session, and dock

Elements that appear inside reactive expressions or conditional blocks may not have their markers in the initial template output. These are "stashed" for deferred processing (see the Stashed Elements section).

### Phase 3: Element Processing (Launchpad.parseElements)

After all parts are primed and assembled with the vessel template, `parseElements()` is called. It performs the final transformation in a `while (replacing)` loop, capped at 16 passes, that continues until no more elements need processing (handling nested elements that are revealed by processing outer elements).

Before the main loop runs, `parseElements` calls `reconcileMarkerCount(html)`, which spins up any element instances needed to cover handler markers currently in the HTML. The reconciliation pass uses the recipes stored in `binding._stashed_elements` (every compile-time element id stashes one) and is the single point that handles both:

- **Stashed-on-first-appearance** — a compile-time-known element that wasn't in the initial primed output (e.g. inside a conditional or an empty reactive list) is being rendered now.
- **Reactive-update shortfall** — a reactive expression that previously emitted *N* copies of the same `<g:foo>` placeholder now emits *N+M* copies; the surplus needs fresh instances or its handler markers will stay in the payload as raw `<!-- {id}-handler -->` rather than becoming real `<custom-element>` tags with handler JS.

The 16-pass cap defends against an Element whose `prerender()` accidentally emits its own tag (a true cycle would otherwise loop forever). When the cap is hit, the loop terminates safely — the offending Element doesn't render, but the server doesn't hang.

For each element instance, the method performs these steps in order:

**Step 1: Replace temporary tags.**

```groovy
html = html.replaceFirst('<' + id, '<' + tagName + ' element-id="' + rid + '"')
html = html.replaceFirst('</' + id + '>', '</' + tagName + '><!-- /' + rid + ' -->')
```

The temporary 10-character ID tag is replaced with the real kebab-case element tag, and an `element-id` attribute is added. The closing tag gets a trailing comment marker used for scoped append injection.

**Step 2: Extract body and attributes.**

The framework extracts the content between the `<!-- id-body -->` and `<!-- END id-body -->` markers as the `body` parameter. It parses the element's HTML attributes from the tag string using a regex-based attribute parser that handles quoted values, single-quoted values, and unquoted values.

Server-side attribute values (created by `${ _{ expression }}` syntax in the template) are resolved at this point. The framework detects attribute values starting with `/!/`, looks up the corresponding binding UUID, and calls the stored closure to get the actual Groovy object:

```groovy
for (def key in attributes.keySet()) {
    if (attributes[key].startsWith('/!/') && !key.toLowerCase().startsWith('on-')) {
        def bindingId = attributes[key].split('&')[1].split('=')[1]
        if (binding._bindings.containsKey(bindingId)) {
            attributes[key] = binding._bindings.get(bindingId).action.call()
            binding._bindings.remove(bindingId)
        }
    }
}
```

The attributes map is also enhanced with convenience methods:

```groovy
attributes.tap {
    it.'getNumber'  = { v -> /* parse as Number (Long or Double) */ }
    it.'getBool'    = { val -> owner.get(val)?.toBoolean() }
    it.'getString'  = { val -> owner.get(val)?.toString() }
    it.'getList'    = { val -> /* parse as List: JSON array, CSV, or single value */ }
    it.'getInteger' = { val -> owner.get(val)?.toInteger() }
}
```

**Step 3: Call `prerender()` and rewrite any nested `<g:>` tags it emitted.**

```groovy
def prerendered = element.prerender(body, attributes)
prerendered = processNestedElements(prerendered)   // scan return value for <g:> tags
html = StringUtils.replaceOnce(html, fullMatch, prerendered)
```

The body marker region is replaced with whatever `prerender()` returns. Before substitution, `processNestedElements()` scans the returned HTML for `<g:>` patterns; for each tag it finds, a fresh Element instance is created, registered into `binding._elements`, and inlined as the same placeholder markup the template engine would have produced at compile time. The outer 16-pass loop then picks up these new instances on subsequent passes, giving them the same treatment (CSS dedup, handler emission, recursive nesting, qualified-syntax resolution) as template-authored tags.

This means an Element can compose other Elements declaratively rather than concatenating their rendered HTML by hand:

```groovy
class Page implements Element {
    String prerender(String body, Map attributes) {
        return "<div><g:sidebar></g:sidebar><main>${body}</main></div>"
    }
}
```

`processNestedElements()` also accepts the qualified `<g:slice/element>` form, reaching directly into a named Launchpad's local elements map (bypassing the rendering Launchpad's local + pool resolution).

**Step 4: Call `initialize()`.**

```groovy
if (!element._initialized) {
    element.initialize()
}
```

This processes all annotations and builds the aggregated CSS and JavaScript (as described in the Annotations section above). The `_initialized` flag prevents double initialization.

**Step 5: Inject CSS and JavaScript.**

```groovy
def globalStyle = ''
if (element._style?.length() > 0 && !globalStyled.contains(element.tagName)) {
    globalStyle = element._style
    globalStyled.add(element.tagName)
}
html = StringUtils.replaceOnce(html, "<!-- " + id + "-styles -->",
    "${globalStyle}${element._scopedStyle}")
html = StringUtils.replaceOnce(html, "<!-- " + id + "-handler -->",
    element._handler)
```

The style marker is replaced with `<style>` tags. Global `@CSS` content (`_style`) is emitted **once per element type per response** — subsequent instances of the same type skip it via the `globalStyled` tracker. Per-instance `@ScopedCSS` content (`_scopedStyle`) is still emitted for every instance. The handler marker is replaced with the `<script>` tag containing the element's JavaScript.

**Step 6: Inject prepend/append content.**

Global prepend and append content is injected once per element type, tracked by the `prepended` and `appended` lists:

```groovy
// Global prepend: injected before </head> (or at top of output)
if (element._prepend?.length() > 0 && !prepended.contains(element.tagName)) {
    if (html.contains('</head>')) {
        html = StringUtils.replaceOnce(html, '</head>', element._prepend + '\n</head>')
    } else {
        html = element._prepend + '\n' + html
    }
    prepended.add(element.tagName)
}

// Global append: injected after </body> (or at bottom of output)
if (element._append?.length() > 0 && !appended.contains(element.tagName)) {
    if (html.contains('</body>')) {
        html = StringUtils.replaceOnce(html, '</body>', '</body>\n' + element._append)
    } else {
        html = html + '\n' + element._append
    }
    appended.add(element.tagName)
}
```

Scoped prepend/append content is injected adjacent to each specific element instance:

```groovy
// Scoped prepend: inserted immediately before the element tag
if (element._scopedPrepend?.length() > 0) {
    html = StringUtils.replaceOnce(html, '<' + tagName + ' element-id="' + rid + '"',
        element._scopedPrepend + '\n<' + tagName + ' element-id="' + rid + '"')
}

// Scoped append: inserted immediately after the element closing tag
if (element._scopedAppend?.length() > 0) {
    html = StringUtils.replaceOnce(html, '<!-- /' + rid + ' -->',
        '\n' + element._scopedAppend)
}
```

---

## CSS/JS Asset Injection -- Final Placement

The rendered HTML for a single element instance in the final page output looks like this:

```html
<!-- CSS is injected at the style marker position (before the element) -->
<style>my-element { display: block; ... }</style>
<style>[element-id=a1b2c3] > .item { ... }</style>

<!-- The element tag itself -->
<my-element element-id="a1b2c3">
    <!-- prerender() output -->
</my-element>

<!-- JavaScript is injected at the handler marker position (after the element) -->
<script>
let element_a1b2c3 = document.querySelector('[element-id="a1b2c3"]')
element_a1b2c3._listeners = [];
element_a1b2c3.listen = function(type, handler, options) {
    this.addEventListener(type, handler, options);
    this._listeners.push({ type: type, handler: handler, options: options });
};
// @Bind proxy functions
// @Javascript methods and inline code
if (element_a1b2c3.constructed) {
    element_a1b2c3.constructed(element_a1b2c3)
}
</script>
```

For `@Prepend`/`@Append` content (e.g., external library `<link>` and `<script>` tags from a `TextEditor` element), the content appears once in `<head>` or after `</body>`, regardless of how many instances of that element type exist on the page.

---

## The Binding System for Reactive Data

### Reactive Expressions in Elements

Elements can use Launchpad's reactive expression syntax (`${{ }}`) inside `prerender()`. During the element source preprocessing step (at discovery time), the `${{ expr }}` syntax is transformed to `${ _{ _script, _registration -> expr }}`, which is the same transformation applied to `.ghtml` templates.

When the reactive closure executes during `prerender()`, it registers a reaction in the element's `_reactions` map. The reaction stores the closure that produces the current value and the current payload for change detection.

When a `@Bind` method executes on the server, the framework re-evaluates every reaction in the element's `_reactions` map. If a payload has changed, the new value is pushed to the client via WebSocket as an `applyReaction` message. HUD-Core on the client uses DOM comment markers (`<!-- START_CHUNK@uuid -->` / `<!-- END_CHUNK@uuid -->`) to locate and replace the affected DOM region.

### Server Actions in Elements

The `_(Closure c)` method on the trait delegates to `launchpad.bind(c)`, which registers the closure in the Launchpad binding system and returns a URL-like reference. When used in `prerender()` output as an event handler attribute:

```groovy
return """<button on-click=${ _{ counter.inc() }}>+</button>"""
```

The closure is registered as a server action. When the button is clicked, HUD-Core sends the action to the server via WebSocket, the closure executes, and any reactive expressions that depend on the modified state are re-evaluated and pushed to the client.

---

## Server-Side WebSocket Handling for @Bind

When a `@Bind` method is invoked from the client, the WebSocket message is routed through Launchpad's socket handler:

```groovy
if (r.context.data.action == 'element-call') {
    def element = bindingSatellites.get(r.context.data.uuid)?._elements?.find {
        it.element._id == r.context.data.element
    }
    if (element) {
        def function = r.context.data.function
        def method = element.element._handlerMap.get(function)

        def returnValue
        if (r.context.data.args instanceof List) {
            returnValue = method.method(*r.context.data.args)
        } else {
            returnValue = method.method()
        }

        def responsePayload = [
            'action': 'elementResponse',
            'function': r.context.data.function,
            'payload': returnValue
        ]
        if (requestId) responsePayload.put('requestId', requestId)
        r.writeToRemote(responsePayload)
    }
}
```

The flow:

1. The `launchpad-socket` handler receives the `element-call` message
2. The element instance is located by its `_id` within the launch session's `_elements` list (stored in `bindingSatellites`)
3. The handler method is found in the element's `_handlerMap` by function ID
4. Arguments are spread into the method call using the Groovy spread operator (`*args`)
5. The response (including any request ID for client callback matching) is sent back as an `elementResponse` message

On the client side, if the caller provided a callback:

```javascript
if (data.action === 'elementResponse') {
    if (data.requestId) {
        var callback = window.spaceportCallbacks && window.spaceportCallbacks[data.requestId];
        if (typeof callback === 'function') {
            callback(data.payload);
            delete window.spaceportCallbacks[data.requestId];
        }
    }
}
```

---

## Element Lifecycle

### Server-Side Lifecycle

1. **Discovery** -- Element `.groovy` files are scanned from each Launchpad's `elements/` subdirectory during that Launchpad's construction
2. **Source preprocessing** -- Reactive `${{ }}` syntax is transformed to internal closure format
3. **Class compilation** -- A single shared `GroovyClassLoader` (per Launchpad) compiles all that Launchpad's preprocessed Element sources into Class objects, so sibling Elements can reference each other by class name
4. **Registration** -- The Class is stored in the Launchpad's per-instance `launchpad.elements` map and contributed to the static `Launchpad.sharedPool` according to ownership rules
5. **Instantiation** -- On each page request, `newInstance()` creates a fresh element instance
6. **Context injection** -- `launchpad`, `client`, `dock`, and `_id` are set on the instance
7. **`prerender()` call** -- The element's body and parsed attributes are passed in; the method returns inner HTML
8. **`initialize()` call** -- Annotations are processed; CSS and JavaScript are aggregated
9. **Asset injection** -- CSS (`<style>`), JavaScript (`<script>`), and prepend/append content are injected into the HTML output at their respective marker positions

### Client-Side Lifecycle

On page load, as the browser parses the HTML and encounters the element's `<script>` tag:

1. **Element reference** -- `window.element_<id>` is set to the DOM element via `document.querySelector('[element-id="<id>"]')`
2. **Listener infrastructure** -- The `_listeners` array and `.listen()` helper method are attached
3. **Inline JavaScript** -- Any `@Javascript` fields that are not function definitions execute immediately
4. **Function attachment** -- `@Javascript` function properties are assigned as methods on the element
5. **Bind proxies** -- `@Bind` proxy functions are assigned as methods on the element
6. **`constructed` callback** -- If a `constructed` function is defined, it is called with the element as its argument

On removal (managed by HUD-Core when DOM regions are replaced):

1. **Listener cleanup** -- All listeners registered via `.listen()` are automatically removed by iterating `_listeners`
2. **`deconstructed` callback** -- If defined, called with the element as its argument for manual cleanup
3. **Global cleanup** -- `window.element_<id>` is deleted from the window object

### What Gets Cleaned Up Automatically

- All event listeners registered via `.listen()` on the root element
- The `window.element_<id>` global variable

### What Must Be Cleaned Up Manually (in `deconstructed`)

- Event listeners added directly via `addEventListener` on child elements or `document`/`window`
- `setInterval` / `setTimeout` timers
- `ResizeObserver`, `MutationObserver`, `IntersectionObserver` instances
- `requestAnimationFrame` loops
- Content appended to `document.body` (e.g., popover content moved outside the element)

---

## Hot-Reload Behavior

In debug mode (`debug: true` in the manifest), Spaceport sets up a `Beacon` (file system watcher) on the `launchpad/` directory during the `on initialize` alert. When any file changes:

```groovy
new Beacon(dir, { path, kind ->
    if (!reloading) {
        reloading = true
        Thread.start {
            Thread.sleep(100)  // 100ms debounce
            SpaceportTemplateEngine.templateCache.clear()
            clearAllElements()   // per-instance maps + shared pool + pool owners
            reloading = false
        }
    }
})
```

`clearAllElements()` iterates `Launchpad.byName.values()` and clears each Launchpad's `elements` map, then clears `sharedPool` and `poolOwners`. The debounce prevents cascading reloads when an IDE saves multiple files at once. After clearing:

- Every Launchpad's `elements` map is empty, so the next request triggers full element rediscovery and recompilation for whichever Launchpad serves the request
- Template cache is cleared, so `.ghtml` files are re-parsed
- Existing connected client sessions are not affected until the user refreshes the page

In addition, the template cache is cleared **each time a Launchpad finishes installing its Elements**, regardless of debug mode. This guarantees that adding a new Element file doesn't leave templates compiled against an older view of the registry (which would otherwise produce `<!-- SpaceportTemplateEngine: Unknown tag: ... -->` until something else forced a recompile).

Hot-reload does **not** push changes to already-connected clients. A page refresh is required to see updated elements.

---

## Stashed Elements, Reactive Updates, and Marker Reconciliation

Elements that appear inside reactive expressions (`${{ }}`) or within conditional blocks present two related timing challenges:

1. **Deferred appearance** — a compile-time-known element wasn't in the initial primed output (e.g. inside an `<% if %>` block that evaluated false, or inside a reactive expression over an initially-empty list). Its handler markers show up later, when the page is reactively re-rendered.
2. **Reactive-update shortfall** — a reactive expression that emitted *N* copies of a `<g:foo>` placeholder on first render now emits *N+M* copies after a state change (e.g. `items` grew from 6 to 9). The framework's per-placeholder element instances cover only the first *N*; the surplus *M* placeholders carry handler markers that no instance has been spun up for.

Both cases route through `binding._stashed_elements` and the `reconcileMarkerCount` pass at the start of `parseElements`.

### Stash every compile-time recipe

`prime()` stashes **every** `template.elements` entry into `binding._stashed_elements` — not just entries that didn't appear in the initial primed output:

```groovy
if (binding._stashed_elements == null) binding._stashed_elements = [:]
for (def element in template.elements) {
    binding._stashed_elements.put(element.key, element.value)
}
```

This makes `_stashed_elements` an "all compile-time recipes" map. The reconcile pass needs a recipe to spin up a fresh instance for any placeholder id, so every id must be present here regardless of whether it appeared in the initial render.

### Reconcile markers against existing instances

At the start of `parseElements`, `reconcileMarkerCount(html)` counts handler markers per id in the incoming HTML and compares them to `binding._elements` entries that haven't already been resolved (no `<tagName element-id="rid">` in the payload yet). For each id, if there are more markers than available instances, it creates the shortfall using the stashed recipe:

```groovy
private void reconcileMarkerCount(String html) {
    if (binding._stashed_elements == null || binding._stashed_elements.isEmpty()) return
    if (binding._elements == null) binding._elements = []

    def markerCounts = [:]
    def matcher = Pattern.compile(/<!-- (\w*)-handler -->/).matcher(html)
    while (matcher.find()) {
        String id = matcher.group(1)
        markerCounts[id] = (markerCounts[id] ?: 0) + 1
    }

    markerCounts.each { String id, Integer markerCount ->
        int availableCount = binding._elements.count { e ->
            e.replacementId == id &&
            !html.contains('<' + e.element.tagName + ' element-id="' + e.element._id + '"')
        }
        int shortfall = markerCount - availableCount
        if (shortfall <= 0) return

        def recipe = binding._stashed_elements[id]
        Class cls = resolveForEntry(recipe)
        if (cls == null) return

        shortfall.times {
            Element inst = cls.newInstance()
            inst.launchpad = this
            inst.client = binding.client ?: binding.'$client'
            inst.dock   = binding.dock   ?: binding.'$dock'
            inst._id    = 6.randomID()
            binding._elements << [name: recipe.name, replacementId: id, element: inst]
        }
    }
}
```

Reconciliation **preserves existing entries** and only adds what's missing. Earlier versions of the code renamed `replacementId`s on existing entries when stashed elements were processed; that logic is gone — the new reconcile pass is correct for both the deferred-appearance case and the reactive-update-grew case.

### Why this matters in practice

Reactive lists are a common pattern. Before this reconciliation, anything like `items.combine { "<g:row>..." }` where `items` grew over time silently dropped Elements past the initial count — they appeared in the DOM as bare custom tags but without their handler hookup, so any `@Bind`-driven JS behavior didn't work for the elements born via reactive update.

The reconcile pass also handles the case where a reactive expression initially evaluated to an empty list. Earlier logic stashed entries only on first appearance and would miss subsequent updates; the unconditional stash + reconcile combination covers it correctly.

---

## Thread Safety Considerations

- **`launchpad.elements`** (per-instance) and **`Launchpad.sharedPool` / `Launchpad.poolOwners` / `Launchpad.byName`** (static) are all `ConcurrentHashMap`s, safe for concurrent reads during request handling and writes during hot-reload.
- **Element instances** are created per-request via `newInstance()`, so element state is request-scoped with no shared mutable state between requests.
- **`launch()` is `synchronized`**, preventing concurrent rendering of the same Launchpad instance. Different Launchpad instances (different routes, different slices) render concurrently.
- **`_bindings`, `_reactions`, and `_elements`** on the Launchpad binding are per-session, tied to a specific launch ID.
- **`binding`** (the per-Launchpad script-binding map) is `Collections.synchronizedMap([:])` — chosen over `ConcurrentHashMap` because templates legitimately assign `null` values via the `def`-strip mechanism (e.g. `<% def foo = something?.maybeMissing %>` routes through `Binding.setVariable`, which `ConcurrentHashMap` would NPE on). The synchronized HashMap preserves coarse-grained thread-safety for concurrent websocket-event paths without the null-rejection.
- **Cargo objects** used within elements provide their own thread-safety guarantees.
- **Hot-reload** clears every Launchpad's `elements` map plus the shared pool and template cache on a separate thread with a debounce delay; the `reloading` flag prevents overlapping reloads.
