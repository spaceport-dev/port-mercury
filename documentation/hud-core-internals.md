# HUD-Core.js -- Internals

This document covers the implementation details of HUD-Core.js v1.1.3. It is intended for contributors, advanced developers debugging behavior, or anyone curious about how the client-side machinery works under the hood.

## Architecture Overview

HUD-Core is a single-file library with no dependencies. It runs entirely in the browser and organizes its functionality around five core systems:

1. **MutationObserver** -- Watches the DOM and processes new/removed elements
2. **Event binding** -- Converts `on-*` attributes into event listeners
3. **`fetchDataAndUpdate()`** -- The data collection, fetch, and response processing pipeline
4. **`documentData` Proxy** -- Reactive data binding via JavaScript Proxy
5. **HREF navigation** -- Click/keyboard handlers for non-anchor elements with `href`

These systems are initialized on `DOMContentLoaded` and operate for the lifetime of the page.

---

## Initialization Sequence

When `DOMContentLoaded` fires, HUD-Core performs the following steps in order:

```
1. scanForComments(document)       -- Parse CDATA comments for documentData
2. Define cleanupLaunchpadElement  -- Closure for Server Element teardown
3. Create MutationObserver         -- Start watching for DOM changes
4. Bind existing on-* elements     -- querySelectorAll for each built-in event
5. Dispatch on-load events         -- Synthetically fire 'load' on elements with on-load
6. Setup existing HREF elements    -- querySelectorAll('[href]')
7. observer.observe(document.body) -- Activate the observer
```

The observer configuration is:

```javascript
{ attributes: true, childList: true, subtree: true, attributeOldValue: true }
```

This means HUD-Core sees every node addition, removal, and attribute change anywhere in the document body.

---

## MutationObserver Pipeline

The MutationObserver is the heart of HUD-Core. It ensures that dynamically added content (from Transmissions, HREF navigation, or any other source) is fully functional.

### Processing Added Nodes

For each added node of type `ELEMENT_NODE` (nodeType 1):

1. **Mutated hook**: If the node has a `mutated` property (a function), it is called with the node as argument. This is used by Server Elements for initialization.

2. **Event binding**: The node is scanned against the `builtInEvents` array. For each matching `on-*` attribute, `setupOnAttribute()` is called.

3. **Script evaluation**: If the node is a `<script>` tag, its `innerHTML` is evaluated via `window.eval()`.

4. **HREF setup**: If the node has an `href` attribute, `setupHREF()` is called.

5. **Children processing**: All descendants are scanned via `querySelectorAll('*')`. Each child undergoes the same checks: mutated hook, script evaluation, event binding, HREF setup, and popover target handling.

6. **Comment scanning**: All comment nodes within the added element's subtree are found recursively and parsed for `documentData` CDATA.

For added nodes of type `COMMENT_NODE` (nodeType 8), the node is passed to `parseForDocumentData()`.

### Processing Removed Nodes

For each removed node of type `ELEMENT_NODE`:

1. **Server Element cleanup**: If the node has an `element-id` attribute, `cleanupLaunchpadElement()` is called. All nested elements with `element-id` are also cleaned up.

2. **Removed hook**: If the node has a `removed` property (a function), it is called.

3. **Server action unbinding**: All elements with `lp-uuid` attributes (server action endpoints) in the removed subtree have their UUIDs collected. A POST request is sent to `/!/lp/bind/u/` with the list of UUIDs, allowing the server to release the associated closures and free memory.

4. **Event listener cleanup**: If the node has an `eventListeners` array, all stored listeners are removed.

### Processing Attribute Changes

When an attribute changes on any observed element, HUD-Core checks if the element has an `attributeChanged` function property. If so, it is called with `(node, attributeName, oldValue, newValue)`.

---

## Event Binding Mechanism

### `setupOnAttribute(eventName, element)`

This function binds a standard DOM event listener to the element. The implementation is straightforward:

```javascript
function setupOnAttribute(eventName, element) {
    const endpoint = element.getAttribute('on-' + eventName)
    element.addEventListener(eventName, event => {
        fetchDataAndUpdate(event, endpoint)
        if (eventName === 'submit' && element.tagName === 'FORM') {
            event.preventDefault()
        }
        if (eventName === 'change' && element.tagName === 'INPUT') {
            element.addEventListener('keydown', event => {
                if (event.key === 'Enter') { event.currentTarget.blur() }
            })
        }
    })
    element.setAttribute('lp-uuid', endpoint.replace('/!/lp/bind?uuid=', ''))
}
```

