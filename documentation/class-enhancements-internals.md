# Class Enhancements Internals

This document explains how Spaceport's metaclass enhancement system works under the hood, including the mechanism by which methods are added to standard Groovy types, the timing of when enhancements are applied, the interaction with hot-reload, and the implementation details of the reactive `_update()` method.


## The Enhancement Mechanism

Spaceport's class enhancements are implemented using Groovy's metaclass system. Every class in Groovy has an associated `metaClass` object that controls method dispatch. By assigning closures to `ClassName.metaClass.methodName`, you can add new instance methods to any class at runtime.

All enhancements are defined in a single static method:

```
spaceport.engineering.MetaClassEnhancements.enhance()
```

This method contains a series of `metaClass` assignments that attach new methods and properties to `String`, `Number`, `Integer`, `List`, `Map`, `Collection`, `Object`, and `Closure`.

### How metaClass Assignment Works

When Groovy encounters a method call like `"hello".kebab()`, it checks the `String` metaclass for a method named `kebab`. If one has been registered via `String.metaClass.kebab = { ... }`, Groovy invokes that closure with the string instance as the `delegate`.

Inside each enhancement closure, `delegate` refers to the object the method was called on. For example:

```groovy
String.metaClass.kebab = {
    // 'delegate' is the String instance, e.g., "HelloWorld"
    String s = delegate.replaceAll(/([A-Z])([A-Z])(?=[a-z])/, '$1-$2')
    // ... further transformations ...
    return s.toLowerCase()
}
```

Properties are added the same way. For example, `cleanType` is registered as a property on `String`:

```groovy
String.metaClass.cleanType = null
```

This makes `cleanType` a settable/gettable property on every `String` instance.


## When Enhancements Are Applied

`MetaClassEnhancements.enhance()` is called at two points in Spaceport's lifecycle:

### 1. Application Startup

When Spaceport boots as a full application server, enhancements are applied early in the startup sequence, before any source modules are loaded or any user code executes. This ensures that all user-written route handlers, Launchpad templates, and Server Elements have access to the enhanced methods from the start.

### 2. Migration Runtime

When the `--migrate` CLI command is used, the `Migration.runMigration()` method explicitly calls `MetaClassEnhancements.enhance()` after loading the configuration manifest but before presenting the migration selection menu. This means migration scripts have full access to all enhanced methods.

The relevant line in `Migration.groovy`:

```groovy
// Enhancing Classes
MetaClassEnhancements.enhance()
```

### Timing Guarantee

Because `enhance()` is called before any user code runs, you can rely on enhanced methods being available in:

- Alert handlers (`@Alert` annotated methods)
- Launchpad templates (`.ghtml` files)
- Server Elements
- Migration scripts
- Any Groovy code loaded by the Spaceport runtime


## Which Classes Are Enhanced and Why

| Class | Purpose |
|:---|:---|
| `String` | The most heavily enhanced type. Web applications constantly manipulate strings for URLs, HTML, formatting, encoding, and parsing. |
| `Number` | Date/time formatting, currency formatting, duration conversion. Web apps frequently work with timestamps (epoch milliseconds) and monetary values. |
| `Integer` | Random number and ID generation. Extends `Number` enhancements with integer-specific utilities. |
| `List` | Template rendering (`combine`), temporary reactive data (`snap`), HTML sanitization of collections, and fluent modification (`including`). |
| `Map` | Same patterns as List -- `combine`, `snap`, `including`, `json` -- applied to key-value data. |
| `Collection` | JSON serialization for any collection type (covers Sets and other non-List collections). |
| `Object` | Reactive update propagation (`_update`, `_forceUpdate`), universal membership checking (`isPresent`), and CDATA wrapping (`cdata`). Applied to `Object` so these methods are available on every type. |
| `Closure` | Delegate manipulation (`callWith`) for advanced closure invocation patterns. |


## The `_update()` Implementation in Detail

The `_update()` method on `Object` is the most complex enhancement. It is the mechanism by which server-side data changes are propagated to connected clients through Spaceport's Launchpad reactivity system.

### How It Works

When `_update()` is called on an object:

1. **Iterate over all active binding satellites.** `Launchpad.bindingSatellites` is a map of all currently active reactive connections between the server and clients. Each satellite represents a client connection with its associated reactive state.

2. **Skip inactive connections.** For each satellite, the method checks:
   - Does the satellite have reactions or elements? If not, skip it.
   - Does the satellite have a valid, open WebSocket? If not, skip it.

3. **Check satellite variables.** The method iterates over the satellite's properties (skipping internal keys like `prime`, `_reactions`, `_bindings`, `cookies`, `_self`, `out`, `_socket`, etc.). For each property, it checks whether the property's value is the same object (identity check via `!==`) as the `delegate` (the object `_update()` was called on).

4. **Find matching reactions.** When a matching variable is found, the method checks which reactions are triggered by that variable name. Each reaction has a `_triggers` list that specifies which variables cause it to re-evaluate.

