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

Retrieves an existing Client by its internal ID (the user document `_id` for authenticated clients).

- **Parameters:**
  - `clientId` — The client/user ID
- **Returns:** `Client` or `null`

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
| `user_id` | `String` | private | The user document ID; access via `getUserID()` |
| `cargo` | `Cargo` | read (final) | The Client's root Cargo container |
| `authenticated` | `boolean` | private | Authentication state; access via `isAuthenticated()` |
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

Disassociates a cookie value from this Client. Used for logout.

- **Parameters:**
  - `cookie` — The cookie value to remove

```groovy
client.removeCookie(cookies.'spaceport-uuid')
```

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
  - `password` — The plaintext password (will be hashed with BCrypt)
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

- **Parameters:**
  - `newPassword` — The new plaintext password
  - `hash` — Whether to BCrypt-hash the password (default: `true`). Pass `false` only for pre-hashed values.

```groovy
user.changePassword('newS3cureP@ss')
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

Tracks all active Client instances in a `CopyOnWriteArrayList`. Clients are added when created but are **never automatically removed** — the registry grows for the lifetime of the application.

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