Key details:

- The endpoint URL is extracted from the `on-*` attribute value. This URL points to a Spaceport server action, typically in the format `/!/lp/bind?uuid=<uuid>`.
- For `submit` events on forms, `preventDefault()` is called to suppress the native form submission.
- For `change` events on inputs, an additional `keydown` listener is added so pressing Enter blurs the input (triggering the change event for text inputs that normally require blur).
- The `lp-uuid` attribute is set on the element with the extracted UUID. This is used later for cleanup when elements are removed from the DOM.

### Initial Binding (Page Load)

On `DOMContentLoaded`, HUD-Core iterates the `builtInEvents` array and calls `querySelectorAll` for each event name. This handles elements present in the initial HTML. For `on-load` elements, a synthetic `load` event is dispatched immediately after binding.

---

## `fetchDataAndUpdate()` -- The Core Pipeline

This is the most complex function in HUD-Core. It orchestrates the full round-trip: collecting data, sending it to the server, and processing the response.

### Phase 1: Pre-Flight Checks

```
1. Check for fatal-error attribute on event.target -- abort if present
2. event.stopPropagation() -- prevent event bubbling
3. getTargetElement(event) -- resolve the payload target
```

### Phase 2: Active Target Resolution

The "active target" is the element whose data will be collected. This is determined by the `source` attribute:

```
source not set / "auto"  -->  event.target (the actually clicked element)
source = "strict"        -->  abort if event.target !== event.currentTarget
source = CSS selector    -->  walk up from event.target to find a match
source = "#id"           -->  document.querySelector(source)
```

The source resolution walks upward from `event.target` through parent elements, stopping at `event.currentTarget`. If a match is found, that becomes the active target. If the source starts with `#` and no match was found during the walk, it falls back to `document.querySelector`.

### Phase 3: Data Collection

Data is assembled into a `postData` object in this order:

1. **Element value** -- Determined by element type (checkbox, radio, file, select-multiple, or generic)
2. **Mouse event data** -- clientX/Y, pageX/Y, screenX/Y, offsetX/Y, movementX/Y, button/buttons
3. **Keyboard event data** -- key, keyCode, shiftKey, ctrlKey, altKey, metaKey, repeat (modifier keys only if true)
4. **Element metadata** -- contentEditable, bind, elementId, classList, tagName, innerText, textContent
5. **URL query parameters** -- parsed from `window.location.search`
6. **Form data** -- if active target is inside a `<form>`, all named inputs via `FormData`. Date inputs are converted to epoch milliseconds. Also scans for custom `[name]` elements not in FormData.
7. **Data attributes** -- `data-*` from `event.currentTarget`, then `data-*` from active target (overrides allowed unless source is `strict`)
8. **Included data** -- parsed from `include` attribute (localStorage, sessionStorage, element attributes)
9. **Endpoint query params** -- any query string on the endpoint URL itself is extracted and merged

### Phase 4: Fetch

```javascript
const fetchOptions = {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(postData)
}
```

Before the fetch, `loading="true"` is set on the payload target (or active target). After the fetch completes, `loading` is removed.

The response is read in two ways: as text (`response.text()`) and as JSON (`responseData.json()`). The JSON parse is wrapped in a try-catch; if it fails, `payload` remains `undefined`.

### Phase 5: Response Processing

The processing branches based on the response type:

```
Is the text "null" or undefined?  -->  Return (no update)
Does the text start with "<fatal"?  -->  Mark element with fatal-error
Is payload a valid JSON array?  -->  Process as Array Transmission
Is payload a valid JSON object?  -->  Process as Map Transmission
Otherwise  -->  Process as Single Value Transmission
```

**Array processing** iterates each entry. Strings starting with `@` are treated as actions. Strings starting with `+` add a class. Strings starting with `-` remove a class. Bare strings toggle the class.

**Map processing** iterates each key-value pair. The key prefix determines the operation:
- `@` prefix: action (with `this`/`it`/`source` targeting)
- `+` prefix: add class
- `-` prefix: remove class
- `&` prefix: inline style
- `*` prefix: data attribute
- `?` prefix: query parameter
- `~` prefix: localStorage
- `~~` prefix: sessionStorage
- `#` prefix: getElementById innerHTML replacement
- `>` prefix: descendant querySelector innerHTML replacement
- Standard keys: attribute operations on payload target
- Special keys: `value`, `innerHTML`, `outerHTML`, `innerText`, `append`, `prepend`, `insertBefore`, `insertAfter`

