# Documents (CouchDB ORM) --- Internals

This document covers the internal implementation of the Document system: how saves actually work, how conflicts are detected and resolved, how caching operates, how Jackson serialization is configured, and how Documents integrate with Cargo for reactivity.

---

## Class Hierarchy and Packages

All Document-related classes live in `spaceport.computer.memory.physical`:

```
spaceport.computer.memory.physical
  +-- Document          Base ORM class
  +-- ViewDocument      CouchDB design documents (views)
  +-- View              View query results
  +-- Operation          Result object for DB operations
  +-- CouchHandler      Low-level HTTP bridge to CouchDB
```

`ClientDocument` (in `spaceport.personnel`) extends `Document` for user management, adding authentication fields and session cookie tracking.

---

## CouchHandler: The HTTP Bridge

`CouchHandler` is the sole point of communication between Spaceport and CouchDB. Every document operation --- get, create, update, delete, query --- passes through this class as an HTTP request.

### Construction and Session Authentication

`CouchHandler` is instantiated during Spaceport startup based on the `memory cores` configuration in the manifest:

```yaml
memory cores:
  main:
    type: couchdb
    address: 'http://127.0.0.1:5984'
    username: admin
    password: secret
```

The constructed instance is stored as `Spaceport.main_memory_core`. On creation:

1. The address is normalized (trailing `/` added, `http://` prepended if missing).
2. If credentials are provided, `authenticate(user, pass)` is called.
3. Authentication POSTs to CouchDB's `_session` endpoint and extracts the `AuthSession` cookie from the `Set-Cookie` header.
4. The session expiry is parsed from either `Max-Age` or `Expires` in the cookie header, then set to 90% of the actual expiry to ensure refresh happens before the session truly expires.
5. Credentials are stored in memory for automatic session refresh.

Every subsequent CouchDB request calls `ensureValidSession()` before executing. If the session has expired (or is within 10% of expiry), `authenticate()` is called again automatically with the stored credentials.

### Jackson ObjectMapper Configuration

`CouchHandler` uses a Jackson `ObjectMapper` configured as follows:

- `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so extra JSON fields do not cause deserialization errors.
- `FAIL_ON_EMPTY_BEANS` is disabled, allowing empty objects to serialize correctly.
- A custom `CargoSerializer` is registered to handle `Cargo` objects, which do not follow standard bean conventions.

This ObjectMapper is used for all document serialization and deserialization between Spaceport and CouchDB.

---

## Document Caching

`CouchHandler` maintains a static `ConcurrentHashMap` called `cachedDocuments` that caches documents in memory, keyed by `"{_id}/{database}"`.

### Cache Behavior

- **On `get()`**: The cache is checked first. If a cached document with a compatible type exists, it is returned immediately without hitting CouchDB. Cache hits are logged at debug level.
- **On `getUncached()`**: The cache is bypassed entirely. A fresh copy is fetched from CouchDB and replaces the cached entry.
- **On `getIfExists()`**: Uses the same cache-first logic as `get()`.
- **On `exists()`**: Always bypasses the cache (`ignoreCache = true`) to check actual database state.
- **On `close()`**: The document is removed from the cache. The next `get()` call will fetch from CouchDB.
- **On `asType()` (type conversion)**: If the target type differs from the cached type, the cache entry is evicted and the document is re-fetched as the new type via `getAs()`.

### Cache Key Format

```
{_id}/{database}
```

For example, `"settings/my-app"` or `"_design/views/articles"`.

### Cache Implications

- Multiple calls to `Document.get('x', 'db')` return the **same object instance**, so mutations are visible to all holders of that reference.
- The cache is never automatically invalidated. If another process modifies a document in CouchDB, the cached copy becomes stale until `getUncached()` or `close()` is called.
- The cache is application-wide (static field), so all threads share the same document instances.

---

## Save Lifecycle (Normal Path)

When `document.save()` is called and no revision conflict exists:

```
1. [synchronized] Enter save()
2. Fetch latest uncached revision from CouchDB
3. Compare current._rev with this._rev
4. _rev values MATCH -> normal save path
   a. Record timestamp in states.'updated'
   b. If CREATE_DIFF feature is enabled:
      - Use ObjectDifferBuilder to compare 'this' vs 'current'
      - Walk the diff tree, collecting changed paths into diffMap
      - Skip paths containing '/updates{' (to avoid noise from the updates map itself)
   c. If KEEP_HISTORY feature is enabled AND diffMap is non-empty:
      - Add entry to updates[timestamp] with action='saved' and list of changed paths
   d. Fire 'on document save' alert (synchronous)
      - Context: type, old (the fetched current), document (this), difference (diffMap)
      - Handlers can modify the document before it is written
   e. CouchHandler.updateDoc(this, database) -> HTTP PUT to CouchDB
   f. Update this._rev with the new revision from Operation.rev
   g. Fire 'on document modified' alert (synchronous)
      - Context: type, action='saved', old, document, difference, operation
   h. Fire 'on document saved' alert (asynchronous, in Thread.start{})
      - Context: type, old, document, difference, operation
