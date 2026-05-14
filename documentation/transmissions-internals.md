# Transmissions -- Internals

This document explains how Spaceport's Transmission system works under the hood: how template syntax is preprocessed, how the server tracks dependencies, how bindings are stored and served, how WebSocket reactions propagate changes, and how HUD-Core processes responses on the client side.

Target audience: contributors, advanced users debugging reactive behavior, or anyone extending the framework.

---

## Architecture Overview

The Transmission system spans three layers:

1. **Template preprocessing** (compile time) -- `${{ }}` and `_{ }` syntax is transformed into `bind()` calls
2. **Server-side binding management** (runtime) -- `Launchpad.bind()` creates bindings/reactions, stored in `bindingSatellites`
3. **Client-side processing** (runtime) -- HUD-Core handles HTTP responses and WebSocket messages

The two user-facing syntaxes map to distinct data flows:

```
SERVER ACTIONS (_{ })                       REACTIVE BINDINGS (${{ }})
─────────────────────                       ──────────────────────────
User event fires                            Cargo mutation / variable change
    |                                            |
    v                                            v
HUD-Core collects data                      Cargo.synchronize() queues _update()
POSTs to /!/lp/bind?launch=...&uuid=...         |
    |                                            v
    v                                       Object._update() iterates
Launchpad.serveBinding()                    all bindingSatellites
    |  Catch proxy tracks variables         Matches reactions by trigger name
    |  Checks reactions for intersections        |
    v                                            v
Returns HTTP response                       Re-evaluates reaction closure
    |                                       Compares against _current payload
    v                                            |  (if changed)
HUD-Core applies Transmission                   v
to payloadTarget element                    Sends JSON over WebSocket:
                                            { action: "applyReaction", uuid, payload }
                                                 |
                                                 v
                                            Client socket.onmessage handler
                                            Finds comment markers, replaces DOM content
```

---

## Template Preprocessing

### The `${{ }}` Transformation

During template compilation, Launchpad transforms reactive double-brace expressions into server action calls. Inside the `prime()` method, a regex replacement converts the syntax:

```groovy
text = text.replaceAll(/\$\{\{\s*/, '\\${ _{ _script, _registration -> ')
```

This means that when you write:

```html
<span>Count: ${{ counter }}</span>
```

It becomes:

```html
<span>Count: ${ _{ _script, _registration -> counter }}</span>
```

The `_` reference is actually `this.&bind` -- a method reference to `Launchpad.bind()`. The two parameters (`_script`, `_registration`) signal to `bind()` that this is a reactive expression (2-parameter closure), not a server action (1-parameter closure).

### The `_{ }` Binding

Server action closures (`_{ code }`) are also calls to `bind()`, but with a 0 or 1-parameter closure. The `_` variable is set up during the launch sequence:

```groovy
binding.putAll([
    // ...
    _            : this.&bind,            // Server action / reactive binding handler
    __           : this.&parseElements,   // Used internally for element parsing
    '_reactions' : new ConcurrentHashMap(),
    '_bindings'  : new ConcurrentHashMap(),
    '_launchID'  : '',
    '_scripts'   : [:]
])
```

---

## The `bind()` Method

`Launchpad.bind(Closure closure)` is the core of the Transmission system. It examines the closure's parameter count to determine whether it is a **server action** (0 or 1 parameter) or a **reaction** (2 parameters).

### Script Discovery

Before processing, `bind()` walks up the closure's owner chain to find the enclosing Script (a `.ghtml` template) or Element. This is necessary to store the binding/reaction in the correct scope and to determine whether a WebSocket connection is needed:

```groovy
def closestScript = closure.owner
while (closestScript) {
    if (closestScript instanceof Script) {
        if (!(closestScript._socket instanceof SocketContext))
            closestScript._socket = true   // Flag: this launch needs a socket
        break
    } else if (closestScript instanceof Element) {
        uuid = 'element-' + uuid
        if (!(closestScript.launchpad.binding._socket instanceof SocketContext))
            closestScript.launchpad.binding._socket = true
        break
    } else {
        closestScript = closestScript.owner
    }
}
```

