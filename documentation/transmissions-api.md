# Transmissions -- API Reference

Complete reference for Spaceport's Transmission system: server action syntax, reactive binding syntax, Transmission response formats, target resolution, the `t` data object, and all supported directives.

## Server Action Syntax: `_{ }`

Server actions are Groovy closures that execute on the server when a DOM event fires. They are embedded in `on-*` attributes using the `${ _{ } }` syntax.

### No-Parameter Form

The closure receives no client data. Used for fire-and-forget actions or when the server already has everything it needs:

```html
<button on-click=${ _{ ['@print'] }}>Print</button>
```

### Single-Parameter Form (Action Binding)

The closure receives a `t` object containing all data sent from the client:

```html
<button on-click=${ _{ t -> processClick(t) }}>Go</button>
```

The `t` parameter name is conventional but arbitrary -- you can name it anything.

### Closures Defined in Scriptlets

For complex logic, define closures in a `<% %>` block and reference them inline:

```groovy
<%
    def handleSubmit = { t ->
        def name = t.getString('name')
        // ... business logic ...
        return ['#result': "Hello, ${name}!"]
    }
%>

<form on-submit=${ _{ t -> handleSubmit(t) }}>
    <input name="name">
    <button type="submit">Submit</button>
    <div id="result"></div>
</form>
```

### Inline Closures with Captured Variables

Server actions can capture variables from the template's scope. This is especially useful inside loops:

```groovy
<% for (item in items) { %>
    <button on-click=${ _{ deleteItem(item.id); ['@remove'] }} target="parent">
        Delete ${ item.name }
    </button>
<% } %>
```

The closure captures the current `item` from the loop iteration.

---

## Reactive Binding Syntax: `${{ }}`

Double-brace expressions create reactive subscriptions. They are re-evaluated on the server whenever a referenced variable changes, and the new value is pushed to the client via WebSocket.

```html
<span>Count: ${{ counter }}</span>
<p>Status: ${{ cargo.get('status') }}</p>
```

### How Dependencies Are Tracked

The expression inside `${{ }}` is executed with a `Catch` delegate that intercepts all property accesses. Any variable or Cargo field accessed during evaluation becomes a tracked dependency. When a server action later modifies one of those dependencies, the expression is re-evaluated and the result is sent to the client.

### Rendering Complex Content

Reactive bindings can return full HTML. The `.combine` method on lists is commonly used:

```html
${{ participants.combine { p -> """
    <div class="entry">
        <strong>${ p.name }</strong>
        <span>${ p.time_signed.relativeTime() }</span>
    </div>
""" } }}
```

### Conditional Rendering

Use the `.if` class enhancement for conditional output within reactive bindings:

```html
${{ "<div class='alert'>Guestbook is closed.</div>".if { !gb.isOpen() } }}
```

---

## Available Server Events

Attach server actions to any of these events using the `on-` prefix (note the dash, which distinguishes them from standard inline event handlers):

### Mouse Events

`on-click`, `on-dblclick`, `on-mousedown`, `on-mouseup`, `on-mouseover`, `on-mouseout`, `on-mouseenter`, `on-mouseleave`, `on-mousemove`, `on-wheel`, `on-contextmenu`

### Keyboard Events

`on-keydown`, `on-keyup`, `on-keypress`

### Input and Form Events

`on-submit`, `on-change`, `on-input`, `on-select`, `on-focus`, `on-blur`, `on-focusin`, `on-focusout`, `on-formblur`

### Drag and Drop Events

`on-dragenter`, `on-dragleave`, `on-drop`

### Touch Events

`on-touchstart`, `on-touchmove`, `on-touchend`, `on-touchcancel`

### Lifecycle Events

`on-load`, `on-beforeunload`, `on-nudge`

> The `on-nudge` event is a Spaceport custom event. It can be triggered programmatically from a Transmission using the `@nudge` action.

---

## The `t` Data Object

When a server action includes a parameter (`_{ t -> ... }`), the `t` object contains all contextual data collected from the client by HUD-Core.

### Automatic Data Collection

