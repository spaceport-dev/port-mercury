# Sessions & Client Management — Examples

Practical patterns drawn from real Spaceport applications, including port-mercury (starter template) and MadAve-Collab (production application).

---

## Basic Login and Logout

The simplest authentication pattern from port-mercury:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.personnel.Client

class Auth {

    // Login route
    @Alert('on /login POST')
    static _login(HttpResult r) {
        def username = r.context.data.username
        def password = r.context.data.password

        def authorized = Client.getAuthenticatedClient(username, password)

        if (authorized) {
            authorized.attachCookie(r.context.cookies.'spaceport-uuid' as String)
            r.setRedirectUrl('/dashboard')
        } else {
            r.setRedirectUrl('/login?error=1')
        }
    }

    // Logout route
    @Alert('on /logout hit')
    static _logout(HttpResult r) {
        r.client.removeCookie(r.context.cookies.'spaceport-uuid' as String)
        r.setRedirectUrl('/')
    }
}
```

Key points:
- `getAuthenticatedClient()` handles the CouchDB lookup and BCrypt verification
- You must call `attachCookie()` to link the session cookie to the authenticated Client
- `removeCookie()` is the only step needed for logout — the Client object remains in memory but is no longer associated with the browser's cookie

---

## Authorization Plug Pattern

A **plug** is a closure that runs before route handlers to enforce access control. This is the standard Spaceport pattern for protecting routes.

### Simple Authentication Check

```groovy
// Define the plug as a static closure
static authPlug = { HttpResult r ->
    if (!r.client.authenticated) {
        r.setRedirectUrl('/login')
        r.cancelled = true
        return false
    }
    return true
}

// Apply to route handlers using r.authorize()
@Alert('on /dashboard hit')
static _dashboard(HttpResult r) {
    if (!r.authorize(authPlug)) return
    launchpad.assemble(['dashboard.ghtml']).launch(r, 'wrapper.ghtml')
}

@Alert('on /profile hit')
static _profile(HttpResult r) {
    if (!r.authorize(authPlug)) return
    r.context.data.user = r.client.document
    launchpad.assemble(['profile.ghtml']).launch(r, 'wrapper.ghtml')
}
```

### Permission-Based Authorization

From port-mercury — requiring a specific permission:

```groovy
static adminPlug = { HttpResult r ->
    if (!r.client.authenticated ||
        !r.client.document?.hasPermission('spaceport-administrator')) {
        r.setRedirectUrl('/login')
        r.cancelled = true
        return false
    }
    return true
}

@Alert('on /admin/settings hit')
static _adminSettings(HttpResult r) {
    if (!r.authorize(adminPlug)) return
    launchpad.assemble(['admin/settings.ghtml']).launch(r, 'wrapper.ghtml')
}
```

---

## Role-Based Access Control (RBAC)

MadAve-Collab implements a full RBAC system with permission constants and group-based access.

### Defining Permission Constants

```groovy
class Permissions {
    static final String ADMIN         = 'admin'
    static final String TRAFFIC       = 'traffic'
    static final String FULFILLMENT   = 'fulfillment'
    static final String CREATIVE      = 'creative'
    static final String ACCOUNTING    = 'accounting'
    static final String TALENT        = 'talent'
    static final String PROJECT_MGMT  = 'project-management'
    static final String EXECUTIVE     = 'executive'
}
```

### Permission Group Helper Methods

```groovy
class UserManager {
    static boolean isInternalUser(ClientDocument doc) {
        return doc.hasPermission(Permissions.ADMIN) ||
               doc.hasPermission(Permissions.TRAFFIC) ||
               doc.hasPermission(Permissions.FULFILLMENT)
    }

    static boolean isAdminUser(ClientDocument doc) {
        return doc.hasPermission(Permissions.ADMIN) ||
               doc.hasPermission(Permissions.EXECUTIVE)
    }

    static boolean isTalentUser(ClientDocument doc) {
        return doc.hasPermission(Permissions.TALENT)
    }
}
```

### Filesystem-Based Route Permission Mapping

MadAve-Collab maps permissions to routes by scanning the Launchpad template directory structure. Each subdirectory under `secure/` corresponds to a permission:

```
launchpad/parts/secure/
  ├── admin/          → requires 'admin' permission
  ├── traffic/        → requires 'traffic' permission
  ├── fulfillment/    → requires 'fulfillment' permission
  └── talent/         → requires 'talent' permission
```

```groovy
// Build route-to-permission map from filesystem
static permissionMap = [:]

@Alert('on initialized')
static _buildPermissionMap(Result r) {
    new File('launchpad/parts/secure/').eachDir { dir ->
        def permission = dir.name
        dir.eachFileRecurse { file ->
            def route = fileToRoute(file)
            permissionMap[route] = permission
        }
    }
}