Setting `_socket = true` tells `launch()` to inject the WebSocket setup script into the page.

### Reactions (2-Parameter Closures from `${{ }}`)

When `bind()` receives a closure with 2 parameters:

1. **Generates a unique UUID** (stripped of dashes).
2. **Creates a `Catch` delegate** proxy wrapping the closure's owner scope.
3. **Executes the closure once** with the Catch delegate to capture the initial return value and record which variables were accessed (dependency tracking).
4. **Stores the reaction** in `_reactions`:

```groovy
closestScript._reactions.put(uuid, [
    'created'   : System.currentTimeMillis(),
    'reaction'  : closure,       // The re-evaluable closure
    '_source'   : sourceText,    // Debug: template source line
    '_current'  : returnString,  // Current rendered value (for change detection)
    '_triggers' : delegate.caught // Set of variable names this depends on
])
```

5. **Returns the initial value wrapped in HTML comment markers**:

```html
<!-- START_CHUNK@a1b2c3d4e5f6 -->
<div class='entry'><strong>Alice</strong></div>
<!-- END_CHUNK@a1b2c3d4e5f6 -->
```

These comment markers persist in the DOM for the lifetime of the page. They serve as stable anchors that the client-side WebSocket handler uses to locate and replace the reactive region.

### Actions (0/1-Parameter Closures from `_{ }`)

When `bind()` receives a closure with 0 or 1 parameters:

1. **Generates a unique UUID**.
2. **Stores the closure** in `_bindings`:

```groovy
closestScript._bindings.put(uuid, [
    'created': System.currentTimeMillis(),
    'action' : closure,
    'script' : closestScript,
    '_source': sourceText   // Debug: the template source line
])
```

3. **Returns a URL string** that HUD-Core will POST to when the event fires:

```
/!/lp/bind?launch=937bea3e89a24a0398a6d7d8341de3dc&uuid=ba081fbef40543d4a17c58f89d5fa961
```

The `launch` parameter identifies the Launchpad session (the "binding satellite"), and `uuid` identifies the specific closure.

---

## The Catch Proxy: Dependency Tracking

The `Catch` class (`spaceport/launchpad/Catch.groovy`) is a Groovy delegate proxy that intercepts all property access and records which variable names are read or written. It is the mechanism that powers reactive dependency tracking.

### Implementation

```groovy
class Catch {
    Object through      // The real object (the template's Binding/Script)
    def caught = new HashSet()

    def get(String name) {
        caught << name
        return through."$name"
    }

    def getProperty(String name) {
        if (name == 'caught') return caught
        caught << name
        return through."$name"
    }

    void setProperty(String name, value) {
        caught << name
        through."$name" = value
    }

    def invokeMethod(String name, args) {
        caught << name
        // If the target is a Closure, clone and re-delegate to keep tracking
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

- Every `getProperty` call adds the property name to the `caught` set.
- Every `setProperty` call also records the name, so the system knows the closure modified it.
- Method invocations on template-scoped closures are re-delegated through `Catch`, so nested variable access within those closures is also tracked.
- The `caught` property itself is exempted from tracking (to avoid infinite recursion).

### Catch in Server Actions

When `serveBinding()` executes a server action, it wraps the closure's delegate with `Catch`:

```groovy
def delegate = new Catch(binding.script)
action.resolveStrategy = Closure.DELEGATE_ONLY
action.delegate = delegate

def call = action(r.context.data)  // Execute the closure

