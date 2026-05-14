# Documents (CouchDB ORM) --- API Reference

Complete API reference for the Document system: `Document`, `ViewDocument`, `View`, `Operation`, and `CouchHandler`. All classes are in the `spaceport.computer.memory.physical` package.

---

## Document

**`spaceport.computer.memory.physical.Document`**

The base ORM class for CouchDB. Provides get-or-create semantics, automatic JSON serialization via Jackson, revision-based conflict detection, file attachments, state tracking, and lifecycle alerts.

### Static Methods

#### `get(String id, String database)`

Retrieves the document with the given `_id` from the specified database. If the document does not exist, it is created in CouchDB and the `on document created` alert is fired.

Returns are cached --- subsequent calls with the same `id` and `database` return the same in-memory object.

```groovy
def doc = Document.get('settings', 'my-app')
```

- **Parameters:** `id` --- the document `_id`; `database` --- the CouchDB database name.
- **Returns:** `T extends Document` --- the document, cast to the calling class type.
- **Alerts fired:** `on document created` (if the document was newly created).

---

#### `getUncached(String id, String database)`

Same as `get()`, but always fetches a fresh copy from CouchDB, bypassing the in-memory cache. If the document does not exist, it is created.

```groovy
def fresh = Document.getUncached('settings', 'my-app')
```

- **Parameters:** `id`, `database`.
- **Returns:** `T extends Document`.
- **Alerts fired:** `on document created` (if newly created).

---

#### `getAs(Class documentType, String id, String database)`

Retrieves the document and deserializes it as the specified type. Useful when calling from `Document` directly rather than a subclass.

```groovy
def job = Document.getAs(Job, 'job-123', 'jobs')
```

- **Parameters:** `documentType` --- the class to deserialize as; `id`; `database`.
- **Returns:** `T extends Document`.
- **Alerts fired:** `on document created` (if newly created).

---

#### `getNew(String database)`

Creates a new document with a randomly generated UUID (hyphens removed) and returns it.

```groovy
def doc = Document.getNew('articles')
// doc._id is something like "a3f8b2c1d4e5..."
```

- **Parameters:** `database`.
- **Returns:** `T extends Document`.
- **Alerts fired:** `on document created`.

---

#### `getNewAs(Class documentType, String database)`

Creates a new document with a random UUID and deserializes it as the specified type.

```groovy
def job = Document.getNewAs(Job, 'jobs')
```

- **Parameters:** `documentType`; `database`.
- **Returns:** `T extends Document`.

---

#### `getIfExists(String id, String database)`

Retrieves the document if it exists, or returns `null` if it does not. Does **not** create the document.

```groovy
def doc = Document.getIfExists('maybe-exists', 'my-app')
if (doc) {
    // document exists
}
```

- **Parameters:** `id`; `database`.
- **Returns:** `T extends Document` or `null`.

---

#### `exists(String id, String database)`

Checks whether a document exists in the database without returning the full document. Always bypasses the cache.

```groovy
if (Document.exists('settings', 'my-app')) {
    // document is present
}
```

- **Parameters:** `id`; `database`.
- **Returns:** `boolean`.

---

### Persisted Properties

These properties are serialized to and from CouchDB automatically.

| Property | Type | Description |
|:---------|:-----|:------------|
| `_id` | `String` | Unique identifier within the database. Managed by CouchDB. |
| `_rev` | `String` | Revision identifier. Updated on every save. Used for conflict detection. |
| `_attachments` | `Map` | Inline file attachments stored as base64. Keyed by a generated `file-UUID`. |
| `fields` | `Map` | General-purpose key-value store. Intended for data exposed to HTTP requests. |
| `cargo` | `Cargo` | Built-in Cargo object. Can be mirrored with `Cargo.fromDocument()` for reactivity. |
| `updates` | `Map` | Timestamped log of save operations (keyed by `System.currentTimeMillis()`). |
| `states` | `Map` | State tracking with current value and history per key. |
| `type` | `def` | Application-defined type tag. Used in CouchDB views to filter documents by type. |

### Transient Properties

These properties exist only in memory and are not saved to CouchDB.

