# Sessions & Client Management — API Reference

## Client

**Package:** `spaceport.personnel`

The Client class represents a browser session. Clients are identified by the `spaceport-uuid` cookie and tracked in the global ClientRegistry.

### Static Methods

#### `Client.getNewClient()`

Creates a new Client with a generated UUID.

- **Returns:** `Client`

```groovy
def client = Client.getNewClient()
```

---

#### `Client.getNewClient(String cookie)`

Creates a new Client and immediately attaches the given cookie value.

- **Parameters:**
  - `cookie` — The `spaceport-uuid` cookie value to associate
- **Returns:** `Client`

```groovy
def client = Client.getNewClient(cookies.'spaceport-uuid')
```

---

#### `Client.getClient(String clientId)`

Retrieves an existing Client by its internal ID (the user document `_id` for authenticated clients). If no registered Client matches, a new one is created and registered with the given ID.

- **Parameters:**
  - `clientId` — The client/user ID
- **Returns:** `Client` (never `null` for a non-null ID; a new Client is created if none exists)
- **Note:** More than one registered Client can share a `user_id` (e.g., a durable session restored on one cookie while the same user is logged in on another). When that happens, this method prefers a Client with a live WebSocket connection (`isActive()`), falling back to the first match — so a server-initiated push by user ID reaches the session that can actually receive it.

```groovy
def client = Client.getClient(userId)
```

---

#### `Client.getAuthenticatedClient(String userId, String password)`

Authenticates a user and returns the associated Client. Queries the CouchDB `user-views/authenticate` view, verifies the password with BCrypt, and fires authentication alerts.

- **Parameters:**
  - `userId` — The user ID (case-sensitive)
  - `password` — The plaintext password to verify
- **Returns:** `Client` if authentication succeeds, `null` if it fails
- **Alerts fired:**
  - `'on client auth'` with `[userID, client]` on success (can be cancelled)
  - `'on client auth failed'` with `[userID, exists]` on failure

```groovy
def client = Client.getAuthenticatedClient('jeremy', 'hunter2')
if (client) {
    client.attachCookie(cookies.'spaceport-uuid')
}
```

---

#### `Client.getClientByCookie(String cookie)`

Finds the Client currently associated with a given cookie value.

- **Parameters:**
  - `cookie` — The `spaceport-uuid` cookie value
- **Returns:** `Client` or `null`

```groovy
def client = Client.getClientByCookie(cookies.'spaceport-uuid')
```

---

#### `Client.getClientByHandler(SocketHandler socketHandler)`

Finds the Client associated with a given WebSocket handler.

- **Parameters:**
  - `socketHandler` — The WebSocket handler instance
- **Returns:** `Client` or `null`

---

### Instance Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `created` | `long` | read | Timestamp (millis) when the Client was created |
| `user_id` | `String` | private | The user document ID; access via `getUserID()`, set via `authenticate(userId)` |
| `cargo` | `Cargo` | read (final) | The Client's root Cargo container |
| `authenticated` | `boolean` | private | Authentication state; access via `isAuthenticated()`, set via `authenticate(userId)` / cleared via `deauthenticate(cookie)` — never by direct field access |
| `authenticationCookies` | `List<String>` | read | Cookie values linked to this Client |
| `sockets` | `Set<WeakReference<SocketHandler>>` | read | Active WebSocket connections |

### Instance Methods

#### `isAuthenticated()`

Returns whether this Client has been authenticated against a ClientDocument.

- **Returns:** `boolean`

```groovy
if (client.isAuthenticated()) {
    // user is logged in
}
```

---

#### `getUserID()`

Returns the user document ID for an authenticated Client.

- **Returns:** `String` or `null`

---

#### `getDock(String spaceportUUID)`

Returns the Dock (session-scoped Cargo) for a given session UUID.

- **Parameters:**
  - `spaceportUUID` — The `spaceport-uuid` cookie value
- **Returns:** `Cargo`
- **Behavior:**
  - **Authenticated clients:** Returns a `Cargo.fromDocument(document)` stored under `"spaceport-docks.${spaceportUUID}"` — data is persisted to CouchDB.
  - **Unauthenticated clients:** Returns a child of the client's in-memory `cargo` under the same key — data is volatile.

```groovy
def dock = client.getDock(cookies.'spaceport-uuid')
dock.set('theme', 'dark')
```

---

#### `getDocument()`

Returns the ClientDocument for an authenticated Client.

- **Returns:** `ClientDocument` or `null` if not authenticated

```groovy
def doc = client.getDocument()
def name = doc?.getName()
```

---

#### `attachCookie(String cookie)`

Associates a `spaceport-uuid` cookie value with this Client. Used after authentication to link the browser session.

- **Parameters:**
  - `cookie` — The cookie value to attach

```groovy
client.attachCookie(cookies.'spaceport-uuid')
```

---

#### `removeCookie(String cookie)`

Disassociates a cookie value from this Client. This is a low-level primitive — it only drops the cookie from the Client's cookie list, leaving the authenticated flag, WebSockets, and registry entry untouched. It is also used internally by `attachCookie()` to reassign a cookie between Clients, which must not log anyone out.