// delegate.caught now contains all variable names touched
```

After execution, `delegate.caught` is compared against the `_triggers` sets of all registered reactions. If there is an intersection, those reactions are re-evaluated and their new values are pushed to the client via WebSocket.

### Catch in Reactive Bindings

When a `${{ }}` expression is first evaluated during template rendering, `Catch` discovers which variables the expression depends on:

```groovy
def delegate = new Catch(closure.owner)
closure.resolveStrategy = Closure.DELEGATE_ONLY
closure.delegate = delegate

def returnString = closure(closure.owner, uuid)  // Initial evaluation
// delegate.caught now contains the dependency set, e.g., {'gb', 'participants'}
```

The `caught` set becomes the reaction's `_triggers`, determining when the reaction should fire in the future.

---

## Binding Satellites

`Launchpad.bindingSatellites` is a static `ConcurrentHashMap` that stores all active Launchpad sessions with reactive content. The key is the `launchID` (a UUID generated per Launchpad launch), and the value is the Binding object containing:

| Field | Type | Purpose |
|:---|:---|:---|
| `_bindings` | `ConcurrentHashMap` | UUID-to-closure map for server actions |
| `_reactions` | `ConcurrentHashMap` | UUID-to-reaction map for reactive expressions |
| `_socket` | `SocketContext`, `Boolean`, or `String` | WebSocket connection state |
| `_elements` | `List` | Server Element instances in this launch |
| `_launchID` | `String` | The launch identifier |
| `client` | `Client` | The authenticated client for security checks |
| `dock` | `Cargo` | Session-scoped Cargo |
| Template variables | various | All `def` variables from the template |

### Lifecycle

| Stage | What Happens |
|:---|:---|
| **Template rendered** | `launch()` creates the Binding, populates it with closures, variables, and metadata. If reactivity is detected (`binding._socket` is truthy), it stores the binding in `bindingSatellites.put(launchID, binding)`. |
| **Socket connected** | HUD-Core opens a WebSocket to `/!/lp/socket` and sends a `register` message with the `launchID`. The server associates the `SocketContext` with the binding satellite, replacing the boolean `true` flag. |
| **Active** | Server actions read/write the binding. Reactions fire and push updates. Ping/pong heartbeat keeps the socket alive. |
| **Socket closed** | The heartbeat timer detects the closed session, removes the satellite from `bindingSatellites`, and cancels the timer. |
| **Unbinding** | When HUD-Core detects removed DOM elements with `lp-uuid` attributes, it sends stale UUIDs to `/!/lp/bind/u/` for cleanup. |

---

## Serving Actions: `serveBinding`

When HUD-Core fires a server action, it POSTs to `/!/lp/bind?launch={id}&uuid={id}`. The `serveBinding` method (annotated with `@Alert('on /!/lp/bind hit')`) handles the request:

### Step 1: Lookup

Retrieves the binding closure from the satellite using the launch ID and UUID. If not found at the template level, checks Server Element bindings:

```groovy
def binding = bindingSatellites.get(launch)?._bindings?.get(uuid)
if (binding == null) {
    for (def e in bindingSatellites.get(launch)?._elements) {
        if (e.element._bindings.containsKey(uuid)) {
            binding = e.element._bindings.get(uuid)
            element = e.element
            break
        }
    }
}
```

### Step 2: Security Check

Verifies that the requesting client matches the client that created the binding. Returns HTTP 405 if mismatched:

```groovy
if (bindingSatellites.get(launch)?.client != r.client) {
    r.setStatus(HttpStatus.METHOD_NOT_ALLOWED_405)
}
```

### Step 3: Catch Proxy Setup

Creates a new `Catch` delegate for the action closure to track variable access:

```groovy
def delegate = new Catch(binding.script)
action.resolveStrategy = Closure.DELEGATE_ONLY
action.delegate = delegate
```

### Step 4: Data Enrichment

The `t` object (`r.context.data`) is enriched with type-conversion helpers:

```groovy
r.context.data.tap {
    it.'getNumber'  = { v -> /* parse to Long or Double */ }
    it.'getBool'    = { v -> /* coerce to boolean */ }
    it.'getString'  = { v -> /* convert to String */ }
    it.'getList'    = { v -> /* parse to List */ }
    it.'getInteger' = { v -> /* parse to Integer */ }
}
```

### Step 5: Action Execution

The closure is called with the enriched data:

```groovy
def call = action(r.context.data)
```

### Step 6: Reaction Propagation

After the action executes, the `Catch` proxy's `caught` set contains all variables that were accessed or modified. The server iterates all reactions for this launch:

```groovy
for (def reaction in bindingSatellites.get(launch)?._reactions) {
    // Skip reactions whose triggers don't intersect with caught variables
    if ((reaction.value._triggers as List).intersect(delegate.caught).size() == 0) continue
    // Skip Cargo objects -- they handle their own reactivity
    if (binding.script."$reaction.key" instanceof Cargo) continue

    def payload = reaction.value.reaction(action.owner, reaction.key).toString()
    payload = bindingSatellites.get(launch).__(payload) // Process Server Elements

    // Only push if the payload actually changed
    if (reaction.value._current != payload) {
        bindingSatellites.get(launch)._socket.session?.remote?.sendStringByFuture(
            new JsonBuilder([
                'action' : 'applyReaction',
                'uuid'   : reaction.key,
                'payload': payload
            ]).toString()
        )
        reaction.value._current = payload
    }
}
```

The same process repeats for Element-level reactions if the binding originated from a Server Element.

### Step 7: HTTP Response

The action's return value is serialized and written to the HTTP response:

- Map or List: converted to JSON via `JsonBuilder`
- String or GString: processed through `parseElements()` for Server Element rendering, then returned as-is
- Other types: converted to String via `toString()`

```groovy
if (call instanceof Map || call instanceof List) {
    call = new JsonBuilder(call).toString()
} else if (call instanceof String || call instanceof GString) {
    call = bindingSatellites.get(launch).__(call) // Server Elements
} else {
    call = call.toString()
}
r.writeToClient(call)
```

### The Cargo Exception

When a caught variable is a `Cargo` instance, reactions triggered by that variable name are skipped in `serveBinding`. This is because Cargo has its own synchronization mechanism that handles reactive updates independently (see below). Without this check, Cargo-dependent reactions would fire twice: once from `serveBinding` and once from Cargo's `_update()`.

---

## Cargo-Driven Reactivity

Cargo objects manage their own reactive updates independently from the `Catch`-based system used by plain template variables.

### The Synchronize Chain

Every mutating Cargo operation (`set`, `delete`, `inc`, `dec`, `toggle`, `push`, `pull`, `clear`, etc.) calls `synchronize()`:

1. **Parent propagation**: If the Cargo is a nested child, delegates to `_parent.synchronize()` to bubble up to the root.
2. **Document mirror**: If the root Cargo is backed by a Document, queues a document save with a 3ms debounce.
3. **Satellite update**: Queues a call to `_update()` with a 5ms debounce.

```groovy
private synchronized void synchronize() {
    if (_parent == null) {
        // Save to document (if mirrored) with 3ms debounce
        if (_documentPath != null && !_documentSynchronizeQueue) {
            _documentSynchronizeQueue = Thread.start {
                Thread.sleep(3)
                document.save()
                _documentSynchronizeQueue = null
            }
        }
    } else {
        _parent.synchronize()
    }

    // Broadcast to satellites with 5ms debounce
    if (!_satelliteSynchronizeQueue) {
        _satelliteSynchronizeQueue = Thread.start {
            Thread.sleep(5)
            try { this._update() }
            finally { _satelliteSynchronizeQueue = null }
        }
    }
}
```

The debouncing is critical for performance: when a server action modifies a Cargo object multiple times in rapid succession (e.g., inside a loop), only one WebSocket push and one document save happen for the entire batch.

### The `_update()` Method

The `_update()` method is a MetaClass enhancement added to `Object` at Spaceport's initialization (in `MetaClassEnhancements.groovy`). This means it is available on any object -- Cargo instances, Documents, Lists, Maps, or custom types.

When called, `_update()` scans all active binding satellites globally and pushes updates to any client whose reactions reference the updated object:

```groovy
Object.metaClass._update = { forcedUpdate = false ->
    for (def satellite in Launchpad.bindingSatellites.values()) {
        // Skip satellites with no reactions or no open socket
        if (!satellite._reactions && !satellite._elements) continue
        if (satellite._socket == null ||
            satellite._socket instanceof String ||
            satellite._socket instanceof Boolean ||
            !satellite._socket.session?.open) continue

        // Check template-level variables
        for (def node in satellite) {
            // Skip internal keys
            if (internalKeys.contains(node.key)) continue
            // Identity check: is this the object that was updated?
            if (node.value !== delegate) continue

            def nodeName = node.key.startsWith('$') ? node.key.substring(1) : node.key

            for (def reaction in satellite._reactions) {
                if (!(reaction.value._triggers as List).contains(nodeName)) continue

                def payload = reaction.value.reaction(satellite, reaction.key).toString()
                payload = satellite.__(payload) // Process Server Elements

                // Change detection (skip if identical, unless forced)
                if (!forcedUpdate && reaction.value._current == payload) continue

                satellite._socket?.session?.remote?.sendStringByFuture(
                    new JsonBuilder([
                        'action' : 'applyReaction',
                        'uuid'   : reaction.key,
                        'payload': payload
                    ]).toString()
                )
                reaction.value._current = payload
            }
        }

        // Also check Server Element properties
        for (def e in satellite._elements) {
            for (def property in e.element.properties) {
                if (property.value == delegate) {
                    // Re-evaluate element reactions similarly
                }
            }
        }
    }
}
```

Key details:

- Uses **identity comparison** (`!==` / `==`), not equality, to match the updated object against satellite variables. This means two different Cargo objects with identical data will not trigger each other's reactions.
- Scans **all** binding satellites, meaning a single `_update()` call can push updates to every connected client simultaneously. This is how multi-user real-time updates work.
- A companion method `_forceUpdate()` bypasses the change-detection check, sending the payload even if it is identical to the current value.
- Internal binding keys (`_reactions`, `_bindings`, `r`, `data`, `context`, `client`, `_`, `_socket`, `_scripts`, `_elements`, `_stashed_elements`, etc.) are skipped to avoid spurious updates.

### Explicit vs. Automatic `_update()`

Cargo calls `_update()` automatically through `synchronize()` after any mutation. However, in some situations you may need to call `_update()` explicitly:

- When you mutate a Document's fields directly (not through Cargo) and want to push the change to reactive bindings.
- When you modify a shared data structure (like a View result Cargo) from a background process or an Alert handler.

This pattern appears frequently in the MadAve-Collab project:

```groovy
job.updateStatus(newStatus)
job.save()
job._update()   // Explicitly push changes to all connected clients
```

---

## The WebSocket Connection

### Establishment

When `Launchpad.launch()` detects that a template uses reactive bindings or server actions (i.e., `binding._socket` is truthy), it injects a WebSocket setup script into the HTML output before `</body>`:

```javascript
var socket