5. Return Operation
```

### Property Filtering During Save

`CouchHandler.updateDoc()` does not serialize the entire Groovy object. It builds a filtered map:

1. Starts with the base property set: `_id`, `_rev`, `type`, `fields`, `states`, `cargo`, `updates`, `_attachments`.
2. If the document has a `customProperties` list, those property names are added.
3. For each property in the combined list, the value is read from the document object.
4. Any entries in `additionalProperties` (Jackson's catch-all map) that are not already covered are added.
5. The filtered map is serialized to JSON with the ObjectMapper and PUT to CouchDB.

This means transient fields (like `database`) and unlisted properties are never sent to CouchDB.

---

## Save Lifecycle (Conflict Path)

When `document.save()` is called and the `_rev` values do **not** match:

```
1. [synchronized] Enter save()
2. Fetch latest uncached revision from CouchDB -> 'current'
3. Compare current._rev with this._rev
4. _rev values DO NOT MATCH -> conflict path
   a. Fetch the specific revision matching this._rev from CouchDB -> 'previous'
      (This is the version the caller was working from)
   b. Compute 'changesRequested': diff between 'this' and 'previous'
      - This tells us what the caller intended to change
   c. Compute 'differences': diff between 'previous' and 'current'
      - This tells us what someone else changed while we were working
   d. Fire 'on document conflict' alert (synchronous)
      - Context: type, action='conflict', document (this), conflicted (current),
                 changes (changesRequested map), differences (diff map)
      - Conflict handlers can modify 'this' to merge changes
   e. If the conflict alert was called (handler existed):
      - Recompute final diff between modified 'this' and 'previous'
   f. If KEEP_HISTORY enabled and changes exist, record in updates
   g. Fire 'on document save' alert (synchronous, pre-write)
   h. Force this._rev = current._rev (so CouchDB accepts the write)
   i. CouchHandler.updateDoc(this, database) -> HTTP PUT
   j. Update this._rev with new revision
   k. Fire 'on document modified' and 'on document saved' alerts
