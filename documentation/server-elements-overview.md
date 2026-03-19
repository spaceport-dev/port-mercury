# Server Elements -- Overview

Server Elements are Spaceport's reusable component system. They let you encapsulate HTML structure, CSS styling, JavaScript behavior, and server-side Groovy logic into a single class that you use like a custom HTML element in your Launchpad templates.

## What Are Server Elements?

A Server Element is a Groovy class that implements the `Element` trait, lives in the `launchpad/elements/` directory, and gets used in `.ghtml` templates with a `g:` prefix. When Launchpad renders a page, it discovers these elements, calls their `prerender` method on the server to produce the initial HTML, and injects any associated CSS and JavaScript into the page automatically.

```groovy
import spaceport.launchpad.element.*

class Highlight implements Element {
    String prerender(String body, Map attributes) {
        def color = attributes.color ?: 'yellow'
        return "<mark style='background-color: ${color}'>${body}</mark>"
    }
}
```

Use it in a `.ghtml` template:

```html
<g:highlight color="coral">This text stands out.</g:highlight>
```

The rendered output wraps your content in a custom `<highlight>` tag with the prerendered inner HTML:

```html
<highlight element-id="a1b2c3"><mark style='background-color: coral'>This text stands out.</mark></highlight>
```

That is the simplest possible element. From here, annotations let you layer in CSS, JavaScript, server-bound methods, and lifecycle hooks -- all within the same class.

## Why Server Elements?

Most web frameworks force a choice between server-rendered components and client-side interactivity. Server Elements bridge both sides:

- **Server-side rendering out of the box.** The `prerender` method produces HTML on the server, so content is included in the initial HTTP response. No blank-page-while-JavaScript-loads problem.
- **Encapsulated CSS and JavaScript.** Annotate properties with `@CSS` or `@Javascript` and the framework aggregates and injects them automatically. No separate asset pipeline configuration.
- **Server methods callable from the client.** The `@Bind` annotation exposes Groovy methods as client-callable functions over WebSocket, without writing API endpoints.
- **Integration with Launchpad reactivity.** Elements can use Cargo objects, reactive expressions (`${{ }}`), and server actions (`on-click=${ _{ } }`) -- the same tools available in regular templates.
- **Reusable across templates.** Define a component once, use it in any `.ghtml` file with a simple HTML-like tag.

## Element Structure at a Glance

Every element has a class that implements `Element` and a `prerender` method. Beyond that, you add capabilities through property annotations:

```groovy
import spaceport.launchpad.element.*
import spaceport.computer.memory.virtual.Cargo

class Counter implements Element {

    def counter = new Cargo(0)

    @CSS String styles = """
        & {
            display: inline-flex;
            gap: 0.5em;
            align-items: center;
        }
    """

    String prerender(String body, Map attributes) {
        if (attributes.containsKey('value'))
            counter.set(attributes.getInteger('value'))

        return """
            <button on-click=${ _{ counter.dec() }}>-</button>
            <span>${{ counter.get() }}</span>
            <button on-click=${ _{ counter.inc() }}>+</button>
        """
    }

    @Bind def getValue() {
        return counter.get()
    }

    @Bind def setValue(def value) {
        counter.set(value.toInteger())
    }
}
```

```html
<g:counter value="10"></g:counter>
```

This single class provides:

- **CSS** (`@CSS`) -- styling scoped to the `<counter>` tag via the `&` selector
- **Server-side rendering** (`prerender`) -- initial HTML with reactive expressions
- **Reactive updates** (`${{ }}` and Cargo) -- the count updates in real time when buttons are clicked
- **Server actions** (`on-click=${ _{ } }`) -- button clicks execute Groovy on the server
- **Client-callable methods** (`@Bind`) -- JavaScript on the page can call `document.querySelector('counter').setValue(25)` and the server state updates

## Naming Convention

Class names use CamelCase. In templates, use the kebab-case equivalent with a `g:` prefix:

| Class Name | Template Tag |
|---|---|
| `Highlight` | `<g:highlight>` |
| `StarRating` | `<g:star-rating>` |
| `TextEditor` | `<g:text-editor>` |
| `HudDialog` | `<g:hud-dialog>` |

The conversion from CamelCase to kebab-case happens automatically via the `getTagName()` method on the Element trait.

## File Location

Elements live in the `launchpad/elements/` directory alongside your templates:

```
launchpad/
  parts/              <-- Page templates (.ghtml)
    index.ghtml
    wrapper.ghtml
  elements/           <-- Server Element classes (.groovy)
    StarRating.groovy
    Counter.groovy
    TextEditor.groovy
```

Each element is a single `.groovy` file. Spaceport discovers and registers all element classes in this directory when the application starts. In debug mode, elements are hot-reloaded when their files change.

## Two Strategies for Building Elements

Server Elements support a spectrum of strategies depending on where you want the logic to live:

### Client-Focused

Handle most interaction in JavaScript. The server provides the initial HTML, and `@Javascript` properties define client-side behavior. Good for purely visual components, form controls, and interactions that do not need server state.

Real-world example: A `CheckBox` element that wraps a native input with custom styling and keyboard support -- all interaction is client-side JavaScript.

### Server-Focused

Handle all state on the server using Cargo and reactive expressions. Button clicks and other events trigger server-side closures via server actions. Good for components that manage persistent data or need server-side validation.

Real-world example: A `UserAlerts` element that reads notification data from the user's document and reactively updates the badge count.

### Mixed (Most Common)

Combine both approaches. Use client-side JavaScript for instant visual feedback (optimistic UI), then sync state to the server via `@Bind` methods. This is the recommended approach for most production components.

Real-world example: A notes component that uses `contenteditable` for instant client-side editing, then persists the content to Cargo on blur via a server action.

## Available Annotations

| Annotation | Applies To | Purpose |
|---|---|---|
| `@CSS` | Properties | Global CSS for the element type |
| `@ScopedCSS` | Properties | CSS scoped to a specific element instance |
| `@Javascript` | Properties | Client-side JavaScript (functions, lifecycle hooks, inline code) |
| `@Bind` | Methods | Exposes a Groovy method as a client-callable function via WebSocket |
| `@Prepend` | Properties | HTML injected into `<head>` (or page start) -- once per element type |
| `@ScopedPrepend` | Properties | HTML injected immediately before this element instance |
| `@Append` | Properties | HTML injected after `</body>` (or page end) -- once per element type |
| `@ScopedAppend` | Properties | HTML injected immediately after this element instance |

## What is Next

- **[Server Elements API Reference](server-elements-api.md)** -- Complete reference for the Element trait, all annotations, template integration, and configuration.
- **[Server Elements Internals](server-elements-internals.md)** -- How element discovery, initialization, CSS/JS aggregation, and the client-side lifecycle work under the hood.
- **[Server Elements Examples](server-elements-examples.md)** -- Real-world patterns from production Spaceport applications, including form controls, dialogs, rich text editors, and reactive dashboard components.