if (window.location.protocol === 'https:') {
    socket = new WebSocket("wss://" + window.location.host + "/!/lp/socket")
} else {
    socket = new WebSocket("ws://" + window.location.host + "/!/lp/socket")
}

socket.onopen = function() {
    socket.send(JSON.stringify({
        "handler-id": "launchpad-socket",
        "action": "register",
        "uuid": "<launchID>"
    }))
}
```

### Server-Side Socket Handling

The WebSocket endpoint `/!/lp/socket` is handled by `SocketConnector`, which delegates to `SocketHandler`. On connection, `SocketHandler`:

1. Caches cookies and headers from the upgrade request.
2. Looks up the `Client` via the `spaceport-uuid` cookie.
3. Attaches the socket to the client and retrieves the session `dock` (Cargo).
4. Fires the `'on socket connect'` Alert for application-level monitoring.

Incoming messages are dispatched based on the `handler-id` field. Messages with `"handler-id": "launchpad-socket"` are handled by `Launchpad._registerLaunchpadSocket()`.

### Socket Registration

When the registration message arrives, the server associates the WebSocket session with the binding satellite:

```groovy
@Alert('on socket launchpad-socket')
static _registerLaunchpadSocket(SocketResult r) {
    if (r.context.data.action == 'register') {
        def uuid = r.context.data.'uuid'
        Binding binding = bindingSatellites.get(uuid)
        binding._socket = r.context  // Replace boolean 'true' with the SocketContext
    }
}
```

From this point, `binding._socket.session.remote.sendStringByFuture()` can push data to the client.

### Heartbeat / Keep-Alive

The server starts a periodic timer (every 10 seconds) that sends ping messages:

```groovy
def timer = new Timer()
def task = new TimerTask() {
    void run() {
        if (r.context.handler.session?.isOpen()) {
            r.writeToRemote(['action': 'ping'])
        } else {
            bindingSatellites.remove(uuid)
            timer.cancel()
            timer.purge()
        }
    }
}
```

The client responds with a pong:

```javascript
if (data.action === 'ping') {
    socket.send(JSON.stringify({
        "handler-id": "launchpad-socket",
        "action": "pong"
    }))
}
```

When the socket closes, the heartbeat timer detects it, removes the binding satellite from the map, and cancels itself.

### WebSocket Message Format

All WebSocket messages use JSON. Server-to-client messages:

| `action` Field | Purpose | Additional Fields |
|:---|:---|:---|
| `ping` | Keep-alive heartbeat | (none) |
| `applyReaction` | Push a reactive update | `uuid` (reaction ID), `payload` (new HTML content) |
| `elementResponse` | Response from a Server Element function call | `requestId`, `payload` |

Client-to-server messages:

| `handler-id` | `action` | Additional Fields |
|:---|:---|:---|
| `launchpad-socket` | `register` | `uuid` (launchID) |
| `launchpad-socket` | `pong` | (none) |
| `launchpad-socket` | `element-call` | `function`, `uuid`, `element`, `requestId` |

---

## Client-Side Processing

### Server Action Responses (HTTP)

When an `on-*` event fires, HUD-Core's event handler:

1. **Resolves the target**: Calls `getTargetElement(event)` to find the payload target based on the `target` attribute. If the element itself has no `target`, walks up the DOM tree.
2. **Determines the source**: Resolves the `source` attribute to find the active target element for data collection.
3. **Collects data**: Gathers element value, form data, data attributes, keyboard/mouse event details, URL query parameters, and included storage values.
4. **Sets loading state**: Adds `loading="true"` attribute to the payload target (or active target if no payload target).
5. **POSTs to the server**: Sends the collected data as form-encoded POST to the binding URL.
6. **Removes loading state**: Removes the `loading` attribute.
7. **Parses the response**: Attempts JSON parsing. If successful, it is a Map or Array Transmission. If parsing fails, it is a Single Value Transmission (raw text).

### The Three Transmission Types on the Client

**Array Transmission Processing:**

Each entry in the array is processed sequentially against the `payloadTarget`:

```javascript
payload.forEach(className => {
    if (typeof className !== 'string') return

    if (className.startsWith('@')) {
        // Execute action: @click, @focus, @remove, @reload, etc.
    } else if (className.startsWith('-')) {
        payloadTarget.classList.remove(className.substring(1))
    } else if (className.startsWith('+')) {
        payloadTarget.classList.add(className.substring(1))
    } else {
        // Toggle class
        payloadTarget.classList.toggle(className)
    }
})
```

**Map Transmission Processing:**

Each key-value pair is processed based on the key prefix. The processing order:

1. **Content keys**: `value`, `insertBefore`, `insertAfter`, `append`, `prepend`, `innerHTML`, `outerHTML`, `innerText` -- direct DOM manipulation on `payloadTarget`
2. **URL query** (`?` prefix): update URL via `history.pushState` without page reload
3. **Data attributes** (`*` prefix): set `payloadTarget.dataset[key]`
4. **Inline styles** (`&` prefix): set `payloadTarget.style[key]`
5. **Actions** (`@` prefix): execute the action, checking the value for element targeting (`'this'` = `event.target`, `'it'` = `event.currentTarget`, `'source'` = active target, default = payload target)
6. **Session storage** (`~~` prefix): set `sessionStorage`
7. **Local storage** (`~` prefix): set `localStorage`
8. **Class add** (`+` prefix): add CSS class, value determines target element
9. **Class remove** (`-` prefix): remove CSS class, value determines target element
10. **Selector keys** (`#id`): `getElementById`, then set `innerHTML`
11. **Descendant selector** (`>` prefix): `querySelector` on active target, then set `innerHTML`
12. **Default**: set as HTML attribute on `payloadTarget` (`null` value removes the attribute)

