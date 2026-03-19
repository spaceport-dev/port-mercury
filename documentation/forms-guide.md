# Forms Guide

Forms in Spaceport can work in two fundamentally different ways: as traditional page-submitting HTML forms, or as server-action forms that process data without a page reload. This guide covers both approaches, the data handling for each, and the patterns that emerge from combining them.

---

## Two Approaches to Forms

### Traditional Forms (MPA Style)

A standard HTML form with an `action` attribute submits via a full HTTP request. You handle it with an `@Alert` route, read the data from `r.context.data`, and either redirect or render a response. The page reloads.

```groovy
@Alert('on /contact POST')
static _submitContact(HttpResult r) {
    def name = r.context.data.name
    def email = r.context.data.email
    def message = r.context.data.message

    saveContactMessage(name, email, message)
    r.setRedirectUrl('/contact?sent=true')
}
```

```html
<form action="/contact" method="POST">
    <input name="name" required>
    <input name="email" type="email" required>
    <textarea name="message" required></textarea>
    <button type="submit">Send</button>
</form>
```

### Server Action Forms (Transmission Style)

A form with `on-submit` uses a server action closure instead of a route. HUD-Core intercepts the submit event, collects all form data, and POSTs it to the server via an internal endpoint. The closure runs server-side and returns a Transmission response — no page reload, no route to define.

```groovy
<%
    def submitContact = { t ->
        saveContactMessage(t.name, t.getString('email'), t.getString('message'))
        ['@reload']
    }
%>

<form on-submit=${ _{ t -> submitContact(t) }}>
    <input name="name" required>
    <input name="email" type="email" required>
    <textarea name="message" required></textarea>
    <button type="submit">Send</button>
</form>
```

The server action approach is the more common pattern in Spaceport applications — it's simpler (no route needed) and provides a smoother user experience.

---

## How Form Data Reaches the Server

### Traditional Forms

When a traditional form submits, the browser sends the data as either `application/x-www-form-urlencoded` (default) or `multipart/form-data` (when `enctype` is set, typically for file uploads). Spaceport's `HttpRequestHandler` parses the incoming data and places it into `r.context.data` as a flat Map.

**URL-encoded forms** (default):

```html
<form action="/register" method="POST">
    <input name="username" value="alice">
    <input name="age" value="30">
</form>
```

Server receives: `r.context.data == [username: 'alice', age: '30']`

All values are strings. Use type coercion as needed:

```groovy
@Alert('on /register POST')
static _register(HttpResult r) {
    def username = r.context.data.username          // String
    def age = r.context.data.age as int             // Cast to int
}
```

**JSON bodies** (`Content-Type: application/json`):

If the client sends JSON (e.g., from a JavaScript `fetch` call), Spaceport parses it with `JsonSlurper` and the data map preserves the original types — numbers are numbers, booleans are booleans, nested objects are maps.

```groovy
@Alert('on /api/order POST')
static _createOrder(HttpResult r) {
    def items = r.context.data.items    // List
    def total = r.context.data.total    // Number
}
```

**GET and DELETE requests** have their query parameters placed into `r.context.data`. When a parameter appears multiple times, the last value wins.

### Server Action Forms

When a form with `on-submit` is submitted, HUD-Core collects the data client-side using the `FormData` API and sends it as a JSON POST to the server action's internal endpoint (`/!/lp/bind`). The closure receives the data as its `t` parameter — a Map enhanced with type-coercion helper methods.

HUD-Core applies special handling to certain input types before sending:

| Input Type | Client-Side Behavior | What `t` receives |
|:-----------|:--------------------|:-------------------|
| Text, email, tel, url, etc. | Sent as string | `String` |
| Checkbox | Sent as `true`/`false` (not `"on"`) | `boolean` |
| Radio | Sent as the selected value, or `true`/`false` | `boolean` or `String` |
| Number, range | Sent as string | `String` (use `t.getNumber()`) |
| Date, datetime-local, month, week, time | Converted to milliseconds since epoch | `Number` (long) |
| Select | Sent as the selected option's value | `String` |
| Select (multiple) | Sent as an array of selected values | `List` |
| File | Converted to base64 with metadata | `List` of `[name: String, value: String]` |
| Textarea | Sent as string | `String` |
| Hidden | Sent as string | `String` |

Additionally, HUD-Core collects:
- **`data-*` attributes** from the element with the event listener and the element that triggered the event (stripped of the `data-` prefix)
- **`id`** of the active target element (if it has one)
- **`value`** of the active target element (for non-form events like `on-click`)
- **Keyboard modifiers** — `keyCode`, `shiftKey`, `ctrlKey`, `altKey`, `metaKey` (when applicable)

