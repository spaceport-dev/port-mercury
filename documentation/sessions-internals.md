# Sessions & Client Management — Internals

This document covers the internal implementation details of Spaceport's session system. It is intended for contributors and advanced users who need to understand how sessions work under the hood.

## Cookie Creation Pipeline

The `spaceport-uuid` cookie is created at the HTTP layer before any route handling occurs.

### HTTP Request Flow

When `HttpRequestHandler` processes an incoming request:

1. Parse the `Cookie` header and extract any existing `spaceport-uuid` value.
2. If no `spaceport-uuid` cookie is present:
   - Generate a new UUID via `UUID.randomUUID().toString()`
   - Build a `Set-Cookie` header with the following attributes:
     - `Path=/`
     - `HttpOnly`
     - `SameSite=Lax`
     - `Secure` (only if not in debug mode)
     - `Max-Age` from manifest config `http.spaceport cookie expiration` (default: 5,184,000 seconds / 60 days)
   - Add the cookie to the response
3. The cookie value is then used to resolve or create the Client for this request.

The header is constructed by the pure static helper `HttpRequestHandler.buildSessionCookieHeader(String uuid, int maxAge, boolean secure)` and emitted as a **raw `Set-Cookie` header** via `response.addHeader(...)` rather than through `response.addCookie(...)`. This is deliberate: the bundled Servlet 3.1 API's `javax.servlet.http.Cookie` has no way to express `SameSite` (that arrived in Servlet 6 / Jakarta), while Jetty 9.4 — the bundled server — honors the raw header directly. Keeping the construction in a pure helper also makes the security attributes unit-testable without a servlet container.

A production header looks like:

```
spaceport-uuid=<uuid>; Path=/; Max-Age=5184000; HttpOnly; SameSite=Lax; Secure
```

Debug mode drops only the `Secure` attribute (so the cookie works over `http://localhost`).

### WebSocket Upgrade

When a WebSocket connection is established, the upgrade HTTP request contains cookies. The server:

1. Reads `spaceport-uuid` from the upgrade request's cookies
2. Calls `Client.getClientByCookie(cookie)` to find the associated Client
3. Calls `client.attachSocketHandler(handler)` to register the WebSocket connection
4. The socket handler is stored as a `WeakReference<SocketHandler>` in the Client's `sockets` set

This means WebSocket connections are automatically associated with the same Client as the browser's HTTP requests, with no additional application code required.

## Client Resolution Pipeline

Every incoming request resolves a Client through this sequence:

```
Request arrives with spaceport-uuid cookie
  │
  ├─ Cookie present?
  │    ├─ Yes → Client.getClientByCookie(cookieValue)
  │    │    ├─ Client found → use it
  │    │    └─ Client not found → Client.getNewClient(cookieValue)
  │    │
  │    └─ No → generate UUID, Client.getNewClient(uuid), set cookie
  │
  └─ Client placed in request context as r.context.client
     Dock resolved as r.context.dock via client.getDock(spaceportUUID)
```

### ClientRegistry Lookup

`Client.getClientByCookie()` iterates through the `ClientRegistry`'s `CopyOnWriteArrayList<Client>` and checks each client's `authenticationCookies` list for a matching value. This is a linear scan — O(n) where n is the total number of Clients ever created.

## Authentication Internals

### CouchDB View Query

`Client.getAuthenticatedClient(userId, password)` authenticates against CouchDB:

1. Queries the `user-views/authenticate` view in the `users` database with `key: userId`
2. The view returns the user document row if the user ID exists
3. If no row is returned, authentication fails immediately

### Password Verification

Once the user document is found:

1. Instantiate a `ClientDocument` from the row
2. Call `clientDoc.checkPassword(password)`
3. `checkPassword()` uses `BCrypt.checkpw(password, storedHash)` if the stored value looks like a BCrypt hash
4. Falls back to plaintext string comparison for legacy accounts (pre-BCrypt migration)

### Alert Dispatch and Cancellation

After password verification succeeds:

1. A new Client is created (or an existing one is located)
2. `client.authenticated` is set to `true`
3. `client.user_id` is set to the user document ID
4. `Alerts.invoke('on client auth', [userID, client])` is fired
5. If any listener returns `[cancelled: true]`, authentication is rolled back:
   - `client.authenticated` is reset to `false`
   - `getAuthenticatedClient()` returns `null`