**Single Value Transmission Processing:**

```javascript
if (activeTarget.getAttribute('target') === 'outer') {
    payloadTarget.outerHTML = text
} else {
    if (payloadTarget.value !== undefined) {
        payloadTarget.value = text
    } else if (payloadTarget.setValue) {
        payloadTarget.setValue(text)
    } else {
        payloadTarget.innerHTML = text
    }
}
```

### Reactive Binding Updates (WebSocket)

When a WebSocket message with `action: 'applyReaction'` arrives, the client uses a DOM comment node iterator to find the chunk markers and replace the content between them:

```javascript
socket.onmessage = function(event) {
    var data = JSON.parse(event.data)

    if (data.action === 'applyReaction') {
        const comments = document.createNodeIterator(
            document.body, NodeFilter.SHOW_COMMENT
        )

        let startComment = null
        let endComment   = null
        let comment
        while (comment = comments.nextNode()) {
            if (comment.nodeValue.trim() === 'START_CHUNK@' + data.uuid) {
                startComment = comment
            } else if (comment.nodeValue.trim() === 'END_CHUNK@' + data.uuid) {
                endComment = comment
                break
            }
        }

        if (startComment && endComment) {
            const range = document.createRange()
            range.setStartAfter(startComment)
            range.setEndBefore(endComment)

            const tempDiv = document.createElement('div')
            tempDiv.innerHTML = data.payload.toString()
            const newContent = document.createDocumentFragment()
            while (tempDiv.firstChild) {
                newContent.appendChild(tempDiv.firstChild)
            }

            range.deleteContents()
            range.insertNode(newContent)
        }
    }
}
```