| Property | Type | Description |
|:---------|:-----|:------------|
| `database` | `String` | The database this document was loaded from. Set automatically. |
| `additionalProperties` | `Map<String, Object>` | Catch-all for JSON properties not mapped to explicit fields. Managed by Jackson's `@JsonAnySetter`/`@JsonAnyGetter`. |

### Custom Properties

When subclassing `Document`, declare a `customProperties` list to specify which additional properties should be persisted to CouchDB. Properties not in this list (and not in the base set) will not be saved.

```groovy
class Article extends Document {
    def customProperties = ['title', 'author', 'body']

    String title
    String author
    String body
}
```

The base set of persisted properties is: `_id`, `_rev`, `type`, `fields`, `states`, `cargo`, `updates`, `_attachments`. Everything in `customProperties` is added to this set during `save()`.

---

### Instance Methods

#### `save()`

Persists the document to CouchDB. This method is `synchronized` to prevent concurrent saves of the same document instance.

```groovy
doc.fields.counter = 42
Operation result = doc.save()
```

- **Returns:** `Operation` --- the result of the CouchDB PUT request.
- **Behavior:**
  1. Fetches the latest uncached revision from CouchDB.
  2. Compares `_rev` values. If they match, proceeds with a normal save. If they differ, enters the conflict resolution path.
  3. If `DocumentFeature.CREATE_DIFF` is enabled, computes a property-level diff.
  4. If `DocumentFeature.KEEP_HISTORY` is enabled and changes exist, records them in `updates`.
  5. Fires `on document save` (synchronous, pre-write).
  6. Writes to CouchDB via `CouchHandler.updateDoc()`.
  7. Updates `_rev` with the new revision.
  8. Fires `on document modified` (synchronous, post-write).
  9. Fires `on document saved` (asynchronous, in a new thread).
- **See also:** [Documents Internals](documents-internals.md) for the full save lifecycle and conflict resolution flow.

---

#### `remove()`

Deletes the document from CouchDB.

```groovy
Operation result = doc.remove()
```

- **Returns:** `Operation`.
- **Alerts fired:** `on document remove` (before delete), `on document removed` (after delete), `on document modified` (after delete, with `action: 'removed'`).

---

#### `close()`

Removes the document from the in-memory cache. The next `get()` call for this document will fetch a fresh copy from CouchDB. Changes made to the object after `close()` will not be reflected in the cache, and any unsaved changes will be lost.

```groovy
doc.close()
```

---

#### `asType(Class type)`

Supports Groovy's `as` operator for type coercion between Document subclasses. If the target type is compatible, returns the cached instance. If conversion is needed, evicts the cache entry and re-fetches as the new type.

```groovy
def job = doc as Job
```

- **Parameters:** `type` --- the target class. Must extend `Document`.
- **Returns:** The document cast to the target type.
- **Throws:** `Exception` if `type` does not extend `Document`.

---

### Attachment Methods

#### `addAttachment(File file, Map details)`

Attaches a file to the document as a base64-encoded inline attachment. The file is stored in `_attachments` with a generated `file-UUID` key. Also records the operation in `updates`.

```groovy
def fileId = doc.addAttachment(new File('/path/to/image.png'), [label: 'Profile Photo'])
doc.save()
```

- **Parameters:** `file` --- the file to attach; `details` --- a map of metadata to associate with the attachment.
- **Returns:** `String` --- the generated UUID key (e.g., `"file-a3f8b2c1-..."`).
- **Note:** You must call `save()` after adding an attachment.

---

#### `addBase64Attachment(String base64, Map details)`

Attaches base64-encoded data directly. The `details` map **must** contain `name` (desired filename) and `type` (MIME type). These keys are consumed and removed from the details map.

```groovy
def fileId = doc.addBase64Attachment(encodedData, [name: 'photo.jpg', type: 'image/jpeg'])
doc.save()
```

- **Parameters:** `base64` --- the base64-encoded file content; `details` --- must include `name` and `type`.
- **Returns:** `String` --- the generated UUID key.

---

#### `getAttachment(String fileId, OutputStream o)`

Retrieves an attachment from CouchDB and writes it to the provided `OutputStream`.

```groovy
def out = new ByteArrayOutputStream()
doc.getAttachment('file-a3f8b2c1-...', out)
```

