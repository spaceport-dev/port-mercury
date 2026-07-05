# Sessions & Client Management — Overview

## What Are Sessions in Spaceport?

Spaceport manages user sessions through a **Client** object that represents a browser session. Every visitor to your application — whether logged in or anonymous — gets a Client. This Client follows them across page loads, WebSocket connections, and even between authenticated and unauthenticated states.

The session system is built around three core pieces:

- **Client** — The in-memory session object. One per browser session, identified by a cookie.
- **ClientDocument** — A CouchDB-backed user record. One per registered user. Contains profile data, permissions, and credentials.
- **Dock** — A per-session Cargo container for storing arbitrary session state.

## How Sessions Work

When a browser makes its first HTTP request to your Spaceport application, the framework automatically creates a `spaceport-uuid` cookie and assigns a new Client object. No configuration is needed — sessions are always active.

```
Browser makes request
  → Spaceport checks for spaceport-uuid cookie
    → Cookie missing? Create new Client + set cookie
    → Cookie found? Look up existing Client
  → Client is available as r.context.client in routes
```

The `spaceport-uuid` cookie is:
- **HttpOnly** — JavaScript cannot read it, preventing XSS-based session theft
- **SameSite=Lax** — not sent on cross-site subrequests, defending against CSRF while still working on normal links into your app
- **Secure** in production — only sent over HTTPS
- **Persistent** — defaults to 60 days, configurable in your manifest

## Client vs. ClientDocument

These two classes serve different purposes and it is important to understand the distinction:

| | Client | ClientDocument |
|---|---|---|
| **Represents** | A browser session | A registered user account |
| **Lifecycle** | Created on first visit | Created during user registration |
| **Storage** | In-memory only | CouchDB `users` database |
| **Multiplicity** | One per browser/cookie | One per user account |
| **Data** | Session state, socket refs | Profile, permissions, password |

A single user can have multiple Clients (e.g., logged in on their phone and laptop). A single Client starts anonymous and becomes linked to a ClientDocument upon successful authentication.

## Authentication

Authentication connects a Client to a ClientDocument. The typical flow looks like this:

```groovy
// In a login route handler
@Alert('on /login POST')
static _login(HttpResult r) {
    def authorized = Client.getAuthenticatedClient(
        r.context.data.username,
        r.context.data.password
    )

    if (authorized) {
        // Link the session cookie to the authenticated client
        authorized.attachCookie(r.context.cookies.'spaceport-uuid' as String)
        r.setRedirectUrl('/dashboard')
    } else {
        // Authentication failed
        r.setRedirectUrl('/login?error=invalid')
    }
}
```

Once authenticated, the Client gains access to the user's ClientDocument (profile, permissions) and its Dock becomes database-backed instead of memory-only.

Logging out is equally straightforward. The `deauthenticate` verb detaches the session cookie and, when it was the client's last session, fully tears the Client down — clears the authenticated flag, closes its WebSockets, and removes it from the registry:

```groovy
@Alert('on /logout hit')
static _logout(HttpResult r) {
    r.client.deauthenticate(r.context.cookies.'spaceport-uuid' as String)
    r.setRedirectUrl('/')
}
```

For a "sign out of all devices" action, `client.deauthenticateAll()` logs the user out of every session at once.

There is also a third verb for the opposite direction: `client.authenticate(userId)` marks an already-bound Client as authenticated **without a password**. It exists for restoring sessions that were validated some other way — for example, from a durable session store after a server restart, or from an SSO assertion. Because it skips credential checks, it should only be called after your code has already validated the session.

## The Dock: Session-Scoped Storage

Every Client has a **Dock** — a Cargo container scoped to that specific session. Think of it as a per-session key-value store that supports Spaceport's reactive data features.

```groovy
// In a route handler — dock is available via r.context.dock
@Alert('on /cart/add hit')
static _addToCart(HttpResult r) {
    def dock = r.context.dock
    dock.append('cart_items', r.context.data.item)
}
```

The Dock behaves differently based on authentication state:

- **Anonymous users** — Dock data lives in memory only. It disappears when the server restarts.
- **Authenticated users** — Dock data is persisted to CouchDB under the user's document. It survives restarts and is available across devices (scoped per session UUID).

In Launchpad templates, the dock is available directly:

```html
<p>Items in cart: ${dock.get('cart_items')?.size() ?: 0}</p>
```

## Permissions

Spaceport includes a simple string-based permission system on ClientDocument. Permissions are arbitrary strings that your application defines and checks:

```groovy
// Check permissions in a route
@Alert('on /admin hit')
static _admin(HttpResult r) {
    if (!r.client.document?.hasPermission('administrator')) {
        r.writeToClient('Access denied', 403)
        return
    }
    // ... render admin page
}
```

There is no built-in role hierarchy — permissions are flat strings. Your application decides what they mean and how to organize them. This keeps the framework flexible while giving you the building blocks for simple or complex authorization schemes.

## When to Use What

| Need | Use |
|---|---|
| Track a visitor's session | `r.context.client` (automatic) |
| Store temporary session data | `r.context.dock` |
| Create a user account | `ClientDocument.createNewClientDocument(userId, password)` |
| Log a user in | `Client.getAuthenticatedClient(userId, password)` |
| Log a user out | `client.deauthenticate(cookie)` |
| Log a user out everywhere | `client.deauthenticateAll()` |
| Restore an already-validated session | `client.authenticate(userId)` |
| Check login status | `client.isAuthenticated()` |
| Manage user permissions | `client.document.hasPermission()`, `addPermission()`, `removePermission()` |
| Access user profile | `client.document.getName()`, `getEmail()`, etc. |