The comment markers are never removed -- they persist in the DOM for the lifetime of the page, allowing repeated updates to the same reactive region.

---

## The MutationObserver: Making Dynamic Content Interactive

HUD-Core uses a `MutationObserver` to watch for DOM changes. When new elements are added to the DOM (whether from Transmissions, reactive updates, or any other source), the observer:

1. **Attaches event listeners**: Scans new elements for `on-*` attributes and sets up server action handlers.
2. **Evaluates scripts**: Runs inline `<script>` tags via `window.eval()`.
3. **Sets up navigation**: Configures `href` attributes for client-side routing.
4. **Parses Document Data**: Scans comment nodes for embedded CDATA.
5. **Calls mutation hooks**: Invokes `mutated()` on elements that define the callback.
6. **Handles popovers**: Sets up `popovertarget` attributes.

For removed elements, the observer:

1. **Cleans up Server Elements**: Calls `deconstructed()` hooks, removes event listeners, and deletes the global component instance.

This ensures that dynamically inserted HTML from Transmissions is fully functional, with all server action bindings and event listeners active, without requiring any manual setup.

---

## Target Resolution

The `getTargetElement(event)` function resolves the `target` attribute through a multi-step process:

1. Check `event.currentTarget` for a `target` attribute.
2. If not found, walk up parent elements until one with `target` is found.
3. Process the target value:
   - **Positional keywords**: `self`, `parent`, `grandparent`, `next`, `previous`, `nextnext`, `previousprevious`, `first`, `last`
   - **Insertion keywords**: `append`, `prepend`, `after`, `before` -- create a new element (using the `wrapper` attribute, default `<div>`) and insert it at the specified position
   - **Index-based**: `nth-child` and `nth-sibling` use the `index` attribute
   - **Special**: `none` returns null, `outer` returns the element itself (but changes how Single Value is applied)
   - **Selector values**: `>` prefix searches descendants, `<` prefix searches ancestors, everything else uses global `querySelector`