---

## The Transmission Object (`t`)

In a server action closure like `_{ t -> ... }`, the `t` parameter is a Groovy Map with all the form data as key-value pairs. Spaceport enhances this Map with helper methods for type coercion.

### Direct Access

Access form values directly by their input `name`:

```groovy
_{ t ->
    def name = t.name           // String value from <input name="name">
    def email = t.email         // String value from <input name="email">
    def page = t.page           // String value from <input name="page"> or data-page attribute
}
```

### Type Coercion Methods

| Method | Returns | Behavior |
|:-------|:--------|:---------|
| `t.getString(key)` | `String` | Calls `.toString()` on the value. Returns empty string on null. |
| `t.getBool(key)` | `boolean` | Groovy truthiness coercion. `true`, `"true"`, `"on"`, checked → `true`. |
| `t.getInteger(key)` | `int` | Calls `.toInteger()`. Use for whole numbers. |
| `t.getNumber(key)` | `Number` | Parses intelligently: strings with decimals → `Double`, without → `Long`. Returns `0` on failure. |
| `t.getList(key)` | `List` | Returns Lists as-is. Parses JSON arrays. Splits comma-separated strings. Wraps single values. |

### Examples

```groovy
_{ t ->
    // Text fields
    def name = t.name                           // raw string
    def cleanName = t.name.clean()              // sanitized HTML

    // Checkboxes (HUD-Core sends true/false, not "on")
    def agreed = t.getBool('termsAccepted')

    // Numbers
    def quantity = t.getInteger('quantity')
    def price = t.getNumber('price')

    // Dates (HUD-Core converts to epoch millis)
    def startDate = t.getNumber('startDate')    // milliseconds

    // Multi-select
    def tags = t.getList('tags')                // List of selected values

    // Data attributes from the triggering element
    def itemId = t.itemId                       // from data-item-id="..."
}
```

### Sanitization

Always sanitize user-provided content before storing or displaying it:

```groovy
_{ t ->
    def name = t.name.clean()          // Strip dangerous HTML
    def bio = t.getString('bio').clean()
}
```

Use `.clean()` for content that will be rendered as HTML, and `.escape()` for content used in HTML attribute values.

---

## Targeting and Feedback

Server action forms return Transmission responses that update the DOM. The `target` attribute on the form (or its ancestors) determines where the response is applied.

### Setting the Target

```html
<!-- Update a specific element by selector -->
<form on-submit=${ _{ t -> processForm(t) }} target="#result">
    ...
</form>
<div id="result"></div>

<!-- Update the submit button itself -->
<form on-submit=${ _{ t -> processForm(t) }} target="button">
    ...
    <button type="submit">Save</button>
</form>

<!-- No UI update needed -->
<form on-submit=${ _{ t -> saveQuietly(t) }} target="none">
    ...
</form>
```

### Target Resolution

If no `target` is specified on the form, HUD-Core walks up the DOM looking for an ancestor with a `target` attribute.

| Target Value | Resolves To |
|:-------------|:------------|
| `"self"` or `"outer"` | The element with the event listener (the form) |
| `"this"` | The element that triggered the event (e.g., the button) |
| `"parent"` | The parent element of the event listener |
| `"none"` | No DOM update (fire-and-forget) |
| `"#id"` | `document.querySelector('#id')` |
| `".class"` | `document.querySelector('.class')` |
| `"tag"` | `document.querySelector('tag')` |

### Returning Feedback

**String** — replaces the target's innerHTML:

```groovy
_{ t -> "Saved successfully!" }
```

**Map** — multiple instructions executed in key order:

```groovy
_{ t ->
    [
        '#status': 'Saved!',              // Update #status element's innerHTML
        'disabled': 'true',               // Set disabled attribute on target
        '+success': 'it',                 // Add 'success' CSS class to target
    ]
}
```

**Array** — action commands:

```groovy
_{ t -> ['@reload'] }                     // Reload the page
_{ t -> ['@clear'] }                      // Clear the target element's value
_{ t -> ['@redirect', '/dashboard'] }     // Navigate to URL
```

### The `loading` Attribute

While a server action is in-flight, HUD-Core sets `loading="true"` on the target element. Use this for CSS-based loading states:

```css
button[loading] {
    opacity: 0.6;
    pointer-events: none;
}

form[loading] button[type="submit"] {
    cursor: wait;
}
```

