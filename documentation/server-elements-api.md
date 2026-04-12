# Server Elements -- API Reference

## `Element` Trait

```groovy
import spaceport.launchpad.element.Element
```

The core trait that all Server Elements implement. Defined in `spaceport.launchpad.element.Element`. Classes implementing this trait become usable as custom HTML elements in Launchpad templates.

### Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `_bindings` | `Map` | `[:]` | Reactive bindings registered by this element. Managed internally. |
| `_source` | `String` | `''` | Reserved for debugging. Not currently implemented. |
| `_handler` | `String` | `''` | Aggregated JavaScript code block generated during `initialize()`. Becomes a `<script>` tag in the rendered output. |
| `_handlerMap` | `Map` | `[:]` | Maps generated function IDs to `@Bind` method closures. Used to route WebSocket calls to the correct server method. |
| `_id` | `String` | `'uninitialized'` | Unique identifier assigned to each element instance. Set by Launchpad during template processing (6-character random ID). Used as the `element-id` attribute in the DOM. |
| `_reactions` | `Map` | `[:]` | Reactive expression subscriptions belonging to this element. Managed internally. |
| `_style` | `String` | `''` | Aggregated CSS from `@CSS`-annotated fields. Wrapped in `<style>` tags during `initialize()`. |
| `_scopedStyle` | `String` | `''` | Aggregated CSS from `@ScopedCSS`-annotated fields, with selectors rewritten to scope to the element instance. Wrapped in `<style>` tags during `initialize()`. |
| `_initialized` | `boolean` | `false` | Whether `initialize()` has been called. Prevents double initialization. |
| `_prepend` | `String` | `''` | Aggregated HTML from `@Prepend`-annotated fields. Injected once per element type into `<head>`. |
| `_scopedPrepend` | `String` | `''` | Aggregated HTML from `@ScopedPrepend`-annotated fields. Injected before this specific element instance. |
| `_append` | `String` | `''` | Aggregated HTML from `@Append`-annotated fields. Injected once per element type after `</body>`. |
| `_scopedAppend` | `String` | `''` | Aggregated HTML from `@ScopedAppend`-annotated fields. Injected after this specific element instance. |
| `client` | `Client` | *(set by Launchpad)* | The `Client` object for the current session. Provides access to authentication state, user document, and permissions. |
| `dock` | `Cargo` | *(set by Launchpad)* | The session-scoped Cargo object. Shared persistent key-value store for user-specific data. |
| `launchpad` | `Launchpad` | *(set by Launchpad)* | Reference to the parent Launchpad instance. Provides access to the binding context. |

### Methods

#### `prerender(String body, Map<String, String> attributes)`

**Required.** Called server-side during page rendering to produce the element's inner HTML.

| Parameter | Type | Description |
|---|---|---|
| `body` | `String` | The inner HTML content between the element's opening and closing tags in the template |
| `attributes` | `Map<String, String>` | All attributes from the element's HTML tag, as string key-value pairs |

**Returns:** `String` -- the rendered inner HTML of the element.

The `attributes` map is enhanced with convenience methods:

- `attributes.getInteger(key)` -- returns the value as an `Integer`
- `attributes.get(key)` -- returns the value as a `String` (standard map access)
- `attributes.containsKey(key)` -- checks for the presence of an attribute (including boolean attributes like `checked` or `disabled`)

```groovy
String prerender(String body, Map attributes) {
    def color = attributes.color ?: 'blue'
    def size = attributes.containsKey('large') ? '2em' : '1em'
    return "<span style='color: ${color}; font-size: ${size}'>${body}</span>"
}
```

#### `initialize()`

Called automatically by Launchpad after `prerender()`, but only once per element instance (guarded by `_initialized`). Processes all annotation-based properties to build the aggregated CSS and JavaScript for the element.

You should not normally call this method directly. It is invoked by the framework during template rendering.

**Processing order within `initialize()`:**

1. `@CSS` fields -- aggregated into `_style`, wrapped in `<style>` tags
2. `@ScopedCSS` fields -- aggregated into `_scopedStyle` with instance-scoped selectors
3. `@Prepend` fields -- aggregated into `_prepend`
4. `@ScopedPrepend` fields -- aggregated into `_scopedPrepend`
5. `@Append` fields -- aggregated into `_append`
6. `@ScopedAppend` fields -- aggregated into `_scopedAppend`
7. `@Bind` methods -- generates client-side JavaScript proxy functions and registers server-side handlers in `_handlerMap`
8. `@Javascript` fields -- appended to the JavaScript handler block
9. Wraps all JavaScript in a `<script>` tag that initializes the client-side element reference

