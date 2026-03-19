# Server Elements -- Examples

Real-world patterns drawn from the MadAve-Collab production application, which uses 17+ elements across its interface. These examples illustrate the range of element design strategies: from simple display-only components to complex interactive form controls with client-server communication.

---

## Simple Display Element: UserBadge

The simplest useful element pattern -- server-side rendering only, no JavaScript, no interactivity. The element accesses `client` to read the authenticated user's data.

**`UserBadge.groovy`**

```groovy
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element

class UserBadge implements Element {

    @CSS
    static String styles = """
        & {
            display: flex;
            align-items: center;
            gap: 1.25em;
            user-select: none;

            user-icon {
                background-color: var(--dk-gray);
                border-radius: 50%;
                min-width: 2.75em;
                min-height: 2.5em;
                display: flex;
                align-items: center;
                justify-content: center;
                font-weight: 900;
                border: 1px outset #00000050;
                border-bottom: 4px solid #00000050;
                cursor: pointer;
            }

            user-name {
                font-size: 0.75em;

                span:first-child {
                    font-size: 1.25em;
                    font-weight: 600;
                    display: block;
                    line-height: 1;
                    margin-bottom: 0.25em;
                }

                span badge {
                    background-color: var(--md-red);
                    color: white;
                    padding: 0.1em 0.4em;
                    border-radius: var(--border-minimal);
                    font-size: 0.85em;
                    font-weight: 600;
                }
            }
        }
    """

    String prerender(String body, Map attributes) {
        def userIcon = client.with {
            return "<img src='/assets/icons/user.svg'>"
        }

        def userName = client.with {
            def roles = document.permissions.collect {
                if (it == 'spaceport-administrator') {
                    return 'Developer'.surroundWith('<badge>')
                } else {
                    return it.titleCase().surroundWith('<badge>')
                }
            }
            if (document.fields.name) {
                return "<span>${ document.fields.name }</span><span>${ roles?.first() }</span>"
            } else {
                return "<span>${ userID.replace('@', '@<wbr>') }</span><span>${ roles?.first() }</span>"
            }
        }

        return """
            <user-icon>${ userIcon }</user-icon>
            <user-name>${ userName }</user-name>
        """
    }
}
```

**Template usage:**

```html
<g:user-badge></g:user-badge>
```

**Patterns demonstrated:**
- **Accessing `client`:** The element reads from the authenticated user's `document` (permissions, name, userID) -- all available through the `client` property injected by the trait.
- **Custom child elements for structure:** `<user-icon>` and `<user-name>` are not Server Elements -- they are plain custom HTML tags used for semantic structure and CSS targeting. This avoids class-name collisions.
- **Static CSS:** The `static` keyword signals that this CSS is identical for all instances.
- **Class enhancements:** `.surroundWith('<badge>')`, `.titleCase()`, and `.replace()` are used to format display output inline.

---

## Reactive Display: UserAlerts with Cargo

An element that reactively updates when server-side data changes, using a Cargo-backed reactive expression.

**`UserAlerts.groovy`**

```groovy
import spaceport.computer.memory.virtual.Cargo
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element

class UserAlerts implements Element {

    def alerts

    @CSS
    static String styles = """
        & {
            display: flex;
            align-items: center;
            justify-content: center;
        }
    """

    def showAlerts = {
        if (alerts.size() > 0) {
            return "<img src='/assets/icons/notifications.svg' alt='You have ${ alerts.size() } new alerts' title='You have ${ alerts.size() } new alerts'>"
        } else {
            return "<img src='/assets/icons/notifications-empty.svg' alt='No new alerts' title='No new alerts'>"
        }
    }

    String prerender(String body, Map attributes) {
        alerts = Cargo.fromDocument(client.document).alerts
        return "${{ showAlerts() }}"
    }
}
```

**Template usage:**

```html
<g:user-alerts></g:user-alerts>
```

**Patterns demonstrated:**
- **Reactive expression as the entire return value.** The `${{ showAlerts() }}` wrapping means the entire element content is reactive. When the user's `alerts` Cargo changes anywhere in the application, HUD-Core pushes the re-evaluated closure result to the client.
- **Cargo from a Document.** `Cargo.fromDocument(client.document).alerts` accesses the user's document-backed Cargo, which persists across sessions and can be modified from any part of the application.
- **Closure as a rendering helper.** The `showAlerts` closure is defined as a class property and called from within the reactive expression. This keeps the `prerender()` body clean.

---

## Client-Focused Form Control: CheckBox

A styled checkbox that wraps a native `<input>` with custom visuals, keyboard accessibility, and label association. All interaction is client-side.

**`CheckBox.groovy`**

```groovy
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element
import spaceport.launchpad.element.Javascript

class CheckBox implements Element {

    @CSS
    static String styles = """
        & {
            display: flex;
            align-items: center;
            gap: 0.75em;
            cursor: pointer;
            user-select: none;
            font-size: 0.95rem;

            &:has(input:disabled) {
                cursor: not-allowed;
                opacity: 0.5;
            }

            input {
                position: absolute;
                opacity: 0;
                width: 0;
                height: 0;
                pointer-events: none;
            }

            check-box-control {
                display: flex;
                align-items: center;
                justify-content: center;
                width: 1.5em;
                height: 1.5em;
                border: 1px solid #00000030;
                border-bottom: 4px solid #00000040;
                border-radius: var(--border-minimal);
                background: white;
                flex-shrink: 0;
                transition: background 0.15s ease, border-color 0.15s ease;

                svg {
                    width: 1em;
                    height: 1em;
                    opacity: 0;
                    transform: scale(0.5);
                    transition: opacity 0.15s ease, transform 0.15s ease;
                    stroke: white;
                    stroke-width: 3;
                    fill: none;
                }
            }

            &:has(input:checked) check-box-control {
                background: var(--lt-black);
                border-color: var(--lt-black);

                svg {
                    opacity: 1;
                    transform: scale(1);
                }
            }

            &:has(input:focus-visible) check-box-control {
                outline: 2px solid var(--md-blue, #5b9bd5);
                outline-offset: 2px;
            }
        }
    """


    String prerender(String body, Map attributes) {
        def name = attributes.name ?: ''
        def value = attributes.value ?: 'on'
        def checked = attributes.containsKey('checked') ? 'checked' : ''
        def disabled = attributes.containsKey('disabled') ? 'disabled' : ''
        def required = attributes.containsKey('required') ? 'required' : ''

        return """
            <input type="checkbox" name="${name}" value="${value}" ${checked} ${disabled} ${required}>
            <check-box-control>
                <svg viewBox="0 0 24 24">
                    <polyline points="4,12 10,18 20,6"></polyline>
                </svg>
            </check-box-control>
        """
    }


    @Javascript
    String constructed = /* language=javascript */ """
        function(element) {
            const input = element.querySelector('input');
            if (!input) return;

            // Transfer id to inner input for label[for] support
            if (element.id) {
                input.id = element.id;
                element.removeAttribute('id');
            }

            // Click on element toggles checkbox
            element.addEventListener('click', (e) => {
                if (e.target === input) return; // Let native handle direct input clicks
                if (input.disabled) return;
                if (element.closest('label')) return;

                input.checked = !input.checked;
                input.dispatchEvent(new Event('change', { bubbles: true }));
            });

            // Keyboard support
            element.addEventListener('keydown', (e) => {
                if (e.key === ' ' || e.key === 'Enter') {
                    e.preventDefault();
                    if (input.disabled) return;
                    input.checked = !input.checked;
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }
            });

            // Make focusable if not disabled
            if (!input.disabled) {
                element.setAttribute('tabindex', '0');
            }
        }
    """
}
```

