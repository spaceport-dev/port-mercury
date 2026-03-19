# Transmissions -- Examples

Real-world Transmission patterns drawn from production Spaceport applications, including the Guestbook.ing and MadAve-Collab projects. Each example demonstrates a common use case with copy-paste-ready code.

## Basic Patterns

### Updating an Element's Text

The simplest Transmission -- return a string, and it replaces the target element's content:

```groovy
<%
    def counter = 0
%>

<button on-click=${ _{ counter++; "${counter} clicks" }} target="#display">
    Click me
</button>
<span id="display">0 clicks</span>
```

The server increments the counter (which persists in the session) and returns the new display string.

### Self-Updating Button

Use `target="self"` to update the button that was clicked:

```html
<button target="self" on-click=${ _{ ['innerText': 'Confirmed!', '+confirmed': 'it'] }}>
    Confirm
</button>
```

The Map Transmission changes the button's text and adds a CSS class to it.

### Fire-and-Forget Action

Use `target="none"` when the server action does not need to update the UI:

```html
<button target="none" on-click=${ _{ logEvent('user-clicked-cta') }}>
    Track This
</button>
```

### Triggering a Print Dialog

Array Transmissions are concise for simple actions:

```html
<button on-click="${ _{ ['@print'] }}">
    Print Poster
</button>
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

---

## Form Processing

### Basic Form Submission

Collect form data via the `t` object, process on the server, and return feedback:

```groovy
<%
    def editGuestbook = { t ->
        gb.info.name = t.name.clean()
        gb.info.open = t.getBool('open')
        gb.save()
        ['@reload']
    }
%>

<form on-submit=${ _{ t -> editGuestbook(t) }}>
    <label for='event-name'>Event Name:</label>
    <input name='name' required type='text' id='event-name'
           autocomplete='off' placeholder='Enter the name of your event'
           value="${ gb.info.name.escape() }">
    <flex>
        <input type='checkbox' name='open' id='open'
               ${ gb.info.open ? 'checked' : '' }>
        <label for='open'>Open for signing</label>
    </flex>
    <flex class='spread'>
        <span></span>
        <button type='submit'>Update</button>
    </flex>
</form>
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

Key points:
- `t.name` gives the raw string value from the `name` input
- `t.name.clean()` sanitizes HTML to prevent XSS
- `t.getBool('open')` safely converts the checkbox value to a boolean
- `['@reload']` is an Array Transmission that refreshes the page

### Inline Form with Multiple Inputs

For simpler forms, define the server action inline:

```html
<form on-submit='${ _{ t ->
    gb.addParticipant(t.name, t.email, t.getBool('public'), t.message, cookie)
    ['@reload']
}}'>
    <input name='name' required type='text' autocomplete='off'
           placeholder='Enter your name'>
    <input name='email' type='email' placeholder='Enter your email (optional)'>
    <flex>
        <input type='checkbox' checked name='public' id='public_email'>
        <label for='public_email'>Show email publicly</label>
    </flex>
    <textarea placeholder="Leave a message (optional)" name="message" rows="4"></textarea>
    <flex class='spread'>
        <span></span>
        <button type='submit'>Done</button>
    </flex>
</form>
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

### Form with Targeted Feedback

Target a specific element with the response while performing server-side validation:

```groovy
<%
    def processOrder = { t ->
        def quantity = t.getInteger('quantity')
        def isPriority = t.getBool('priorityShipping')
        def price = t.getNumber('price')

        if (isPriority && quantity > 0) {
            // ... process order ...
        }

        return ['#order-status': 'Order processed!', 'disabled': 'true']
    }
%>

<form on-submit=${ _{ t -> processOrder(t) }} target='button'>
    <input name="quantity" value="5" data-price="19.99">
    <input type="checkbox" name="priorityShipping" checked>
    <button type="submit">Submit</button>
    <div id="order-status"></div>
</form>
```

The Map Transmission updates the `#order-status` element by selector and disables the button (the `target`).

### Comment Form with Clear

After submitting a comment, clear the textarea using an Array Transmission:

```groovy
<%
    def doAddComment = { t ->
        def commentText = t.getString('comment')?.trim()
        if (commentText) {
            def userName = client.document?.fields?.name ?: 'Unknown'
            job.addComment(client.document._id, userName, commentText)
            job.save()
            job._update()
        }
        return ['@clear']
    }
%>

<form on-submit="${ _{ t -> doAddComment(t) }}" target="textarea" class="comment-form">
    <textarea name="comment" rows="2" placeholder="Write a comment..." required></textarea>
    <br><button type="submit">Send</button>
</form>
```

*Source: MadAve-Collab -- `view.ghtml`*

The `target="textarea"` resolves to the `<textarea>` element, and `['@clear']` empties its value. Meanwhile, `job._update()` triggers reactive updates for all connected clients viewing this job.

---

## Dropdown/Select Change Handler

Use `on-change` to handle select element changes:

```groovy
<%
    def doChangeStatus = { t ->
        def newStatus = t.getString('value')
        if (newStatus && newStatus != job.status && availableStatuses.contains(newStatus)) {
            job.updateStatus(newStatus)
            job.save()
            job._update()
        }
    }
%>

<select name="status" on-change="${ _{ t -> doChangeStatus(t) }}">
    <% availableStatuses.each { status -> %>
    <option value="${ status }" ${ status == job.status ? 'selected' : '' }>${ status }</option>
    <% } %>
</select>
```

*Source: MadAve-Collab -- `view.ghtml`*

The `t.value` (accessed here via `t.getString('value')`) contains the selected option's value.

---

## Reactive Displays with `${{ }}`

### Auto-Updating Text

Reactive bindings update automatically when the underlying data changes:

```html
<strong>Guestbook for <span id='guestbook-name'>${{ gb.info.name }}</span></strong>
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

When `gb.info.name` changes (e.g., via a form submission), the `<span>` updates on all connected clients.

### Reactive Status Badge

Combine reactive bindings with dynamic CSS:

```html
${{ """<badge color="${ statusColor(job.status) }">${ job.status }</badge>""" }}
```

*Source: MadAve-Collab -- `view.ghtml`*

The entire badge HTML is re-rendered whenever `job.status` changes, updating both the text and color.

### Conditional Display

Use the `.if` class enhancement for conditional rendering:

```html
${{ "<div id='closed-guestbook-notice' class='centered narrow-width top-margin padded'><strong>Guestbook is closed.</strong></div>".if { !gb.isOpen() } }}
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

The notice appears or disappears based on the guestbook's open state, reactively.

### Reactive Lists with `.combine`

Render a dynamic list that updates when the underlying data changes:

```html
${{ gb.participants.combine { participant ->
    """
    <div class='narrow-width text-spaced way-less-block-margin'>
        <strong>${ participant.name }</strong>
        ${ (participant.email ?: '').if { participant.public_email } }
        ${ '(You!)'.if { participant.cookie == cookie }}
        <br>
        ${ participant.time_signed.relativeTime() }
    </div>
    """
} }}
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

When a new participant signs the guestbook, the entire list re-renders for all connected clients.

### Reactive List with Inline Actions

Combine reactive rendering with server actions inside the reactive block:

```html
${{ gb.participants.combine { Guestbook.ParticipantSchema participant ->
    """
    <div class='narrow-width text-spaced way-less-block-margin'>
        <strong>${ participant.name }</strong>
        ${ participant.email ?: '' }
        <span target='parent' style='cursor: pointer; user-select: none;'
              on-click=${ _{ gb.removeParticipant(participant.cookie); ['@remove'] }}>
            Delete
        </span><br>
        ${ participant.time_signed.relativeTime() }
        ${ "<br><p>${ participant.message}</p>".if { participant.message } }
    </div>
    """
} }}
```

*Source: Guestbook.ing -- `guestbook.ghtml`*

Each participant entry has its own delete button. The `_{ }` closure captures the specific `participant.cookie` from the loop iteration. Clicking delete removes the participant server-side and removes the parent `<div>` from the DOM with `['@remove']`.

---

## Reactive Comment Thread

A real-time comment thread that updates for all viewers when comments are added or edited:

```html
<comment-thread>
    ${{
        if (!job.comments || job.comments.isEmpty()) {
            return """<info-container color="gray">No comments yet.</info-container>"""
        }

        def sortedComments = job.comments.sort { it.timestamp }

        sortedComments.combine { comment ->
            def isOwner = comment.userId == currentUserId
            def ownerClass = isOwner ? ' own' : ''
            def actionsHtml = ''

            if (isOwner) {
                actionsHtml = """<span class="comment-actions">
                    <a on-click="${ _{ editCommentDialog(comment.id) }}">Edit</a>
                    <a on-click="${ _{ deleteCommentDialog(comment.id) }}">Delete</a>
                </span>"""
            }

            """<comment-bubble class="${ ownerClass }">
                <span class="author">${ comment.userName }</span>
                <span class="date">${ comment.timestamp?.date() ?: '' }</span>
                ${ actionsHtml }
                <p>${ comment.text }</p>
            </comment-bubble>"""
        }
    }}