5. Return Operation
```

The conflict path uses `CouchHandler.getDocRevision()` to fetch a specific historical revision from CouchDB (via the `?rev=` query parameter), enabling precise three-way comparison.

**Important:** The conflict resolution mechanism is marked as "WORK IN PROGRESS" in the source code and has not been used in any of the real-world example projects. In practice, conflicts are rare in typical Spaceport applications because the `save()` method is `synchronized` per document instance, and the caching system means most code operates on the same in-memory object.

---

## Jackson Serialization

`Document` uses several Jackson annotations to control its JSON representation:

### `@JsonInclude(JsonInclude.Include.NON_EMPTY)`

Applied at the class level. Empty collections, null values, and empty strings are omitted from the serialized JSON. This keeps CouchDB documents lean.

### `@JsonIgnore`

Applied to the `database` field and all static methods. The `database` field is `transient` and should never be serialized.

### `@JsonAnySetter` / `@JsonAnyGetter`

These annotations on `addAdditionalProperty()` and `getAdditionalProperties()` create a catch-all mechanism:

- **Deserialization:** Any JSON property that does not match a declared Groovy property is captured in the `additionalProperties` map.
- **Serialization:** All entries in `additionalProperties` are written back as top-level JSON properties.

This ensures that Document subclasses do not lose data. If a CouchDB document has fields that the Groovy class does not declare, they are preserved through the read-modify-write cycle.

### `propertyMissing` (Groovy Dynamic Properties)

Document overrides Groovy's `propertyMissing` to delegate to `additionalProperties` for both reads and writes. This means you can access undeclared JSON properties directly:

```groovy
def doc = Document.get('config', 'app')
// If CouchDB has a field 'theme' that's not declared on Document:
println doc.theme  // Works, reads from additionalProperties
```

However, setting a property that does not exist in `additionalProperties` throws `MissingPropertyException`. This is a read-existing/write-existing mechanism, not a fully dynamic property system.

---

## Diff Engine

When `DocumentFeature.CREATE_DIFF` is enabled, `save()` uses the `java-object-diff` library (`ObjectDifferBuilder`) to compute a structural diff between the document being saved and the previously known version.

The diff walker visits every changed leaf node and records:
- The property path (e.g., `/fields/title`)
- The old value
- The new value

Paths containing `/updates{` are explicitly skipped to avoid recording changes to the updates map itself (which changes on every save due to timestamps).

The resulting diff map is passed to the `on document save`, `on document modified`, and `on document saved` alerts, enabling handlers to inspect exactly what changed.

---

## ViewDocument Internals

`ViewDocument` stores CouchDB design documents. The `_id` is always prefixed with `_design/` --- the `get()` method prepends this automatically, so callers use plain names.

### View Function Storage

Views are stored in the `views` map as:

```
views['view-name'] = [
    'map': 'function(doc) { ... }',
    'reduce': '_count'  // optional
]
```

The `customProperties` for ViewDocument are hardcoded to `['language', 'views']`, ensuring these are always serialized.

### `setViewIfNeeded` Logic

This method is designed for idempotent startup initialization:

1. If the view name does not exist in `views`, create it and mark as needing save.
2. If the view exists but the map function differs from the provided one, update it and mark as needing save.
3. If the view has a reduce function that was not requested, remove it and mark as needing save.
4. If any changes were made, call `save()`.
5. If nothing changed, skip the save entirely.

This prevents unnecessary CouchDB writes on every application restart.

### View Querying

`CouchHandler.getView()` constructs a URL in the format:

```
{address}/{database}/_design/{document_id}/_view/{view_id}
```

It strips any `_design/` prefix from the document ID to avoid doubling it, then appends any query parameters. The JSON response is deserialized into a `View` object.

---

## Document-Cargo Integration

Every Document has a built-in `cargo` property of type `Cargo`. This provides a lightweight, reactive data container embedded directly in the document.

### Direct Access

```groovy
def doc = Document.get('settings', 'app')
doc.cargo.set('theme', 'dark')
doc.save()
```

### Mirrored Cargo via `Cargo.fromDocument()`

The static method `Cargo.fromDocument(document)` creates a Cargo instance that is linked to the document's `cargo` field. Changes to the Cargo are automatically debounced and saved to CouchDB. This is commonly used for counters and reactive state:

```groovy
def hits = Cargo.fromDocument(Document.get('page-hits', 'app'))
hits.inc()  // Increments and auto-saves
```

The `CargoSerializer` registered on CouchHandler's ObjectMapper handles the serialization of Cargo objects to JSON, since Cargo has a non-standard internal structure (it extends `ConcurrentHashMap`).

---

## Changes Feed

`CouchHandler` provides access to CouchDB's `_changes` feed for monitoring database modifications:

### Sequence Tracking

The `getNewChanges()` method maintains a sequence checkpoint per database:

1. **In-memory:** A `sequenceTracker` map holds the last known sequence per database.
2. **Persistent:** A `checkpoint_{database}` document in the `_spaceport` system database stores the sequence for recovery across restarts.

The lookup order for the "since" parameter is: explicitly provided > in-memory tracker > persistent checkpoint > `'0'` (start from beginning).

After fetching changes, the new `last_seq` is stored in both the in-memory tracker and (unless `no_store: true`) the persistent checkpoint.

---

## Threading Considerations

- `Document.save()` is `synchronized` on the document instance, preventing concurrent saves of the same object.
- `ViewDocument.save()` is also `synchronized`.
- The `cachedDocuments` map is a `ConcurrentHashMap`, providing thread-safe reads and writes.
- The `on document saved` alert runs in a new `Thread.start{}`, so handlers must be thread-safe.
- The `on document save` and `on document modified` alerts run synchronously in the calling thread, so they block the save operation until complete.
- Session refresh (`ensureValidSession()`) is not synchronized, so concurrent requests during a refresh window may see brief authentication failures. In practice this is rare due to the 90% expiry buffer.
