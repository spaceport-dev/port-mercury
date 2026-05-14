# HUD-Core.js -- Overview

HUD-Core.js is the client-side JavaScript library that powers Launchpad's interactive features. It is the bridge between the browser and Spaceport's server-side templating engine, enabling server-driven UI updates without writing custom JavaScript. When you add an `on-click` attribute to a button in a Launchpad template, HUD-Core is what makes it work.

## What HUD-Core Does

HUD-Core handles three core responsibilities:

1. **Event binding** -- It watches the DOM for elements with `on-*` attributes (like `on-click`, `on-submit`, `on-input`) and automatically binds them to server round-trips. When the event fires, HUD-Core collects contextual data from the element, sends it to the server, and applies the returned Transmission to the DOM.

2. **DOM observation** -- A MutationObserver monitors the entire document. When new elements are added (for example, from a Transmission that injects HTML), HUD-Core automatically processes them: binding events, evaluating scripts, and setting up HREF navigation. This means dynamically inserted content works identically to content present at page load.

3. **Reactive data** -- The `documentData` system provides a global reactive data object (`document.data`) that can be populated from the server and automatically updates any bound elements when values change.

## How It Connects to Launchpad

Launchpad templates (`.ghtml` files) define server actions using the `_{ }` closure syntax. When a template is rendered, Spaceport compiles each server action into a unique endpoint URL. HUD-Core reads that URL from the `on-*` attribute and calls it when the event fires.

The full cycle looks like this:

```
1. Browser: User clicks a button with on-click attribute
2. HUD-Core: Collects element value, form data, data-* attributes, query params
3. HUD-Core: POSTs the collected data to the server action endpoint
4. Server: Groovy closure executes, returns a Transmission (Map, Array, or value)
5. HUD-Core: Receives the Transmission and applies it to the target element
6. Browser: DOM is updated -- no page reload needed
```

## Installation

Include HUD-Core in your HTML wrapper (vessel) with a script tag:

```html
<script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
```

This is typically placed in your main wrapper template so it loads on every page. HUD-Core is lightweight and activates automatically on `DOMContentLoaded` -- no initialization code is required.

## The `on-*` Event Model

HUD-Core supports over 35 built-in events. You bind them by adding an attribute to any HTML element:

```html
<button target="self" on-click=${ _{ 'Clicked!' }}>
    Click me
</button>
```

When the button is clicked, HUD-Core sends a POST request to the server action endpoint. The server closure executes and returns the string `'Clicked!'`. HUD-Core receives this and sets the button's `innerHTML` to `"Clicked!"` because the target is `self`.

Events cover the full range of browser interactions:

- **Mouse**: `on-click`, `on-dblclick`, `on-mousedown`, `on-mouseup`, `on-mouseover`, `on-mouseout`, and more
- **Keyboard**: `on-keydown`, `on-keyup`, `on-keypress`
- **Form**: `on-submit`, `on-change`, `on-input`, `on-focus`, `on-blur`, `on-formblur`
- **Touch**: `on-touchstart`, `on-touchmove`, `on-touchend`, `on-touchcancel`
- **Drag & Drop**: `on-dragenter`, `on-dragleave`, `on-drop`, and more
- **Lifecycle**: `on-load`, `on-beforeunload`, `on-nudge`

## Transmissions: Three Response Formats

When the server action returns a value, HUD-Core interprets it as a Transmission. There are three formats, each suited for different complexity levels.

**Single Value** -- The simplest. A returned string replaces the target's content:

```html
<span id="greeting" target="self" on-click=${ _{ 'Hello, world!' }}>
    Click for greeting
</span>
```

**Map** -- The most powerful. Each key-value pair is an instruction:

```html
<button target="self" on-click=${ _{ [
    'innerText': 'Saved!',
    '+success': 'it',
    'disabled': 'true'
] }}>Save</button>
```

This changes the button text, adds a CSS class, and disables it -- all in one response.

**Array** -- A shorthand for class operations and simple actions:

```html
<button target="self" on-click=${ _{ ['+active', '-pending'] }}>
    Activate
</button>
```

## Automatic DOM Processing

HUD-Core does not just bind events at page load. Its MutationObserver watches for any DOM changes and automatically processes new elements. This means:

- HTML injected by a Transmission gets its `on-*` attributes bound
- Scripts inside Transmissions are evaluated
- HREF navigation is set up on new elements
- Server Elements are properly initialized and cleaned up

This is what makes Launchpad's server-driven approach feel seamless. You can return an entire form from a Transmission, and its `on-submit` handler will work immediately without any additional setup.

## Other Key Features

**HREF Navigation** -- Any non-anchor element with an `href` attribute gets click and keyboard navigation automatically. This allows `<div href="/dashboard">` to behave like a link.

**Document Data** -- A reactive data system that syncs server state to the client. Elements with a `bind` attribute automatically update when the bound data changes:

```html
<span bind="user.name">Loading...</span>
```

**WebSocket Communication** -- The `sendData()` function sends data over a WebSocket connection for real-time communication, as an alternative to the HTTP-based `on-*` events.

**Form Blur Event** -- The custom `on-formblur` event fires when focus leaves a form entirely, allowing you to trigger server actions when a user finishes interacting with a form without explicitly submitting it.

## What HUD-Core Is Not

HUD-Core is not a general-purpose JavaScript framework. It does not provide a virtual DOM, a component model, or a state management library. Its purpose is narrowly focused: make Launchpad's server-side templating interactive. The functions in HUD-Core are called automatically by the framework -- in most applications, you will never call them directly.

For custom client-side behavior beyond what `on-*` events and Transmissions provide, Spaceport offers Server Elements, which encapsulate reusable client-side logic in Groovy classes with `@Javascript` annotations.

## What's Next

- **[HUD-Core API Reference](hud-core-api.md)** -- Complete reference for all events, data payloads, Transmission formats, and special attributes.
- **[HUD-Core Internals](hud-core-internals.md)** -- How the MutationObserver, event binding, fetch pipeline, and Proxy-based reactivity work under the hood.
- **[HUD-Core Examples](hud-core-examples.md)** -- Real-world patterns from production Spaceport applications.