- **Parameters:** `fileId` --- the attachment UUID; `o` --- the output stream to write to.
- **Returns:** `Map` with `content_type` and `content_length`.

---

#### `removeAttachment(String attachment)`

Removes an attachment from the document directly in CouchDB. Updates `_rev` automatically on success.

```groovy
doc.removeAttachment('file-a3f8b2c1-...')
```

- **Parameters:** `attachment` --- the attachment UUID.
- **Note:** This method saves to CouchDB immediately (no separate `save()` call needed).

---

### State Management Methods

Documents have a built-in state machine via the `states` map. Each state key tracks a current value, a history of previous values, and a last-updated timestamp.

#### `setState(String id, def value, def details)`

Sets the current state for the given key, pushing the previous state into history.

```groovy
doc.setState('status', 'approved', [approvedBy: 'admin'])
```

- **Parameters:** `id` --- the state key; `value` --- the new state value; `details` --- additional metadata.

---

#### `setState(String id, Closure closure)`

Sets the state using a closure that receives the current state and returns the new state.

```groovy
doc.setState('counter') { current ->
    [value: (current?.value ?: 0) + 1]
}
```

---

#### `setState(String id, String state)` / `setState(String id, Number state)`

Convenience overloads that set the state with an empty details map.

```groovy
doc.setState('phase', 'review')
doc.setState('retryCount', 3)
```

---

#### `getState(String key)`

Returns the current state map for the given key. The map contains `value` and `details`. If the key does not exist, it is initialized with empty defaults.

```groovy
def status = doc.getState('status')
println status.value   // 'approved'
println status.details // [approvedBy: 'admin']
```

- **Returns:** `Map` with keys `value` and `details`.

---

#### `getStateHistory(String key)`

Returns the history map for the given key, keyed by timestamp. Each entry contains the previous state value and details.

```groovy
def history = doc.getStateHistory('status')
history.each { timestamp, state ->
    println "At ${new Date(timestamp as Long)}: ${state}"
}
```

- **Returns:** `Map` keyed by timestamp.

---

### Document Features

The `features` list controls optional behavior. Add features in your subclass constructor or initializer.

| Feature | Description |
|:--------|:------------|
| `DocumentFeature.CREATE_DIFF` | Computes a property-level diff on every `save()`. The diff is passed to lifecycle alerts. |
| `DocumentFeature.KEEP_HISTORY` | Records a summary of changes in the `updates` map on every `save()` when diffs are non-empty. |

```groovy
class AuditedDocument extends Document {
    {
        features = [DocumentFeature.CREATE_DIFF, DocumentFeature.KEEP_HISTORY]
    }
}
```

---

## Document Lifecycle Alerts

These alerts are fired during document operations. Listen for them with `@Alert` on static methods in any source module.

| Alert | Timing | Context Map Keys |
|:------|:-------|:-----------------|
| `on document created` | After a new document is created in CouchDB | `id`, `database`, `doc`, `classType` |
| `on document save` | Before writing to CouchDB (synchronous) | `type`, `old`, `document`, `difference` |
| `on document saved` | After writing to CouchDB (asynchronous, new thread) | `type`, `old`, `document`, `difference`, `operation` |
| `on document modified` | After save or remove (synchronous) | `type`, `action` (`'saved'` or `'removed'`), `old`, `document`, `difference`, `operation` |
| `on document remove` | Before deleting from CouchDB (synchronous) | `type`, `document` |
| `on document removed` | After deleting from CouchDB (synchronous) | `type`, `document`, `operation` |
| `on document conflict` | During save when `_rev` mismatch is detected | `type`, `action`, `document`, `conflicted`, `changes`, `differences` |

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result

class AuditLog {

