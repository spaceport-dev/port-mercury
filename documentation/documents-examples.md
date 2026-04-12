# Documents (CouchDB ORM) --- Examples

Practical patterns for working with Documents, drawn from real Spaceport applications. Each section shows a pattern, explains when to use it, and provides code you can adapt.

---

## Custom Document Subclass

The most common pattern: extend `Document` with typed properties, a `customProperties` list, and a `type` field for view filtering.

```groovy
package documents

import spaceport.computer.memory.physical.Document

class Guestbook extends Document {

    // Tell Spaceport which properties to persist to CouchDB
    def customProperties = ['info', 'participants']

    // Type tag for CouchDB views
    def type = 'guestbook'

    // Typed properties with defaults
    List<ParticipantSchema> participants = []
    GuestbookInfoSchema info = new GuestbookInfoSchema()

    // Inner schema classes for strong typing
    static class ParticipantSchema {
        String name
        String email
        Boolean public_email
        String message
        Long time_signed
        String cookie
    }

    static class GuestbookInfoSchema {
        String name
        String email
        String owner_cookie
        Boolean open
    }
}
```

**Key points:**
- Every property in `customProperties` must have a matching field declaration on the class.
- Properties not listed in `customProperties` (and not in the base Document set) will not be saved to CouchDB.
- Inner static schema classes give you type safety and IDE autocomplete when working with nested data.

---

## Extending customProperties in a Subclass

When you extend a custom Document class, use an instance initializer block to add properties to the parent's list without replacing it.

```groovy
package data

class VoiceJob extends Job {

    {
        // Extend the parent's customProperties list
        customProperties = customProperties + [
            'directionNotes',
            'engineeringNotes',
            'myNotes',
            'rateType'
        ]
    }

    String directionNotes = ''
    String engineeringNotes = ''
    String myNotes = ''
    String rateType = ''
}
```

This pattern is important because `customProperties` is a list, not a set. Using `+` creates a new list containing both the parent's properties and the subclass additions. If you instead assigned a fresh list, you would lose the parent's properties and those fields would stop being saved.

---

## Factory Methods

Wrap `getNew()` in a static factory method that sets the `type` and any initialization logic. This keeps document creation consistent and encapsulates your database name.

```groovy
class Job extends Document {

    static Job makeNew() {
        def job = getNew('jobs') as Job
        job.type = 'job'
        job.dateCreated = System.currentTimeMillis()
        job.updates.put(System.currentTimeMillis(), 'Job created.')
        return job
    }

    // Convenience wrapper for fetching
    static Job getIfExists(String id) {
        return getIfExists(id, 'jobs') as Job
    }
}
```

An alternative using Groovy's `tap` for a more fluent style:

```groovy
class RateCard extends Document {

    static RateCard makeNew() {
        def card = getNew('rate-cards').tap {
            type = 'rate-card'
            updates.put(System.currentTimeMillis(), 'Rate card created.')
            save()
        }
        return card as RateCard
    }
}
```

---

## Self-Initializing Views

Register CouchDB views at application startup using `@Alert('on initialized')` combined with `setViewIfNeeded()`. This ensures views exist before any code tries to query them, and avoids unnecessary writes on subsequent restarts.

```groovy
import spaceport.Spaceport
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.computer.memory.physical.Document
import spaceport.computer.memory.physical.ViewDocument

class Job extends Document {

    @Alert('on initialized')
    static _init(Result r) {
        // Ensure database exists
        Spaceport.main_memory_core.createDatabaseIfNotExists('jobs')

        // Register view (only writes to CouchDB if the function changed)
        ViewDocument.get('jobs', 'jobs').setViewIfNeeded(
            'list-jobs', /* language=javascript */ '''
                function(doc) {
                    if (doc.type === 'job') {
                        emit(doc.states.created, {
                            jobId:       doc.jobId,
                            client:      doc.client,
                            status:      doc.status,
                            dateCreated: doc.dateCreated || doc.states.created
                        });
                    }
                }
            '''
        )
    }
}
```

**Tips:**
- The `/* language=javascript */` comment is an IntelliJ hint for syntax highlighting inside the string.
- Only emit the fields you actually need in listings. Keep views lightweight; load the full document only when needed.
- Chain multiple `setViewIfNeeded()` calls to register several views on the same design document.

---

## Typed View Results (Row/Value Pattern)

Define inner `Row` and `Value` classes that match the shape of your view's `emit()` output. This gives you compile-time type safety when consuming view results.