**Template usage:**

```html
<g:check-box name="active" checked>Enable notifications</g:check-box>
<g:check-box name="terms" required>I agree to the terms</g:check-box>
<g:check-box name="archived" disabled>Archived</g:check-box>
```

**Patterns demonstrated:**
- **Boolean attributes.** `checked`, `disabled`, `required` are detected via `attributes.containsKey()` -- they have no meaningful value, just presence.
- **Hidden native input.** A visually hidden native `<input type="checkbox">` handles form submission while custom elements handle the visual presentation. This preserves form compatibility.
- **Accessibility.** Keyboard support (Space and Enter), `tabindex` for focus, `focus-visible` styling, and ID transfer for `<label for="...">` association.
- **No `@Bind` needed.** The element is purely client-side. Form submission carries the checkbox value through the hidden native input.
- **CSS `:has()` selector.** Used to style the visual control based on the hidden input's state (`:has(input:checked)`, `:has(input:disabled)`).

---

## Dashboard Component: StatCard with Closures as Attributes

A statistics display card that receives data and transform closures from the template, with reactive updates.

**`StatCard.groovy`**

```groovy
import spaceport.computer.memory.virtual.Cargo
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element

class StatCard implements Element {

    @CSS
    static String styles = """
        & {
            display: flex;
            align-items: center;
            justify-content: space-between;
            line-height: 1.25em;
            gap: 1em;
            border: 1.5px outset var(--border-outset-color);
            border-radius: var(--border-small);
            padding: 1.75em;

            &[color=gray]   { background-color: var(--lt-gray); }
            &[color=blue]   { background-color: var(--lt-blue); }
            &[color=green]  { background-color: var(--lt-green); }
            &[color=yellow] { background-color: var(--lt-yellow); }
            &[color=red]    { background-color: var(--lt-red); }

            div {
                display: flex;
                flex-direction: column;
                gap: 0.75em;

                span:first-child {
                    font-size: 2em;
                    font-weight: 400;
                }

                span:last-child {
                    font-size: 1em;
                    font-weight: 500;
                    opacity: 0.5;
                }
            }
        }
    """

    def value
    def transform

    String prerender(String body, Map attributes) {
        value     = attributes.value      // A data source (e.g., a Cargo list)
        transform = attributes.transform  // A closure that transforms for display

        return """
            <div>
                <span>${{ transform ? transform(value) : value }}</span>
                <span>${ body }</span>
            </div>
            <span color="${ attributes.color ?: 'gray' }">
                <img src='/assets/icons/card-icons/${ attributes.icon }.svg' alt='${ body } icon'>
            </span>
        """
    }
}
```

**Template usage:**

```html
<%
    def userList = dock.users
    def countUsers = { list -> list.size() }
    def countCreatives = { list -> list.findAll { it.role == 'Creative' }.size() }
%>

<g:stat-card value="${ _{ userList }}" transform="${ _{ countUsers }}" color="green" icon="users">
    Total Users
</g:stat-card>

<g:stat-card value="${ _{ userList }}" transform="${ _{ countCreatives }}" color="yellow" icon="creatives">
    Creatives
</g:stat-card>
```

**Patterns demonstrated:**
- **Closures as attributes.** The `${ _{ expression }}` syntax passes Groovy objects (a Cargo list, a closure) into the element. The template engine resolves these server-side bindings before passing them to `prerender()`.
- **Reactive computed display.** The `${{ transform ? transform(value) : value }}` expression re-evaluates whenever the underlying Cargo data changes. If a user is added to `dock.users`, all stat cards that reference it update automatically.
- **Color via attribute selectors.** The `color` attribute maps to CSS via `&[color=blue]` attribute selectors, keeping color logic in CSS rather than inline styles.
- **Body as label.** The element's body content becomes the label text ("Total Users", "Creatives"), demonstrating how body and attributes serve different roles.

---

## External Library Integration: TextEditor with @Prepend

A rich text editor that wraps the Quill.js library, loading external dependencies via `@Prepend` and providing localStorage draft persistence.

**`TextEditor.groovy`**