    @Alert('on document saved')
    static _logSave(Result r) {
        def doc = r.context.document
        def type = r.context.type
        println "Document saved: ${doc._id} (type: ${type})"
    }
}
```

---

## ViewDocument

**`spaceport.computer.memory.physical.ViewDocument`**

Wraps a CouchDB design document (`_design/...`) that contains JavaScript map/reduce view functions.

### Static Methods

#### `get(String id, String database)`

Retrieves the design document `_design/{id}` from the specified database. Creates it if it does not exist.

```groovy
def viewDoc = ViewDocument.get('my-views', 'articles')
```

- **Parameters:** `id` --- the design document name (without `_design/` prefix); `database`.
- **Returns:** `ViewDocument`.

---

#### `getIfExists(String id, String database)`

Retrieves the design document or returns `null` if it does not exist.

- **Returns:** `ViewDocument` or `null`.

---

#### `exists(String id, String database)`

Checks whether the design document exists.

- **Returns:** `boolean`.

---

### Instance Methods

#### `setView(String id, def map)`

Sets a view with the given map function and saves the design document immediately.

```groovy
viewDoc.setView('by-author', '''
    function(doc) {
        if (doc.type === 'article') {
            emit(doc.author, null);
        }
    }
''')
```

- **Parameters:** `id` --- the view name; `map` --- the JavaScript map function as a string.
- **Returns:** `ViewDocument` (for chaining).

---

#### `setView(String id, def map, def reduce, boolean save = true)`

Sets a view with both map and reduce functions. Optionally defers saving for chaining.

```groovy
viewDoc.setView('count-by-author', '''
    function(doc) {
        if (doc.type === 'article') emit(doc.author, 1);
    }
''', '_count', false)
```

- **Parameters:** `id`; `map`; `reduce` --- the JavaScript reduce function (or a CouchDB built-in like `'_count'`, `'_sum'`, `'_stats'`); `save` --- whether to save immediately (default `true`).
- **Returns:** `ViewDocument`.

---

#### `setViewIfNeeded(String id, def map)`

Sets the view only if it does not already exist or if the map function has changed. This is the preferred method for view initialization at startup, since it avoids unnecessary writes to CouchDB.

```groovy
ViewDocument.get('views', 'articles')
    .setViewIfNeeded('all', '''
        function(doc) {
            if (doc.type === 'article') {
                emit(doc._id, { title: doc.title });
            }
        }
    ''')
