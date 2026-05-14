# Cargo: Reactive Data Containers

## What is Cargo?

Cargo is Spaceport's reactive data container -- a flexible key-value store that automatically propagates changes to connected Launchpad templates in real time. When you update a value in a Cargo instance, any template expression referencing that value re-renders instantly on the client via WebSocket, with no manual wiring required.

At its core, Cargo wraps a `Map` and adds three capabilities on top:

1. **Path-based access** -- read and write nested values using dot-separated paths like `'user.profile.theme'`
2. **Reactive synchronization** -- mutations automatically push updates to connected Launchpad templates
3. **Optional persistence** -- Cargo can mirror its contents to a CouchDB document, surviving server restarts

## The Three Modes

Cargo operates in one of three modes depending on how you create it.

### Local Cargo

Created with `new Cargo()` or `new Cargo(existingMap)`. Lives entirely in memory, scoped to whatever variable holds it. Useful for temporary computation, building up data structures, or passing reactive state within a single request lifecycle.

```groovy
def cart = new Cargo()
cart.set('items.0.name', 'Rocket Fuel')
cart.set('items.0.qty', 3)
cart.set('total', 29.99)

println cart.get('items.0.name')  // "Rocket Fuel"
```

### Store Cargo

Created with `Cargo.fromStore('name')`. Retrieves a named singleton from `Spaceport.store`, a server-wide `ConcurrentHashMap`. Store Cargo survives hot-reloads during development but does **not** persist across server restarts.

This mode is ideal for caching expensive query results that should be shared across all requests.

```groovy
// Cache a CouchDB view result so templates can bind to it reactively
def jobList = Cargo.fromStore('job-list')
jobList.set(View.get('jobs/active', 'main-db').rows)
```

Any Launchpad template bound to `jobList` will re-render when you call `set()` again with fresh data.

### Document Cargo

Created with `Cargo.fromDocument(doc)` or accessed directly via `doc.cargo` on any Document instance. This mode mirrors the Cargo contents to the document's `cargo` field in CouchDB. Changes auto-save with a short debounce, so data persists across server restarts.

```groovy
def statsDoc = Document.get('site-stats', 'my-db')
def stats = Cargo.fromDocument(statsDoc)

// Increment a page-hit counter -- auto-saved to CouchDB
stats.inc('pageViews')
```

Every Spaceport Document already has a built-in `cargo` property, so you can also write:

```groovy
def doc = Document.get('site-stats', 'my-db')
doc.cargo.inc('pageViews')
```

## Reactivity with Launchpad

Cargo's real power emerges when paired with Launchpad templates. Use the `${{ }}` reactive syntax to bind template expressions to Cargo values. When the Cargo changes server-side, the client updates automatically.

```html
<!-- In a .ghtml Launchpad template -->
<p>Page views: ${{ stats.get('pageViews') }}</p>
<p>Current theme: ${{ userPrefs.get('theme') }}</p>
```

When `stats.inc('pageViews')` runs on the server (from a route handler, an Alert listener, or anywhere else), the displayed count updates on every connected client without a page refresh.

## The Dock: Per-Session Cargo

Every client session in Spaceport has a special Cargo instance called the **dock**. It acts as per-user session state:

- For **authenticated** users, the dock is backed by their `ClientDocument` in CouchDB, so it persists across sessions.
- For **unauthenticated** users, the dock lives in memory only.

Access the dock in route handlers via `r.context.dock` and in Launchpad templates as simply `dock`.

```groovy
// In a route handler
r.context.dock.set('lastVisited', '/dashboard')
r.context.dock.set('prefs.sidebar', 'collapsed')
```

```html
<!-- In a Launchpad template -->
<div class="${{ dock.get('prefs.sidebar') == 'collapsed' ? 'narrow' : 'wide' }}">
    ...
</div>
```

The dock is one of the most practical uses of Cargo -- it gives you reactive, per-user state with almost no setup.

## When to Use Each Mode

| Mode | Persists? | Shared? | Best For |
|---|---|---|---|
| Local (`new Cargo()`) | No | No | Temporary data, request-scoped state |
| Store (`Cargo.fromStore()`) | Survives hot-reload only | Yes, server-wide singleton | Caching view results, shared counters |
| Document (`Cargo.fromDocument()`) | Yes, in CouchDB | Yes, via document | Persistent counters, user prefs, durable state |
| Dock (`r.context.dock`) | Authenticated: yes. Otherwise: no | Per-session | Session state, UI preferences, wizard progress |

## Path Notation

Cargo supports dot-separated paths for nested access. Maps assigned as values are automatically coerced into nested Cargo instances, so the entire tree stays reactive.

```groovy
def c = new Cargo()
c.set('app.config.features.darkMode', true)
c.get('app.config.features.darkMode')  // true
c.exists('app.config.features')         // true
c.delete('app.config.features.darkMode')
```

This means you can organize Cargo data into logical namespaces without manually creating intermediate maps.

## Next Steps

- **[Cargo API Reference](cargo-api.md)** -- complete method reference for all operations
- **[Cargo Internals](cargo-internals.md)** -- how the reactive synchronization mechanism works under the hood
- **[Cargo Examples](cargo-examples.md)** -- real-world patterns drawn from production Spaceport applications