</comment-thread>
```

*Source: MadAve-Collab -- `view.ghtml`*

This pattern embeds `_{ }` server actions (for edit/delete dialogs) within a `${{ }}` reactive block. When any user adds or modifies a comment and calls `job._update()`, the entire comment thread re-renders on all connected clients.

---

## Notification Flag Management with Cargo

Using Cargo within reactive bindings for admin tooling:

```html
${{
    notificationFlags.combine { key, label ->
        def flagValue = job.cargo.'notifications-sent'?."${ key }"
        def statusBadge = flagValue
            ? """<badge color="green">Sent</badge>
                 <span class="muted">${ new Date(flagValue as long).format('MM/dd/yyyy h:mm a') }</span>"""
            : """<badge color="gray">Not Sent</badge>"""
        def clearBtn = flagValue
            ? """ <a class="button small"
                     on-click="${ _{ job.cargo.'notifications-sent'.delete(key); job.save(); job._update() }}">
                     Clear</a>"""
            : ''

        """<detail-item>
            <label>${ label }</label>
            <span class="value">${ statusBadge }${ clearBtn }</span>
        </detail-item>"""
    }
}}
```

*Source: MadAve-Collab -- `view.ghtml`*

Each flag shows its sent status and timestamp, with a "Clear" button that deletes the Cargo value, saves the document, and triggers a reactive update.

---

## Edit-in-Place Pattern

Swap between view and edit states using `target="outer"` to replace the entire component:

```groovy
<%
    def userName = user.name

    def showEditUI = {
        return """
        <div id="user-profile" target="outer">
            <input type="text" name="newName" value="${userName.escape()}">
            <button on-click=${ _{ t -> saveUserName(t.newName) }}>Save</button>
            <button on-click=${ _{ showViewUI() }}>Cancel</button>
        </div>
        """
    }

    def saveUserName = { newName ->
        user.name = newName
        user.save()
        return showViewUI()
    }

    def showViewUI = {
        return """
        <div id="user-profile" target="outer">
            <span>${user.name.escape()}</span>
            <button on-click=${ _{ showEditUI() }}>Edit</button>
        </div>
        """
    }
%>

<div id="user-profile" target="outer">
    <span>${userName.escape()}</span>
    <button on-click=${ _{ showEditUI() }}>Edit</button>
</div>
```

The `target="outer"` directive causes the entire `<div>` to be replaced with the returned HTML (via `outerHTML`), effectively swapping between view and edit modes. Each new state includes fresh server actions for the next interaction.

---

## Load More / Pagination

Progressively load items without a full page refresh:

```groovy
<%
    def getItems = { page = 0, perPage = 5 ->
        def allItems = (1..23).collect { "Item #$it" }
        def start = page * perPage
        def end = Math.min(start + perPage, allItems.size())
        if (start >= allItems.size()) return [:]
        return [
            items: allItems[start..<end],
            hasMore: end < allItems.size()
        ]
    }

    def loadMoreItems = { t ->
        def nextPage = t.page.toInteger()
        def results = getItems(nextPage)

        def newItemsHtml = results.items.combine { "<li>${it}</li>" }

        def transmission = [
            append: newItemsHtml,
            '#page': nextPage + 1
        ]

        if (!results.hasMore) {
            transmission['@hide'] = 'it'
        }

        return transmission
    }
%>

<ul id="item-list">
    <% getItems().items.each { item -> %>
        <li>${item}</li>
    <% } %>
</ul>

<button target="#item-list" data-page="1"
        on-click=${ _{ t -> loadMoreItems(t) }}>
    Load More
</button>
```

Key techniques:
- `append` adds new `<li>` elements to the `<ul>` without replacing existing content
- `data-page` tracks the current page number, accessed via `t.page`
- `'@hide': 'it'` hides the button itself (`it` = `event.currentTarget`) when there are no more items

---

## Combined Target and Selector Updates

Update multiple parts of the page from a single server action:

```groovy
return [
    'disabled': true,           // Disable the button (payloadTarget)
    '+loading': 'it',           // Add 'loading' class to the button
    '#order-status': 'Saving…'  // Update a separate element by ID
]
```

The `target` attribute controls which element receives attribute and class changes, while selector keys (`#id`, `.class`, `> child`) perform independent innerHTML replacements anywhere in the document.

---

## Dialog Triggers

Open dialogs by returning their rendered HTML from a server action:

```groovy
<%
    @Provided def deleteJobDialog
    prime('/v/job/dialogs/delete-job.ghtml')
%>

<li on-click="${ _{ deleteJobDialog() }}" style="color: var(--md-red); font-weight: 700;">
    Delete Job
</li>
```

*Source: MadAve-Collab -- `view.ghtml`*

The `deleteJobDialog` closure is defined in the primed dialog template file and returns the dialog HTML as a Single Value Transmission, which is rendered into the target.

---

## Tips and Gotchas

### Return Values Matter

Groovy implicitly returns the last expression in a closure. An explicit `return` is only needed if you want to return early:

```groovy
// These are equivalent:
_{ ['@reload'] }
_{ return ['@reload'] }
```

### Map Key Ordering

Groovy maps maintain insertion order. Transmission instructions execute in key order, which can matter when combining content updates with actions:

```groovy
// innerHTML is set BEFORE the focus action fires
return ['innerHTML': '<input id="edit">', '@focus': '#edit']
```

### Escaping in Templates

Use `.escape()` for HTML attribute values and `.clean()` for user-provided content to prevent XSS:

```groovy
<input value="${ userName.escape() }">
<p>${ userComment.clean() }</p>
```

### The `loading` Attribute

While a Transmission is in-flight, HUD-Core sets `loading="true"` on the payload target. Use this for CSS-based loading states:

```css
button[loading] {
    opacity: 0.6;
    pointer-events: none;
}
```

---

## What's Next

- **[Transmissions Overview](transmissions-overview.md)** -- high-level introduction
- **[Transmissions API Reference](transmissions-api.md)** -- complete syntax reference
- **[Transmissions Internals](transmissions-internals.md)** -- implementation deep-dive