```

- **Parameters:** `id`; `map`.
- **Returns:** `ViewDocument` (for chaining).

---

#### `containsView(String id)`

Checks whether the design document contains a view with the given name.

- **Returns:** `boolean`.

---

#### `getView(String id)`

Queries the named view and returns the results.

- **Parameters:** `id` --- the view name.
- **Returns:** `View`.

---

#### `save()`

Saves the design document to CouchDB. Synchronized.

- **Returns:** `Operation`.

---

#### `remove()`

Deletes the design document from CouchDB.

- **Returns:** `Operation`.

---

### Persisted Properties

| Property | Type | Default | Description |
|:---------|:-----|:--------|:------------|
| `_id` | `def` | | Design document ID (e.g., `_design/my-views`). |
| `_rev` | `def` | | CouchDB revision string. |
| `language` | `def` | `'javascript'` | The language for view functions. |
| `views` | `Map` | `[:]` | Map of view names to their `map`/`reduce` function definitions. |

---

## View

**`spaceport.computer.memory.physical.View`**

Represents the result of querying a CouchDB view. Contains the returned rows, total count, and offset.

### Static Methods

#### `get(String document_id, String view_id, String database)`

Queries the specified view and returns the results.

```groovy
def results = View.get('my-views', 'by-author', 'articles')
```

- **Parameters:** `document_id` --- the design document name; `view_id` --- the view name; `database`.
- **Returns:** `View`.

---

#### `getWithDocuments(String document_id, String view_id, String database)`

> **Note:** This method is experimental and not yet recommended for production use. Prefer the [Row/Value pattern](documents-examples.md#typed-view-results-rowvalue-pattern) with a `grabDocument()` method on your Row class, which gives you typed results and loads full documents on demand rather than eagerly.

Queries the view with `include_docs=true`, so each row contains the full document. Use `getDocuments()` on the result to get `Document` objects.

```groovy
def results = View.getWithDocuments('my-views', 'all', 'articles')
def docs = results.getDocuments()
```

- **Returns:** `View`.

---

#### `get(String document_id, String view_id, String database, Map parameters)`

Queries the view with additional CouchDB parameters (e.g., `key`, `startkey`, `endkey`, `limit`, `descending`, `group`, `reduce`).

```groovy
def results = View.get('my-views', 'by-author', 'articles', [
    key: '"Alice"',
    limit: 10
])
```

- **Parameters:** `document_id`; `view_id`; `database`; `parameters` --- a map of CouchDB view query parameters.
- **Returns:** `View`.

---

### Instance Properties

| Property | Type | Description |
|:---------|:-----|:------------|
| `rows` | `List` | The list of result rows. Each row is a map with `id`, `key`, and `value`. |
| `total_rows` | `Integer` | The total number of rows in the view (before any `limit`). |
| `offset` | `Integer` | The offset of the first returned row. |
| `database` | `def` | The database this view was queried from (transient, not serialized). |

### Instance Methods

#### `getDocuments()`

> **Note:** This method is experimental. Prefer the [Row/Value pattern](documents-examples.md#typed-view-results-rowvalue-pattern) with a `grabDocument()` method on your Row class for typed, on-demand document loading.

Converts the view rows into `Document` objects. If the view was queried with `include_docs=true`, deserializes the embedded documents. Otherwise, fetches each document by `_id`.

```groovy
List<Document> docs = results.getDocuments()
```

- **Returns:** `List<Document>`.

---

## Operation

**`spaceport.computer.memory.physical.Operation`**

Represents the result of a CouchDB operation (create, update, delete). Also used as a general-purpose result object throughout Spaceport.

### Static Factory Methods

#### `Operation.success(String reason = "...")`

Creates a successful Operation.

```groovy
def op = Operation.success('Document saved successfully.')
```

---

#### `Operation.successWithId(String id, String reason = "...")`

Creates a successful Operation with an associated document ID.

---

#### `Operation.failure(String reason = "...")`

Creates a failed Operation.

---

#### `Operation.failureWithError(String error, String reason = "...")`

Creates a failed Operation with an error code.

---

### Instance Properties

| Property | Type | Description |
|:---------|:-----|:------------|
| `ok` | `def` | `true` if the operation succeeded, `false` otherwise. |
| `reason` | `def` | A human-readable description of the outcome. |
| `id` | `def` | The document `_id` (on success). |
| `rev` | `def` | The new document `_rev` (on success). |
| `error` | `def` | An error code or identifier (on failure). |

### Instance Methods

#### `wasSuccessful()`

Returns `true` if `ok == true`.

```groovy
def result = doc.save()
if (result.wasSuccessful()) {
    println "Saved with rev: ${result.rev}"
}
```

---

## CouchHandler Configuration

CouchDB connection is configured in the `.spaceport` manifest file under `memory cores`:

```yaml
memory cores:
  main:
    type: couchdb
    address: 'http://127.0.0.1:5984'
```

With authentication:

```yaml
memory cores:
  main:
    type: couchdb
    address: 'http://127.0.0.1:5984'
    username: admin
    password: secret
```

If `username` or `password` are omitted but CouchDB requires authentication, Spaceport will prompt for credentials at startup.

### CouchHandler Database Methods

The `CouchHandler` instance is accessible via `Spaceport.main_memory_core` and provides low-level database management:

| Method | Description |
|:-------|:------------|
| `createDatabase(String id)` | Creates a new CouchDB database. Returns `Operation`. |
| `createDatabaseIfNotExists(String id)` | Creates the database only if it does not already exist. |
| `deleteDatabase(String id)` | Deletes a CouchDB database. |
| `containsDatabase(String id)` | Checks whether a database exists. Returns `boolean`. |
| `getAllDatabases()` | Returns a `List<String>` of all database names. |

```groovy
Spaceport.main_memory_core.createDatabaseIfNotExists('articles')
```

### CouchHandler Changes Feed Methods

| Method | Description |
|:-------|:------------|
| `getChanges(String database, Map parameters)` | Fetches the raw CouchDB changes feed for a database. |
| `getNewChanges(String database, Map parameters)` | Fetches only changes since the last recorded sequence. Tracks sequence checkpoints automatically. Use `no_store: true` in parameters to skip persisting the checkpoint. |

---

## Import Summary

```groovy
import spaceport.computer.memory.physical.Document
import spaceport.computer.memory.physical.ViewDocument
import spaceport.computer.memory.physical.View
import spaceport.computer.memory.physical.Operation
```