#### `getTagName()`

Returns the kebab-case version of the class's simple name. Used as the HTML tag name in the rendered output.

```groovy
// For class StarRating:
getTagName() // returns "star-rating"
```

#### `grab(String reactiveVariableName)`

Retrieves a reactive variable from the parent Launchpad template's binding context by name.

| Parameter | Type | Description |
|---|---|---|
| `reactiveVariableName` | `String` | The name of the variable in the template binding |

**Returns:** The value of the reactive variable.

#### `_(Closure c)`

Shorthand for creating a Launchpad server action binding (equivalent to calling `launchpad.bind(c)` from within the element). Used in `prerender()` to create `on-click`, `on-blur`, and similar event handlers.

```groovy
String prerender(String body, Map attributes) {
    return """<button on-click=${ _{ counter.inc() }}>Increment</button>"""
}
```

#### `getName()` (static)

Returns the lowercase version of the class's simple name.

---

## Annotations

All annotations are defined in the `spaceport.launchpad.element` package with `@Retention(RetentionPolicy.RUNTIME)`. They are marker annotations with no attributes.

```groovy
import spaceport.launchpad.element.*
```

---

### `@CSS`

Marks a field whose value contains CSS to be injected globally for this element type. The CSS is aggregated from all `@CSS` fields during `initialize()` and wrapped in a `<style>` tag.

**Target:** Fields (String or GString)

**Behavior:**
- Multiple `@CSS` fields are concatenated in declaration order
- The `&` character at the start of the CSS is replaced with the element's tag name, enabling self-referencing styles
- CSS is injected into the rendered page via a `<style>` tag placed at the element's style marker position
- The CSS is global -- all instances of the element type share the same styles

**Example:**

```groovy
@CSS String styles = """
    & {
        display: inline-flex;
        gap: 0.5em;

        span {
            font-size: 2em;
            color: goldenrod;
        }
    }
"""
```

If the element's class is `StarRating`, the `&` is replaced with `star-rating`, producing:

```css
star-rating {
    display: inline-flex;
    gap: 0.5em;

    span {
        font-size: 2em;
        color: goldenrod;
    }
}
```

**Notes:**
- You can use `static` on the field (common pattern when CSS does not reference instance state)
- No `<style>` tags needed -- the framework wraps the CSS automatically
- Modern nested CSS syntax is supported (nesting selectors inside `&`)

---

### `@ScopedCSS`

Marks a field whose value contains CSS that is scoped to a specific element instance using its `element-id` attribute.

**Target:** Fields (String or GString)

**Behavior:**
- Each CSS selector is automatically prefixed with `[element-id=<id>] > ` where `<id>` is the unique instance ID
- Selectors separated by commas are each individually prefixed
- The `&` reference (when combined with the scoped selector) resolves to the specific instance, not the tag name
- Useful when multiple instances of the same element need different styles, or to avoid style leakage between instances

**Example:**

```groovy
@ScopedCSS String scopedStyles = """
    .item {
        padding: 1em;
    }
    .item:hover, .item:focus {
        background: var(--lt-blue);
    }
"""
```

Produces (for an instance with `element-id="a1b2c3"`):

```css
[element-id=a1b2c3] > .item {
    padding: 1em;
}
[element-id=a1b2c3] > .item:hover, [element-id=a1b2c3] > .item:focus {
    background: var(--lt-blue);
}
```

---

### `@Javascript`

Marks a field whose value contains JavaScript code to be injected on the client side.

**Target:** Fields (String, GString, or `def`)

**Behavior depends on the field name and content:**

#### Special field: `constructed`

A function that receives the DOM element as its parameter. Called after the element's HTML has been inserted into the DOM and all other `@Javascript` properties have been evaluated.

```groovy
@Javascript String constructed = /* language=js */ """
    function(element) {
        element.listen('click', function(e) {
            console.log('Element clicked');
        });
    }
"""
```

#### Special field: `deconstructed`

A function that receives the DOM element as its parameter. Called when the element is removed from the DOM. Used for cleanup of external resources (timers, global event listeners, observers).

