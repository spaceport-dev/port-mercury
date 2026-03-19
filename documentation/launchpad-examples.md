# Launchpad (Templating Engine) -- Examples

Real-world patterns drawn from production Spaceport applications: Guestbook, port-mercury, and MadAve-Collab.

---

## Basic Page Composition

### Simple Page with Wrapper (port-mercury)

The most common Launchpad pattern: a route handler assembles content parts and wraps them in a base layout.

**Route handler:**

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult
import spaceport.launchpad.Launchpad

class Router {

    static Launchpad launchpad = new Launchpad()

    @Alert('on / hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

**Wrapper template (`wrapper.ghtml`):**

```html
<%@ page import="spaceport.computer.memory.physical.Document; spaceport.computer.memory.virtual.Cargo; spaceport.Spaceport" %>
<!DOCTYPE html>
<html lang='en'>
<head>
    <meta charset='UTF-8'>
    <meta name='viewport' content='width=device-width, initial-scale=1.0'>
    <title>${ Spaceport.config.PWA?.name ?: 'Spaceport App' }</title>
    <link rel='stylesheet' href='https://cdn.jsdelivr.net/gh/aufdemrand/spacewalk.css@latest/spacewalk.css'>
    <script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
</head>
<body>

    <header style='background-color: #3c3c3c'>
        <img href='/' src='/assets/img/icon.svg' style='height: 5em;'>
    </header>

    <main>
        /// Content from assembled parts goes here
        <payload/>
    </main>

    <footer class='spread'>
        <span>Powered by <a href='https://spaceport.sh'>Spaceport</a></span>
        /// Use Cargo mirrored to a Document for a persistent page hit counter
        <span>${ Cargo.fromDocument(Document.get('page-hits', 'mercury')).inc() }</span>
    </footer>

</body>
</html>
```

### Multiple Parts (Guestbook)

Assemble multiple template files into a single page. Each part renders in order and shares the same variable binding.

```groovy
@Alert('on /index.html hit')
static _index(HttpResult r) {
    launchpad.assemble(['index.ghtml', 'history.ghtml']).launch(r, '_wrapper.ghtml')
}
```

The `index.ghtml` part handles guestbook creation, and the `history.ghtml` part displays the user's past guestbooks -- both rendered within the same wrapper.

### Launch Without a Wrapper

For simple pages or API-like responses, skip the wrapper entirely:

```groovy
@Alert('on /login GET')
static _loginPage(HttpResult r) {
    launchpad.assemble(['admin/login.ghtml']).launch(r)
}
```

---

## Dynamic Routes with Regex

### Capturing URL Parameters (Guestbook)

Use regex alert patterns to capture dynamic URL segments. The captured groups are available in the template via `r.matches`.

```groovy
@Alert('~on /g/(.*) hit')
static _event(HttpResult r) {
    // r.matches[0] is available in the template
    launchpad.assemble(['guestbook.ghtml']).launch(r, '_wrapper.ghtml')
}
```

In the template:

```html
<%
    def guestbook_id = r.matches[0]
    def gb = Document.get(guestbook_id, 'guestbooks') as Guestbook
%>

<h2>Guestbook for ${ gb.info.name }</h2>
```

---

## GET/POST Handling in Templates

### Form Submission (Guestbook)

A single template can handle both the form display (GET) and the submission processing (POST):

```html
<%@ page import="documents.Guestbook" %>

<% if (context.method == 'GET') { %>

/// Display the form
<details open class='bordered padded-more white centered narrow-width'>
    <summary class="pointer"><strong>Make a new Guestbook</strong></summary>
    <form action='index.html' method='POST'>
        <input name='name' required type='text' placeholder='Enter the name of your event'>
        <label for='email'>Your email:</label>
        <input name='email' id='email' type='email' placeholder='Optional'>
        <button type='submit'>Done</button>
    </form>
</details>

<% } else if (context.method == 'POST') {

    /// Process the form submission
    Guestbook gb = Guestbook.getNew('guestbooks') as Guestbook
    gb.info.name = data.name
    gb.info.email = data.email
    gb.info.owner_cookie = cookies.get('spaceport-uuid')
    gb.info.open = true
    gb.save()
%>

<p>Guestbook created! <a href='/g/${ gb._id }'>View it here</a>.</p>

<% } %>
```

### Authentication Form (MadAve-Collab)

A production login page that handles both rendering and processing:

```html
<%@ page import="javax.servlet.http.Cookie; spaceport.personnel.Client" %>

<% if (context.method == 'GET') { %>

<form action="/login" method="POST">
    <label for="username">Username:</label>
    <input type="text" id="username" name="username" autocomplete="current-username" required>
    <label for="password">Password:</label>
    <input type="password" id="password" name="password" autocomplete="current-password" required>
    <input type="submit" value="Log In">
</form>
<p><a href="forgot">Forgot your password?</a></p>

<% } else if (context.method == 'POST') {

    def username = (data.username as String).toLowerCase().trim()
    def authenticated = Client.getAuthenticatedClient(username, data.password as String)

    if (authenticated) {
        authenticated.attachCookie(cookies.'spaceport-uuid' as String)
        r.setRedirectUrl(data.'redirect-url' ?: '/')
    } else {
        r.setRedirectUrl('/login?error=invalid_credentials')
    }
} %>
```

---

## Server Actions

### Basic Click Handler (Guestbook)

Bind a server-side closure to a button click. The return value is a list of transmission instructions -- here, `'@print'` triggers the browser's print dialog:

```html
<button on-click="${ _{ [ '@print' ] }}">
    Print Poster
</button>
```

### Redirect from Server Action (Guestbook)

```html
<button on-click=${ _{ [ '@redirect' : 'g/' + gb._id ] }}>
    View Guestbook
</button>
```

### Form Handling with Server Action (port-mercury)

Instead of a traditional POST, use a server action for form submission. The closure receives the form data as a transmission object `t`:

```html
<%
    def submitNewMessage = { t ->
        def name = t.name as String
        String email = t.get('email')
        String message = t.getString('message')

        def newMessage = [
            'name': name.clean(),
            'email': email.clean(),
            'message': message.clean(),
            'timestamp': System.currentTimeMillis()
        ]

        Document messages = Document.get('messages', 'mercury')
        Cargo.fromDocument(messages).setNext(newMessage)

        return "<p class='bordered info padded'>Thank you, ${ name }!</p>"
    }
%>

<section id='contact-section'>
    <form target='#contact-section' on-submit=${ _{ t -> submitNewMessage(t) }}>
        <label for='name'>Name</label>
        <input type='text' id='name' name='name' required>
        <label for='email'>Email</label>
        <input type='email' id='email' name='email' required>
        <label for='message'>Message</label>
        <textarea id='message' name='message' rows='4' required></textarea>
        <button type='submit'>Send Message</button>
    </form>
</section>
```

Note the `target='#contact-section'` attribute -- the server action's return value (a thank-you message) replaces the entire `#contact-section` content, including the form.

### Inline Server Action with Multiple Operations (Guestbook)

Define and execute logic inline. This example adds a participant and reloads the page:

```html
<form on-submit='${ _{ t ->
    gb.addParticipant(t.name, t.email, t.getBool('public'), t.message, cookie)
    [ '@reload' ]
}}'>
    <input name='name' required type='text' placeholder='Enter your name'>
    <input name='email' type='email' placeholder='Email (optional)'>
    <textarea name='message' placeholder='Leave a message' rows='4'></textarea>
    <button type='submit'>Done</button>
</form>
```

### Closure-Based Server Action (Guestbook)

Define the handler as a closure variable, then reference it in the template. This keeps complex logic separate from the markup:

```html
<%
    def editGuestbook = { t ->
        gb.info.name = t.name.clean()
        gb.info.open = t.getBool('open')
        gb.save()
        [ '@reload' ]
    }
%>

<form on-submit=${ _{ t -> editGuestbook(t) }}>
    <label for='event-name'>Event Name:</label>
    <input name='name' required type='text' value="${ gb.info.name.escape() }">
    <input type='checkbox' name='open' ${ gb.info.open ? 'checked' : '' }>
    <label for='open'>Open for signing</label>
    <button type='submit'>Update</button>
</form>
```

### Remove Element on Click (Guestbook)

Use `target='parent'` and `'@remove'` to delete a DOM element after a server-side operation:

```html
<span target='parent' style='cursor: pointer;'
      on-click=${ _{ gb.removeParticipant(participant.cookie); [ '@remove' ] }}>
    Remove
</span>
```

---

## Reactive Bindings

### Auto-Updating Display (Guestbook)

The `${{ }}` syntax creates a reactive region. When `gb.info.name` changes (e.g., via a server action that edits the guestbook), the span updates automatically:

```html
<strong>Guestbook for <span>${{ gb.info.name }}</span></strong>
```

### Reactive List Rendering (Guestbook)

Use `${{ }}` with the `.combine {}` class enhancement to render a list that automatically re-renders when items are added or removed:

```html
${{ gb.participants.combine { participant -> """
    <div class='narrow-width text-spaced'>
        <strong>${ participant.name }</strong>
        ${ (participant.email ?: '').if { participant.public_email } }
        ${ '(You!)'.if { participant.cookie == cookie } }
        <br>
        ${ participant.time_signed.relativeTime() }
    </div>
""" } }}
```

### Conditional Reactive Content (Guestbook)

Render content conditionally using the `.if {}` class enhancement inside a reactive binding:

```html
${{ "<div class='centered narrow-width padded'><strong>Guestbook is closed.</strong></div>".if { !gb.isOpen() } }}

${{ "No signatures yet!".if { gb.participants.size() == 0 && gb.isOpen() } }}
```

### Reactive Counter with Server Action

Combine reactive bindings with server actions for a live-updating counter:

```html
<p>Counter: ${{ dock.counter.getDefaulted(0) }}</p>
<button on-click=${ _{ dock.counter.inc() }}>Increment</button>
```

---

## Using `prime()` for Nested Templates

### Role-Based Dashboard (MadAve-Collab)

Use `prime()` to dynamically include a sub-template based on runtime conditions:

```html
<%@ page import="data.Job; data.VoiceJob; data.UserManager" %>
<%
    def userPerms = client.document?.permissions ?: []
    def userName = client.document?.fields?.name?.split(' ')?.first() ?: 'there'

    def dashboardRole = 'admin'
    if (userPerms.contains('traffic')) dashboardRole = 'traffic'
    else if (userPerms.contains('voice-talent')) dashboardRole = 'voice-talent'
    else if (userPerms.contains('engineer')) dashboardRole = 'engineer'

    @Provided def roleDashboard
    prime("/secure/dashboards/_${dashboardRole}.ghtml")

    def hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    def greeting = hour < 12 ? 'Good morning' : (hour < 17 ? 'Good afternoon' : 'Good evening')
%>

<main>
    <h2>${ greeting }, ${ userName }!</h2>
    /// Insert the role-specific dashboard
    ${ roleDashboard() }
</main>
```

The primed template defines a `roleDashboard` closure that the parent template calls to inject the content.

### Inline Prime for Delegation (MadAve-Collab)

Use `<%= prime() %>` to delegate to another template entirely:

```html
<%= prime('secure/admin/active-jobs.ghtml') %>
```

This pattern is used when multiple routes need to render the same content. The traffic role's active-jobs page simply delegates to the admin version.

---

## Conditional CSS and Rendering

### Conditional Stylesheets (MadAve-Collab)

Use the `.if {}` class enhancement to conditionally include stylesheets based on the current route:

```html
${ "<link rel='stylesheet' href='/assets/css/secure.css'>".if { context.target.startsWith('/home/') } }
${ "<link rel='stylesheet' href='/assets/css/insecure.css'>".if { !context.target.startsWith('/home/') } }
```

### Conditional Content Based on Authentication

```html
<% if (client?.authenticated) { %>
    <span>Welcome, ${ client.document.name }!</span>
    <a href="/logout">Logout</a>
<% } else { %>
    <a href="/login">Login</a>
<% } %>
```

### Permission-Based Content (MadAve-Collab)

```html
<%
    def userPerms = client.document?.permissions ?: []
%>

<% if (userPerms.contains('spaceport-administrator') || userPerms.contains('admin')) { %>
    <a href="/admin/overview">Admin Panel</a>
<% } %>
```

---

## Error Pages (port-mercury)

### Catch-All Error Handler

Use `on page hit` to handle error status codes with custom templates:

```groovy
@Alert('on page hit')
static _checkStatusCode(HttpResult r) {
    if (r.context.response.status >= 500) {
        launchpad.assemble(['error-pages/5XX.ghtml']).launch(r)
    } else if (r.context.response.status >= 400) {
        launchpad.assemble(['error-pages/4XX.ghtml']).launch(r)
    }
}
```

---

## Server Elements in Templates

### Using Elements with Server Actions (MadAve-Collab)

Server Elements are used as custom HTML tags via `<g:element-name>`. This example from MadAve-Collab uses a `stat-card` element with data computed in a scriptlet:

```html
<%
    def allJobs = jobs.get()
    def activeJobs = allJobs.findAll { it.value.status != Job.STATUS_COMPLETE }
    def rushJobs = activeJobs.findAll { it.value.priority == Job.PRIORITY_RUSH }
%>

<panel class="fourths">
    <g:stat-card value="${ activeJobs.size() }" color="blue" icon="jobs">
        Active Jobs
    </g:stat-card>

    <g:stat-card value="${ rushJobs.size() }" color="red" icon="rush">
        Rush Jobs
    </g:stat-card>
</panel>
```

### Dialog Pattern (MadAve-Collab)

Use a variable in the wrapper to conditionally render a dialog element:

```html
/// In the wrapper (_wrapper.ghtml)
<% def dialog = '' %>

<body>
    <app-wrapper>
        <payload/>
    </app-wrapper>

    <dialogs>${{ dialog ? "<g:hud-dialog open>${ dialog }</g:hud-dialog>" : '' }}</dialogs>
</body>
```

A part template can set the `dialog` variable, and the reactive binding in the wrapper will render it.

---

## Class Enhancement Patterns in Templates

### Data Sanitization with `.clean()`

Always sanitize user input before displaying or storing it:

```html
<%
    def editGuestbook = { t ->
        gb.info.name = t.name.clean()
        gb.save()
    }
%>
```

### Safe Attribute Values with `.escape()` and `.quote()`

```html
<input value="${ gb.info.name.escape() }">
<input value=${ item.getString('text').quote() }>
```

### Conditional Strings with `.if {}` and `.unless {}`

```html
/// Show email only if public
${ (participant.email ?: '').if { participant.public_email } }

/// Show "(You!)" label
${ '(You!)'.if { participant.cookie == cookie } }

/// Conditional attribute
<input type='checkbox' ${ gb.info.open ? 'checked' : '' }>
```

### List Rendering with `.combine {}`

The `.combine {}` enhancement maps each item through a closure and joins the results into a single string. It is the primary pattern for rendering lists in Spaceport templates:

```html
${ userGuestbooks.combine { """
    <div class='way-less-block-margin'>
        <a href='/g/${ it.id }'>
            <strong>${ it.value.info.name }</strong>
        </a>
    </div>
""" } }
```

### Persistent Page Counter with Cargo

```html
<span>${ Cargo.fromDocument(Document.get('page-hits', 'mercury')).inc() }</span>
```

This creates or retrieves a Cargo instance backed by a CouchDB document and increments its value, providing a persistent page view counter in a single expression.

---

## Passing Data from Route Handlers

Set data in the route handler, access it in the template via the `data` variable:

```groovy
@Alert('~on /article/(.*) hit')
static _article(HttpResult r) {
    r.context.data.article = Document.get(r.matches[0], 'articles')
    r.context.data.pageTitle = 'My Article'
    launchpad.assemble(['article.ghtml']).launch(r, 'wrapper.ghtml')
}
```

```html
<% def article = data.article %>
<h1>${ article.fields.title }</h1>
<div>${ article.fields.content }</div>
```