// Auth plug that checks the map
static secureRoutePlug = { HttpResult r ->
    if (!r.client.authenticated) {
        r.setRedirectUrl('/login')
        r.cancelled = true
        return false
    }

    def requiredPermission = permissionMap[r.context.target]
    if (requiredPermission && !r.client.document?.hasPermission(requiredPermission)) {
        r.setStatus(403)
        r.cancelled = true
        return false
    }
    return true
}
```

---

## API Route Authentication

For JSON API endpoints, return structured error responses instead of redirects:

```groovy
@Alert('on /api/projects POST')
static _createProject(HttpResult r) {
    if (!r.client.authenticated) {
        r.setStatus(401)
        r.writeToClient([success: false, error: 'Not authenticated'])
        return
    }

    if (!r.client.document?.hasPermission('traffic')) {
        r.setStatus(403)
        r.writeToClient([success: false, error: 'Insufficient permissions'])
        return
    }

    // handle the request
    def project = Document.getNew('projects')
    project.fields.name = r.context.data.name
    project.save()
    r.writeToClient([success: true, id: project._id])
}
```

---

## "Keep Me Logged In" Pattern

Adjust the `spaceport-uuid` cookie's max-age based on user preference:

```groovy
@Alert('on /login POST')
static _login(HttpResult r) {
    def authorized = Client.getAuthenticatedClient(
        r.context.data.username, r.context.data.password)

    if (authorized) {
        authorized.attachCookie(r.context.cookies.'spaceport-uuid' as String)

        if (r.context.data.remember_me) {
            // Extend cookie to 30 days
            r.addResponseCookie(new javax.servlet.http.Cookie('spaceport-uuid',
                r.context.cookies.'spaceport-uuid').with {
                maxAge = 30 * 24 * 60 * 60  // 30 days in seconds
                path = '/'
                httpOnly = true
                secure = !spaceport.Spaceport.config.'debug'
                it
            })
        } else {
            // Session-only cookie (expires when browser closes)
            r.addResponseCookie(new javax.servlet.http.Cookie('spaceport-uuid',
                r.context.cookies.'spaceport-uuid').with {
                maxAge = -1  // session cookie
                path = '/'
                httpOnly = true
                secure = !spaceport.Spaceport.config.'debug'
                it
            })
        }

        r.setRedirectUrl('/dashboard')
    } else {
        r.setRedirectUrl('/login?error=1')
    }
}
```

The login form:

```html
<form method="POST" action="/login">
    <input type="text" name="username" placeholder="Username" />
    <input type="password" name="password" placeholder="Password" />
    <label>
        <input type="checkbox" name="remember_me" value="true" />
        Keep me logged in
    </label>
    <button type="submit">Log In</button>