```groovy
import spaceport.launchpad.element.*

class TextEditor implements Element {

    def editorId = 6.randomID()

    @Prepend
    String prepend = """
        <link href="https://cdn.quilljs.com/1.3.6/quill.snow.css" rel="stylesheet">
        <script src="https://cdn.quilljs.com/1.3.6/quill.js"></script>
    """

    @CSS
    String css = """
        & {
            display: block;
            height: 18em;
            background-color: var(--white);

            .ql-container.ql-snow {
                border: 1.5px inset #00000040;
                border-radius: var(--border-minimal);
                border-top-left-radius: 0;
                border-top-right-radius: 0;
                height: calc(100% - 2.75rem);
                font-size: 1.25rem;
            }

            .ql-toolbar.ql-snow {
                border: 1.5px outset #00000020;
                background-color: var(--lt-gray);
                height: 2.75rem;
            }

            div.body {
                display: none;
            }
        }
    """

    @Override
    String prerender(String body, Map attributes) {
        return """
            <div id=${ editorId }></div>
            <div class='body'>${ body }</div>
        """
    }

    @Javascript
    String constructed = /* language=js */ """
        function(element) {
            element.editor = new Quill('#${ editorId }', { theme: 'snow' })

            // localStorage persistence via key attribute
            element.storageKey = element.getAttribute('key')
            if (element.storageKey) {
                element.storageKey = 'texteditor_' + element.storageKey
            }

            element.editor.on('text-change', function(delta, oldDelta, source) {
                element.value = element.editor.root.innerHTML
                element.dispatchEvent(new Event('change'))
                if (element.storageKey) {
                    localStorage.setItem(element.storageKey, element.editor.root.innerHTML)
                }
            })

            // Load content: localStorage first, then body slot
            let savedContent = null
            if (element.storageKey) {
                savedContent = localStorage.getItem(element.storageKey)
            }
            let bodyDiv = element.querySelector('.body')
            if (savedContent) {
                element.setValue(savedContent)
            } else if (bodyDiv && bodyDiv.innerHTML.trim() !== '') {
                element.setValue(bodyDiv.innerHTML)
            }
        }
    """

    @Javascript
    def setValue = /* language=javascript */ """
        function(value) {
            this.editor.pasteHTML(value);
        }
    """

    @Javascript
    def clearStorage = /* language=javascript */ """
        function() {
            if (this.storageKey) {
                localStorage.removeItem(this.storageKey);
            }
        }
    """
}
```

**Template usage:**

```html
<g:text-editor key="draft-notes">
    <p>Default content appears if no draft is saved...</p>
</g:text-editor>
```

**Patterns demonstrated:**
- **`@Prepend` for external dependencies.** The Quill CSS and JS are loaded into `<head>` once per page, regardless of how many text editors appear. This prevents duplicate library loading.
- **Instance-unique IDs.** `def editorId = 6.randomID()` generates a unique ID used in both the server-rendered HTML and the client-side JavaScript (via Groovy GString interpolation in the `@Javascript` field). This prevents conflicts when multiple editors are on the same page.
- **localStorage draft persistence.** The `key` attribute enables automatic draft saving and restoring via `localStorage`. The `clearStorage()` method allows programmatic cleanup (e.g., after successful form submission).
- **Hidden body div pattern.** The `body` parameter is rendered into a hidden `<div class='body'>`, then extracted by the `constructed` hook to seed the editor. This lets server-side content flow through the element's body slot.
- **Dispatching standard events.** The element dispatches native `change`, `blur`, and `focus` events so that external code can listen to it like any other form element.

---

## Modal Dialog: HudDialog with Tabs and Dragging

A dialog component that uses the native `<dialog>` element, with optional tab navigation and drag-to-reposition behavior.

**`HudDialog.groovy`**

```groovy
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element
import spaceport.launchpad.element.Javascript

class HudDialog implements Element {

    @CSS
    String styles = """
        & {
            dialog {
                position: absolute;
                margin-inline: auto;
                top: min(10rem, 10vh);
                width: min(700px, 90vw);
                border: 0;
                min-height: 250px;
                border-radius: var(--border-large);
                box-shadow: 0px 2px 5px #80808063;
                padding: 1.5em;

                h2 {
                    margin: .33rem 0 2.25rem 0;
                    font-size: 1.5em;
                    user-select: none;
                }
            }

            [for='close-button'] {
                position: absolute;
                top: 1.4em;
                right: 1.5em;
                cursor: pointer;
                opacity: 0.5;
            }

            tab-strip {
                display: block;
                border-bottom: 2px solid var(--md-gray);

                li {
                    padding: 1em 1.5em;
                    cursor: pointer;
                    border-bottom: 3px solid transparent;

                    &.active {
                        border-bottom-color: var(--madave-purple);
                        background-color: var(--lt-gray);
                    }
                }
            }

            tab-section {
                display: none;
                &.active { display: block; }
            }
        }
    """

    String prerender(String body, Map attributes) {
        return """
            <dialog>
                <img for='close-button' src="/assets/icons/close.svg"
                     alt="Close dialog" title="Close dialog"
                     onclick="this.closest('hud-dialog').remove();">
                ${ body }
            </dialog>
        """
    }

    @Javascript
    String constructed = /* language=javascript */ """
        function(element) {
            const dialog = element.querySelector('dialog');
            dialog.showModal();

            // Tab functionality
            const tabStrip = dialog.querySelector('tab-strip');
            if (tabStrip) {
                const tabButtons = tabStrip.querySelectorAll('li');
                const tabSections = dialog.querySelectorAll('tab-section');

                if (tabButtons.length > 0 && tabSections.length > 0) {
                    tabButtons[0].classList.add('active');
                    tabSections[0].classList.add('active');
                }

                tabButtons.forEach((tab) => {
                    tab.addEventListener('click', function() {
                        tabButtons.forEach(t => t.classList.remove('active'));
                        tabSections.forEach(s => s.classList.remove('active'));
                        tab.classList.add('active');
                        const targetId = tab.getAttribute('for');
                        if (targetId) {
                            const targetSection = dialog.querySelector(
                                'tab-section[id=\"' + targetId + '\"]');
                            if (targetSection) targetSection.classList.add('active');
                        }
                    });
                });
            }

            // Draggable dialog
            let isDragging = false;
            let startX, startY, initialLeft, initialTop;

            dialog.addEventListener('mousedown', function(e) {
                if (e.target.closest('button, input, select, textarea, a, [onclick], tab-strip'))
                    return;
                isDragging = true;
                const rect = dialog.getBoundingClientRect();
                startX = e.clientX; startY = e.clientY;
                initialLeft = rect.left; initialTop = rect.top;
                dialog.style.margin = '0';
                dialog.style.left = initialLeft + 'px';
                dialog.style.top = initialTop + 'px';
                e.preventDefault();
            });

            document.addEventListener('mousemove', function(e) {
                if (!isDragging) return;
                dialog.style.left = (initialLeft + e.clientX - startX) + 'px';
                dialog.style.top = (initialTop + e.clientY - startY) + 'px';
            });

            document.addEventListener('mouseup', function() { isDragging = false; });
        }
    """
}
```

**Template usage -- reactive dialog creation:**

```html
<%
    def dialog = ''
%>
<dialogs>${{ dialog ? "<g:hud-dialog>${ dialog }</g:hud-dialog>" : '' }}</dialogs>

<button on-click=${ _{ dialog = """
    <h2>Edit Item</h2>
    <form on-submit="${ _{ t -> saveItem(t) }}">
        <input name="title" value="${ item.title }">
        <button type="submit">Save</button>
        <g:cancel-button>Cancel</g:cancel-button>
    </form>
""" }}>Edit</button>
```