```groovy
class Job extends Document {

    // Matches the shape of the 'list-jobs' view
    static class Row {
        String key    // The emit() key (states.created timestamp)
        String id     // CouchDB document _id
        Value  value  // The emit() value object

        static class Value {
            String jobId
            String client
            String status
            Long dateCreated
        }

        // Load the full document on demand
        Job getDocument() {
            return Job.getIfExists(id, 'jobs') as Job
        }
    }

    // Query the view and return typed results
    static List<Row> getAllJobs() {
        return View.get('jobs', 'list-jobs', 'jobs').rows as List<Row>
    }

    // Filter helpers
    static List<Row> getActiveJobs() {
        return getAllJobs().findAll { it.value.status != 'Complete' }
    }
}
```

Usage in a route handler or template:

```groovy
def jobs = Job.getAllJobs()
jobs.each { row ->
    println "${row.value.jobId}: ${row.value.client} - ${row.value.status}"
}

// Load the full document on demand via the Row's getDocument() method
def fullJob = jobs.first().getDocument()
fullJob.fields.notes = 'Updated from view listing'
fullJob.save()
```

---

## View-Based Querying with Cargo.fromStore

Combine View queries with `Cargo.fromStore()` for reactive view results that automatically propagate to connected Launchpad templates when `_update()` is called.

```groovy
import spaceport.computer.memory.physical.View
import spaceport.computer.memory.virtual.Cargo

class Job extends Document {

    // Returns REACTIVE Cargo wrapping the current job list
    static Cargo getCurrentJobs() {
        def current = Cargo.fromStore('job-list')
        current.set(View.get('jobs', 'list-jobs', 'jobs').rows as List<Row>)
        return current
    }
}
```

In a Launchpad template, this Cargo can be used with reactive bindings:

```html
<%
    def jobs = Job.getCurrentJobs()
%>
<ul>
    ${{ jobs.get().combine { """
        <li>${ it.value.jobId } - ${ it.value.status }</li>
    """ } }}
</ul>
```

When a job is saved elsewhere and `Job.currentJobs._update()` is called, the reactive binding re-renders the list for all connected clients.

---

## Singleton / Named Documents

Use `Document.get()` with a well-known ID for configuration objects, counters, and other singleton data. Since `get()` creates the document if it does not exist, you always get a valid object.

```groovy
// Page hit counter using Cargo.fromDocument for auto-save
def hits = Cargo.fromDocument(Document.get('page-hits', 'my-app'))
hits.inc()  // Atomically increments and auto-saves to CouchDB
```

```groovy
// Application configuration document
def config = Document.get('app-config', 'my-app')
config.fields.maintenanceMode = false
config.fields.maxUploadSize = 10_000_000
config.save()
```

---

## The save() + _update() Pattern

After modifying and saving a Document, call `_update()` to notify Launchpad that any reactive bindings referencing this document should re-render. This is essential for real-time UI updates.

```groovy
class Guestbook extends Document {

    void addParticipant(String name, String email, Boolean publicEmail,
                        String message, String cookie) {
        if (!isOpen()) return

        def participant = new ParticipantSchema().tap {
            it.name = name.clean()
            it.message = message.clean()
            it.email = email.clean()
            it.public_email = publicEmail
            it.time_signed = System.currentTimeMillis()
            it.cookie = cookie.clean()
        }

        participants.add(participant)
        save()         // Persist to CouchDB
        this._update() // Notify Launchpad to re-render reactive bindings
    }

    void removeParticipant(String cookie) {
        participants.removeIf { it.cookie == cookie }
        save()
        this._update()
    }
}
```

**When to use `_update()`:** Whenever a Document change should be immediately reflected in the UI for connected clients. Without `_update()`, the data is saved but the UI does not re-render until the next full page load.

For Cargo-based reactive lists, call `_update()` on the Cargo object:

```groovy
// After saving a job, update the reactive job list
job.save()
Job.currentJobs._update()  // Re-renders any template using getCurrentJobs()
```

---

## Database Initialization

Ensure your database exists before any documents are accessed. The standard pattern uses `@Alert('on initialized')`:

```groovy
@Alert('on initialized')
static _init(Result r) {
    Spaceport.main_memory_core.createDatabaseIfNotExists('rate-cards')
}
```

This runs once when source modules are loaded (and again on hot-reload in debug mode). `createDatabaseIfNotExists()` is idempotent -- it only creates the database if it does not already exist.

---

## Notification Deduplication with on document save

Use the synchronous `on document save` alert to intercept saves before they hit CouchDB. This is useful for validation, deduplication, or injecting computed fields.

```groovy
class NotificationManager {

    @Alert('on document save')
    static _beforeSave(Result r) {
        def doc = r.context.document
        def type = r.context.type

        if (type == 'job') {
            // Inject a computed field before every job save
            doc.fields.'last-modified-by' = getCurrentUser()
        }
    }

    @Alert('on document saved')
    static _afterSave(Result r) {
        def doc = r.context.document
        def type = r.context.type

        // Send notification after save (runs in a new thread)
        if (type == 'job' && doc.status == 'Complete') {
            sendCompletionEmail(doc)
        }
    }
}
```