```groovy
@Javascript String deconstructed = /* language=js */ """
    function(element) {
        if (element._cleanup) element._cleanup();
    }
"""
```

#### Named function properties

If the field value starts with `function(`, it becomes a method on the client-side element instance. The field name becomes the method name. Inside the function, `this` refers to the DOM element.

```groovy
@Javascript def getValue = /* language=javascript */ """
    function() {
        return this.querySelector('input').value;
    }
"""

@Javascript def setValue = /* language=javascript */ """
    function(val) {
        this.querySelector('input').value = val;
    }
"""
```

These are callable from client-side JavaScript:

```javascript
document.querySelector('my-element').getValue();
document.querySelector('my-element').setValue('hello');
```

#### Inline execution

If the field value does not start with `function(`, it executes as inline JavaScript when the element initializes on the client. References to `this` are automatically rewritten to reference the element instance.

```groovy
@Javascript String setup = """
    this.dataset.initialized = 'true';
    console.log('Element ready:', this.tagName);
"""
```

**Notes:**
- The `/* language=js */` or `/* language=javascript */` comment is a hint for IDE syntax highlighting and is stripped during processing
- All JavaScript is aggregated and wrapped in a single `<script>` tag per element instance
- Inline JavaScript executes before `constructed` is called
- The element is referenced on the client as `element_<id>` (a global variable on `window`)

---

### `@Bind`

Marks a method as callable from client-side JavaScript via WebSocket. The framework generates a JavaScript proxy function on the element instance that, when called, sends a WebSocket message to the server to invoke the Groovy method.

**Target:** Methods

**Behavior:**

- For zero-parameter methods: the client-side proxy takes no arguments
- For methods with parameters: the client-side proxy accepts arguments, which are serialized as JSON and sent to the server. The last argument can be a callback function.
- After the bound method executes on the server, the framework checks all registered reactions and pushes any changed payloads to the client
- The return value is sent back to the client. If a callback was provided, it is invoked with the return value.

**Example:**

```groovy
@Bind def getValue() {
    return counter.get()
}

@Bind def setValue(def value) {
    counter.set(value.toInteger())
}
```

Client-side usage:

```javascript
// Fire-and-forget
document.querySelector('counter').setValue(42);

// With callback for return value
document.querySelector('counter').getValue(function(result) {
    console.log('Current value:', result);
});
```

**Notes:**
- Bound methods execute on the server in the context of the element instance
- The element's `client`, `dock`, and `launchpad` are available within bound methods
- The WebSocket connection must be established (HUD-Core loaded) for `@Bind` to function
- Each bound method gets a unique random function ID to prevent collisions

---

### `@Prepend`

Marks a field whose value is HTML to be injected at the beginning of the page (inside `<head>` if present, otherwise at the top of the output). Injected **once per element type**, regardless of how many instances appear on the page.

**Target:** Fields (String or GString)

**Common use case:** Loading external CSS or JavaScript libraries required by the element.

```groovy
@Prepend String prepend = """
    <link href="https://cdn.quilljs.com/1.3.6/quill.snow.css" rel="stylesheet">
    <script src="https://cdn.quilljs.com/1.3.6/quill.js"></script>
"""
```

---

### `@ScopedPrepend`

Marks a field whose value is HTML to be injected immediately before this specific element instance in the DOM. Unlike `@Prepend`, this is per-instance.

**Target:** Fields (String or GString)

---

### `@Append`

Marks a field whose value is HTML to be injected at the end of the page (after `</body>` if present, otherwise at the bottom of the output). Injected **once per element type**.

**Target:** Fields (String or GString)

---

### `@ScopedAppend`

Marks a field whose value is HTML to be injected immediately after this specific element instance in the DOM. Unlike `@Append`, this is per-instance.

**Target:** Fields (String or GString)

---

## Template Integration

### Using Elements in GHTML Templates

Elements are referenced using the `g:` prefix followed by the kebab-case element name:

```html
<g:element-name attribute1="value1" attribute2="value2">
    Inner body content
</g:element-name>
```

Elements must have both opening and closing tags.

### Attribute Passing

All attributes on the element tag are passed to `prerender()` as a `Map<String, String>`:

```html
<g:star-rating value="4" color="gold" readonly></g:star-rating>
```

In `prerender()`:

```groovy
String prerender(String body, Map attributes) {
    // attributes = [value: "4", color: "gold", readonly: ""]
    def stars = attributes.getInteger('value') ?: 0
    def isReadonly = attributes.containsKey('readonly')
}
```