**Template usage -- with tabs:**

```html
<g:hud-dialog>
    <h2>User Settings</h2>
    <tab-strip>
        <ul>
            <li for="profile">Profile</li>
            <li for="security">Security</li>
            <li for="preferences">Preferences</li>
        </ul>
    </tab-strip>
    <tab-section id="profile">Profile settings content...</tab-section>
    <tab-section id="security">Security settings content...</tab-section>
    <tab-section id="preferences">Preferences content...</tab-section>
</g:hud-dialog>
```

**Patterns demonstrated:**
- **Native `<dialog>` integration.** Uses `dialog.showModal()` for proper modal behavior with backdrop and focus trapping.
- **Reactive creation.** The dialog is rendered when a reactive variable is set to the dialog content. The `${{ }}` wrapper in the template renders the `<g:hud-dialog>` when the variable is non-empty. Setting the variable back to `''` removes the dialog.
- **Tab system via body content.** The element does not define tabs itself -- the body content includes `<tab-strip>` and `<tab-section>` elements that the `constructed` hook automatically wires up. This is a content-agnostic pattern.
- **Close via DOM removal.** The close button calls `this.closest('hud-dialog').remove()`, which triggers HUD-Core's element cleanup (calling `deconstructed`, removing listeners, deleting the global reference).
- **Draggable.** The dialog becomes draggable on mousedown, excluding interactive child elements. The dialog transitions from CSS-centered to absolute positioning on first drag.

---

## Convenience Wrapper: CancelButton

The simplest possible element -- a thin wrapper that reduces boilerplate for a common pattern.

**`CancelButton.groovy`**

```groovy
import spaceport.launchpad.element.Element

class CancelButton implements Element {

    String prerender(String body, Map attributes) {
        def buttonText = body?.trim() ?: 'Cancel'
        return """<button type='button' class='secondary' target='hud-dialog'
                         on-click="${ _{ [ '@remove' ] }}">${buttonText}</button>"""
    }
}
```

**Template usage:**

```html
<g:cancel-button>Cancel</g:cancel-button>
<g:cancel-button>Close</g:cancel-button>
<g:cancel-button>No Thanks</g:cancel-button>
```

**Patterns demonstrated:**
- **Zero-annotation element.** No `@CSS`, `@Javascript`, or `@Bind` -- just `prerender()`. This is the absolute minimum for a useful element.
- **Server action in prerender.** Uses `_{ [ '@remove' ] }` to create a server action that sends a `@remove` transmission, removing the closest `hud-dialog` ancestor from the DOM.
- **Boilerplate reduction.** Without this element, every cancel button in every dialog would need `type='button' class='secondary' target='hud-dialog' on-click="${ _{ [ '@remove' ] }}"`. The element collapses that into a single tag.

---

## Complex Interactive Element: TagInput with Drag-to-Reorder

A form control that manages a list of tags with inline editing, drag-to-reorder, and keyboard navigation.

**`TagInput.groovy`** (abbreviated -- full implementation includes drag-and-drop, inline editing, and keyboard support)

```groovy
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element
import spaceport.launchpad.element.Javascript

class TagInput implements Element {

    @CSS
    static String styles = """
        & {
            display: flex;
            flex-wrap: wrap;
            align-items: center;
            gap: 0.5em;
            cursor: text;

            tag-item {
                display: inline-flex;
                align-items: center;
                gap: 0.25em;
                padding: 0.5em;
                padding-left: 1em;
                background: var(--white);
                border-radius: var(--border-minimal);
                cursor: grab;
                user-select: none;
                border: 1.5px outset var(--md-gray);
                height: 40px;

                &.dragging { opacity: 0.5; cursor: grabbing; }
                &.drag-over { transform: translateX(4px); }

                tag-text {
                    cursor: text;
                    min-width: 1em;
                    outline: none;
                }

                tag-remove {
                    cursor: pointer;
                    opacity: 0.5;
                    &:hover { opacity: 1; }
                }
            }

            tag-input-field {
                flex: 1;
                min-width: 150px;

                input {
                    width: 150px;
                    height: 40px;
                    border: 1px dashed var(--dk-gray);
                }
            }

            &[disabled] {
                cursor: default;
                opacity: 0.7;
                tag-item { cursor: default; }
                tag-remove { display: none; }
                tag-input-field { display: none; }
            }
        }
    """

    String prerender(String body, Map attributes) {
        def name = attributes.name ?: ''
        def value = attributes.value ?: '[]'
        def placeholder = attributes.placeholder ?: 'Add tag...'
        def listAttr = attributes.list ? " list=\"${attributes.list}\"" : ''

        // Handle both List and String inputs
        def tagsJson
        if (value instanceof List) {
            tagsJson = groovy.json.JsonOutput.toJson(value)
        } else if (value instanceof String && value.trim()) {
            tagsJson = value.trim()
            if (!tagsJson.startsWith('[')) tagsJson = '[]'
        } else {
            tagsJson = '[]'
        }

        return """
            <input type="hidden" name="${name}" value="">
            <tag-input-field>
                <input type="text" placeholder="${placeholder}"${listAttr}>
            </tag-input-field>
            <script type="application/json" data-initial-tags>${tagsJson}</script>
        """
    }

    @Javascript
    String constructed = /* language=javascript */ """
        function(element) {
            const hiddenInput = element.querySelector('input[type="hidden"]');
            const textInput = element.querySelector('tag-input-field input');
            const initialDataEl = element.querySelector('script[data-initial-tags]');
            let tags = [];

            // Load initial tags from embedded JSON
            if (initialDataEl) {
                try { tags = JSON.parse(initialDataEl.textContent); } catch (e) { tags = []; }
                initialDataEl.remove();
            }

            function render() { /* re-creates all tag-item elements */ }
            function addTag(text) { /* validates and adds */ }
            function startEdit(textEl, index) { /* contentEditable inline editing */ }

            // Keyboard: Enter to add, Backspace to edit last tag
            textInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    if (addTag(textInput.value)) textInput.value = '';
                } else if (e.key === 'Backspace' && !textInput.value && tags.length > 0) {
                    e.preventDefault();
                    // Focus last tag for editing
                }
            });

            // Expose for getValue/setValue
            element._tagInput = {
                getTags: () => tags,
                setTags: (newTags) => { tags = Array.isArray(newTags) ? [...newTags] : []; render(); }
            };

            render();
        }
    """

    @Javascript
    String getValue = /* language=javascript */ """
        function() {
            const hiddenInput = this.querySelector('input[type="hidden"]');
            return hiddenInput ? hiddenInput.value : '[]';
        }
    """

    @Javascript
    String setValue = /* language=javascript */ """
        function(newTags) {
            if (this._tagInput) this._tagInput.setTags(newTags);
        }
    """
}
```