For logging a user out, use `deauthenticate(cookie)` instead, which performs the full session teardown.

- **Parameters:**
  - `cookie` — The cookie value to remove

```groovy
client.removeCookie(cookies.'spaceport-uuid')
```

---

#### `authenticate(String userId)`

Marks this already-bound Client as authenticated for the given user, **in place and without a password**. This is the supported way to restore a session that was validated out-of-band — for example, from a durable/persisted session store after a restart, or from an SSO assertion.

Because it mutates the Client the framework already bound to the request's cookie, the cookie↔Client binding stays intact and later requests keep resolving to the same object via `getClientByCookie()`. It deliberately does **not** mint a new Client (as `getClient(userId)` would) and does **not** verify credentials (as `getAuthenticatedClient(userId, password)` does).

- **Parameters:**
  - `userId` — The authenticated user's ID
- **Security:** This sets authenticated state without checking credentials. Only call it after your code has already validated the session (a valid, non-revoked, non-expired session record; a verified SSO assertion; etc.).

```groovy
// Restoring a session validated against a persisted session store
if (!client.isAuthenticated()) {
    client.authenticate(userId)
    r.context.dock = client.getDock(uuid)
}
```

---

#### `deauthenticate(String cookie)`

Logs this Client out of a single session. Calls `removeCookie(cookie)`, and if that was the Client's **last** cookie, performs the full teardown: sets `authenticated = false`, closes all WebSockets via `closeSockets()`, and removes the Client from the ClientRegistry.

If other cookies remain (the user is still logged in on another device), the Client stays authenticated and registered — only the one session is detached.

- **Parameters:**
  - `cookie` — The session cookie (`spaceport-uuid`) to log out
- **Behavior:**
  - Safe to call during a request: the in-flight request already holds its Client reference, so registry removal does not affect it. The browser's next request finds no matching cookie and is bound to a fresh anonymous Client.
  - After full teardown, `Client.getClient(userId)` no longer resurfaces the logged-out Client — this is intended.

```groovy
@Alert('on /logout hit')
static _logout(HttpResult r) {
    r.client.deauthenticate(r.context.cookies.'spaceport-uuid' as String)
    r.setRedirectUrl('/')
}
```

---

#### `deauthenticateAll()`

Logs this Client out of every session ("log out everywhere"). Unconditionally clears all cookies, sets `authenticated = false`, closes all WebSockets, and removes the Client from the ClientRegistry.

```groovy
// "Sign out of all devices" action
client.deauthenticateAll()
```

---

#### `closeSockets()`

Closes and detaches all of this Client's WebSocket connections. Called automatically during logout (`deauthenticate` / `deauthenticateAll`) so a de-authenticated session does not keep a live authenticated socket open. Rarely needs to be called directly.

---

#### `attachSocketHandler(SocketHandler handler)`

Registers a WebSocket handler with this Client. Called internally when a WebSocket connection is established.

- **Parameters:**
  - `handler` — The WebSocket handler

---

#### `removeSocketHandler(SocketHandler handler)`

Removes a WebSocket handler from this Client. Called internally when a WebSocket connection closes.

- **Parameters:**
  - `handler` — The WebSocket handler

---

## ClientDocument

**Package:** `spaceport.personnel`
**Extends:** `Document`
**Database:** `users`

A ClientDocument represents a registered user stored in CouchDB. It contains profile information, credentials (BCrypt-hashed), permissions, and notes.

### Static Methods

#### `ClientDocument.createNewClientDocument(String userId, String password)`

Creates a new user document in the `users` database with a BCrypt-hashed password.

- **Parameters:**
  - `userId` — The user ID (becomes the document `_id`)
  - `password` — The plaintext password (will be hashed with BCrypt, using the configured work factor — see `changePassword()`)
- **Returns:** `ClientDocument`

```groovy
def user = ClientDocument.createNewClientDocument('jeremy', 's3cureP@ss')
user.setName('Jeremy')
user.setEmail('jeremy@example.com')
user.addPermission('administrator')
```

---

#### `ClientDocument.getClientDocument(String userId)`

Retrieves an existing user document by ID.

- **Parameters:**
  - `userId` — The user ID
- **Returns:** `ClientDocument` or `null`

```groovy
def user = ClientDocument.getClientDocument('jeremy')
```

---

### Profile Methods

All profile setters apply XSS sanitization via `clean()` and automatically save the document to CouchDB.

#### `getName()` / `setName(String name)`

- **Returns:** `String`

```groovy
user.setName('Jeremy Smith')
def name = user.getName()
```

---

#### `getEmail()` / `setEmail(String email)`

- **Returns:** `String`

```groovy
user.setEmail('jeremy@example.com')
```

---

#### `getPhone()` / `setPhone(String phone)`

- **Returns:** `String`

---

#### `getStatus()` / `updateStatus(String status)`

- **Returns:** `String`
- Note: The setter is named `updateStatus`, not `setStatus`.

```groovy
user.updateStatus('Away')
```

---

### Authentication Methods

#### `checkPassword(String password)`

Verifies a plaintext password against the stored hash. Supports both BCrypt hashes and legacy plaintext comparison.

