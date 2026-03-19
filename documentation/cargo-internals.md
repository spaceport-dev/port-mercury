# Cargo Internals

This document explains how Cargo works under the hood: the reactive synchronization mechanism, document mirroring, nested Cargo coercion, threading considerations, and integration with Launchpad's binding system.

## Class Structure

`Cargo` resides in the `engineering` package. It is a Groovy class that wraps a `Map root = [:]` with reactive behavior layered on top.

### Key Fields

```groovy
class Cargo {
    Map root = [:]          // The underlying data store
    String _id              // Unique ID generated via 12.randomID()
    Document document       // Mirrored document (null for local/store Cargo)
    Cargo parent            // Parent reference for nested sub-cargos (null for root)
}
```

### Static State

```groovy
static Map<String, Cargo> mirrors = [:]  // document._id -> Cargo instance
```

The `mirrors` map is the global registry that tracks which Cargo instances are mirrored to which documents. This allows `Cargo.fromDocument()` to return the same instance when called multiple times for the same document, and enables the `@Alert('on document saved')` handler to locate the right Cargo when a document changes externally.

## Instance Creation

### Local Cargo

`new Cargo()` or `new Cargo(map)` initializes the root map and generates a unique `_id`. No entry in `mirrors`, no document reference. The instance exists purely in the JVM heap.

### Store Cargo

`Cargo.fromStore(name)` delegates to `Spaceport.store`, which is a `ConcurrentHashMap` on the `Spaceport` singleton. If no entry exists for `name`, a new Cargo is created, stored, and returned. If one already exists, the existing instance is returned.

Because `Spaceport.store` is a class-level field on the `Spaceport` singleton, it survives Groovy class reloading during development (hot-reload). However, since it is in-memory only, it does not survive a full server restart.

### Document Cargo

`Cargo.fromDocument(doc)` checks the static `mirrors` map for an existing Cargo keyed by the document's `_id`. If found, it returns the existing instance. Otherwise, it:

1. Creates a new `Cargo` initialized with the document's `cargo` field contents
2. Sets `this.document = doc`
3. Registers itself in `mirrors[doc._id] = this`
4. Returns the new instance

The `Document` class itself exposes a `cargo` property that effectively calls into this same mechanism, so `doc.cargo` and `Cargo.fromDocument(doc)` converge on the same instance.

## Nested Cargo Coercion

When you set a `Map` value into a Cargo, the map is automatically wrapped in a new `Cargo` instance with its `parent` reference set to the enclosing Cargo.

```groovy
cargo.set('user', [name: 'Alice', prefs: [theme: 'dark']])
// Internally:
//   cargo.root['user'] = new Cargo([name: 'Alice', prefs: [theme: 'dark']])
//   cargo.root['user'].parent = cargo
//   cargo.root['user'].root['prefs'] = new Cargo([theme: 'dark'])
//   cargo.root['user'].root['prefs'].parent = cargo.root['user']
```

This coercion is recursive: any nested map at any depth becomes a Cargo. This ensures that mutations at any level of nesting can trigger synchronization by walking up the parent chain to the root.

## The Reactive Mechanism: `synchronize()`

Every mutating operation (`set`, `delete`, `clear`, `inc`, `dec`, `toggle`, `append`, `remove`, `setNext`, `addToSet`, `takeFromSet`) calls `synchronize()` after modifying the root map.

`synchronize()` performs two actions:

### 1. Document Persistence (3ms Debounce)

If the Cargo has a non-null `document` reference (i.e., it was created via `fromDocument`), `synchronize()` schedules a save of the cargo data back to the document. This save is debounced at 3 milliseconds, meaning rapid successive mutations within a 3ms window coalesce into a single CouchDB write.

The debounce prevents excessive database writes during burst operations like looping through a list and updating multiple keys.

### 2. Launchpad Update via `_update()` (5ms Debounce)

`synchronize()` also calls `_update()`, which notifies the Launchpad binding system that this Cargo's data has changed. This is debounced at 5 milliseconds.

The `_update()` method interacts with Launchpad's **binding satellites** -- the mechanism that tracks which template expressions reference which Cargo instances. When `_update()` fires, Launchpad:

1. Identifies all active template bindings that reference this Cargo's `_id`
2. Re-evaluates those expressions server-side
3. Diffs the results against previously sent values
4. Pushes only changed values to connected clients via WebSocket

The 5ms debounce (slightly longer than the 3ms persistence debounce) ensures that the document save completes or is in-flight before the client receives the update, maintaining consistency.

## Parent Delegation