**Template usage:**

```html
<g:tag-input name="tags" value="${ _{ ['Design', 'Branding', 'Print'] }}"></g:tag-input>
<g:tag-input name="keywords" placeholder="Add keyword..." list="keyword-suggestions"></g:tag-input>
```

**Patterns demonstrated:**
- **JSON data transfer.** Initial tag values are serialized as JSON into a `<script type="application/json">` tag, which the `constructed` hook reads and removes. This avoids escaping issues with complex data in HTML attributes.
- **Hidden input for form submission.** A hidden input stores the JSON-serialized tag list, allowing standard HTML form submission to capture the element's value.
- **Internal state object.** The `element._tagInput` object exposes internal state to the `getValue`/`setValue` methods. This is a common pattern for complex elements where the `constructed` function creates the state and the `@Javascript` methods need access to it.
- **Disabled state via attribute.** The `disabled` attribute is checked both in CSS (`&[disabled]`) and JavaScript (`element.hasAttribute('disabled')`), controlling both appearance and behavior.
- **Datalist integration.** The `list` attribute connects to a `<datalist>` for autocomplete suggestions, demonstrating integration with standard HTML features.

---

## File Upload: FileDrop with Client-Side R2 Upload

A file upload component with drag-and-drop, file validation, and optional direct-to-R2 cloud storage upload with progress tracking.

**`FileDrop.groovy`** (abbreviated)

```groovy
import spaceport.launchpad.element.CSS
import spaceport.launchpad.element.Element
import spaceport.launchpad.element.Javascript

class FileDrop implements Element {

    @CSS
    static String styles = """
        & {
            display: block;

            drop-zone {
                display: flex;
                flex-direction: column;
                align-items: center;
                padding: 2rem;
                border: 2px dashed var(--md-gray);
                border-radius: var(--border-minimal);
                background: var(--lt-gray);
                cursor: pointer;
                min-height: 120px;

                &:hover { border-color: var(--md-blue); }
                &.drag-over { border-color: var(--md-blue); border-style: solid; }
            }

            file-list {
                display: flex;
                flex-direction: column;
                gap: 0.5rem;
                margin-top: 1rem;
                &:empty { display: none; }

                file-item {
                    display: flex;
                    align-items: center;
                    gap: 0.75rem;
                    padding: 0.5rem 0.75rem;
                    background: white;
                    border: 1px solid var(--lt-gray);
                    position: relative;
                    overflow: hidden;

                    &[data-status="uploading"] { border-color: var(--md-blue); }
                    &[data-status="complete"]  { border-color: var(--md-green); }
                    &[data-status="error"]     { border-color: var(--md-red); }

                    file-progress {
                        position: absolute;
                        bottom: 0;
                        left: 0;
                        height: 3px;
                        background: var(--md-blue);
                        transition: width 0.2s ease;
                    }
                }
            }
        }
    """

    String prerender(String body, Map attributes) {
        def name = attributes.name ?: ''
        def accept = attributes.accept ?: ''
        def multiple = attributes.containsKey('multiple') ? 'multiple' : ''
        def uploadUrl = attributes['upload-url'] ?: ''
        def jobId = attributes['job-id'] ?: ''

        return """
            <drop-zone>
                <drop-icon><!-- SVG upload icon --></drop-icon>
                <drop-text><strong>Click to upload</strong> or drag and drop</drop-text>
                <input type="file" ${accept ? "accept=\\"${accept}\\"" : ''} ${multiple}
                       data-upload-url="${uploadUrl}" data-job-id="${jobId}">
                <input type="hidden" name="${name}" class="file-metadata">
            </drop-zone>
            <file-list></file-list>
        """
    }

    @Javascript
    String constructed = /* language=javascript */ """
        function(element) {
            const dropZone = element.querySelector('drop-zone');
            const fileInput = element.querySelector('input[type="file"]');
            const uploadUrl = fileInput.dataset.uploadUrl || null;
            const jobId = fileInput.dataset.jobId || null;
            const r2Mode = !!(uploadUrl && jobId);

            let fileEntries = [];

            // R2 upload with progress tracking via XMLHttpRequest
            async function uploadFile(entry) {
                entry.status = 'uploading';
                render();

                const response = await fetch(uploadUrl, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        jobNumber: jobId,
                        fileName: entry.file.name,
                        contentType: entry.file.type
                    })
                });
                const data = await response.json();

                await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.upload.addEventListener('progress', (e) => {
                        if (e.lengthComputable) {
                            entry.progress = (e.loaded / e.total) * 100;
                            render();
                        }
                    });
                    xhr.addEventListener('load', () => resolve());
                    xhr.open('PUT', data.url);
                    xhr.send(entry.file);
                });

                entry.status = 'complete';
                entry.key = data.key;
            }

            // Drag-and-drop event handlers
            dropZone.addEventListener('dragover', (e) => { e.preventDefault(); });
            dropZone.addEventListener('drop', (e) => {
                e.preventDefault();
                addFiles(e.dataTransfer.files);
            });

            // Expose state
            element._fileDrop = { getFiles: () => /* ... */, isUploading: () => /* ... */ };
        }
    """

    @Javascript
    String getValue = /* language=javascript */ """
        function() { return this._fileDrop ? this._fileDrop.getFiles() : []; }
    """

    @Javascript
    String isUploading = /* language=javascript */ """
        function() { return this._fileDrop ? this._fileDrop.isUploading() : false; }
    """

    @Javascript
    String clear = /* language=javascript */ """
        function() { if (this._fileDrop) this._fileDrop.clear(); }
    """
}
```

**Template usage:**

```html
<!-- Basic file selection -->
<g:file-drop name="attachment" accept="image/*,.pdf"></g:file-drop>

<!-- Direct-to-R2 upload with progress -->
<g:file-drop name="files" upload-url="/api/upload-url" job-id="V-25-000001" multiple></g:file-drop>
```

