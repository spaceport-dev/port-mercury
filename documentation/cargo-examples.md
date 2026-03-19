# Cargo Examples

Practical patterns drawn from real Spaceport applications. Each example shows a common use case with complete, working code.

## Page-Hit Counter

The simplest Document Cargo pattern: a persistent counter that increments on every page view and displays reactively.

```groovy
// In a route handler
@Alert('on /home hit')
static _home(HttpResult r) {
    def stats = Cargo.fromDocument(Document.get('site-stats', 'my-db'))
    stats.inc('pageViews')
    launchpad.assemble(['home.ghtml']).launch(r, 'wrapper.ghtml')
}
```

```html
<!-- home.ghtml -->
<p>This page has been viewed ${{ stats.get('pageViews') }} times.</p>
```

Because `stats` is a Document Cargo, the count persists across restarts, and every connected client sees the number update in real time.

For an even more compact inline version:

```groovy
Cargo.fromDocument(Document.get('stats', 'db')).inc('hits')
```

## View Caching with Store Cargo

CouchDB views can be expensive to query on every request. Use Store Cargo to cache the result and serve it reactively.

```groovy
// Refresh the cache (call on startup and when data changes)
def refreshJobList() {
    def jobs = Cargo.fromStore('job-list')
    jobs.set(View.get('jobs/active', 'main-db').rows)
}
```

```groovy
// In a route handler -- serve from cache
@Alert('on /jobs hit')
static _jobs(HttpResult r) {
    r.context.data.jobs = Cargo.fromStore('job-list')
    launchpad.assemble(['jobs.ghtml']).launch(r, 'wrapper.ghtml')
}
```

```html
<!-- jobs.ghtml -->
<ul>
    ${{ jobs.combine { id, job -> """
        <li>${job.title} - ${job.location}</li>
    """ } }}
</ul>
```

To refresh the cache when data changes, listen for the relevant alert:

```groovy
@Alert('on document saved')
static _onJobSaved(Result r) {
    if (r.context.document._id.startsWith('job:')) {
        refreshJobList()
    }
}
```

## Message Board with Auto-Incrementing Keys

Use `setNext()` to build an ordered message list without managing keys.

```groovy
@Alert('on /messages POST')
static _postMessage(HttpResult r) {
    def board = Cargo.fromDocument(Document.get('message-board', 'my-db'))
    board.setNext([
        from: r.client.document?.fields?.name,
        text: r.context.data.text,
        time: new Date().time
    ])
    r.writeToClient([success: true])
}
```

```html
<!-- messages.ghtml -->
<div class="messages">
    ${{ board.combine({ a, b -> a.value.time <=> b.value.time }, { k, msg -> """
        <div class="message">
            <strong>${msg.from}</strong>: ${msg.text}
        </div>
    """ }) }}
</div>
```

Messages appear on all connected clients as soon as they are posted -- no polling, no refresh.

## Notification Deduplication Flags

When processing document saves in an `@Alert` handler, you often need to ensure a side effect (like sending a notification) happens only once. Use Cargo flags with timestamps for atomic deduplication.

```groovy
@Alert('on document saved')
static _notifyOnStatusChange(Result r) {
    def doc = r.context.document
    if (doc.status == 'approved') {
        def flags = doc.cargo.'notifications-sent' as Cargo
        def flagKey = "approved-${doc._rev.split('-')[0]}"

        if (!flags.exists(flagKey)) {
            flags.set(flagKey, new Date().time)
            // Send the notification -- this block runs only once
            sendApprovalEmail(doc)
        }
    }
}
```

The flag key includes the revision number prefix to distinguish between different saves. Once set, the flag persists in the document's cargo, preventing duplicate notifications even if the alert fires again.

## Dock: Per-Session State

### Simple Preferences

```groovy
@Alert('on /toggle-sidebar hit')
static _toggleSidebar(HttpResult r) {
    r.context.dock.toggle('prefs.sidebarCollapsed')
    r.writeToClient([success: true])
}
```

```html
<!-- layout.ghtml -->
<aside class="${{ dock.get('prefs.sidebarCollapsed') ? 'collapsed' : 'expanded' }}">
    <!-- sidebar content -->
</aside>
```

### Multi-Step Wizard (State Machine)

Use the dock to track progress through a multi-step workflow like password reset.

```groovy
@Alert('on /reset-password/start POST')
static _startReset(HttpResult r) {
    def process = r.context.dock.'reset-process' as Cargo
    process.set('step', 'verify-email')
    process.set('email', r.context.data.email)
    process.set('code', 6.randomID())
    sendVerificationEmail(process.get('email'), process.get('code'))
    launchpad.assemble(['reset-verify.ghtml']).launch(r, 'wrapper.ghtml')
}

@Alert('on /reset-password/verify POST')
static _verifyCode(HttpResult r) {
    def process = r.context.dock.'reset-process' as Cargo
    if (r.context.data.code == process.get('code')) {
        process.set('step', 'new-password')
        launchpad.assemble(['reset-new-password.ghtml']).launch(r, 'wrapper.ghtml')
    } else {
        process.inc('attempts')
        r.context.data.error = 'Invalid code'
        launchpad.assemble(['reset-verify.ghtml']).launch(r, 'wrapper.ghtml')
    }
}

@Alert('on /reset-password/complete POST')
static _completeReset(HttpResult r) {
    def process = r.context.dock.'reset-process' as Cargo
    if (process.get('step') == 'new-password') {
        updatePassword(process.get('email'), r.context.data.password)
        process.clear()
        r.setRedirectUrl('/login')
    }
}
```