</form>
```

---

## Password Reset via Dock State Machine

MadAve-Collab implements a password reset flow using the Dock as a state machine. The Dock stores the reset code and expiry, avoiding the need for a separate database table.

### Step 1: Request Reset Code

```groovy
@Alert('on /forgot-password POST')
static _requestReset(HttpResult r) {
    def userId = r.context.data.username
    def userDoc = ClientDocument.getClientDocument(userId)

    if (!userDoc) {
        // Don't reveal whether user exists
        r.setRedirectUrl('/forgot-password?sent=true')
        return
    }

    // Generate 6-digit code
    def code = String.format('%06d', new Random().nextInt(999999))

    // Store in dock with 15-minute expiry
    def dock = r.context.dock
    dock.set('reset_code', code)
    dock.set('reset_user', userId)
    dock.set('reset_expires', System.currentTimeMillis() + (15 * 60 * 1000))

    // Send code via email
    sendEmail(userDoc.getEmail(), 'Password Reset Code', "Your code: ${code}")

    r.setRedirectUrl('/forgot-password?sent=true')
}
```

### Step 2: Verify Code

```groovy
@Alert('on /verify-reset-code POST')
static _verifyCode(HttpResult r) {
    def dock = r.context.dock
    def submittedCode = r.context.data.code

    def storedCode = dock.get('reset_code')
    def expires = dock.get('reset_expires')

    if (!storedCode || !expires) {
        r.setRedirectUrl('/forgot-password?error=no-code')
        return
    }

    if (System.currentTimeMillis() > expires) {
        dock.set('reset_code', null)
        dock.set('reset_expires', null)
        r.setRedirectUrl('/forgot-password?error=expired')
        return
    }

    if (submittedCode != storedCode) {
        r.setRedirectUrl('/verify-reset-code?error=invalid')
        return
    }

    // Code verified — allow password change
    dock.set('reset_verified', true)
    r.setRedirectUrl('/reset-password')
}
```

### Step 3: Set New Password

```groovy
@Alert('on /reset-password POST')
static _resetPassword(HttpResult r) {
    def dock = r.context.dock

    if (!dock.get('reset_verified')) {
        r.setRedirectUrl('/forgot-password')
        return
    }

    def userId = dock.get('reset_user')
    def userDoc = ClientDocument.getClientDocument(userId)
    userDoc.changePassword(r.context.data.new_password)

    // Clean up dock state
    dock.set('reset_code', null)
    dock.set('reset_user', null)
    dock.set('reset_expires', null)
    dock.set('reset_verified', null)

    r.setRedirectUrl('/login?reset=success')
}
```

---

## Admin User Management

### Creating Users with Temporary Passwords

```groovy
@Alert('on /admin/create-user POST')
static _createUser(HttpResult r) {
    def userId = r.context.data.username
    def tempPassword = generateTempPassword()  // e.g., random 12-char string

    def userDoc = ClientDocument.createNewClientDocument(userId, tempPassword)
    userDoc.setName(r.context.data.full_name)
    userDoc.setEmail(r.context.data.email)
    userDoc.addPermission(r.context.data.role)

    // Flag that password must be changed on first login
    userDoc.fields.require_password_change = true
    userDoc.save()

    // Send credentials via email
    sendEmail(r.context.data.email, 'Your Account',
        "Username: ${userId}\nTemporary password: ${tempPassword}\nPlease change your password after logging in.")

    r.setRedirectUrl('/admin/users')
}
```

### Forced Logout (Admin Password Reset)

When an admin resets a user's password, force-logout all of their sessions:

```groovy
@Alert('on /admin/reset-password POST')
static _adminResetPassword(HttpResult r) {
    def userId = r.context.data.user_id
    def newPassword = generateTempPassword()

    // Change password
    def userDoc = ClientDocument.getClientDocument(userId)
    userDoc.changePassword(newPassword)

    // Force logout — clear all authentication cookies
    def userClient = Client.getClient(userId)
    if (userClient) {
        userClient.authenticationCookies.clear()
    }

    // Flag for password change
    userDoc.fields.require_password_change = true
    userDoc.save()

    sendEmail(userDoc.getEmail(), 'Password Reset',
        "Your password has been reset. New temporary password: ${newPassword}")

    r.setRedirectUrl('/admin/users')
}
```

---

## Migration Script: Creating Initial Admin User

Use a migration script to create the first admin user when deploying a new application:

```groovy
// migrations/001-create-admin.groovy
import spaceport.personnel.ClientDocument

def adminUser = ClientDocument.createNewClientDocument('admin', 'changeme123')
adminUser.setName('System Administrator')
adminUser.addPermission('spaceport-administrator')
adminUser.addPermission('admin')
```

---

## Monitoring Authentication Events

Use alerts to log and monitor authentication activity:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.personnel.ClientDocument

class AuthMonitor {

    static failedAttempts = [:].withDefault { 0 }

    // Log successful logins
    @Alert('on client auth')
    static _logLogin(Result r) {
        println "User logged in: ${r.context.userID} at ${new Date()}"
    }

    // Track failed login attempts
    @Alert('on client auth failed')
    static _trackFailures(Result r) {
        if (r.context.exists) {
            failedAttempts[r.context.userID]++
            println "Failed login for ${r.context.userID} (attempt #${failedAttempts[r.context.userID]})"

            // Lock out after 5 failed attempts
            if (failedAttempts[r.context.userID] >= 5) {
                println "Account locked: ${r.context.userID}"
            }
        } else {
            println "Login attempt for non-existent user: ${r.context.userID}"
        }
    }

    // Block banned users
    @Alert(value = 'on client auth', priority = 10)
    static _blockBanned(Result r) {
        def userDoc = ClientDocument.getClientDocument(r.context.userID)
        if (userDoc?.fields?.banned) {
            r.cancelled = true  // reject authentication
        }
    }
}
```

---

## Checking Authentication in Templates

Launchpad templates have access to `client` and `dock`:

```html
<!-- Conditional content based on auth status -->
<div class="header">
    <% if (client.isAuthenticated()) { %>
        <span>Welcome, ${client.document.getName()}</span>
        <form method="POST" action="/logout">
            <button type="submit">Log Out</button>
        </form>
    <% } else { %>
        <a href="/login">Log In</a>
    <% } %>
</div>

<!-- Permission-based UI -->
<% if (client.isAuthenticated() && client.document.hasPermission('admin')) { %>
    <a href="/admin">Admin Panel</a>
<% } %>

<!-- Using dock for session state -->
<p>Your theme: ${dock.getOrDefault('theme', 'default')}</p>
```