**Patterns demonstrated:**
- **Two modes via attributes.** Without `upload-url`, the element operates as a simple file selector. With `upload-url` and `job-id`, it enables direct browser-to-R2 uploads with presigned URLs.
- **Progress tracking.** Uses `XMLHttpRequest` instead of `fetch()` to access upload progress events, rendering a progress bar on each file item.
- **Data attributes for config.** Configuration values (`upload-url`, `job-id`) are passed through `data-*` attributes on the hidden file input, making them accessible in JavaScript without needing `@Bind`.
- **Custom events.** Dispatches `upload-start`, `upload-complete`, and `change` events, allowing parent code to react to upload state changes.
- **Multiple API methods.** Exposes `getValue()`, `isUploading()`, and `clear()` as separate `@Javascript` functions for flexible programmatic control.

---

## Elements Composing Other Elements: FileAttachments with @Bind

An element that manages saved file attachments and uses `@Bind` methods for server-side operations (save, remove, toggle visibility).

**`FileAttachments.groovy`** (abbreviated)

```groovy
import spaceport.launchpad.element.*
import data.Job

class FileAttachments implements Element {

    @Prepend
    static String prepend = """
    <style>
    .fa-confirm-popover { /* popover styles for confirmation dialog */ }
    </style>
    """

    @CSS
    static String styles = """
        & {
            display: block;
            file-list { /* list styles */ }
            file-item { /* item styles with icon, info, visibility toggle, remove button */ }
        }
    """

    Job job

    String prerender(String body, Map attributes) {
        def jobDocId = attributes.job ?: ''
        def name = attributes.name ?: 'attachments'
        job = jobDocId ? Job.getIfExists(jobDocId) : null

        def attachmentHtml = ''
        if (job?.attachments) {
            attachmentHtml = '<label>Existing Attachments:</label><file-list>'
            job.attachments.each { attachment ->
                attachmentHtml += """
                    <file-item data-id="${ attachment.id }">
                        <file-icon><img src="/assets/icons/attachment.svg"></file-icon>
                        <file-info>
                            <file-name>
                                <a href="/download/${ job.jobId }/${ attachment.fileName }"
                                   target="_blank">${ attachment.fileName }</a>
                            </file-name>
                            <file-size>${ formatFileSize(attachment.fileSize) }</file-size>
                        </file-info>
                        <file-visibility title="Toggle visibility">
                            <img src="/assets/icons/${ attachment.visibleToTalent ? 'unlocked' : 'lock' }.svg">
                        </file-visibility>
                        <file-remove title="Remove attachment">
                            <img src="/assets/icons/close.svg">
                        </file-remove>
                    </file-item>
                """
            }
            attachmentHtml += '</file-list>'
        }

        return """
            ${ attachmentHtml }
            <file-list class="pending-list"></file-list>
            <input type="hidden" name="${ name }" class="file-attachments-metadata">
        """
    }

    @Bind def removeAttachment(def attachmentId) {
        if (!job) return [success: false, error: 'Job not found']
        def attachment = job.attachments?.find { it.id == attachmentId }
        if (!attachment) return [success: false, error: 'Attachment not found']
        // Delete from R2 cloud storage
        if (attachment.r2Key) R2.deleteObject(attachment.r2Key)
        job.attachments.removeIf { it.id == attachmentId }
        job.save()
        return [success: true]
    }

    @Bind def toggleVisibility(def attachmentId) {
        if (!job) return [success: false, error: 'Job not found']
        def attachment = job.attachments?.find { it.id == attachmentId }
        attachment.visibleToTalent = !attachment.visibleToTalent
        job.save()
        return [success: true, visible: attachment.visibleToTalent]
    }

    @Bind def saveAttachments(def files) {
        if (!job) return [success: false, error: 'Job not found']
        files?.each { fileMeta ->
            def att = new Job.Attachment()
            att.fileName = fileMeta.fileName
            att.r2Key = fileMeta.key
            att.uploadedAt = System.currentTimeMillis()
            job.attachments.add(att)
        }
        job.save()
        return [success: true, attachments: job.attachments.collect { /* ... */ }]
    }

    // @Javascript constructed wires up click handlers that call
    // element.removeAttachment(id), element.toggleVisibility(id), etc.
}
```

**Template usage:**

```html
<!-- Display and manage attachments for an existing job -->
<g:file-attachments job="${ job._id }"></g:file-attachments>

<!-- Pair with file-drop for new uploads -->
<g:file-drop name="files" upload-url="/api/upload-url" job-id="${ job.jobId }" multiple></g:file-drop>
<g:file-attachments job="${ job._id }"></g:file-attachments>
```