When a nested (child) Cargo is mutated, it does not directly call `synchronize()` on itself. Instead, it delegates upward through the `parent` chain until it reaches the root Cargo (where `parent == null`). The root Cargo then calls `synchronize()`, which saves the entire tree and pushes updates.

This design ensures that:
- Only one document write occurs regardless of which level of nesting was modified
- The full Cargo tree is serialized consistently
- Launchpad bindings on the root Cargo capture all changes, even deeply nested ones

```
Mutation at cargo.root['a'].root['b'].root['c']
  -> child Cargo 'c' delegates to parent 'b'
    -> child Cargo 'b' delegates to parent 'a'
      -> child Cargo 'a' delegates to parent root
        -> root Cargo calls synchronize()
          -> saves to document (if mirrored)
          -> calls _update() to push to Launchpad
```

## External Save Handling: `@Alert('on document saved')`

When a document is saved externally (by another process, another server in a cluster, or a CouchDB replication event), the Cargo mirror must be updated to reflect the new state. This is handled by an Alert listener:

```groovy
@Alert('on document saved')
```

When this alert fires for a document that has a registered mirror in `Cargo.mirrors`, the handler:

1. Reads the updated `cargo` field from the freshly saved document
2. Merges or replaces the in-memory Cargo's root map with the new data
3. Calls `_update()` to push the changes to any connected Launchpad templates

This keeps the in-memory Cargo consistent with the database, even when changes originate elsewhere.

## CargoSerializer

The `CargoSerializer` (in the `engineering` package) handles conversion between Cargo's in-memory tree of nested Cargo objects and the flat `Map` representation stored in CouchDB.

**Serialization (Cargo -> Map for storage):** Recursively walks the Cargo tree, extracting each nested Cargo's `root` map into a plain `Map`. The result is a standard nested `Map` structure with no Cargo class references, suitable for JSON serialization into CouchDB.

**Deserialization (Map from storage -> Cargo):** Takes a plain `Map` from a CouchDB document's `cargo` field and reconstructs the nested Cargo tree, setting up parent references at each level.

## Thread Safety Considerations

Cargo itself does not use explicit synchronization primitives (no `synchronized` blocks, no locks). Thread safety is managed at different levels depending on the mode:

- **Store Cargo:** The `Spaceport.store` is a `ConcurrentHashMap`, so retrieval and creation of store-level Cargo instances is thread-safe. However, concurrent mutations to the same Cargo instance from multiple threads (e.g., two HTTP requests incrementing the same counter) can race. In practice, the debounced save mechanism and CouchDB's conflict resolution mitigate data loss, but developers should be aware that Cargo is not an atomic counter.

- **Document Cargo:** CouchDB's optimistic concurrency (revision-based conflict detection) provides the safety net. If two writes conflict, CouchDB rejects one, and the alert-based sync mechanism reconciles.

- **Local Cargo:** Typically scoped to a single thread (request handler), so thread safety is not a concern.

For high-contention counters or flags, the recommended pattern is to use Document Cargo and rely on CouchDB's conflict handling rather than attempting in-process locking.

## Integration with Launchpad Binding Satellites

Launchpad templates that contain reactive expressions like `${{ cargo.get('key') }}` create **binding satellites** during template rendering. A binding satellite records:

- The Cargo instance's `_id`
- The expression to re-evaluate
- The client connection to push updates to
- The DOM location where the result should be inserted

When `_update()` fires on a Cargo, Launchpad queries its satellite registry for all satellites referencing that Cargo's `_id`, re-evaluates each expression, and sends targeted DOM updates via WebSocket to each connected client.

This is the mechanism that makes `${{ }}` expressions "live" -- they are not polling. The server pushes changes to the client only when the underlying Cargo actually mutates.

## Data Flow Summary

```
  Mutation (set/inc/delete/etc.)
       |
       v
  Parent delegation (nested -> root)
       |
       v
  synchronize()
       |
       +---> Document save (3ms debounce) ---> CouchDB
       |
       +---> _update() (5ms debounce)
                |
                v
          Launchpad satellite lookup by Cargo._id
                |
                v
          Re-evaluate bound expressions
                |
                v
          Diff against previous values
                |
                v
          Push changed values via WebSocket to clients
```

## See Also

- [Cargo Overview](cargo-overview.md) -- high-level introduction
- [Cargo API Reference](cargo-api.md) -- complete method listing
- [Cargo Examples](cargo-examples.md) -- real-world usage patterns
- [Launchpad Internals](launchpad-internals.md) -- details on the template binding system
- [Alerts Internals](alerts-internals.md) -- the event system powering `on document saved`