| Category | Properties | Description |
|:---|:---|:---|
| **Element Value** | `value` | Intelligently determined: input text, checkbox state, file content (Base64), or trimmed innerHTML |
| **Element Info** | `elementId`, `tagName`, `classList`, `innerText`, `textContent` | Properties of the active target element |
| **Keyboard Events** | `key`, `keyCode`, `shiftKey`, `ctrlKey`, `altKey`, `metaKey`, `repeat` | Present for keyboard events. Boolean keys only appear when `true` |
| **Mouse Events** | `clientX`, `clientY`, `pageX`, `pageY`, `button`, `buttons`, `offsetX`, `offsetY`, `movementX`, `movementY` | Present for mouse events |
| **Form Data** | `[input-name]` | All named inputs from the enclosing `<form>`, keyed by `name` attribute |
| **Custom Data** | `[data-attribute]` | All `data-*` attributes as camelCase keys (e.g., `data-user-id` becomes `t.userId`) |
| **URL Data** | `[query-param]` | All query parameters from the current page URL |
| **Included Data** | `[key]` | Values from `localStorage` (`*key`), `sessionStorage` (`~~key`), or element attributes specified via the `include` attribute |

### Type Conversion Helpers

Data arrives from the client as strings. These helper methods safely convert values:

| Method | Returns | Behavior |
|:---|:---|:---|
| `t.getString('key')` | `String` | Converts value to String |
| `t.getBool('key')` | `boolean` | Handles `"true"`, `"on"`, `"yes"`, checkbox states. Returns `false` otherwise |
| `t.getNumber('key')` | `Long` or `Double` | Parses numeric strings. Returns `Long` for integers, `Double` for decimals. Returns `0L` on failure |
| `t.getInteger('key')` | `Integer` | Parses to Integer. Returns `0` on failure |
| `t.getList('key')` | `List` | Parses comma-separated strings, JSON arrays, or wraps a single item |

### Direct Property Access

You can also access `t` properties directly as dynamic fields:

```groovy
def name = t.name       // Direct access (returns String)
def name = t.getString('name')  // Same, but explicit
```

---

## The `source` Attribute

Controls **which element's data** is sent to the server. Important for event delegation patterns.

| Value | Behavior |
|:---|:---|
| *(not set)* | Data comes from `event.target` (the actual element interacted with) |
| **CSS selector** | Data comes from the closest ancestor of `event.target` matching the selector. Used for delegation (e.g., `source="li"` on a `<ul>`) |
| **`strict`** | Event only fires if `event.target === event.currentTarget`. Clicks on child elements are ignored |
| **`auto`** | Explicitly sets default behavior (data from `event.target`). Rarely needed |

**Example -- Event Delegation:**

```html
<ul on-click=${ _{ t -> handleItemClick(t) }} source="li" target="self">
    <li data-id="1">Item 1</li>
    <li data-id="2">Item 2</li>
    <li data-id="3">Item 3</li>
</ul>
```

Clicking any `<li>` sends that specific `<li>`'s data attributes and content to the server.

---

## The `target` Attribute

Determines **which element receives the Transmission update**. HUD-Core resolves the target by first checking the element with the `on-*` attribute, then walking up the DOM tree to find an ancestor with a `target` attribute.

### Positional Targets

| Value | Target Element |
|:---|:---|
| `self` | The element with the `on-*` event |
| `none` | No element (fire-and-forget) |
| `parent` | Immediate parent element |
| `grandparent` | Parent of the parent element |
| `next` / `previous` | Next or previous sibling element |
| `nextnext` / `previousprevious` | Second sibling in the given direction |
| `first` / `last` | First or last child of the current element |

### Insertion Targets

These targets create a new element in the DOM, then apply the Transmission to it:

| Value | Behavior | Additional Attributes |
|:---|:---|:---|
| `append` | Inserts new element as the last child | `wrapper` (tag name, default: `div`) |
| `prepend` | Inserts new element as the first child | `wrapper` (tag name, default: `div`) |
| `after` | Inserts new element after the current element | `wrapper` (tag name, default: `div`) |
| `before` | Inserts new element before the current element | `wrapper` (tag name, default: `div`) |

### Index-Based Targets

| Value | Behavior | Additional Attributes |
|:---|:---|:---|
| `nth-child` | Targets a child by 0-based index | `index="n"` |
| `nth-sibling` | Targets a sibling by 0-based index | `index="n"` |

