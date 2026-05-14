# Documents (CouchDB ORM) --- Overview

Documents are Spaceport's data layer. Every piece of persistent data in a Spaceport application --- user records, configuration, uploaded files, application state --- lives in a CouchDB database and is accessed through the `Document` class. Documents give you a Groovy object with automatic JSON serialization, revision tracking, conflict detection, a built-in lifecycle event system, and tight integration with Spaceport's reactive Cargo and Launchpad systems.

## What Is a Document?

A `Document` is a Groovy class that maps directly to a JSON document in CouchDB. When you call `Document.get('settings', 'my-app')`, Spaceport either fetches the existing document with `_id` "settings" from the "my-app" database, or creates it if it does not exist. The returned object has Groovy properties you can read and write, and a `save()` method that persists your changes back to CouchDB.

```groovy
import spaceport.computer.memory.physical.Document

// Fetch (or create) a document by ID and database name
def doc = Document.get('homepage-config', 'my-app')

// Read and write the built-in 'fields' map
doc.fields.title = 'Welcome'
doc.fields.subtitle = 'Built with Spaceport'

// Persist to CouchDB
doc.save()
```

Every Document carries a handful of built-in properties that CouchDB and Spaceport manage for you:

| Property | Purpose |
|:---------|:--------|
| `_id` | Unique identifier within its database |
| `_rev` | CouchDB revision string (used for conflict detection) |
| `type` | Application-defined type tag (e.g., `'article'`, `'job'`) |
| `fields` | A general-purpose `Map` for storing arbitrary key-value data |
| `cargo` | A built-in `Cargo` object for reactive counters, toggles, and sets |
| `updates` | A timestamped map tracking save history |
| `states` | A state-tracking map with current values and history |
| `_attachments` | Inline file attachments (base64-encoded) |

## Custom Document Classes

For real applications, you extend `Document` to create typed data models with named properties, business logic, and convenience methods. The key requirement is the `customProperties` list, which tells Spaceport which properties to persist to CouchDB beyond the built-in ones.

```groovy
class Article extends Document {

    // Properties to serialize to CouchDB
    def customProperties = ['title', 'author', 'body', 'published']

    // Typed properties with defaults
    String title = 'Untitled'
    String author = ''
    String body = ''
    boolean published = false

    // Set a type for use in CouchDB views
    def type = 'article'

    // Factory method
    static Article makeNew() {
        def article = getNew('articles') as Article
        article.type = 'article'
        return article
    }

    // Business logic
    void publish() {
        this.published = true
        save()
    }
}
```

With a custom class, you get IDE autocomplete, compile-time type checking, and a single place to define both the data shape and the behavior.

## Querying with Views

CouchDB does not support ad-hoc queries the way SQL databases do. Instead, you define **views** --- small JavaScript functions that CouchDB runs against every document in a database to produce a sorted, queryable index.

In Spaceport, views are managed through two classes: `ViewDocument` (which stores the JavaScript map/reduce functions) and `View` (which queries the results).

```groovy
import spaceport.computer.memory.physical.ViewDocument
import spaceport.computer.memory.physical.View

// Define a view (typically done once at startup)
ViewDocument.get('views', 'articles')
    .setViewIfNeeded('by-author', '''
        function(doc) {
            if (doc.type === 'article' && doc.published) {
                emit(doc.author, { title: doc.title });
            }
        }
    ''')

// Query the view
def results = View.get('views', 'by-author', 'articles')
results.rows.each { row ->
    println "${row.value.title} by ${row.key}"
}
```

Views are the primary way to list, filter, and aggregate documents. They run inside CouchDB itself and are incrementally updated, making them efficient even for large datasets.

## Document Lifecycle

Documents fire Alerts at key points in their lifecycle, letting other parts of your application react to data changes without tight coupling:

- **`on document created`** --- A new document was created in CouchDB.
- **`on document save`** --- A document is about to be saved (synchronous; you can modify it).
- **`on document saved`** --- A document was successfully saved (asynchronous; runs in a new thread).
- **`on document modified`** --- A document was saved or removed (synchronous).
- **`on document remove`** / **`on document removed`** --- A document is being or was deleted.
- **`on document conflict`** --- A revision conflict was detected during save.

This means you can build notification systems, audit logs, cache invalidation, and other cross-cutting concerns as separate modules that simply listen for document events.

## When to Use Documents

Documents are the right choice whenever you need data to survive a server restart. Common patterns include:

- **Named singleton documents** for configuration and counters: `Document.get('page-hits', 'my-app')`
- **Custom Document subclasses** for structured domain models: `Job`, `RateCard`, `Guestbook`
- **View-backed listings** for dashboards and search results
- **Cargo-mirrored documents** for persistent reactive state: `Cargo.fromDocument(doc).inc()`
- **File storage** via the built-in attachment system

For transient, session-scoped data that does not need to persist, use `Cargo.fromStore()` or session-level state instead. Documents are for data that matters.

## Related Topics

- **[Cargo](cargo-overview.md)** --- Reactive data containers that can be mirrored to Documents for persistence.
- **[Alerts](alerts-overview.md)** --- The event system that powers document lifecycle hooks.
- **[Sessions & Clients](sessions-overview.md)** --- `ClientDocument` extends `Document` for user management.