6. If no cancellation occurs, the authenticated Client is returned

On failure:

1. `Alerts.invoke('on client auth failed', [userID, exists])` is fired
2. `exists` is `true` if the user ID was found but the password was wrong
3. `exists` is `false` if no user document was found at all
4. `getAuthenticatedClient()` returns `null`

This alert-based cancellation allows applications to implement bans, IP restrictions, or multi-factor authentication checks without modifying the core auth code.

Note that the in-place marking in steps 2–3 (setting `authenticated` and `user_id` on an existing Client) is also exposed publicly as `client.authenticate(userId)`. Application code that restores an out-of-band-validated session — for example, from a durable session store — calls that method rather than touching the private fields.

### Logout Teardown

The logout verbs perform a staged teardown built on top of the `removeCookie` primitive:

1. `deauthenticate(cookie)` calls `removeCookie(cookie)`, dropping the cookie from `authenticationCookies`
2. If that was the Client's **last** cookie:
   - `authenticated` is set to `false`
   - `closeSockets()` closes and detaches every live WebSocket session
   - The Client is removed from `ClientRegistry.activeClients`
3. If other cookies remain (another device is still logged in), nothing else happens — the Client stays authenticated and registered

`deauthenticateAll()` skips the per-cookie logic: it clears all cookies and performs the full teardown unconditionally.

The registry removal matters for two reasons. A cookieless Client can no longer be resolved by `getClientByCookie()`, so leaving it registered would only lengthen the O(n) registry scans — and it would remain resolvable (still authenticated) via `getClient(userId)`, resurfacing a logged-out session to server-initiated pushes. Removal closes both gaps. Removal is concurrency-safe (`activeClients` is a `CopyOnWriteArrayList`), and the in-flight request is unaffected because it already holds its Client reference.

`closeSockets()` iterates a **snapshot** of the socket set: closing a session triggers `onWebSocketClose → removeSocketHandler` on the socket's own thread, which would otherwise mutate the set mid-iteration. Each still-connected handler is closed via `handler.getSession()?.close()` — the same mechanism `SocketResult` uses — and the set is then cleared.

## Dock Storage Model

The Dock is a per-session Cargo container. Its storage backend depends on whether the Client is authenticated.

### Authenticated Dock

```
ClientDocument (CouchDB)
  └─ "spaceport-docks"
       └─ "${spaceportUUID}"   ← Cargo.fromDocument(document)
            ├─ key1: value1
            ├─ key2: value2
            └─ ...
```

- Created via `Cargo.fromDocument(document)` which binds the Cargo to a path within the CouchDB document
- Reads and writes are persisted to the `users` database
- Each session UUID gets its own namespace, so a user logged in on two devices has two independent docks
- Data survives server restarts

### Unauthenticated Dock

```
Client (in-memory)
  └─ cargo (root Cargo)
       └─ "spaceport-docks.${spaceportUUID}"   ← child Cargo
            ├─ key1: value1
            └─ ...
```

- Created as a child path on the Client's in-memory `cargo` object
- No database backing — data is lost on server restart
- When a Client authenticates, the dock reference changes from memory-backed to document-backed. Any data stored in the anonymous dock is **not migrated** to the authenticated dock.

### Dock Access Path

In route handlers, `r.context.dock` is resolved by:

1. Getting the current Client from the request context
2. Reading the `spaceport-uuid` cookie value from the request
3. Calling `client.getDock(spaceportUUID)`
4. The result is placed in the context and also made available to Launchpad templates as `dock`

## ClientRegistry Implementation

### Data Structure

```groovy
class ClientRegistry {
    static final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>()
}
```

The `CopyOnWriteArrayList` provides thread-safe iteration without explicit locking, which is important since client lookups happen on every HTTP request. The trade-off is that writes (adding new clients) are more expensive because they copy the entire list.

### No Automatic Eviction

The ClientRegistry removes Clients only on explicit logout — `deauthenticate(cookie)` unregisters a Client when its last cookie is detached, and `deauthenticateAll()` unregisters unconditionally. Beyond that, there is no automatic cleanup. This is a deliberate simplicity trade-off:

- Clients that never log out accumulate for the lifetime of the JVM process
- There is no expiration, no LRU eviction, no periodic cleanup
- For applications with moderate traffic, this is not a problem — Client objects are lightweight
- For high-traffic applications or long-running servers, memory usage will grow proportionally to the number of unique visitors who never log out