### Selector Targets

| Value | Behavior |
|:---|:---|
| `> selector` | CSS selector matched against descendants of the current element |
| `< selector` | CSS selector matched against ancestors of the current element |
| Any other string | Global CSS selector against the document (e.g., `#my-id`, `.my-class`) |

### The `outer` Target

The `outer` value is a special modifier. It behaves like `self`, but changes how Single Value Transmissions are applied:

- **Standard targets** (`self`, `#id`, etc.): replace the target's `innerHTML`
- **`outer`**: replace the **entire element** via `outerHTML`

This is essential for the edit-in-place pattern where you swap out an entire component.

---

## Transmission Response Formats

### Single Value Transmission

Returned when the server action produces a string, number, or other non-JSON value.

**Behavior:**
- If the target has a `.value` property (inputs, textareas): sets `.value`
- Otherwise: sets `.innerHTML`
- With `target="outer"`: sets `.outerHTML` (replaces the entire element)

```groovy
// Returns a string -- replaces target innerHTML
return "Last saved: ${new Date().format('h:mm:ss a')}"
```

### Map Transmission

Returned when the server action produces a Groovy Map. Each key-value pair is a separate instruction.

#### Content and Attribute Operations

| Key / Prefix | Operation | Example |
|:---|:---|:---|
| `innerHTML` | Replace inner HTML | `['innerHTML': '<strong>Done</strong>']` |
| `outerHTML` | Replace entire element | `['outerHTML': '<div>New</div>']` |
| `innerText` | Set text content | `['innerText': 'Updated']` |
| `value` | Set `.value` property | `['value': 'new text']` |
| `append` | Insert HTML as last child | `['append': '<li>New</li>']` |
| `prepend` | Insert HTML as first child | `['prepend': '<li>First</li>']` |
| `insertAfter` | Insert HTML after element | `['insertAfter': '<hr>']` |
| `insertBefore` | Insert HTML before element | `['insertBefore': '<h2>Title</h2>']` |
| `*` prefix | Set `data-*` attribute | `['*user-id': 123]` sets `data-user-id` |
| *(other)* | Set HTML attribute | `['disabled': true, 'title': 'Wait...']` |
| *(null value)* | Remove HTML attribute | `['disabled': null]` removes the attribute |

#### CSS Class Operations

| Key Prefix | Operation | Value Targets |
|:---|:---|:---|
| `+className` | Add CSS class | `null` (payloadTarget), `'this'`, `'it'`, `'source'` |
| `-className` | Remove CSS class | `null` (payloadTarget), `'this'`, `'it'`, `'source'` |

```groovy
return ['+active': 'it', '-loading': null]
```

#### Style Operations

| Key Prefix | Operation | Example |
|:---|:---|:---|
| `&` | Set inline CSS property | `['&backgroundColor': 'yellow', '&fontWeight': 'bold']` |

#### Element and Form Actions

All action keys use the `@` prefix. The value determines which element the action targets:

- `null` or omitted: applies to the `payloadTarget` (determined by `target` attribute)
- `'this'`: applies to `event.target` (the exact element interacted with)
- `'it'`: applies to `event.currentTarget` (the element with the `on-*` listener)
- `'source'`: applies to the `activeTarget` (determined by the `source` attribute)

| Action Key | Description | Value Types |
|:---|:---|:---|
| `@click` | Trigger a click event | `null`, `'this'`, `'it'`, `'source'` |
| `@focus` | Set focus | `null`, `'this'`, `'it'`, `'source'` |
| `@blur` | Remove focus | `null`, `'this'`, `'it'`, `'source'` |
| `@select` | Select text content | `null`, `'this'`, `'it'`, `'source'` |
| `@end` | Move cursor to end of input | `null`, `'this'`, `'it'`, `'source'` |
| `@submit` | Submit a form | `null`, `'this'`, `'it'`, `'source'` |
| `@reset` | Reset a form | `null`, `'this'`, `'it'`, `'source'` |
| `@show` | Show element (restore display) | `null`, `'this'`, `'it'`, `'source'` |
| `@hide` | Hide element (`display: none`) | `null`, `'this'`, `'it'`, `'source'` |
| `@open` | Open `<details>`, `<dialog>`, or window | URL string, `null`, `'this'`, `'it'`, `'source'` |
| `@close` | Close `<details>`, `<dialog>`, or window | `'window'`, `null`, `'this'`, `'it'`, `'source'` |
| `@remove` | Remove element from DOM | `null`, `'this'`, `'it'`, `'source'` |
| `@clear` | Clear `.value` or `.innerHTML` | `null`, `'this'`, `'it'`, `'source'` |
| `@nudge` | Dispatch custom `nudge` event | `null`, `'this'`, `'it'`, `'source'` |
| `@download` | Trigger file download | URL string |
| `@alert` | Show browser alert dialog | Message string |
| `@log` | Log to browser console | Any value |
| `@table` | Log as table in console | Object or array |