**Important timing distinction:**
- `on document save` runs **before** the CouchDB write, synchronously. You can modify the document here.
- `on document saved` runs **after** the write, asynchronously in a new thread. Use this for side effects that should not block the save.

---

## Working with Attachments

Upload and retrieve file attachments on documents.

```groovy
// Upload a file
def doc = Document.get('user-profile', 'users')
def fileId = doc.addAttachment(
    new File('/uploads/photo.jpg'),
    [label: 'Profile Photo', uploadedBy: 'admin']
)
doc.save()  // Must save after adding attachment
println "Attachment stored as: ${fileId}"

// Upload from base64 (e.g., from a form submission)
def fileId2 = doc.addBase64Attachment(base64String, [
    name: 'document.pdf',
    type: 'application/pdf',
    uploadedBy: 'user-123'
])
doc.save()

// Download an attachment
def outputStream = new ByteArrayOutputStream()
def meta = doc.getAttachment(fileId, outputStream)
println "Content-Type: ${meta.content_type}, Size: ${meta.content_length}"

// Remove an attachment (saves to CouchDB immediately)
doc.removeAttachment(fileId)
```

---

## State Tracking

Use the built-in state machine for workflow tracking with automatic history.

```groovy
def job = Document.get('job-001', 'jobs') as Job

// Set state with details
job.setState('approval', 'pending', [requestedBy: 'alice'])

// Later, update the state (previous state moves to history)
job.setState('approval', 'approved', [approvedBy: 'bob', notes: 'Looks good'])

// Read current state
def current = job.getState('approval')
println current.value    // 'approved'
println current.details  // [approvedBy: 'bob', notes: 'Looks good']

// Read history (keyed by timestamp)
def history = job.getStateHistory('approval')
history.each { timestamp, state ->
    println "At ${new Date(timestamp as Long)}: ${state}"
}

job.save()
```

States are stored inside the document and persisted automatically on `save()`. The `states` map structure for each key is:

```
states['approval'] = [
    'current': [value: 'approved', details: [approvedBy: 'bob']],
    'history': [
        1706000000000: [value: 'pending', details: [requestedBy: 'alice']]
    ],
    'last-updated': 1706000060000
]
```

---

## Complete Example: Document Class with Views and Querying

A full example combining custom properties, view initialization, typed rows, factory methods, and reactive querying --- adapted from a production Spaceport application.

```groovy
package data

import spaceport.Spaceport
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.computer.memory.physical.Document
import spaceport.computer.memory.physical.View
import spaceport.computer.memory.physical.ViewDocument
import spaceport.computer.memory.virtual.Cargo

class RateCard extends Document {

    // -- Initialization --

    @Alert('on initialized')
    static _init(Result r) {
        Spaceport.main_memory_core.createDatabaseIfNotExists('rate-cards')

        ViewDocument.get('rate-cards', 'rate-cards').setViewIfNeeded(
            'list-rate-cards', /* language=javascript */ '''
                function(doc) {
                    emit(doc.name, doc)
                }
            '''
        )
    }

    // -- Factory --

    static RateCard makeNew() {
        def card = getNew('rate-cards').tap {
            type = 'rate-card'
            updates.put(System.currentTimeMillis(), 'Rate card created.')
            save()
        }
        return card as RateCard
    }

    static RateCard getIfExists(String id) {
        def doc = getIfExists(id, 'rate-cards')
        return doc ? doc as RateCard : null
    }

    // -- Reactive querying --

    static Cargo getCurrentRateCards() {
        def current = Cargo.fromStore('rate-cards')
        current.set(View.get('rate-cards', 'list-rate-cards', 'rate-cards').rows)
        return current
    }

    static RateCard getByName(String name) {
        def list = getCurrentRateCards().get()
        def row = list.find { it.key.toLowerCase() == name.toLowerCase() }
        return row ? get(row.id, 'rate-cards') as RateCard : null
    }

    // -- Schema --

    static class ProductCodeGroup {
        String name
        Double minimum
        Double rate
        List<ProductCode> codes = []
    }

    static class ProductCode {
        String code
        String description
        Double max
    }

    // -- Persisted Properties --

    def customProperties = ['name', 'description', 'base', 'minimum',
                            'rate', 'groups', 'overrides', 'agency']

    String name
    String description
    String base
    String agency
    Double minimum
    Double rate
    List<ProductCodeGroup> groups = []
    Map<String, Double> overrides = [:]
}
```