### Lookup Methods

All lookups are linear scans over the full list:

| Method | Scans | Matches on |
|---|---|---|
| `getClient(clientId)` | All clients | `client.user_id == clientId` — prefers a match whose WebSocket is live (`isActive()`), else the first match |
| `getClientByCookie(cookie)` | All clients | `cookie in client.authenticationCookies` |
| `getClientByHandler(handler)` | All clients | `handler in client.sockets` (via WeakReference) |

The `getClient` socket preference exists because more than one registered Client can share a `user_id` — for example, a durable session restored onto one cookie's Client while the same user is already logged in on another cookie in the same process. For server-initiated lookups and pushes by user ID, the connected Client is the one that can actually receive the message. Single-match and no-match behavior is unchanged (no match still creates and registers a new Client).

## WebSocket Client Association

WebSocket handlers are stored as `WeakReference<SocketHandler>` in the Client's `sockets` set. This means:

- If a SocketHandler is garbage collected (e.g., the connection was closed and no other references exist), the WeakReference will return `null`
- The `sockets` set is not automatically cleaned of dead references
- `getClientByHandler()` must handle `null` dereferences when iterating
- A Client can have multiple simultaneous WebSocket connections (e.g., multiple browser tabs)

### Socket Lifecycle

1. **Connection opened:** `client.attachSocketHandler(handler)` adds a `WeakReference` to the set
2. **Messages flow:** The handler is used for transmissions and other WebSocket communication
3. **Connection closed:** `client.removeSocketHandler(handler)` removes the reference from the set
4. **Logout:** `client.closeSockets()` (called by `deauthenticate` / `deauthenticateAll`) closes every still-connected session and clears the set, so a logged-out session cannot keep a live authenticated socket
5. **If not explicitly removed:** The WeakReference will eventually return `null` after GC

## Thread Safety Considerations

- **ClientRegistry:** Thread-safe via `CopyOnWriteArrayList` — concurrent reads are lock-free, writes copy the backing array
- **Client.authenticationCookies:** Standard `ArrayList` — not inherently thread-safe, but typically only modified during login/logout (single-threaded per request)
- **Client.sockets:** `Set` of `WeakReference` — modifications happen during WebSocket open/close events
- **ClientDocument:** Extends `Document`, which handles CouchDB read/write synchronization
- **Cargo (Dock):** Thread-safe via Cargo's internal synchronization mechanisms

## Security Notes

### Cookie Security

- `HttpOnly` prevents client-side JavaScript from reading the session cookie, mitigating XSS-based session theft
- `SameSite=Lax` keeps the cookie off cross-site subrequests — the enabling condition for CSRF and one of the vectors for planting a fixed session ID — while still sending it on top-level navigations, so following an external link into the app (or bouncing through a login redirect) arrives with the session intact. `Strict` would withhold the cookie on those navigations too, which is why `Lax` is the right balance (and the modern browser default)
- `Secure` flag is only set in production (non-debug) mode — during development, cookies work over plain HTTP
- The cookie value is a random UUID with no embedded information — session data is server-side only

### XSS Sanitization

All ClientDocument profile setters (`setName`, `setEmail`, `setPhone`, `updateStatus`) and note operations (`addNote`, `updateNote`) pass values through a `clean()` method that strips potentially dangerous HTML/script content. This is a defense-in-depth measure — the data should also be escaped at render time.

### Password Storage

- New passwords are hashed with BCrypt via `changePassword()`, using `BCrypt.gensalt(bcryptCost())`
- The work factor comes from the manifest (`auth` → `bcrypt cost`), resolved by `ClientDocument.resolveBcryptCost()`: values in `4`–`31` are honored; unset, non-numeric, or out-of-range values fall back to the default of `10`. The clamping means a config typo can neither throw (jBCrypt rejects costs outside 4–31) nor silently weaken hashing
- Changing the configured cost never invalidates stored hashes — bcrypt embeds the cost in every hash string (`$2a$<cost>$...`) and `BCrypt.checkpw` reads it from the stored hash, not from config. Only newly set passwords use the new cost
- `checkPassword()` supports both BCrypt hashes and legacy plaintext for migration scenarios
- There is no automatic migration from plaintext to BCrypt on login, and no automatic re-hash when a stored hash's cost is below the configured target — applications must handle either explicitly if needed