The attribute is removed automatically when the response arrives.

---

## File Uploads

### Traditional POST (Recommended for Files)

For file uploads, use a traditional multipart form. Spaceport handles `multipart/form-data` natively — file inputs become maps with `name` (original filename) and `data` (byte array), while regular fields remain strings.

```html
<form action="/upload" method="POST" enctype="multipart/form-data">
    <input type="file" name="avatar">
    <input name="caption" type="text">
    <button type="submit">Upload</button>
</form>
```

```groovy
@Alert('on /upload POST')
static _upload(HttpResult r) {
    def file = r.context.data.avatar         // Map: [name: 'photo.jpg', data: byte[]]
    def caption = r.context.data.caption     // String: 'My photo'

    def filename = file.name                 // 'photo.jpg'
    def bytes = file.data                    // byte[] — the raw file content

    // Save to disk
    new File("uploads/${filename}").bytes = bytes

    r.setRedirectUrl('/gallery')
}
```

Key details:
- Maximum file size: **50 MB**
- Files are streamed to `/tmp` on disk, not buffered in memory
- File data arrives as `byte[]` — write it to disk, store it in a database, or process it directly
- Regular form fields alongside files are plain strings in the same `r.context.data` map

### Server Action Forms (Base64)

When a form with `on-submit` contains a file input, HUD-Core converts the file to **base64** and sends it as JSON over HTTP. This works but is less efficient for large files (base64 adds ~33% overhead, and the entire payload must fit in memory).

```html
<form on-submit=${ _{ t -> handleUpload(t) }}>
    <input type="file" name="attachment">
    <button type="submit">Upload</button>
</form>
```

```groovy
def handleUpload = { t ->
    def files = t.attachment    // List of [name: 'file.pdf', value: 'base64string...']
    files.each { file ->
        def bytes = file.value.decodeBase64()
        new File("uploads/${file.name}").bytes = bytes
    }
    'Upload complete!'
}
```

**Recommendation:** Use traditional multipart POST for file uploads, especially for large files. Use server action forms for everything else.

---

## Custom Server Elements in Forms

Server elements can participate in forms by implementing `getValue` and `setValue` as `@Javascript` fields. This lets custom components like star ratings, tag inputs, rich text editors, and file droppers behave like native form controls.

### How It Works

When HUD-Core collects form data, it makes two passes:

1. **FormData API** — collects all standard HTML form controls (`<input>`, `<select>`, `<textarea>`)
2. **`[name]` query** — finds any element inside the form that has a `name` attribute and a `.value` property, adding it to the submission if not already collected

Custom elements can participate in this second pass by either:
- Exposing a `.value` property on the DOM element
- Using a hidden `<input>` inside the element that the FormData pass picks up

Additionally, HUD-Core uses `getValue()` and `setValue()` in two other contexts:
- **Transmission responses** — when a server action returns a value targeting a custom element, HUD-Core calls `.setValue(value)` instead of setting `.innerHTML`
- **Reactive binding updates** — when document data changes reactively, HUD-Core calls `.setValue(newValue)` and uses `.getValue()` to diff against the current value to avoid unnecessary updates and dispatches a `change` event when the value changes

### Implementing getValue and setValue

Define them as `@Javascript` String fields that start with `function(`:

```groovy
class StarRating implements Element {

    @Javascript String constructed = /* language=javascript */ """
        function(element) {
            element.listen('click', function(e) {
                if (e.target.tagName === 'SPAN') {
                    let value = e.target.getAttribute('i');
                    if (element.getValue() === 1 && value === '1') {
                        element.setValue(0);    // Toggle off if clicking the only star
                    } else {
                        element.setValue(value);
                    }
                }
            })
        }
    """

    @Javascript String setValue = /* language=javascript */ """
        function(val) {
            this.querySelectorAll('span').forEach(function(span) {
                span.textContent = parseInt(span.getAttribute('i')) <= val ? '\u2605' : '\u2606';
            })
        }
    """

    @Javascript String getValue = /* language=javascript */ """
        function() {
            return this.querySelectorAll('span')
                .filter(s => s.textContent === '\u2605').length;
        }
    """

    String prerender(String body, Map attributes) {
        def max = (attributes.max ?: '5') as int
        def initial = (attributes.value ?: '0') as int
        return (1..max).combine { i ->
            "<span i='${i}'>${i <= initial ? '\u2605' : '\u2606'}</span>"
        }
    }
}
```

Usage in a form:

```html
<form on-submit=${ _{ t ->
    def rating = t.getInteger('rating')
    saveReview(t.getString('comment'), rating)
    ['@reload']
}}>
    <star-rating name="rating" value="0" max="5"></star-rating>
    <textarea name="comment" placeholder="Your review..."></textarea>
    <button type="submit">Submit Review</button>
</form>
```

The `name="rating"` attribute on the `<star-rating>` element causes HUD-Core to include its `.value` (from `getValue()`) in the form submission under the key `rating`.

*Source: MadAve-Collab -- StarRating element*

### Using Hidden Inputs

An alternative approach is to manage a hidden `<input>` inside your element. This has the advantage of working with the `FormData` API's first pass (and potentially with traditional form submissions if the hidden input is a standard `<input>`):

```groovy
class TagInput implements Element {

    @Javascript String constructed = /* language=javascript */ """
        function(element) {
            // ... tag management logic ...

            // Keep a hidden input in sync with the tag state
            element._tagInput = {
                getTags: () => tags,
                setTags: (newTags) => {
                    tags = Array.isArray(newTags) ? [...newTags] : [];
                    render();
                }
            };
        }
    """

    @Javascript String getValue = /* language=javascript */ """
        function() {
            const hiddenInput = this.querySelector('input[type="hidden"]');
            return hiddenInput ? hiddenInput.value : '[]';
        }
    """

    @Javascript String setValue = /* language=javascript */ """
        function(newTags) {
            if (this._tagInput) {
                this._tagInput.setTags(newTags);
            }
        }
    """

    String prerender(String body, Map attributes) {
        def name = attributes.name ?: 'tags'
        return """
            <input type="hidden" name="${name}" value="[]">
            <tag-input-field></tag-input-field>
        """
    }
}
```

The hidden `<input name="tags">` is picked up by `FormData` directly, so the tags are submitted even without the `getValue()` path.

*Source: MadAve-Collab -- TagInput element*

### Traditional Forms and Custom Elements

Custom elements with `getValue`/`setValue` **require JavaScript** — they do not work with traditional MPA-style form submissions unless they use the hidden input pattern. If you need to support no-JS form submission, use a hidden `<input>` to carry the value and treat the custom element as a progressive enhancement.

---

## Validation

### Server-Side Validation with Feedback

Validate in the server action and return targeted error messages:

```groovy
<%
    def submitRegistration = { t ->
        def errors = []

        if (!t.getString('username')?.trim()) {
            errors << 'Username is required'
        }
        if (!t.getString('email')?.contains('@')) {
            errors << 'Valid email is required'
        }
        if (t.getString('password')?.length() < 8) {
            errors << 'Password must be at least 8 characters'
        }

        if (errors) {
            return """<div class="error">${errors.combine { "<p>${it}</p>" }}</div>"""
        }

        createUser(t.getString('username').clean(), t.getString('email'), t.getString('password'))
        ['@redirect', '/welcome']
    }
%>

<form on-submit=${ _{ t -> submitRegistration(t) }} target="#feedback">
    <input name="username" required placeholder="Username">
    <input name="email" type="email" required placeholder="Email">
    <input name="password" type="password" required placeholder="Password">
    <div id="feedback"></div>
    <button type="submit">Register</button>
</form>
```

### Multi-Target Validation Feedback

Use a Map Transmission to update multiple elements and modify the button simultaneously:

```groovy
_{ t ->
    if (!isValid(t)) {
        return [
            '#error-message': 'Please fix the errors above.',
            '-success': 'it',          // Remove success class from target
            '+error': 'it',            // Add error class to target
        ]
    }

    processForm(t)
    return [
        '#error-message': '',
        '+success': 'it',
        'disabled': 'true',
        '#status': 'Saved!'
    ]
}
```

---

## Common Patterns

### Clear After Submit

Clear a textarea or form field after successful submission:

```groovy
<%
    def addComment = { t ->
        def text = t.getString('comment')?.trim()
        if (text) {
            job.addComment(client.document._id, client.document.name, text)
            job.save()
            job._update()
        }
        ['@clear']
    }
%>

<form on-submit=${ _{ t -> addComment(t) }} target="textarea">
    <textarea name="comment" rows="2" placeholder="Write a comment..." required></textarea>
    <button type="submit">Send</button>
</form>
```

The `target="textarea"` points to the `<textarea>` inside the form, and `['@clear']` empties its value. Meanwhile, `job._update()` triggers a reactive update for all connected clients viewing this job.

*Source: MadAve-Collab -- view.ghtml*

### Edit-in-Place