---

## Binding Cleanup

HUD-Core tracks server action URLs via `lp-uuid` attributes on elements. When the `MutationObserver` detects that elements with these attributes are removed from the DOM, it collects the stale UUIDs and sends them to `/!/lp/bind/u/` for server-side cleanup:

```groovy
@Alert('on /!/lp/bind/u/ hit')
static void unbind(HttpResult r) {
    def uuids = r.context.data?.uuids
    for (def uuid in uuids) {
        def l = uuid.split('&')[0].split('=')[1]  // launch ID
        def u = uuid.split('&')[1].split('=')[1]  // binding UUID
        if (bindingSatellites.containsKey(l)) {
            bindingSatellites.get(l)._bindings.remove(u)
        }
    }
}
```

This prevents memory leaks from accumulating stale closures in long-lived sessions.

---

## Threading Considerations

### Concurrency Safety

- `bindingSatellites` is a `ConcurrentHashMap` -- thread-safe for concurrent reads and writes.
- `_bindings` and `_reactions` within each binding satellite are also `ConcurrentHashMap` instances.
- Cargo's `synchronize()` method is declared `synchronized` -- only one thread can modify and propagate at a time per Cargo instance.

### Background Threads

- **Cargo document saves**: Queued to a single background thread per root Cargo instance, with a 3ms debounce window.
- **Cargo satellite updates**: Queued to a single background thread per root Cargo instance, with a 5ms debounce window.
- Both use a queue-flag pattern: if a thread is already running (`_documentSynchronizeQueue` or `_satelliteSynchronizeQueue` is non-null), subsequent calls are no-ops. The running thread picks up the latest state after its sleep.

### WebSocket Messages

- Incoming WebSocket messages spawn a new `Thread` for processing (in `SocketHandler.onWebSocketText`). This means socket message handlers run outside the Jetty HTTP thread pool.
- Outgoing WebSocket messages use `sendStringByFuture()` for non-blocking I/O.

### Multi-Client Broadcasting

When `_update()` is called, it iterates all binding satellites globally. A single Cargo change can trigger WebSocket pushes to every connected client that has a reaction depending on that Cargo. The iteration happens on the calling thread, and each `sendStringByFuture()` is non-blocking.

### Server Action Execution

Server actions execute on the Jetty HTTP thread pool. Each incoming POST to `/!/lp/bind` is handled by a thread from the pool. The closure accesses the shared Binding object, which contains template-level variables. Since binding satellites are scoped per client session, concurrent access from the same user (e.g., rapid clicks) is the primary concurrency concern. The `ConcurrentHashMap` containers protect the structural integrity, but application-level race conditions in template variables are the developer's responsibility.

---

## What's Next

- **[Transmissions Overview](transmissions-overview.md)** -- high-level introduction
- **[Transmissions API Reference](transmissions-api.md)** -- complete syntax reference
- **[Transmissions Examples](transmissions-examples.md)** -- real-world patterns from production applications