**Single value processing** checks if `target="outer"` to decide between `outerHTML` and `innerHTML`/`.value` replacement.

### File Upload Handling

File inputs are handled asynchronously. The `fileToBase64()` utility uses `FileReader.readAsDataURL()` to convert files to base64 data URLs:

```javascript
async function fileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(reader.result)
        reader.onerror = error => reject(error)
        reader.readAsDataURL(file)
    })
}
```

For file inputs within a form during submit events, each file is converted to base64 and the element gets a temporary `loading` attribute while processing.

---

## `getTargetElement()` Implementation

This function resolves the `target` attribute to an actual DOM element. The resolution strategy:

1. Read `target` from `event.currentTarget`
2. If no target found, walk up the DOM to find an ancestor with a `target` attribute
3. If `target` is `self` or `outer`, return the element itself
4. If `target` is `none`, return `null`
5. Switch on the target value to handle all named targets (`parent`, `grandparent`, `next`, `previous`, etc.)
6. For `append`/`prepend`/`after`/`before`, create a new element using `insertAdjacentHTML` and return it
7. For `nth-child`, `nth-sibling`, `nth-parent`, read the `index` attribute
8. For `>` prefix, use `querySelector` on the element
9. For `<` prefix, use `closest()` on the element
10. For anything else, use `document.querySelector`

The `wrapper` attribute (default: `div`) determines the tag type for insertion targets.

---

## `documentData` Proxy Implementation

### The Proxy Handler

`documentData` is a JavaScript `Proxy` wrapping a plain object. The handler intercepts `set` and `deleteProperty`:

```javascript
const documentDataHandler = {
    set: function(target, property, value) {
        target[property] = value
        refreshDocumentData()
        return true
    },
    deleteProperty: function(target, property) {
        delete target[property]
        refreshDocumentData()
        return true
    }
}
```

Every property assignment or deletion triggers `refreshDocumentData()`, which scans all elements with a `bind` attribute and updates their displayed value.

### Nested Reactivity

When setting nested objects, `parseForDocumentData()` creates new `Proxy` instances for nested objects using the same handler. The `deepMerge()` utility handles merging while preserving proxy wrapping.

### `setDocumentDataProperty(path, value)`

Supports dot-notation paths (e.g., `"user.profile.name"`). It traverses or creates intermediate proxy objects along the path before setting the final value:

```javascript
setDocumentDataProperty('user.profile.name', 'Alice')
// Creates documentData.user (Proxy) if needed
// Creates documentData.user.profile (Proxy) if needed
// Sets documentData.user.profile.name = 'Alice'
// Each step triggers refreshDocumentData()
```

### `refreshDocumentData()`

Scans the entire document for elements with `bind` attributes:

```javascript
document.querySelectorAll('[bind]').forEach(element => { ... })
```

For each element:
1. Parse the `bind` value (supports dot notation)
2. Traverse `documentData` to find the value
3. If the element has a custom `.setValue()` method, call it
4. Otherwise, for form elements (`INPUT`, `TEXTAREA`, `SELECT`, etc.), set `.value`
5. For all other elements, set `.innerHTML`
6. Dispatch a `change` event after updating (for consistency)
7. Values are formatted via `formatForDisplay()` to avoid showing `[object Object]`

### `formatForDisplay(value)`

Converts values to display-friendly strings:
- `null` / `undefined` returns `''`
- Strings, numbers, booleans returned as-is via `String()`
- Functions return `''`
- Arrays of primitives return comma-separated values; mixed arrays return `'[Array]'`
- Objects return `''`
- HTMLElements return `outerHTML`

### CDATA Parsing

Server-side documentData is transmitted via HTML comments containing CDATA:

```html
<!--<![CDATA[{"key": "value"}]]>-->
```

`parseForDocumentData()` strips the CDATA wrapper, parses the JSON, and assigns properties to the `documentData` proxy. If the parsed object contains `append: true`, properties are deep-merged instead of replaced.

---

## HREF Navigation

### `setupHREF(element)`

Only applies to non-`<a>` elements. Adds:

1. **Click handler**: Walks up from `event.target` to find the element with `href`, then navigates via `window.location.href`.
2. **Tabindex**: Adds `tabindex="0"` if not present, making the element keyboard-focusable.
3. **Keypress handler**: Navigates on Enter key press.

This simple implementation allows any element to function as a navigation link.

### History Management

HUD-Core registers a `popstate` listener that reloads the page on back/forward navigation:

```javascript
window.addEventListener('popstate', function(event) {
    window.location.reload()
})
```

This is a deliberate simplification. Rather than implementing client-side routing with partial updates, HUD-Core relies on full page reloads for history navigation, ensuring server-rendered state is always fresh.

Query parameter updates from `?` Transmission entries use `pushState` to modify the URL without reloading, which integrates with this popstate handler for back button support.

---

## WebSocket Connection

### `sendData(id, data)`

Sends a JSON message over a WebSocket with automatic retry:

```javascript
function sendData(id, data) {
    const payload = { "handler-id": id, ...data }

    function trySend() {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify(payload))
        } else {
            setTimeout(trySend, 25)
        }
    }
    trySend()
}
```

The `handler-id` field is used by Spaceport's server-side WebSocket routing to dispatch the message to the correct `@Alert('on socket <handler-id>')` handler.

The retry mechanism handles the case where the WebSocket is still connecting or temporarily disconnected. It polls every 25ms until the connection is open. Note that this is explicitly asynchronous -- message ordering is not guaranteed.

The `socket` variable is expected to be defined elsewhere (typically by Spaceport's WebSocket initialization, which is separate from HUD-Core).

---

## Form Blur Event

HUD-Core implements a custom `formblur` event that fires when focus leaves a form entirely, rather than just moving between inputs within the form.

### Implementation

Two global listeners track focus:

1. **`focusin`**: Records the currently focused form in the `focusedForm` variable.
2. **`focusout`**: After a `setTimeout(0)` (to let the new focus target settle), checks if `document.activeElement` is still inside the tracked form. If not, dispatches a `formblur` CustomEvent on the form.

```javascript
window.addEventListener('focusout', (event) => {
    setTimeout(() => {
        if (focusedForm && !focusedForm.contains(document.activeElement)) {
            const formBlurEvent = new CustomEvent('formblur', {
                bubbles: true, cancelable: true
            })
            focusedForm.dispatchEvent(formBlurEvent)
            focusedForm = null
        }
    }, 0)
})
```

The `setTimeout(0)` is critical. Without it, `document.activeElement` would still point to the old element during the `focusout` handler, before the browser has updated focus to the new element.

---

## Server Element Lifecycle

### Cleanup on Removal

When a Server Element (identified by `element-id` attribute) is removed from the DOM:

```javascript
function cleanupLaunchpadElement(element) {
    const elementId = element.getAttribute('element-id')
    const componentInstance = window[`element_${elementId}`]

    if (componentInstance) {
        // 1. Remove all listeners registered via .listen()
        componentInstance._listeners.forEach(listener => {
            element.removeEventListener(listener.type, listener.handler, listener.options)
        })
        componentInstance._listeners.length = 0

        // 2. Call deconstructed() hook if defined
        if (componentInstance.deconstructed) {
            componentInstance.deconstructed(componentInstance)
        }

        // 3. Delete the global reference
        delete window[`element_${elementId}`]
    }
}
```

Server Elements register themselves on `window` as `window.element_<id>`. The `_listeners` array stores references to all event listeners added via the `.listen()` helper method (provided by the Server Element framework), enabling clean removal.

### Popover Support

When new elements are added via mutation, HUD-Core checks for `popovertarget` attributes and calls `togglePopover()` on the referenced element. This provides automatic popover activation for dynamically inserted content.

---

## Performance Considerations

### Event Binding

Each `on-*` attribute results in a single `addEventListener` call. There is no event delegation at the HUD-Core level -- each element gets its own listener. For large lists, use the `source` attribute on a parent container for event delegation at the Launchpad level.

### MutationObserver Overhead

The observer watches the entire `document.body` with `subtree: true`. Every DOM mutation triggers the observer callback. For each added element, HUD-Core scans all children via `querySelectorAll('*')`. In pages with frequent, large DOM updates, this could become a performance concern.

Some events are intentionally disabled in the `builtInEvents` array (notably `on-scroll` and `on-resize`) likely to avoid performance issues from high-frequency event firing.

### DocumentData Refresh

`refreshDocumentData()` performs a `document.querySelectorAll('[bind]')` on every property change. For pages with many bound elements or frequent data updates, this full-document scan can be costly. The function does check whether values have actually changed before updating the DOM, avoiding unnecessary reflows.

### Server Action Cleanup

When elements with `lp-uuid` attributes are removed, HUD-Core batches their UUIDs and sends a single POST to the server. This "courtesy unloading" helps the server release memory associated with the server action closures.