#### Browser and Storage Control

| Key / Prefix | Operation | Example |
|:---|:---|:---|
| `@redirect` | Navigate to URL | `['@redirect': '/dashboard']` |
| `@reload` | Reload current page | `['@reload': null]` |
| `@back` | Browser history back | `['@back': null]` |
| `@forward` | Browser history forward | `['@forward': null]` |
| `@replace` | Replace URL without navigation | `['@replace': '/new-path']` |
| `@print` | Open print dialog | `['@print': null]` |
| `?key` | Set URL query parameter (no reload) | `['?page': 2, '?sort': 'asc']` |
| `~key` | Set localStorage value | `['~theme': 'dark']` |
| `~~key` | Set sessionStorage value | `['~~token': 'xyz']` |

#### Scroll Operations

| Action Key | Description |
|:---|:---|
| `@scroll-to` | Scroll to position within target |
| `@scroll-by` | Scroll by offset within target |
| `@scroll-into-view` | Scroll target into viewport |

#### Selector-Based Map Entries

Map keys that look like CSS selectors perform innerHTML replacement on the matched element, independent of the `target` attribute:

| Key Format | Behavior |
|:---|:---|
| `#id` | Updates element with matching ID |
| `> selector` | Finds descendant of the active target |
| `< selector` | Finds closest ancestor of the active target |
| Any other selector | Global `querySelector` on the document |

```groovy
return [
    'disabled': true,           // Attribute on payloadTarget
    '+loading': 'it',           // Class on event.currentTarget
    '#order-status': 'Saving…'  // innerHTML on #order-status
]
```

### Array Transmission

Returned when the server action produces a Groovy List. Each entry is applied to the `payloadTarget` in sequence.

#### Class Operations

| Prefix | Operation | Example |
|:---|:---|:---|
| *(none)* | Toggle class | `['active', 'selected']` |
| `+` | Add class | `['+visible', '+loaded']` |
| `-` | Remove class | `['-hidden', '-loading']` |

#### Chained Actions

Actions from the `@` prefix set (same as Map Transmission actions, but parameter-less):

`@click`, `@focus`, `@blur`, `@select`, `@submit`, `@reset`, `@remove`, `@show`, `@hide`, `@scroll-to`, `@clear`, `@reload`, `@back`, `@forward`, `@print`, `@nudge`

```groovy
return ['-processing', '+completed', '@clear', 'visible']
```

---

## The `include` Attribute

The `include` attribute on an element specifies additional data to send to the server beyond the automatic collection:

```html
<button include="id, *theme, ~~session-token" on-click=${ _{ t -> ... }}>
```

| Prefix | Source |
|:---|:---|
| `*key` | `localStorage.getItem('key')` |
| `~~key` | `sessionStorage.getItem('key')` |
| `key` | Element attribute, or falls back to localStorage, then sessionStorage |
| `all-attributes` | All attributes on the element |

---

## Loading State

While a Transmission is in-flight, HUD-Core automatically sets a `loading` attribute on the payload target (or active target if no payload target exists). This attribute is removed when the response arrives. Use it for CSS-based loading indicators:

```css
[loading] {
    opacity: 0.5;
    pointer-events: none;
}
```

---

## What's Next

- **[Transmissions Overview](transmissions-overview.md)** -- high-level introduction to the Transmission system
- **[Transmissions Internals](transmissions-internals.md)** -- how bindings, the Catch proxy, and WebSocket reactions work
- **[Transmissions Examples](transmissions-examples.md)** -- real-world patterns from production applications