Boolean attributes (like `readonly`, `disabled`, `checked`) have an empty string value but can be detected with `containsKey()`.

### Passing Server-Side Values as Attributes

Use Groovy expressions to pass dynamic values. For simple string values:

```html
<g:page-notes route="${ r.context.target }"></g:page-notes>
```

For passing closures or complex objects as attributes, use the server action binding syntax:

```html
<g:stat-card value="${ _{ myList }}" transform="${ _{ myTransformClosure }}" color="blue" icon="users">
    Total Users
</g:stat-card>
```

The `${ _{ expression }}` syntax creates a server action that is evaluated when the attribute is resolved, allowing complex Groovy objects (lists, closures, Cargo objects) to be passed into elements.

### Reactive Expressions in Elements

Elements can use Launchpad's reactive expression syntax (`${{ }}`) inside `prerender()`:

```groovy
String prerender(String body, Map attributes) {
    return """<span>${{ counter.get() }}</span>"""
}
```

When the underlying Cargo value changes, only the affected DOM region updates automatically via WebSocket.

### Server Actions in Elements

Elements can use Launchpad's server action syntax inside `prerender()`:

```groovy
String prerender(String body, Map attributes) {
    return """<button on-click=${ _{ counter.inc() }}>+</button>"""
}
```

The `_{ }` closure executes on the server when the button is clicked.

### Using Server Actions with Elements in Templates

You can attach server actions to element tags from the template side:

```html
<g:star-rating value="3" on-change=${ _{ t ->
    dock.ratings.put('item-1', t.value)
}}></g:star-rating>
```

---

## Element Discovery and Registration

### Automatic Discovery

When a `Launchpad` instance is created, it scans the `launchpad/elements/` directory for `.groovy` files. Each file is:

1. Read as text
2. Preprocessed (reactive `${{ }}` syntax is transformed)
3. Parsed into a class using `GroovyClassLoader`
4. Registered in the static `Launchpad.elements` map, keyed by the kebab-case name

### Registration Map

```groovy
// Accessible as:
Launchpad.elements  // ConcurrentHashMap<String, Class>
```

The key is the kebab-case element name (e.g., `"star-rating"`), and the value is the compiled Class object.

### Hot Reload

In debug mode, Spaceport watches the `launchpad/elements/` directory via a `Beacon` (file watcher). When any element file changes:

1. The template cache is cleared
2. The elements map is cleared
3. On the next request, elements are rediscovered and recompiled

---

## Built-in Template Variables Available in Elements

Elements have access to the following through the `Element` trait:

| Variable | Source | Description |
|---|---|---|
| `client` | Set by Launchpad | The `Client` session object |
| `dock` | Set by Launchpad | Session-scoped Cargo store |
| `launchpad` | Set by Launchpad | The parent Launchpad instance |

Elements do **not** have direct access to the template's `r` (HttpResult), `data`, `cookies`, or `context` variables. To pass request-specific data into an element, use attributes:

```html
<g:my-element route="${ r.context.target }"></g:my-element>
```

---

## Client-Side Element Reference

On the client, each element instance is registered as a global variable:

```javascript
window.element_<id>  // e.g., window.element_a1b2c3
```

This variable is also the DOM element itself (obtained via `document.querySelector('[element-id="<id>"]')`). It has:

- All `@Javascript` function properties as methods
- All `@Bind` proxy functions as methods
- A `_listeners` array tracking event listeners registered with `.listen()`
- A `.listen(type, handler, options)` method that auto-cleans up on element removal
- A `.constructed` function (if defined) called after DOM insertion
- A `.deconstructed` function (if defined) called on DOM removal

### The `.listen()` Helper

```javascript
element.listen('click', handler, options)
```

Works like `addEventListener` but automatically removes the listener when the element is cleaned up by HUD-Core. Only available on the root element itself, not child elements.

### Lifecycle Order

1. Element `<script>` tag executes
2. `element_<id>` global variable is set to the DOM element
3. `_listeners` array is initialized
4. `@Javascript` inline code executes
5. `@Javascript` function properties are attached
6. `@Bind` proxy functions are attached
7. `constructed(element)` is called (if defined)

On removal:

1. All listeners registered via `.listen()` are removed
2. `deconstructed(element)` is called (if defined)
3. `window.element_<id>` is deleted