- **Parameters:**
  - `password` — The plaintext password to check
- **Returns:** `boolean`

---

#### `changePassword(String newPassword, boolean hash = true)`

Changes the user's password. By default, hashes the new password with BCrypt before storing.

The bcrypt work factor is configurable via the manifest (`auth` → `bcrypt cost`), defaulting to `10`. Values in `4`–`31` are honored; anything else — unset, non-numeric, or out of range — falls back to `10`. Changing the configured cost never invalidates existing hashes — each stored hash records the cost it was created with, so existing passwords keep verifying and only newly set passwords use the new cost. See the [Manifest API Reference](manifest-api.md) for details.

- **Parameters:**
  - `newPassword` — The new plaintext password
  - `hash` — Whether to BCrypt-hash the password (default: `true`). Pass `false` only for pre-hashed values.

```groovy
user.changePassword('newS3cureP@ss')
```

```yaml
# In the config manifest — raise the work factor for new passwords
auth:
  bcrypt cost: 12
```

---

### Permission Methods

Permissions are stored as a `List<String>` on the document. All permission methods automatically save the document.

#### `hasPermission(String permission)`

Checks whether the user has a specific permission.

- **Parameters:**
  - `permission` — The permission string to check
- **Returns:** `boolean`

```groovy
if (user.hasPermission('administrator')) {
    // grant access
}
```

---

#### `addPermission(String permission)`

Adds a permission to the user.

- **Parameters:**
  - `permission` — The permission string to add

```groovy
user.addPermission('editor')
```

---

#### `removePermission(String permission)`

Removes a permission from the user.

- **Parameters:**
  - `permission` — The permission string to remove

```groovy
user.removePermission('editor')
```

---

### Notes Methods

Notes are stored as a timestamped map on the document. All note content is sanitized via `clean()`.

#### `addNote(String note)`

Adds a note with an automatic timestamp key. The note content is sanitized via `clean()`.

- **Parameters:**
  - `note` — The note text (will be HTML-sanitized)

```groovy
user.addNote('Verified email address')
```

---

#### `fetchNote(String key)`

Retrieves a note by its timestamp key.

- **Parameters:**
  - `key` — The note's timestamp key
- **Returns:** `Map`

---

#### `updateNote(def note)`

Updates an existing note (matched by its `key` property). The note content is sanitized via `clean()` and an `updated` timestamp is set automatically.

- **Parameters:**
  - `note` — A map containing the note data, must include a `key` field matching the original timestamp key

---

## ClientRegistry

**Package:** `spaceport.personnel`

Tracks all active Client instances in a `CopyOnWriteArrayList`. Clients are added when created and removed only by an explicit logout — `deauthenticate(cookie)` (when the last cookie is detached) or `deauthenticateAll()`. There is no automatic expiration or eviction, so sessions that never log out remain registered for the lifetime of the application.

The registry is used internally by `Client.getClient()`, `Client.getClientByCookie()`, and `Client.getClientByHandler()` to look up clients.

---

## Authentication Alerts

The authentication system fires alerts that your application can listen to:

### `'on client auth'`

Fired when authentication succeeds, before the Client is returned.

- **Arguments:** `[String userID, Client client]`
- **Cancellable:** Yes — if a listener sets `result.cancelled = true`, authentication is rejected and `getAuthenticatedClient()` returns `null`.

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result

class AuthGuard {

    @Alert('on client auth')
    static _checkBanned(Result r) {
        if (isBannedUser(r.context.userID)) {
            r.cancelled = true
        }
    }
}
```

### `'on client auth failed'`

Fired when authentication fails (wrong password or user not found).

- **Context properties:** `userID` (String), `exists` (boolean)
  - `exists` — `true` if the user ID was found but the password was wrong; `false` if the user ID does not exist.

```groovy
@Alert('on client auth failed')
static _logFailedAuth(Result r) {
    if (r.context.exists) {
        println "Failed login attempt for user: ${r.context.userID}"
    }
}
```

---

## Cookie Configuration

The `spaceport-uuid` cookie behavior is configured in your manifest:

```yaml
http:
  spaceport cookie expiration: 5184000  # seconds (default: 60 days)
```

| Attribute | Value |
|---|---|
| Name | `spaceport-uuid` |
| Value | Random UUID |
| Path | `/` |
| HttpOnly | `true` |
| SameSite | `Lax` |
| Secure | `true` in production, `false` in debug mode |
| Max-Age | Configurable, default 60 days (5,184,000 seconds) |

---

## Route Context Access

In route handlers, sessions are available through the request context:

| Expression | Returns | Description |
|---|---|---|
| `r.context.client` | `Client` | The current session's Client |
| `r.context.dock` | `Cargo` | The current session's Dock |
| `client` | `Client` | Shorthand (if delegated) |
| `client.document` | `ClientDocument` | The user's document (authenticated only) |
| `client.isAuthenticated()` | `boolean` | Whether the session is logged in |

In Launchpad templates, the following variables are available:

| Variable | Type | Description |
|---|---|---|
| `client` | `Client` | The current Client |
| `dock` | `Cargo` | The session Dock |
