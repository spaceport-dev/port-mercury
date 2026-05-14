# HUD-Core.js -- Examples

Real-world patterns and recipes for building interactive Spaceport applications with HUD-Core.js. All examples in this document are drawn from or modeled after production Spaceport applications (Guestbook, port-mercury, and MadAve-Collab).

---

## Table of Contents

- [Basic Event Handling](#basic-event-handling)
- [Form Handling](#form-handling)
- [Transmission Response Patterns](#transmission-response-patterns)
- [Targeting Strategies](#targeting-strategies)
- [Reactive Data Binding](#reactive-data-binding)
- [Conditional Display](#conditional-display)
- [List Rendering Patterns](#list-rendering-patterns)
- [Dialog and Modal Patterns](#dialog-and-modal-patterns)
- [Navigation and Redirects](#navigation-and-redirects)
- [WebSocket Communication](#websocket-communication)
- [Custom JavaScript Integration](#custom-javascript-integration)
- [Loading States](#loading-states)
- [HREF Navigation on Non-Anchor Elements](#href-navigation-on-non-anchor-elements)
- [Multi-Step Workflows](#multi-step-workflows)
- [Autocomplete and Fuzzy Search](#autocomplete-and-fuzzy-search)
- [File Upload Patterns](#file-upload-patterns)
- [Inline Editing and Delete](#inline-editing-and-delete)
- [Server Elements with HUD-Core](#server-elements-with-hud-core)
- [Element Lifecycle Patterns](#element-lifecycle-patterns)
- [Common Gotchas](#common-gotchas)
- [Pattern Summary](#pattern-summary)

---

## Basic Event Handling

### Simple Click Handler

The most basic pattern: a button that triggers a server action when clicked.

```html
<button on-click="${ _{ println 'Button clicked on server' }}">
    Click Me
</button>
```

The `_{ }` syntax creates a server action closure. When the button is clicked, HUD-Core sends a POST request to the server, the closure executes, and the response (if any) is applied to the target element. Since there is no `target` attribute and the closure returns `null` (the result of `println`), no DOM update occurs.

### Click with a Return Value

To update the DOM with the result, set a `target` attribute:

```html
<button target="self" on-click="${ _{ 'Clicked!' }}">
    Click Me
</button>
```

After clicking, the button's `innerHTML` becomes `"Clicked!"`. The `target="self"` directive tells HUD-Core to apply the server response to the button element itself.

### Accessing Transmitted Data

The server closure can accept a parameter (conventionally named `t`) that carries data from the client -- the element's value, form data, data attributes, event metadata, and URL query parameters:

```html
<input type="text" on-blur="${ _{ t ->
    println "User entered: ${ t.value }"
}}">
```

From the port-mercury contact form, a complete example showing multiple ways to access transmission data:

```groovy
def submitNewMessage = { t ->
    def name = t.name as String           // Implicit property access
    String email = t.get('email')         // Explicit get()
    String message = t.getString('message') // Typed access (coerces to String)

    // ... process data ...
    return "<p>Thank you, ${ name }!</p>"
}
```

### Print Action

From the Guestbook application -- triggering the browser's print dialog with an Array Transmission action:

```html
<button on-click="${ _{ [ '@print' ] }}">
    Print Poster
</button>
```

No target is needed because `@print` is a global browser action. The server closure returns the array, and since Groovy returns the last expression in a closure, no `return` keyword is necessary.

*Source: Guestbook -- guestbook.ghtml*

---

## Form Handling

### Basic Form Submission

The `on-submit` attribute intercepts form submission and sends all named inputs to the server. HUD-Core automatically calls `event.preventDefault()` on form elements to suppress the native browser submission.

From the port-mercury contact form:

```html
<%
    def submitNewMessage = { t ->
        def name = t.name as String
        String email = t.get('email')
        String message = t.getString('message')

        def newMessage = [
            'name': name.clean(),       // XSS sanitization
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
    <panel>
        <form target='#contact-section' on-submit=${ _{ t -> submitNewMessage(t) }}>
            <label for='name'>Name</label>
            <input type='text' id='name' name='name' required>
            <label for='email'>Email</label>
            <input type='email' id='email' name='email' required>
            <label for='message'>Message</label>
            <textarea id='message' name='message' rows='4' required></textarea>
            <button type='submit'>Send Message</button>
        </form>
    </panel>
</section>
```

Key points:

- `on-submit` prevents the default form submission automatically.
- `target='#contact-section'` means the entire section (including the form) is replaced with the thank-you message.
- Form inputs are accessible via `t.name`, `t.get('email')`, or `t.getString('message')` -- all equivalent ways to access the transmission data.
- `.clean()` sanitizes input for XSS protection.

*Source: port-mercury -- contact.ghtml*

### Form with Checkboxes and Booleans

From the Guestbook editing form -- checkboxes send their checked state. Use `t.getBool()` on the server to coerce the value to a boolean:

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
    <input name='name' required type='text' id='event-name'
           autocomplete='off'
           placeholder='Enter the name of your event'
           value="${ gb.info.name.escape() }">
    <flex>
        <input type='checkbox' name='open' id='open'
               ${ gb.info.open ? 'checked' : '' }>
        <label for='open'>Open for signing</label>
    </flex>
    <button type='submit'>Update</button>
</form>
```

*Source: Guestbook -- guestbook.ghtml*

### Inline Form Submission

For simpler cases, the server action can be defined entirely inline:

```html
<form on-submit='${ _{ t ->
    gb.addParticipant(t.name, t.email, t.getBool("public"), t.message, cookie)
    [ "@reload" ]
}}'>
    <input name='name' required type='text' placeholder='Enter your name'>
    <input name='email' type='email' placeholder='Enter your email (optional)'>
    <flex>
        <input type='checkbox' checked name='public' id='public_email'>
        <label for='public_email'>Show email publicly</label>
    </flex>
    <textarea placeholder="Leave a message (optional)" name="message" rows="4"></textarea>
    <button type='submit'>Done</button>
</form>
```

*Source: Guestbook -- guestbook.ghtml*

### Dropdown Change Handler

From the MadAve-Collab job view -- an `on-change` event on a `<select>` element sends the selected value to the server:

```html
<%
def doChangeStatus = { t ->
    def newStatus = t.getString('value')
    if (newStatus && newStatus != job.status && availableStatuses.contains(newStatus)) {
        job.updateStatus(newStatus)
        job.save()
        job._update()   // Triggers reactive updates for any listening Cargo bindings
    }
}
%>

<select name="status" on-change="${ _{ t -> doChangeStatus(t) }}">
    <% availableStatuses.each { status -> %>
    <option value="${ status }" ${ status == job.status ? 'selected' : '' }>
        ${ status }
    </option>
    <% } %>
</select>
```

Note there is no `target` attribute -- the closure does not return a Transmission, so no DOM update occurs. The reactive variable system (`job._update()`) handles refreshing other parts of the page that display the job status.

*Source: MadAve-Collab -- job/view.ghtml*

### Form Submission that Clears the Input

From the MadAve-Collab comment form -- the server action returns `[ '@clear' ]` to clear the target after submission. The `target="textarea"` directive targets the textarea element within the form:

```html
<%
def doAddComment = { t ->
    def commentText = t.getString('comment')?.trim()
    if (commentText) {
        def userName = client.document?.fields?.name ?: 'Unknown'
        job.addComment(client.document._id, userName, commentText)
        job.save()
        job._update()
    }
    return [ '@clear' ]
}
%>

<form on-submit="${ _{ t -> doAddComment(t) }}" target="textarea" class="comment-form">
    <textarea name="comment" rows="2" placeholder="Write a comment..." required></textarea>
    <br><button type="submit">Send</button>
</form>
```

The `@clear` action in an Array Transmission clears either the `.value` or `.innerHTML` of the target element.

*Source: MadAve-Collab -- job/view.ghtml*

### Complex Form with Multiple Field Types

From MadAve-Collab's job creation form -- a large form with text inputs, selects, date fields, hidden inputs, and custom elements. The server action accesses all fields through the transmission:

```html
<%
def doCreateJob = { t, String s = Job.STATUS_ASSIGNED_TO_TALENT ->
    def job = VoiceJob.makeNew() as VoiceJob
    job.with {
        client       = t.getString('client')
        projectName  = t.getString('projectName')
        talentId     = t.getString('talentId')
        priority     = t.getString('priority')
        deadline     = t.get('deadline')          // Date inputs arrive as epoch milliseconds
        tags         = t.getList('tags')          // Multi-value fields arrive as lists
        brief        = t.getString('brief')
        directionNotes = t.getString('directionNotes')
    }
    job.save()
    Job.currentJobs._update()
    return [ '@redirect': '/home/active-jobs?m=new-job-success' ]
}
%>

<form on-submit="${ _{ t -> doCreateJob(t) }}">
    <!-- Text inputs -->
    <input type="text" name="client" value="${ existingJob?.client ?: '' }">
    <input type="text" name="projectName" value="${ existingJob?.projectName ?: '' }">

    <!-- Select dropdowns -->
    <select name="priority">
        <option value="Normal" selected>Normal</option>
        <option value="Rush">Rush</option>
    </select>

    <!-- Date/time input (sent as epoch milliseconds by HUD-Core) -->
    <input type="datetime-local" name="deadline">

    <!-- Custom Server Element (participates in form via name attribute) -->
    <g:tag-input name="tags" placeholder="Add a tag..."></g:tag-input>

    <button type="submit">Create Job</button>
</form>
```

Key details:

- Date/time inputs are automatically converted to epoch milliseconds by HUD-Core before sending to the server.
- Custom Server Elements with a `name` attribute participate in form data collection -- HUD-Core reads their `.value` property.
- `t.getList('tags')` retrieves multi-value fields as lists.

*Source: MadAve-Collab -- create-job.ghtml*

---

## Transmission Response Patterns

### Single Value -- Replace Content

The simplest response. Return a string and it replaces the target element's `innerHTML`:

```html
<span id="greeting" target="self" on-click="${ _{ 'Hello, world!' }}">
    Click for greeting
</span>
```

### Array Transmission -- Class Operations and Actions

Use an array to perform CSS class operations and trigger built-in actions:

```html
<!-- Add and remove CSS classes -->
<button target="self" on-click="${ _{ ['+active', '-pending'] }}">
    Activate
</button>

<!-- Remove the target element from the DOM -->
<span target="parent" on-click="${ _{ gb.removeParticipant(participant.cookie); [ '@remove' ] }}">
    Delete
</span>

<!-- Reload the page after a server action -->
<button on-click="${ _{ someData.save(); [ '@reload' ] }}">
    Save and Refresh
</button>

<!-- Toggle a CSS class (no prefix) -->
<button target="self" on-click="${ _{ ['highlighted'] }}">
    Toggle Highlight
</button>
```

### Map Transmission -- Complex DOM Updates

Maps are the most powerful response format. Each key-value pair is an instruction:

```html
<button target="self" on-click="${ _{ [
    'innerText': 'Saved!',
    '+success': 'it',
    'disabled': 'true'
] }}">Save</button>
```

This single response changes the button text, adds a CSS class to `event.currentTarget`, and disables the button.

### Map Transmission -- Updating Multiple Elements by ID

Use `#id` keys to update elements elsewhere on the page:

```html
<%
def doSave = { t ->
    def item = saveItem(t)
    return [
        '#item-count': items.size().toString(),
        '#status-message': 'Item saved successfully!',
        '+saved': null     // Adds 'saved' class to the payload target
    ]
}
%>

<button on-click="${ _{ t -> doSave(t) }}">Save Item</button>
<span id="item-count">0</span>
<span id="status-message"></span>
```

### Map Transmission -- Inline Styles

Prefix keys with `&` to set inline CSS properties:

```html
<div target="self" on-click="${ _{ [
    '&backgroundColor': 'yellow',
    '&border': '2px solid gold'
] }}">Highlight me</div>
```

### Map Transmission -- Data Attributes

Prefix keys with `*` to set `data-*` attributes:

```html
<button target="self" on-click="${ _{ [
    '*status': 'complete',
    '*timestamp': System.currentTimeMillis().toString()
] }}">Mark Complete</button>
```

### Map Transmission -- URL Query Parameters

Prefix keys with `?` to update the URL query string without a page reload (uses `pushState`):

```html
<button on-click="${ _{ [
    '?page': '2',
    '?sort': 'name'
] }}">Page 2</button>
```

### Map Transmission -- Local and Session Storage

Use `~` for `localStorage` and `~~` for `sessionStorage`:

```html
<button on-click="${ _{ [
    '~theme': 'dark',
    '~~last-visited': '/dashboard'
] }}">Set Dark Theme</button>
```

### Map Transmission -- Descendant Selector

Prefix keys with `>` to target a descendant of the active target:

```html
<div target="self" on-click="${ _{ [
    '> .count': dock.counter.get().toString(),
    '+active': true
] }}">
    <span class="count">0</span> likes
</div>
```

---

## Targeting Strategies

### Self Target

The most common target. The server response is applied to the element that triggered the event:

```html
<button target="self" on-click="${ _{ 'Updated!' }}">Click me</button>
```

### Outer Target

Like `self`, but for single-value Transmissions, replaces `outerHTML` instead of `innerHTML`:

```html
<button target="outer" on-click="${ _{ '<span>Replaced entirely!</span>' }}">
    Replace me
</button>
```

### Parent Target

Target the parent element. Useful for removing a list item by its delete button:

```html
<span target="parent" style="cursor: pointer; user-select: none;"
      on-click="${ _{ gb.removeParticipant(participant.cookie); [ '@remove' ] }}">
    Delete
</span>
```

In this Guestbook example, clicking the delete icon removes the parent `<div>` that contains the entire participant entry.

*Source: Guestbook -- guestbook.ghtml*

### CSS Selector Target

Target any element on the page by CSS selector:

```html
<form target="#contact-section" on-submit="${ _{ t -> submitNewMessage(t) }}">
    <!-- form fields -->
</form>

<section id="contact-section">
    <!-- This entire section gets replaced with the server response -->
</section>
```

*Source: port-mercury -- contact.ghtml*

### No Target (Fire and Forget)

Use `target="none"` when you want to execute a server action without updating any DOM element:

```html
<button target="none" on-click="${ _{ logEvent('button-clicked') }}">
    Track Click
</button>
```

Alternatively, omit the `target` attribute entirely and return `null` from the server action.

### Named Dialog Target

From MadAve-Collab -- forms inside dialogs target the dialog container to dismiss or update it:

```html
<form target='hud-dialog' on-submit="${ _{ t -> doEdit(t) }}">
    <!-- form fields -->
    <button type='submit'>Save Changes</button>
</form>
```

*Source: MadAve-Collab -- user-edit.ghtml*

### Append and Prepend Targets

Create new child elements for list patterns:

```html
<ul target="append" wrapper="li" on-click="${ _{ 'New item' }}">
    <li>Existing item</li>
</ul>
```

Clicking appends a new `<li>` containing "New item" to the list. The `wrapper="li"` attribute specifies the tag type for the new element (default is `div`).

---

## Reactive Data Binding

### Server-Driven Reactive Variables

Wrap expressions in `${{ }}` (double braces) to create reactive subscriptions. When the underlying Cargo data changes, the DOM updates automatically:

```html
<p>Counter: ${{ dock.counter.getDefaulted(0) }}</p>
<button on-click="${ _{ dock.counter.inc() }}">Increment</button>
```

Each click increments the Cargo counter on the server, and Spaceport pushes the updated value to the DOM region wrapped in `${{ }}`.

### Reactive `def` Variables

Template-level `def` variables are automatically reactive. Changing them in a server action updates the DOM:

```html
<% def theme = 'light' %>

<button on-click="${ _{ theme = (theme == 'light') ? 'dark' : 'light' }}">
    Toggle Theme
</button>

<p>Current theme: ${{ theme }}</p>
```

### Client-Side Data Binding with `bind`

Elements with a `bind` attribute automatically display the corresponding value from `documentData`:

```html
<span bind="user.name">Loading...</span>
<input bind="user.email">
```

When `documentData.user.name` changes, the `<span>` content updates. For form elements (`INPUT`, `TEXTAREA`, `SELECT`), the `.value` property is set instead of `innerHTML`.

### Populating documentData from the Server

The server sends data to `documentData` using CDATA comment nodes. This is handled automatically by Spaceport's Cargo system:

```html
<!-- <![CDATA[{"user": {"name": "Alice", "role": "admin"}}]]> -->
```

Any element with a matching `bind` attribute updates immediately when this data arrives. Appending data (rather than replacing) uses the `append` flag:

```html
<!-- <![CDATA[{"append": true, "newField": "value"}]]> -->
```

### Writing to documentData from JavaScript

```javascript
// Direct property assignment
documentData.userName = 'Alice'

// Nested objects are automatically wrapped in reactive proxies
documentData.user = { name: 'Alice', role: 'admin' }

// Dot-notation deep set
setDocumentDataProperty('user.profile.name', 'Alice')
```

### Reading and Writing from Bound Elements

HUD-Core adds `hudRead()` and `hudWrite()` methods to all HTML elements:

```javascript
// Read the bound value
let currentName = document.querySelector('[bind="user.name"]').hudRead()

// Write a new value (updates all elements bound to the same path)
document.querySelector('[bind="user.name"]').hudWrite('Bob')
```

---

## Conditional Display

### Server-Side Conditional Rendering

Standard Groovy control flow renders different HTML based on server state:

```html
<% if (gb.isOwner(cookie)) { %>
    <!-- Owner-only UI: edit form, delete buttons -->
<% } else if (!gb.hasParticipant(cookie) && gb.isOpen()) { %>
    <!-- Sign the guestbook form -->
<% } else { %>
    <strong>You've already signed this guestbook.</strong>
<% } %>
```

*Source: Guestbook -- guestbook.ghtml*

### Reactive Conditional Display with `.if{}`

Use `${{ }}` with Spaceport's `.if{}` class enhancement for reactive conditional rendering:

```html
${{ "<div id='closed-guestbook-notice' class='centered narrow-width top-margin padded'><strong>Guestbook is closed.</strong></div>".if { !gb.isOpen() } }}
```

This renders the `<div>` only when the guestbook is closed, and re-evaluates whenever the underlying data changes.

*Source: Guestbook -- guestbook.ghtml*

### Conditional CSS Stylesheet Loading

From MadAve-Collab -- load different stylesheets based on the current route:

```html
${ "<link rel='stylesheet' href='/assets/css/secure.css'>".if { context.target.startsWith('/home/') } }
${ "<link rel='stylesheet' href='/assets/css/insecure.css'>".if { !context.target.startsWith('/home/') } }
```

*Source: MadAve-Collab -- _wrapper.ghtml*

### Conditional Attributes

Use `.if{}` to conditionally include HTML attributes:

```html
<option value="active" ${ 'selected'.if { user?.value?.status != 'disabled' }}>Active</option>
<option value="disabled" ${ 'selected'.if { user?.value?.status == 'disabled' }}>Disabled</option>
```

*Source: MadAve-Collab -- user-edit.ghtml*

### Show/Hide with Transmission Actions

Use `@show` and `@hide` to toggle element visibility from the server:

```html
<div id="details-panel" style="display: none;">
    <p>Hidden details here</p>
</div>

<button on-click="${ _{ [ '@show': '#details-panel' ] }}">Show Details</button>
<button on-click="${ _{ [ '@hide': '#details-panel' ] }}">Hide Details</button>
```

### CSS-Driven Step Visibility

From MadAve-Collab's password reset flow -- use reactive CSS to control which step is visible:

```html
${{
    """
    <style>
        step-container {
            display: none;
            &#email-step { ${ currentStep() == 'email' ? 'display: block;' : '' } }
            &#code-step  { ${ currentStep() == 'code'  ? 'display: block;' : '' } }
        }
    </style>
    """
}}

<step-container id="email-step">
    <!-- Step 1: Email input form -->
</step-container>

<step-container id="code-step">
    <!-- Step 2: Verification code form -->
</step-container>
```

When a server action changes `currentStep()`, the reactive CSS block re-renders, toggling which step is displayed.

*Source: MadAve-Collab -- forgot.ghtml*

---

## List Rendering Patterns

### Basic List Rendering with `.combine{}`

Spaceport's `.combine{}` class enhancement maps a list to HTML strings and joins them. This is the standard way to render lists in templates:

```html
${ userGuestbooks.combine { """
    <div class='way-less-block-margin'>
        <a href='/g/${ it.id }'>
            <strong>${ it.value.info.name }</strong>
        </a>
    </div>
""" }}
```

*Source: Guestbook -- history.ghtml*

### Reactive List Rendering

Wrap `.combine{}` in `${{ }}` to make lists reactive. When the underlying data changes, the DOM updates automatically:

```html
${{
    def jobs = activeJobs()
    if (jobs.isEmpty()) {
        return """<hud-card class="empty-state">
            <p>No jobs ready for audio upload. Check back soon!</p>
        </hud-card>"""
    }
    jobs.combine { row ->
        def job = row.value
        """
        <hud-card>
            <card-header>
                <h3 href="/v/job/${ row.id }">${ job.projectName ?: 'Untitled' }</h3>
            </card-header>
            <card-body>
                <p>${ job.client ?: 'No client' }</p>
                <badge color="${ statusColor(job.status) }">${ job.status }</badge>
            </card-body>
        </hud-card>
        """
    }
}}
```

*Source: MadAve-Collab -- upload-audio.ghtml*

### Table Row Rendering with Per-Row Actions

From MadAve-Collab's admin user list -- each row includes action buttons that trigger server closures. Each `on-click` captures the specific user from the loop iteration. The `on-click` attributes work on dynamically inserted content because HUD-Core's MutationObserver automatically binds events:

```html
${ userList.combine { user ->
    def isVoiceTalent = user.value.permissions?.contains('voice-talent')
    def rateCardMenuItem = isVoiceTalent
        ? """<li on-click="${ _{ userRateCardDialog(user.id) }}">Set Rate Card</li>"""
        : ''

    """
    <table-row>
        <table-cell>
            ${ user.value.status == 'disabled'
                ? '<badge color=red>Disabled</badge>'
                : '<badge>Active</badge>' }
        </table-cell>
        <table-cell>${ user.value.name }</table-cell>
        <table-cell>${ user.id }</table-cell>
        <table-cell>
            <g:hud-popover>
                <img src="/assets/icons/horizontal-dots.svg">
                <ul>
                    <li on-click="${ _{ userEditDialog(user.id) }}">Edit Details</li>
                    ${ rateCardMenuItem }
                    <li on-click="${ _{ passwordEditDialog(user.id) }}">Password Reset</li>
                    <li on-click="${ _{ userDeleteDialog(user.id) }}">Delete User</li>
                </ul>
            </g:hud-popover>
        </table-cell>
    </table-row>
    """
} }
```

Each list item gets its own `on-click` server action closure that captures the specific `user.id` from the loop iteration. This is possible because Launchpad creates a unique server action endpoint for each closure.

*Source: MadAve-Collab -- manage-users.ghtml*

### Reactive Datalist Options

From MadAve-Collab -- a `<datalist>` whose options are reactively generated from server-side search results:

```html
<datalist id="clients">
    ${{ fuzzyClients.combine { """<option value="${ it }"></option>""" } }}
</datalist>
```

The `${{ }}` wrapper makes this reactive: when `fuzzyClients` is updated by a server action, the datalist options re-render automatically.

*Source: MadAve-Collab -- create-job.ghtml*

### Empty State Handling

Always provide feedback when a list is empty:

```html
${{
    def jobs = activeJobs()
    if (jobs.isEmpty()) {
        return """<hud-card class="empty-state">
            <p>No jobs ready for audio upload. Check back soon!</p>
        </hud-card>"""
    }
    jobs.combine { row -> /* ... render cards ... */ }
}}
```

```html
${{ "No signatures yet!".if { gb.participants.size() == 0 && gb.isOpen() } }}
```

---

## Dialog and Modal Patterns

### Reactive Dialog System

MadAve-Collab uses a reactive `dialog` variable defined in the wrapper template. Setting it from a server action causes the dialog to appear. This works because `${{ dialog }}` is a reactive expression that re-renders when the variable changes.

**In the wrapper template:**

```html
<% def dialog = '' %>

<!-- ... page content ... -->

<dialogs>${{ dialog ? "<g:hud-dialog open>${ dialog }</g:hud-dialog>" : '' }}</dialogs>
```

*Source: MadAve-Collab -- _wrapper.ghtml*

### Opening a Dialog

The dialog content is set as an HTML string that includes its own form and event handlers:

```html
<%
    @Provided def dialog   // Inherited from wrapper

    def userEditDialog = { String _id ->
        def user = userList.find { it.id == _id }

        def doEdit = { t ->
            def name  = t.getString('name')
            def phone = t.getString('phone')?.phone()?.toString()
            def status = t.getString('status')
            def permissions = t.getList('permissions')

            def userDoc = user.getDocument()
            userDoc.fields.name  = name
            userDoc.fields.phone = phone
            userDoc.fields.status = status == 'disabled' ? 'disabled' : 'active'
            userDoc.permissions = permissions.collect { it.slugify() }
            userDoc.save()

            return [ '@redirect': '/home/manage-users?m=edit-user-success' ]
        }

        dialog = """
            <h2>Edit User</h2>
            <form target='hud-dialog' on-submit="${ _{ t -> doEdit(t) }}">
                <input type='hidden' name='user_id' value=${ _id?.quote() }>
                <label for='name'>Name:</label>
                <input type='text' name='name' value=${ user?.value?.name?.quote() }>
                <label for='status'>Status:</label>
                <select name='status'>
                    <option value='active' ${ 'selected'.if { user?.value?.status != 'disabled' }}>Active</option>
                    <option value='disabled' ${ 'selected'.if { user?.value?.status == 'disabled' }}>Disabled</option>
                </select>
                <button color='blue' type='submit'>Save Changes</button>
            </form>"""
    }
%>

<!-- Trigger from a list item -->
<li on-click="${ _{ userEditDialog(user.id) }}">Edit Details</li>
```

The pattern works as follows:

1. The `dialog` variable is a `@Provided` reactive variable from the wrapper template.
2. When `userEditDialog(id)` is called from an `on-click`, it sets `dialog` to an HTML string.
3. Because the wrapper uses `${{ dialog }}`, the DOM updates automatically with the dialog.
4. The form inside the dialog has its own `on-submit` handler, which works immediately because HUD-Core's MutationObserver binds events on dynamically inserted content.

*Source: MadAve-Collab -- user-edit.ghtml*

### Dismissing a Dialog

Cancel buttons dismiss the dialog by removing it from the DOM:

```html
<button type="button" target='hud-dialog' on-click="${ _{ [ '@remove' ] }}" class='secondary'>
    Cancel
</button>
```

Or dismiss and redirect:

```groovy
def doEdit = { t ->
    // ... save changes ...
    return [ '@redirect': '/home/manage-users?m=edit-user-success' ]
}
```

*Source: MadAve-Collab -- upload-audio.ghtml*

### Confirmation Dialog

From MadAve-Collab -- a delete confirmation dialog with destructive action:

```html
<%
def deleteCommentDialog = { String commentId ->
    def comment = job.comments?.find { it.id == commentId }
    if (!comment || comment.userId != currentUserId) {
        dialog = """<h2>Error</h2><p>Comment not found or you don't have permission.</p>
            <button-group><button type='button' target='hud-dialog'
                on-click="${ _{ [ '@remove' ] }}">Close</button></button-group>"""
        return
    }

    def doDeleteComment = {
        job.comments?.removeAll { it.id == commentId }
        job.save()
        return [ '@redirect': "/v/job/${ job._id }" ]
    }

    dialog = """
        <h2>Delete Comment</h2>
        <p>Are you sure you want to delete this comment?</p>
        <info-container color="gray">${ comment.text }</info-container>

        <button-group>
            <button color='red' type='button'
                    on-click="${ _{ doDeleteComment() }}">Delete</button>
            <button type='button' target='hud-dialog'
                    on-click="${ _{ [ '@remove' ] }}">Cancel</button>
        </button-group>
    """
}
%>
```

Note the error handling: if the comment is not found or the user lacks permission, the dialog still opens but shows an error message instead of the delete form.

*Source: MadAve-Collab -- edit-comment.ghtml*

---

## Navigation and Redirects

### Server-Side Redirect

Return a Map Transmission with `@redirect` to navigate to a new page:

```html
<button on-click="${ _{ [ '@redirect' : 'g/' + gb._id] }}">
    View Guestbook
</button>
```

*Source: Guestbook -- index.ghtml*

### Navigate Back

Use `@back` to go to the previous page in browser history:

```groovy
def doSave = { t ->
    // ... save data ...
    return [ '@back' ]
}
```

*Source: MadAve-Collab -- create-job.ghtml*

### Conditional Redirects

From MadAve-Collab's job creation -- redirect to different pages based on context and user role:

```groovy
def doCreateJob = { t ->
    // ... create the job ...

    if (existingJob)
        return [ '@back' ]  // Go back to previous page on edit
    else if (UserManager.isInternalUser(client.document))
        return [ '@redirect': '/home/active-jobs?m=new-job-success' ]
    else
        return [ '@redirect': '/home/voiceover-jobs?m=new-job-success' ]
}
```

The `?m=new-job-success` query parameter is used on the target page to display a success notification.

*Source: MadAve-Collab -- create-job.ghtml*

---

## WebSocket Communication

### Sending Data via WebSocket

The `sendData()` function sends a JSON payload to the server over the WebSocket connection. The first argument is the handler ID, which routes the message to the correct `@Alert('on socket <id>')` handler on the server:

```javascript
sendData('chat-message', { text: 'Hello!', room: 'lobby' })
```

On the server, handle the message with an Alert:

```groovy
@Alert('on socket chat-message')
static handleChatMessage(Result r) {
    def text = r.data.text
    def room = r.data.room
    // ... broadcast to room participants ...
}
```

`sendData()` is asynchronous and automatically retries every 25ms if the WebSocket is not yet open. Message ordering is not guaranteed.

### WebSocket Reconnection Handling

From MadAve-Collab's wrapper -- detect when the WebSocket connection drops and prompt the user to reconnect:

```javascript
function setupSocketHandler() {
    if (typeof socket !== 'undefined' && socket !== null && socket.readyState === 1) {
        socket.onclose = function() {
            insertNotification(
                `<p><a style='cursor: pointer; font-weight: 600; text-decoration: underline;'
                      onclick='location.reload();'>Click to reconnect</a></p>`,
                0, true
            );
        };
    } else {
        // Retry after a short delay if socket is not yet available
        setTimeout(setupSocketHandler, 100);
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setupSocketHandler);
} else {
    setupSocketHandler();
}
```

This pattern polls for the `socket` variable (set up by Spaceport's WebSocket initialization, which is separate from HUD-Core) and attaches an `onclose` handler that shows a reconnection notification.

*Source: MadAve-Collab -- _wrapper.ghtml*

---

## Custom JavaScript Integration

### Third-Party Library Integration

From the Guestbook application -- integrating the QRCode.js library alongside HUD-Core:

```html
<script src="/assets/qrcode.min.js" type="application/javascript"></script>

<!-- Later, in the template body -->
<div id="qrcode" class="centered"></div>
<script type="text/javascript">
    var qrcode = new QRCode(document.getElementById('qrcode'), {
        text: 'https://www.guestbook.ing/g/${ gb._id }',
        width: 256,
        height: 256,
        colorDark: '#000000',
        colorLight: '#ffffff',
        correctLevel: QRCode.CorrectLevel.H
    });
</script>
```

HUD-Core does not interfere with third-party libraries. Scripts embedded in templates execute normally at page load, and scripts inside dynamically inserted content (from Transmissions) are evaluated automatically by HUD-Core's MutationObserver.

*Source: Guestbook -- guestbook.ghtml*

### Injecting Notifications via Inline Script

From MadAve-Collab -- display toast-style notifications by including a `<script>` tag in the template output. This works both at page load and when injected via Transmission:

```html
${ """<script>insertNotification("${ messages[data.error] }")</script>""".if { data.error } }
${ """<script>insertNotification("${ messages[data.message] }")</script>""".if { data.message } }
```

*Source: MadAve-Collab -- login.ghtml, forgot.ghtml*

### Notification System

From MadAve-Collab's wrapper -- a reusable client-side notification system that queues messages until the tray element is ready:

```javascript
var notificationQueue = [];
var trayReady = false;

function insertNotification(html, duration, shade = false) {
    var tray = document.querySelector('tray');

    // If tray doesn't exist yet, queue the notification
    if (!tray) {
        notificationQueue.push({ html: html, duration: duration, shade: shade });
        return;
    }

    var uid = 'notif-' + Math.random().toString(36).substr(2, 9);
    tray.insertAdjacentHTML('beforeend',
        `<hud-notification id=\${ uid } \${ shade ? 'shade=true' : '' }>\${ html }</hud-notification>`
    );

    if (duration > 0) {
        setTimeout(function() {
            var notif = document.getElementById(uid);
            if (notif) notif.remove();
        }, duration * 1000);
    }
}
```

Note the escaped `\${}` in the template literal -- this is necessary inside `.ghtml` files because `${}` would otherwise be interpreted as a Groovy expression.

*Source: MadAve-Collab -- _wrapper.ghtml*

### Manipulating the DOM from Inline Scripts

Scripts in templates can manipulate elements on the page. This works because the script executes after the DOM is rendered:

```html
<% if (userGuestbooks.size() > 0) { %>
    <script>
        // Close the "create new" details if user already has guestbooks
        document.querySelector('details').removeAttribute('open')
    </script>
<% } %>
```

*Source: Guestbook -- history.ghtml*

---

## Loading States

### CSS-Based Loading Indicator

During a server round-trip, HUD-Core adds `loading="true"` to the payload target element. Use CSS to style this state:

```css
[loading] {
    opacity: 0.5;
    pointer-events: none;
}
```

More elaborate loading indicators:

```css
[loading] {
    position: relative;
}
[loading]::after {
    content: '';
    position: absolute;
    inset: 0;
    background: rgba(255, 255, 255, 0.7);
}
```

The `loading` attribute is automatically removed when the server response arrives.

---

## HREF Navigation on Non-Anchor Elements

HUD-Core automatically adds click and keyboard navigation to any non-`<a>` element with an `href` attribute:

```html
<!-- Clickable heading navigates to job view -->
<h3 style="cursor: pointer;" href="/v/job/${ row.id }">
    ${ job.projectName ?: 'Untitled' }
</h3>

<!-- Clickable list items in popover menus -->
<ul>
    <li href="/v/job/${ row.id }">View Job</li>
    <li href="/home/create-job?edit=${ job.id }">Edit Job</li>
</ul>

<!-- Image that navigates to the home page -->
<img href="/" src="/assets/img/icon.svg" style="height: 5em;">
```

HUD-Core automatically:

- Adds a click handler that navigates to the URL via `window.location.href`
- Sets `tabindex="0"` for keyboard accessibility (if not already present)
- Adds Enter key navigation

This works on dynamically inserted content as well, since the MutationObserver processes new elements with `href` attributes.

*Sources: MadAve-Collab -- upload-audio.ghtml, active-jobs.ghtml; port-mercury -- wrapper.ghtml*

---

## Multi-Step Workflows

### State-Driven Multi-Step Forms

From MadAve-Collab's password reset flow -- use server-side state (stored in the user's session dock) to control which step of a multi-step workflow is displayed:

```html
<%
    // Keep state in the user's session-scoped Cargo
    def resetProcess = dock.'reset-process' as Cargo

    def currentStep  = { resetProcess.getDefaulted('current-step', 'email') as String }
    def userEmail    = { resetProcess.getDefaulted('user-email', '') as String }

    def submitEmail = { t ->
        def result = sendEmail(t.email)
        if (result.success) {
            resetProcess.set('user-email', result.email)
            resetProcess.set('current-step', 'code')
        }
    }

    def submitCode = { t ->
        def verifyResult = verifyCode(t.code)
        if (verifyResult.success) {
            resetProcess.set('verified', true)
            resetProcess.set('expiry', 30.minutes() + System.currentTimeMillis())
            [ '@redirect' : '/reset' ]
        } else {
            errorMessage = verifyResult.message
        }
    }
%>

<!-- Step 1: Enter email -->
<step-container id="email-step">
    <p>Enter your email and we'll send you a code to reset your password.</p>
    <form on-submit="${ _{ result -> submitEmail(result) }}">
        <label for="email">Email Address:</label>
        <input type="email" id="email" name="email" required>
        <input type="submit" value="Send Code">
    </form>
</step-container>

<!-- Step 2: Enter verification code -->
<step-container id="code-step">
    <p>Enter the code sent to <strong>${{ userEmail() }}</strong>.</p>
    <form on-submit="${ _{ t -> submitCode(t) }}">
        <input type="text" name="code" pattern="[0-9]{6}" maxlength="6" required
               placeholder="000000">
        <input type="submit" value="Verify Code">
    </form>
    <p>
        <a on-click="${ _{ resendCode() }}">Resend code</a> |
        <a on-click="${ _{ resetProcess.set('current-step', 'email') }}">Use different email</a>
    </p>
</step-container>
```

Step visibility is controlled by reactive CSS (shown earlier in the [Conditional Display](#css-driven-step-visibility) section). The `on-click` on "Use different email" changes the server-side state, which causes the reactive CSS to re-render and show the email step again.

Note that `on-click` can be used on `<a>` elements just like `<button>` elements.

*Source: MadAve-Collab -- forgot.ghtml*

---

## Autocomplete and Fuzzy Search

### Reactive Datalist with `on-keyup`

From MadAve-Collab's job creation form -- use `on-keyup` to trigger server-side fuzzy search and reactively update a `<datalist>`:

```html
<%
    def fuzzyClients = []
    def currentClient = ''

    def updateFuzzyClients = { String val ->
        if (val.length() > 2)
            fuzzyClients = ContextManager.getClients(val).take(5)
        else
            fuzzyClients = []
    }
%>

<datalist id="clients">
    ${{ fuzzyClients.combine { """<option value="${ it }"></option>""" } }}
</datalist>

<label for="client">Client</label>
<input type="text" id="client" name="client"
       on-keyup="${ _{ t -> updateFuzzyClients(t.value) }}"
       on-change="${ _{ t -> currentClient = t.value }}"
       list="clients"
       value="${ existingJob?.client ?: '' }">
```

How this works:

1. The user types in the input field.
2. `on-keyup` fires on each keystroke, sending the current value to the server.
3. The server closure searches for matching clients (only if 3+ characters) and updates the `fuzzyClients` list.
4. Because the `<datalist>` content is wrapped in `${{ }}`, it reactively re-renders with the new suggestions.
5. `on-change` fires when the user selects or commits a value, updating `currentClient` for use in dependent fields.

### Chained Autocomplete Fields

The same pattern can chain fields -- the project name autocomplete filters by the selected client:

```html
<%
    def fuzzyProjects = []
    def updateFuzzyProjects = { String val ->
        fuzzyProjects = ContextManager.getProjects(currentClient, val).take(5)
    }
%>

<datalist id="projects">
    ${{ fuzzyProjects.combine { """<option value="${ it }"></option>""" } }}
</datalist>

<label for="projectName">Project Name</label>
<input type="text" id="projectName" name="projectName" list="projects"
       on-keyup="${ _{ t -> updateFuzzyProjects(t.value) }}"
       value="${ existingJob?.projectName ?: '' }">
```

The `updateFuzzyProjects` closure uses `currentClient` (set by the previous field's `on-change`) to filter project suggestions.

*Source: MadAve-Collab -- create-job.ghtml*

---

## File Upload Patterns

### File Upload via Server Element

From MadAve-Collab -- file uploads are handled by a custom `<g:file-drop>` Server Element that manages direct-to-cloud uploads. The form submission sends file metadata (not the file bytes) to the server:

```html
<%
def doUploadAudio = { t ->
    def fileListJson = t.getString('file-list')
    if (fileListJson) {
        def fileMetaList = new groovy.json.JsonSlurper().parseText(fileListJson)
        fileMetaList?.each { fileMeta ->
            Job.Attachment metaData = new Job.Attachment().tap {
                id = UUID.randomUUID().toString()
                fileName = fileMeta.fileName
                fileType = fileMeta.fileType
                fileSize = fileMeta.fileSize as Long
                uploadedAt = System.currentTimeMillis()
                uploadedBy = r.client.userID
            }
            if (!job.attachments) job.attachments = []
            job.attachments.add(metaData)
        }
        job.updateStatus(VoiceJob.STATUS_READY_FOR_ENGINEERING)
        job.save()
        Job.currentJobs._update()
    }
    return [ '@remove' ]
}
%>

<form target='hud-dialog' on-submit="${ _{ t -> doUploadAudio(t) }}">
    <g:file-drop class='top-margin' id="audio-upload" name="file-list" multiple="true"
                 accept="audio/*,.wav,.mp3,.aiff,.m4a"
                 upload-url="/api/upload-url" job-id="${ job.jobId }"></g:file-drop>

    <button type="submit" color="purple">Submit Audio</button>
    <button type="button" on-click="${ _{ [ '@remove' ] }}" class='secondary'>Cancel</button>
</form>
```

Note: For standard `<input type="file">` elements (without a custom Server Element), HUD-Core automatically converts files to base64 data URLs before sending them to the server. The `<g:file-drop>` Server Element handles uploads via presigned URLs for large files, which is a custom implementation.

*Source: MadAve-Collab -- upload-audio.ghtml*

---

## Inline Editing and Delete

### Delete with Parent Removal

From the Guestbook application -- each participant entry has an inline delete button that removes the parent container:

```html
${{ gb.participants.combine { Guestbook.ParticipantSchema participant ->
    """
    <div class='narrow-width text-spaced way-less-block-margin'>
        <strong>${ participant.name }</strong>
        ${ participant.email ?: '' }
        <span target='parent' style='cursor: pointer; user-select: none;'
              on-click=${ _{ gb.removeParticipant(participant.cookie); [ '@remove' ] }}>
            Remove
        </span><br>
        ${ participant.time_signed.relativeTime() }
        ${ "<br><p>${ participant.message}</p>".if { participant.message } }
    </div>
    """ }
}}
```

The `target='parent'` ensures that `@remove` removes the entire `<div>` containing the participant entry, not just the delete span.

*Source: Guestbook -- guestbook.ghtml*

### Inline Flag Clearing

From MadAve-Collab's admin panel -- each notification flag has an inline "Clear" button:

```html
${{
    notificationFlags.combine { key, label ->
        def flagValue = job.cargo.'notifications-sent'?."${ key }"
        def clearBtn = flagValue
            ? """ <a class="button small"
                     on-click="${ _{ job.cargo.'notifications-sent'.delete(key); job.save(); job._update() }}"
                     style="font-size: 0.8em;">Clear</a>"""
            : ''

        """<detail-item>
            <label>${ label }</label>
            <span class="value">${ statusBadge }${ clearBtn }</span>
        </detail-item>"""
    }
}}
```

Since this content is generated inside a `${{ }}` reactive block, the re-render after `_update()` produces fresh HTML with the "Clear" button removed for the flag that was just cleared. HUD-Core's MutationObserver ensures all new `on-click` attributes are properly bound.

*Source: MadAve-Collab -- job/view.ghtml*

### Bulk Action

A single button that performs a batch operation:

```html
<%
def doClearAllFlags = {
    notificationFlags.each { key, label ->
        job.cargo.'notifications-sent'.delete(key)
    }
    job.cargo.delete('last-comment-notification-timestamp')
    job.save()
    job._update()
}
%>

<button class="small secondary" on-click="${ _{ doClearAllFlags() }}">
    Clear All Flags
</button>
```

*Source: MadAve-Collab -- job/view.ghtml*

---

## Server Elements with HUD-Core

### Server Element in a Form

Server Elements integrate seamlessly with HUD-Core's event system and form data collection. When a Server Element has a `name` attribute, HUD-Core includes its `.value` property in the form data:

```html
<g:text-editor class='top-margin' id="brief-editor" name="brief"
               on-change=${ _{ t -> /* handle change */ }}
               key="create-job-brief">
    ${ existingJob?.brief ?: '' }
</g:text-editor>
```

The `TextEditor` Server Element wraps a rich text editor. It dispatches standard DOM events (`change`, `blur`, `focus`) that HUD-Core binds with `on-change`, `on-blur`, etc. When the form is submitted, HUD-Core reads the element's `.value` property (which the Server Element keeps synchronized with the editor content).

### Server Element Stat Cards

Server Elements can accept server action closures as attribute values:

```html
<% def countUsers = { return it.size() } %>
<g:stat-card value="${_{ userList }}" transform="${ _{ countUsers }}" color="green" icon="users">
    Total Users
</g:stat-card>
```

Here the Server Element receives server action endpoints as its `value` and `transform` attributes, allowing it to fetch and transform data reactively.

*Source: MadAve-Collab -- manage-users.ghtml*

### Tag Input Server Element

A multi-value input element that participates in form submission:

```html
<datalist id='permission-options'>
    <option value='Admin'></option>
    <option value='Traffic'></option>
    <option value='Voice Talent'></option>
</datalist>

<g:tag-input id='permissions' name='permissions'
             list='permission-options'
             value='${ _{ user.value.permissions.collect { it.titleCase() } }}'>
</g:tag-input>
```

The `value` attribute receives a server action closure that provides the initial list of tags. The `name="permissions"` attribute means HUD-Core includes the tag values in form data as a list, accessible via `t.getList('permissions')`.

*Source: MadAve-Collab -- user-edit.ghtml*

---

## Element Lifecycle Patterns

### Mutation Hook

Assign a function to an element's `mutated` property. HUD-Core calls it when the element is added to the DOM (including when injected via a Transmission):

```javascript
var myElement = document.getElementById('my-widget')
myElement.mutated = function(node) {
    // Initialize the widget, start animations, etc.
    console.log('Widget added to DOM:', node)
}
```

This is primarily used by Server Elements for initialization.

### Removal Hook

Assign a function to an element's `removed` property. HUD-Core calls it when the element is removed from the DOM:

```javascript
var timer = setInterval(updateClock, 1000)

myElement.removed = function(node) {
    clearInterval(timer)
    console.log('Widget removed, timer cleared')
}
```

### Attribute Change Hook

Monitor attribute changes on an element:

```javascript
myElement.attributeChanged = function(node, attributeName, oldValue, newValue) {
    if (attributeName === 'data-status') {
        console.log('Status changed from', oldValue, 'to', newValue)
    }
}
```

### Server Element Lifecycle

Server Elements (custom elements with `element-id`) get automatic lifecycle management from HUD-Core. When removed from the DOM, HUD-Core automatically:

1. Removes all event listeners registered via the element's `.listen()` method.
2. Calls the `deconstructed()` hook if defined.
3. Deletes the `window.element_<id>` global reference.

This cleanup prevents memory leaks when Server Elements are dynamically added and removed (for example, when dialog content is replaced).

---

## Common Gotchas

### Event Propagation Is Stopped

All `on-*` events call `event.stopPropagation()`. This means nested elements with `on-*` attributes will not bubble events to their parents. If you need a parent handler to also fire, you can use the `@nudge` Transmission action to dispatch a custom event that bubbles:

```html
<button on-click="${ _{ [ '@nudge': null ] }}">
    Nudge parent
</button>
```

### Dollar Signs in Embedded JavaScript

The `$` character is interpreted by Groovy's template engine in `.ghtml` files. When writing JavaScript or CSS inside templates, escape literal dollar signs with a backslash:

```html
<!-- Wrong: Groovy will try to evaluate ${uid} as an expression -->
<script>
    var elem = `<div id=${uid}></div>`
</script>

<!-- Right: Escaped dollar sign -->
<script>
    var elem = `<div id=\${uid}></div>`
</script>
```

External `.js` and `.css` files linked via `<script src>` or `<link href>` do not need escaping.

### Target Resolution Walks Up the DOM

If no `target` attribute is found on the element with the `on-*` attribute, HUD-Core walks up the DOM tree looking for an ancestor with a `target` attribute. This is intentional and allows parent containers to define default targeting behavior for their children. Be aware that an ancestor's target could unexpectedly apply to your element.

### Forms Inside Transmissions Work Automatically

One of HUD-Core's most useful features: when a Transmission injects HTML that contains `on-*` attributes, the MutationObserver automatically binds those events. You can return an entire form from a server action and its `on-submit` handler will work immediately without any additional setup. This is what makes the dialog pattern possible.

### `on-change` on Inputs Adds Enter-Key Blur

For `<input>` elements with `on-change`, HUD-Core adds an additional keydown listener that blurs the input when Enter is pressed. This triggers the change event for text inputs (which normally require the user to click away):

```html
<!-- Pressing Enter in this input triggers the on-change handler -->
<input type="text" on-change="${ _{ t -> updateValue(t.value) }}">
```

### Data Attributes Use Dash-Case in Payloads

When reading `data-*` attributes in the server closure, the keys use their dash-case form with the `data-` prefix stripped (not camelCase):

```html
<button data-user-id="123" on-click="${ _{ t ->
    // Access as: t['user-id'], NOT t.userId
    def userId = t['user-id']
}}">Edit</button>
```

### Server Action Cleanup Is Automatic

When elements with `on-*` attributes are removed from the DOM, HUD-Core collects their UUID references and sends a cleanup request to the server. This allows Spaceport to free memory associated with the server-side closures. This happens automatically and requires no developer action.

### Include HUD-Core After the Body

Place the HUD-Core script tag after the `<body>` content or use the `defer` attribute. HUD-Core needs the DOM to be ready before it can bind events and start the MutationObserver:

```html
<!-- Option 1: defer attribute -->
<script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>

<!-- Option 2: After body content -->
<body>
    <payload/>
</body>
<script src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
```

*Sources: port-mercury -- wrapper.ghtml; MadAve-Collab -- _wrapper.ghtml*

---

## Pattern Summary

| Pattern | Key Technique | Example |
|---|---|---|
| Fire-and-forget action | Array Transmission with action | `['@print']`, `['@reload']` |
| Update self | `target="self"` + single value | `'New button text'` |
| Replace entire element | `target="outer"` + HTML string | Full `outerHTML` replacement |
| Update remote element | `target="#id"` or `#id` map key | Replace innerHTML of a specific element |
| Multi-operation response | Map Transmission | `['disabled': 'true', '#status': 'Saved']` |
| Class manipulation | Array or Map with `+`/`-` | `['+active', '-pending']` |
| Form handling | `on-submit` + `t` parameter | `t.name`, `t.getString()`, `t.getBool()`, `t.getList()` |
| Dialog open/close | Reactive variable + `@remove` | Set dialog HTML, then remove it |
| List item actions | `on-click` in loop with captured variable | Unique closure per item |
| Navigation | `href` on any element | `<div href="/path">`, `<li href="/path">` |
| Redirect | `@redirect` in Map Transmission | `['@redirect': '/dashboard']` |
| Status change | `on-change` on `<select>` | Dropdown triggers server save |
| Clear after submit | `target` + `['@clear']` | Reset form fields after submission |
| Autocomplete | `on-keyup` + reactive `<datalist>` | Server-side fuzzy search with `${{ }}` |
| Conditional display | `${{ "html".if { condition } }}` | Reactive show/hide |
| Multi-step workflow | Session state + reactive CSS | Step visibility controlled by server-side Cargo |
| Loading indicator | CSS `[loading]` selector | Automatic during server round-trips |

---

## What's Next

- **[HUD-Core Overview](hud-core-overview.md)** -- High-level introduction to HUD-Core and how it fits into the Spaceport ecosystem.
- **[HUD-Core API Reference](hud-core-api.md)** -- Complete reference for all events, data payloads, Transmission formats, and special attributes.
- **[HUD-Core Internals](hud-core-internals.md)** -- How the MutationObserver, event binding, fetch pipeline, and Proxy-based reactivity work under the hood.