```html
<!-- reset-verify.ghtml -->
<p>Enter the code sent to ${{ process.get('email') }}</p>
<p>Attempts: ${{ process.get('attempts') ?: 0 }} / 3</p>
```

For authenticated users, the dock is backed by their ClientDocument, so if they close the browser and return, their wizard progress is preserved.

## Reactive Admin Flag Panel

Display and manage arbitrary Cargo flags with a reactive admin interface.

```groovy
@Alert('~on /admin/flags/([^/]+) hit')
static _flagPanel(HttpResult r) {
    def doc = Document.get(r.matches[0], 'my-db')
    r.context.data.docId = r.matches[0]
    r.context.data.flags = doc.cargo
    launchpad.assemble(['flag-panel.ghtml']).launch(r, 'wrapper.ghtml')
}

@Alert('~on /admin/flags/([^/]+)/([^/]+) DELETE')
static _deleteFlag(HttpResult r) {
    def doc = Document.get(r.matches[0], 'my-db')
    doc.cargo.delete(r.matches[1])
    r.writeToClient([success: true])
}
```

```html
<!-- flag-panel.ghtml -->
<table>
    <tr><th>Key</th><th>Value</th><th>Action</th></tr>
    ${{ flags.combine { key, value -> """
        <tr>
            <td>${key}</td>
            <td>${value}</td>
            <td>
                <button onclick="fetch('/admin/flags/${docId}/${key}', {method:'DELETE'})">
                    Remove
                </button>
            </td>
        </tr>
    """ } }}
</table>
```

Because the template uses `${{ }}` reactive syntax, the table updates live when flags are added or removed -- even if another admin modifies them simultaneously.

## Combining Cargo with Alerts for Real-Time Dashboards

Build a live dashboard that updates whenever underlying data changes.

```groovy
// On startup or via scheduled task: populate the dashboard cargo
def refreshDashboard() {
    def dash = Cargo.fromStore('dashboard')
    dash.set('activeUsers', View.get('users/active', 'db').rows.size())
    dash.set('pendingOrders', View.get('orders/pending', 'db').rows.size())
    dash.set('lastRefresh', new Date().format('HH:mm:ss'))
}

// Auto-refresh when relevant documents change
@Alert('on document saved')
static _onDataChange(Result r) {
    if (r.context.document._id.startsWith('user:') || r.context.document._id.startsWith('order:')) {
        refreshDashboard()
    }
}
```

```groovy
@Alert('on /dashboard hit')
static _dashboard(HttpResult r) {
    r.context.data.dash = Cargo.fromStore('dashboard')
    launchpad.assemble(['dashboard.ghtml']).launch(r, 'wrapper.ghtml')
}
```

```html
<!-- dashboard.ghtml -->
<div class="stats">
    <div class="stat">
        <span class="label">Active Users</span>
        <span class="value">${{ dash.get('activeUsers') }}</span>
    </div>
    <div class="stat">
        <span class="label">Pending Orders</span>
        <span class="value">${{ dash.get('pendingOrders') }}</span>
    </div>
    <div class="stat">
        <span class="label">Last Updated</span>
        <span class="value">${{ dash.get('lastRefresh') }}</span>
    </div>
</div>
```

Every connected browser viewing `/dashboard` sees the numbers update in real time as documents are saved -- no JavaScript polling code needed.

## Paginated Cargo Lists

Display large Cargo datasets with server-side pagination.

```groovy
@Alert('on /items hit')
static _itemList(HttpResult r) {
    def items = Cargo.fromStore('all-items')
    def page = (r.context.data.page ?: '1') as int
    def pageSize = 20

    def results = items.paginate(page, pageSize,
        { a, b -> b.value.date <=> a.value.date },   // newest first
        { k, v -> [id: k, title: v.title, date: v.date] }  // shape output
    )

    r.context.data.items = results
    r.context.data.page = page
    r.context.data.pageSize = pageSize
    r.context.data.total = items.size()
    launchpad.assemble(['item-list.ghtml']).launch(r, 'wrapper.ghtml')
}
```

## Typed Access for Form Processing

Use typed getters to safely read and process form-submitted data stored in Cargo.

```groovy
def settings = r.context.dock.'app-settings' as Cargo

// Read with type coercion
String name = settings.getString('display.name')
int maxItems = settings.getInteger('limits.maxItems')
boolean darkMode = settings.getBool('ui.darkMode')

// Read with defaults (no write-back)
int timeout = settings.getOrDefault('network.timeout', 30) as int

// Read with defaults (write-back, initializes if missing)
String locale = settings.getDefaulted('i18n.locale', 'en-US')
```

## See Also

- [Cargo Overview](cargo-overview.md) -- what Cargo is and the three modes
- [Cargo API Reference](cargo-api.md) -- complete method listing
- [Cargo Internals](cargo-internals.md) -- how the reactive mechanism works
- [Alerts Examples](alerts-examples.md) -- more patterns using `@Alert` with Cargo
- [Documents Overview](documents-overview.md) -- the Document class and its built-in `cargo` property