**Patterns demonstrated:**
- **`@Bind` for CRUD operations.** Three server-side methods (`removeAttachment`, `toggleVisibility`, `saveAttachments`) are exposed to client JavaScript. Each performs database operations and returns a result map.
- **Application-specific types.** The element imports `data.Job` (a custom Document subclass) and `integrations.R2` (cloud storage), demonstrating that elements have full access to the application's class hierarchy.
- **Two modes.** With a `job` attribute, the element loads and manages existing attachments (immediate mode). Without it, the element operates in form mode with a pending file list.
- **Optimistic UI.** The `constructed` JavaScript toggles the visibility icon immediately on click, then calls the `@Bind` method to sync the server. This provides instant visual feedback.
- **`@Prepend` for global styles.** Popover styles are injected via `@Prepend` because they are positioned on `document.body` (outside the element's DOM tree) and need global CSS.

---

## Popover Component: HudPopover with @Prepend

An element that demonstrates heavy use of `@Prepend` for injecting both global styles and a shared JavaScript library.

**`HudPopover.groovy`** (abbreviated)

```groovy
import spaceport.launchpad.element.*

class HudPopover implements Element {

    @Prepend
    static String prepend = """
    <style>
    [data-popover-content] {
        position: fixed;
        z-index: 1000;
        visibility: hidden;
        opacity: 0;
        /* ... transition, shadow, padding ... */
    }
    [data-popover-content][data-visible="true"] {
        visibility: visible;
        opacity: 1;
    }
    /* ... arrow positioning for top/bottom/left/right ... */
    </style>
    <script>
    window.HudPopover = window.HudPopover || {
        position: function(trigger, content) { /* viewport-aware positioning */ },
        show: function(trigger, content) { /* position + set visible */ },
        hide: function(content) { /* remove visible */ },
        closeAll: function() { /* close all open popovers */ },
        init: function(element) { /* wire trigger/content from template children */ }
    };

    // Global click handler to close popovers when clicking outside
    document.addEventListener('click', function(e) {
        if (!e.target.closest('g-hud-popover')) window.HudPopover.closeAll();
    });

    // Reposition open popovers on scroll/resize
    window.addEventListener('scroll', function() { /* reposition all visible */ }, true);
    window.addEventListener('resize', function() { /* reposition all visible */ });
    </script>
    """

    @CSS
    static String styles = """
        & {
            position: relative;
            display: inline-block;
            > [data-popover-trigger] { cursor: pointer; user-select: none; }
        }
    """

    String prerender(String body, Map attributes) {
        return """
            <span data-popover-trigger></span>
            <div data-popover-content data-placement="bottom">
                <div class="popover-arrow"></div>
            </div>
            <template data-original>${ body }</template>
        """
    }

    @Javascript
    static String constructed = """
        function(element) {
            window.HudPopover.init(element);
        }
    """
}
```

**Template usage:**

```html
<g:hud-popover>
    <img src="/assets/icons/more.svg">    <!-- First child becomes the trigger -->
    <ul>                                   <!-- Second child becomes the popover content -->
        <li onclick="editItem()">Edit</li>
        <li onclick="deleteItem()">Delete</li>
    </ul>
</g:hud-popover>
```

**Patterns demonstrated:**
- **`@Prepend` for shared infrastructure.** The popover system (styles, positioning logic, global event handlers) is injected once into `<head>`, shared by all popover instances on the page.
- **`<template>` element for content distribution.** The element body is placed inside a `<template data-original>` element. The `init()` function clones the template children into the trigger and content slots. This avoids rendering popover content until the popover is opened.
- **Content moved to `document.body`.** The popover content is appended to `document.body` during initialization to escape stacking contexts (e.g., sticky table cells). This requires global CSS in `@Prepend` rather than element-scoped CSS.
- **Static `constructed`.** The `constructed` field is `static` because it delegates to the shared `HudPopover.init()` function. The logic lives in `@Prepend`, not per-instance.

---

## Data Table: HudTable with Client-Side Sorting

A table component that uses `@Prepend` for global styles, `@Javascript` for column width calculation and sorting, and `<table-head>`/`<table-body>`/`<table-row>`/`<table-cell>` custom elements for semantic structure.

**`HudTable.groovy`** (abbreviated)

```groovy
import spaceport.launchpad.element.*

class HudTable implements Element {

    @Prepend
    static String styles = """
    <style>
    hud-table {
        display: grid;
        table-head {
            display: grid;
            grid-auto-flow: column;
            /* ... header styles ... */
        }
        table-body table-row {
            display: grid;
            grid-auto-flow: column;
            /* ... row styles ... */
        }
    }
    </style>
    """

    String prerender(String body, Map attributes) {
        // Extract table-label to keep it outside the scroll container
        def labelMatch = body =~ /(?s)(<table-label>.*?<\/table-label>)/
        String label = labelMatch ? labelMatch[0][1] : ''
        String rest = body.replaceFirst(/(?s)<table-label>.*?<\/table-label>/, '')

        return """
            ${label}
            <table-scroll>
                <table-inner>${rest}</table-inner>
            </table-scroll>
        """
    }

    @Javascript
    String constructed = /* language=javascript */ """
        function(element) {
            const head = element.querySelector('table-head');
            const columns = head.querySelectorAll(':scope > span');

            // Build grid template from width/min-width attributes
            const columnWidths = columns.map(col => {
                let width = col.getAttribute('width');
                let minWidth = col.getAttribute('min-width');
                if (width) return width + 'px';
                if (minWidth) return 'minmax(' + minWidth + 'px, 1fr)';
                return 'minmax(0, 1fr)';
            });

            const gridTemplate = columnWidths.join(' ');
            head.style.gridTemplateColumns = gridTemplate;
            // Apply same grid to all rows...

            // Add sort icons and click handlers to columns...
            // Set up drag-to-scroll for horizontal overflow...
            // Watch for table-body replacement via MutationObserver...
        }
    """

    @Javascript
    String sort = /* language=javascript */ """
        function(columnIndex) {
            // Sort rows by cell content (numeric or string comparison)
            // Persist sort preference to localStorage by table ID
            // Apply sorted order via CSS 'order' property (no DOM mutation)
        }
    """
}
```

**Template usage:**

```html
<g:hud-table id="jobs-table">
    <table-label>Active Jobs <button color="green" on-click=${ _{ createJob() }}>New Job</button></table-label>
    <table-head>
        <span width="80">ID</span>
        <span min-width="200">Project Name</span>
        <span width="120">Client</span>
        <span width="100">Status</span>
        <span width="100" align="right">Amount</span>
        <span width="60" align="center" sticky no-sort>Actions</span>
    </table-head>
    <table-body>
        ${ jobs.combine { job -> """
            <table-row>
                <table-cell>${ job.jobId }</table-cell>
                <table-cell>${ job.projectName }</table-cell>
                <table-cell>${ job.client }</table-cell>
                <table-cell><badge color="${ statusColor(job.status) }">${ job.status }</badge></table-cell>
                <table-cell>\$${  job.amount }</table-cell>
                <table-cell><img src="/assets/icons/edit.svg" onclick="editJob('${ job._id }')"></table-cell>
            </table-row>
        """ }}
    </table-body>
</g:hud-table>
```

**Patterns demonstrated:**
- **`@Prepend` instead of `@CSS`.** The table uses `@Prepend` with explicit `<style>` tags for its global CSS. This is an alternative approach -- it works the same as `@CSS` but gives you full control over the style tag, and is better suited for element styles that need to apply globally (since the table structure is complex with many sub-elements).
- **Declarative column configuration.** Column widths, alignment, sticky behavior, and sort eligibility are configured via attributes on the `<span>` header elements. The `constructed` JavaScript reads these attributes and generates CSS Grid column templates.
- **CSS-based sorting.** Sorted rows use `row.style.order = index` rather than DOM reordering, avoiding expensive DOM mutations.
- **localStorage persistence.** Sort state is saved to `localStorage` keyed by the table's `id` attribute, restoring the user's sort preference on page reload.
- **MutationObserver.** The element watches for `<table-body>` replacement (which happens when the table is reactively re-rendered) and re-applies column widths and sort state.

---

## Animated Element: LogoScroller with Cleanup

A sliding logo carousel that demonstrates animation lifecycle management.

**`LogoScroller.groovy`**

```groovy
import spaceport.launchpad.element.*

class LogoScroller implements Element {

    String prerender(String body, Map attributes) {
        return """
            <div class="ls-track">
                <div class="ls-set ls-original">${body}</div>
                <div class="ls-set ls-clone" aria-hidden="true">${body}</div>
            </div>
        """
    }

    @CSS String css = """
        & {
            display: block;
            overflow: hidden;
            width: 100%;
            mask-image: linear-gradient(to right, transparent, black 15%, black 70%, transparent 90%);
        }

        & .ls-track {
            display: flex;
            flex-wrap: nowrap;
            align-items: center;
            gap: 50vw;
            will-change: transform;
            transition: transform 0.75s cubic-bezier(0.25, 0.1, 0.25, 1);
        }

        & .ls-set {
            display: flex;
            flex-wrap: nowrap;
            align-items: center;
            flex-shrink: 0;
            gap: 50vw;
        }
    """

    @Javascript String constructed = /* language=js */ """
        function(element) {
            var track = element.querySelector('.ls-track');
            var origSet = element.querySelector('.ls-original');
            var offset = 0;
            var gap = 0;
            var items = [];
            var timer = null;
            var paused = false;

            function slide() {
                if (paused || items.length === 0) return;
                var item = items[offset % items.length];
                offset++;
                // Calculate pixel offset and apply transform
                track.style.transform = 'translateX(-' + px + 'px)';
            }

            function onTransitionEnd() {
                if (offset >= items.length) {
                    offset = 0;
                    track.style.transition = 'none';
                    track.style.transform = 'translateX(0)';
                }
                timer = setTimeout(slide, 3000);
            }

            track.addEventListener('transitionend', onTransitionEnd);

            // Pause on hover
            element.addEventListener('mouseenter', function() { paused = true; });
            element.addEventListener('mouseleave', function() {
                paused = false;
                if (timer) clearTimeout(timer);
                timer = setTimeout(slide, 1500);
            });

            // Wait for all images to load before starting
            var imgs = Array.from(element.querySelectorAll('img'));
            Promise.all(imgs.map(function(img) {
                if (img.complete) return Promise.resolve();
                return new Promise(function(resolve) {
                    img.addEventListener('load', resolve, { once: true });
                    img.addEventListener('error', resolve, { once: true });
                });
            })).then(function() { requestAnimationFrame(init); });

            // Re-measure on resize
            new ResizeObserver(function() { getGap(); }).observe(element);
        }
    """
}
```

**Template usage:**

```html
<g:logo-scroller>
    <a href="https://client1.com"><img src="/assets/logos/client1.svg"></a>
    <a href="https://client2.com"><img src="/assets/logos/client2.svg"></a>
    <img src="/assets/logos/client3.svg">
    <img src="/assets/logos/client4.svg">
</g:logo-scroller>
```

**Patterns demonstrated:**
- **Clone-based infinite scroll.** The body content is rendered twice (original + clone), enabling a seamless loop by resetting the transform when the original set scrolls fully off-screen.
- **Image-aware initialization.** The animation does not start until all images have loaded (`Promise.all` on image load events), preventing layout shifts.
- **Pause on hover.** Mouse enter/leave events pause and resume the animation, using timer management to avoid overlapping timeouts.
- **ResizeObserver.** The element re-measures gap values when the viewport changes, maintaining correct scroll distances.
- **`aria-hidden` on clone.** The cloned logo set is marked `aria-hidden="true"` for screen reader accessibility.

---

## Common Patterns and Conventions

### Use `static` for Shared CSS

When CSS does not reference instance state, declare it as `static`. This is a code clarity signal:

```groovy
@CSS
static String styles = """..."""
```

### Provide `getValue()` and `setValue()` for Form Elements

Elements used in forms should expose these methods so external code can programmatically interact with them:

```groovy
@Javascript
String getValue = """
    function() { return this.querySelector('input').value; }
"""

@Javascript
String setValue = """
    function(val) { this.querySelector('input').value = val; }
"""
```

### Use Custom Child Elements Instead of Classes

Prefer custom tag names over `<div class="...">` for sub-components:

```html
<!-- Preferred -->
<drop-zone>
    <drop-icon>...</drop-icon>
    <drop-text>Click to upload</drop-text>
</drop-zone>

<!-- Avoid -->
<div class="drop-zone">
    <div class="drop-icon">...</div>
    <div class="drop-text">Click to upload</div>
</div>
```

Custom tags are self-documenting in CSS and avoid class-name collisions between elements.

### Use `element.listen()` for Root Listeners

Always use `element.listen()` instead of `element.addEventListener()` for listeners on the root element to ensure automatic cleanup:

```javascript
// Auto-cleanup on element removal
element.listen('click', handler);

// Manual cleanup required (use deconstructed)
document.addEventListener('click', handler);
```

### Prefer Client-Side for Instant Feedback, Server for Persistence

Handle instant visual updates in `@Javascript`, sync state to the server via `@Bind`:

```groovy
@Javascript String constructed = """
    function(element) {
        element.listen('click', function(e) {
            e.target.classList.toggle('active');        // Instant feedback
            element.setFavorite(e.target.classList.contains('active'));  // Server sync
        });
    }
"""

@Bind def setFavorite(def isFavorite) {
    if (isFavorite) favorites.addToSet(itemId)
    else favorites.takeFromSet(itemId)
}
```

### Keep `prerender()` Fast

The `prerender()` method runs synchronously during page rendering. Avoid expensive operations inside it. Pass pre-loaded data via attributes:

```groovy
// In the route handler
r.context.data.userList = View.get('users', 'by-role', 'users').rows

// In the template
<g:user-table data="${ _{ data.userList }}"></g:user-table>

// In the element
String prerender(String body, Map attributes) {
    def users = attributes.data  // Already loaded
    return users.combine { u -> "<tr><td>${u.name}</td></tr>" }
}
```

### Dispatch Standard Events for External Integration

Elements that behave like form controls should dispatch standard DOM events so they work with event listeners and Launchpad server actions:

```javascript
element.dispatchEvent(new Event('change', { bubbles: true }));
element.dispatchEvent(new Event('blur'));
element.dispatchEvent(new CustomEvent('upload-complete', { detail: { count: 5 } }));
```