Swap between view and edit modes using `target="outer"` to replace the entire component:

```groovy
<%
    def showEdit = {
        """<div id="user-name" target="outer">
            <input type="text" name="newName" value="${user.name.escape()}">
            <button on-click=${ _{ t ->
                user.name = t.getString('newName').clean()
                user.save()
                showView()
            }}>Save</button>
            <button on-click=${ _{ showView() }}>Cancel</button>
        </div>"""
    }

    def showView = {
        """<div id="user-name" target="outer">
            <span>${user.name.escape()}</span>
            <button on-click=${ _{ showEdit() }}>Edit</button>
        </div>"""
    }
%>

<div id="user-name" target="outer">
    <span>${user.name.escape()}</span>
    <button on-click=${ _{ showEdit() }}>Edit</button>
</div>
```

Each returned HTML string replaces the entire `<div>` (because of `target="outer"`), and each new state includes fresh server action closures for the next interaction.

### Dropdown Change Handler

Use `on-change` for immediate server-side processing when a select changes:

```groovy
<%
    def changeStatus = { t ->
        def newStatus = t.getString('value')
        if (newStatus && availableStatuses.contains(newStatus)) {
            job.updateStatus(newStatus)
            job.save()
            job._update()
        }
    }
%>

<select name="status" on-change=${ _{ t -> changeStatus(t) }}>
    <% availableStatuses.each { status -> %>
    <option value="${status}" ${status == job.status ? 'selected' : ''}>${status}</option>
    <% } %>
</select>
```

For single element events (not inside a form), `t.value` contains the element's current value.

*Source: MadAve-Collab -- view.ghtml*

### Forms Inside Reactive Blocks

Server action forms can live inside `${{ }}` reactive blocks. When the reactive data changes, the form re-renders with fresh closures:

```html
${{ job.comments.combine { comment ->
    def isOwner = comment.userId == currentUserId
    """<div class="comment">
        <strong>${comment.userName}</strong>: ${comment.text}
        ${ isOwner ? """
            <button on-click=${ _{ deleteComment(comment.id); ['@remove'] }}
                    target="parent">Delete</button>
        """ : '' }
    </div>"""
} }}
```

Each iteration of `.combine` captures the specific `comment` in its closure, so the delete button always targets the correct comment.

### Data Attributes for State

Use `data-*` attributes to pass extra state to the server without hidden inputs:

```html
<button data-item-id="42" data-action="archive"
        on-click=${ _{ t ->
            def item = Document.get(t.itemId, 'items')  // from data-item-id
            item.fields.archived = true
            item.save()
            ['@remove']
        }} target="parent">
    Archive
</button>
```

### Include Attribute for Client Storage

Use the `include` attribute to pull data from `localStorage` or `sessionStorage` into the transmission:

```html
<button include="~sessionToken, ~~userPrefs"
        on-click=${ _{ t ->
            // t.sessionToken comes from sessionStorage
            // t.userPrefs comes from localStorage
        }}>
    Submit
</button>
```

Prefix `~` reads from `localStorage`, prefix `~~` reads from `sessionStorage`.

---

## Choosing Between Approaches

| | Traditional POST | Server Action |
|:--|:-----------------|:-------------|
| **Page reload** | Yes | No |
| **Requires a route** | Yes (`@Alert('on /path POST')`) | No (closure in template) |
| **File uploads** | Native multipart, efficient | Base64 over JSON, less efficient |
| **Real-time feedback** | Requires redirect or full re-render | Targeted DOM updates |
| **Works without JavaScript** | Yes | No (requires HUD-Core + WebSocket) |
| **Bookmarkable result** | Yes (via redirect) | No |
| **Data access** | `r.context.data.*` (all strings) | `t.*` with type coercion helpers |
| **Best for** | File uploads, public forms, SEO | Interactive UIs, admin panels, SPAs |

Most Spaceport applications use **server action forms** as the default and fall back to **traditional POST** for file uploads or when JavaScript-free operation is required.

---

## See Also

- [Transmissions Overview](transmissions-overview.md) — how server responses update the DOM
- [Transmissions API Reference](transmissions-api.md) — complete reference for response formats and targeting
- [Transmissions Examples](transmissions-examples.md) — more form patterns from real projects
- [Launchpad API Reference](launchpad-api.md) — the `_{ }` server action syntax and template binding
- [Routing API Reference](routing-api.md) — traditional HTTP request handling
- [Class Enhancements API](class-enhancements-api.md) — `.clean()`, `.escape()`, and other sanitization methods