5. **Evaluate and send.** For each matching reaction:
   - The reaction closure is evaluated to produce a new HTML payload.
   - The payload is processed through the satellite's `__()` method for real-time element handling.
   - If `forcedUpdate` is false, the new payload is compared against `reaction.value._current`. If unchanged, no update is sent (optimization to avoid unnecessary WebSocket traffic).
   - If the payload has changed (or update is forced), a JSON message is sent to the client's WebSocket with the action `applyReaction`, the reaction's UUID, and the new payload.
   - The reaction's `_current` value is updated for future comparison.

6. **Check Server Elements.** The method also iterates over `satellite._elements`, checking if any element's properties reference the updated object. If so, the same reaction-evaluation and WebSocket-send logic is applied.

### Error Handling

Each level of the iteration is wrapped in try-catch blocks to ensure that a failure in one satellite, node, or reaction does not prevent other updates from being processed. Errors are logged via `Command.error()` but do not propagate.

### Identity vs. Equality

The check `node.value !== delegate` uses identity comparison (not equality). This means `_update()` only triggers reactions for variables that hold a reference to the exact same object instance. If you replace a variable with a new object that has the same value, the old object's `_update()` will not affect the new variable's reactions.

### The `_forceUpdate()` Shortcut

`_forceUpdate()` simply delegates to `_update(true)`, bypassing the payload-change check. This is useful when you need to force a client refresh even though the rendered output might be identical (for example, after an internal state change that does not affect the template output but should trigger client-side logic).


## The `snap()` Implementation

The `snap()` method on `List` and `Map` demonstrates how enhancements can combine temporal behavior with reactivity:

1. The item is added to the collection immediately.
2. A new thread is started with a `sleep()` for the specified duration.
3. After the sleep, the item is removed from the collection.
4. `_update()` is called on the collection to trigger reactive updates on all connected clients.

This pattern enables "flash message" behavior where a notification appears and automatically disappears after a timeout, with the UI updating reactively on the client.

```groovy
// Inside List.metaClass.snap:
Thread.start {
    sleep(time)
    list.remove(obj)
    list._update()
}
```


## Interaction with Hot-Reload

In debug mode, Spaceport supports hot-reloading of source modules. When source modules are reloaded, the metaclass enhancements remain in effect because:

1. Enhancements are applied to the metaclass of the **class itself** (e.g., `String.metaClass`), not to individual instances.
2. Hot-reload recompiles and reloads user source modules, but does not re-initialize the core Groovy runtime classes.
3. `MetaClassEnhancements.enhance()` is called once during startup and does not need to be re-called when user code is reloaded.

This means that enhanced methods remain available even after source modules are modified and reloaded during development. The enhancements are stable for the lifetime of the JVM process.


## Dependencies

The enhancement system relies on several external libraries:

| Dependency | Used For |
|:---|:---|
| Jsoup | HTML sanitization in `clean()`. Provides `Safelist` configurations for different sanitization levels. |
| Jackson `ObjectMapper` | JSON serialization in `json()` and `cdata()`. A single static `ObjectMapper` instance is shared. |
| Groovy `JsonSlurper` | JSON parsing in `jsonList()` and `jsonMap()`. |
| Groovy `JsonBuilder` | JSON construction for WebSocket messages in `_update()`. |
| Java `URLEncoder`/`URLDecoder` | URL encoding/decoding in `encode()` and `decode()`. |
| Java `Base64` | Base64 encoding/decoding in `toBase64()` and `fromBase64()`. |
| Java `SimpleDateFormat` | Date/time formatting in `time()`, `date()`, `dateRaw()`, `dateTime()`, `dateTimeRaw()`. |
| Java `DateTimeFormatter` | Datetime-local formatting in `formTime()` and parsing in `String.time()`. |
| Java `NumberFormat` | Currency formatting in `Number.money()`. |


## Design Considerations

### Why Object.metaClass for _update()?

The `_update()` method is added to `Object.metaClass`, making it available on every object in the system. This is intentional because any object can potentially be bound to a reactive variable in a Launchpad template. Rather than requiring specific types to implement an interface, Spaceport makes the update mechanism universally available.

### Why a Single enhance() Method?

All enhancements are defined in a single `enhance()` method rather than being spread across multiple classes or files. This centralization makes it easy to:

- See all enhancements in one place.
- Ensure they are all applied at the same time.
- Avoid ordering dependencies between enhancements.
- Maintain consistency in the enhancement style.

### Thread Safety in snap()

The `snap()` method spawns a new thread for each timed removal. The `sleep()` and subsequent `remove()` and `_update()` calls happen on this background thread. The collections themselves (Lists and Maps) are not synchronized, so `snap()` is best used with collections that are managed by a single Launchpad connection rather than shared across threads. The try-catch in the removal logic handles cases where the item may have already been removed by other code.


## See Also

- [Class Enhancements Overview](class-enhancements-overview.md) -- High-level introduction to what enhancements are and why they exist.
- [Class Enhancements API Reference](class-enhancements-api.md) -- Complete method reference.
- [Launchpad Internals](launchpad-internals.md) -- How Launchpad's reactive system uses `_update()`.
