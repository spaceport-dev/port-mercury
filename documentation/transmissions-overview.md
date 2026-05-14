# Transmissions -- Server-Driven DOM Updates

Transmissions are how Spaceport's server-side code reaches into the browser and updates the UI. When a user clicks a button, submits a form, or any DOM event fires, the server can respond with a Transmission -- a structured payload that tells the client exactly what to change in the page. No custom JavaScript required.

This is a core piece of the Launchpad templating system. Where most frameworks require you to write frontend code to handle user interactions, Spaceport lets you define that behavior in Groovy, right inside your templates, and the framework handles the round-trip automatically.

## How It Works

The Transmission cycle has three steps:

1. **Event** -- A user interacts with an element that has an `on-*` attribute (e.g., `on-click`, `on-submit`). HUD-Core intercepts the event.
2. **Server Action** -- HUD-Core sends a POST request to the server with contextual data (element values, form data, event details). The server executes the Groovy closure associated with that event.
3. **Transmission** -- The closure returns a value. HUD-Core receives it and applies the instructions to the DOM.

```
User clicks button
       |
       v
  HUD-Core collects event data, POSTs to server
       |
       v
  Server runs Groovy closure, returns Transmission
       |
       v
  HUD-Core applies Transmission to target element
```

There is no page reload. The update is surgical -- only the targeted element changes.

## The Two Syntaxes

Transmissions originate from two template syntaxes, each serving a different purpose.

### Server Actions: `_{ }`

A server action is a Groovy closure that runs when a DOM event fires. You attach it to any element using an `on-*` attribute:

```html
<button target="self" on-click=${ _{ ['innerText': 'Done!', '+confirmed': 'it'] }}>
    Confirm
</button>
```

When the button is clicked, the closure `_{ ... }` executes on the server. The returned map is a Transmission that updates the button's text and adds a CSS class.

The optional `t` parameter gives access to data sent from the client:

```groovy
<form on-submit=${ _{ t ->
    def name = t.getString('name')
    // ... process on server ...
    ['#status': "Saved ${name}!", '+saved': 'it']
}}>
    <input name="name" value="Apollo">
    <button type="submit">Save</button>
    <div id="status"></div>
</form>
```

### Reactive Bindings: `${{ }}`

Reactive bindings create expressions that automatically re-evaluate when their underlying data changes. They use double braces and are powered by [Cargo](cargo-overview.md) reactivity:

```html
<p>Page views: ${{ stats.get('pageViews') }}</p>
```

When `stats.inc('pageViews')` runs anywhere on the server, the displayed count updates on every connected client via WebSocket -- no event trigger needed. This is how Spaceport achieves real-time multi-user updates.

## The Three Transmission Formats

Every Transmission is one of three types, chosen by what the server closure returns.

### Single Value (String or Number)

The simplest form. The return value replaces the target element's content:

```groovy
<button on-click=${ _{ counter++; "$counter items" }} target="#display">Add</button>
<span id="display">0 items</span>
```

Clicking the button replaces the `<span>`'s innerHTML with the new count string.

### Map (Groovy Map)

The most powerful format. Each key-value pair is an instruction:

```groovy
return [
    'innerText': 'Saved!',        // Update text content
    '+success': 'it',             // Add CSS class to the button
    'disabled': true,             // Set an HTML attribute
    '#status': 'Record saved.'    // Update a different element by ID
]
```

Maps can combine content updates, class changes, attribute modifications, browser actions, and selector-based updates -- all in a single response.

### Array (Groovy List)

A shorthand for class toggles and simple actions on the target element:

```groovy
return ['-loading', '+complete', '@focus']
```

This removes the `loading` class, adds `complete`, and focuses the element.

## The `target` Attribute

Every Transmission needs a destination. The `target` attribute on the triggering element (or its ancestors) determines which DOM element receives the update:

```html
<!-- Update the button itself -->
<button target="self" on-click=${ _{ 'Clicked!' }}>Click</button>

<!-- Update an element by ID -->
<button target="#output" on-click=${ _{ 'Hello!' }}>Greet</button>
<div id="output"></div>

<!-- Update the parent element -->
<div>
    <button target="parent" on-click=${ _{ '<p>New content</p>' }}>Update</button>
</div>
```

Spaceport supports a wide range of target values: `self`, `parent`, `grandparent`, `next`, `previous`, `first`, `last`, `append`, `prepend`, CSS selectors, and more. See the [Transmissions API Reference](transmissions-api.md) for the complete list.

## Server State: The Single Source of Truth

A key design principle behind Transmissions is that the server owns the state. Template-level variables in Launchpad persist across interactions for the duration of the session:

```groovy
<%
    def counter = 0

    def increment = {
        counter++
        return counter + ' hot cross buns'
    }

    def decrement = {
        counter--
        return counter + ' hot cross buns'
    }
%>

<div class="counter-widget">
    <button on-click=${ _{ decrement() }} target="#count-display">-</button>
    <span id="count-display">${ counter } hot cross buns</span>
    <button on-click=${ _{ increment() }} target="#count-display">+</button>
</div>
```

The `counter` variable lives on the server, bound to the user's session. Refreshing the page, reopening the browser, or logging in from a different device all see the same state. The Transmission simply tells the client what the current value is -- it never relies on client-side state.

## When to Use Transmissions

Transmissions are ideal for:

- **Form processing** -- validate, save, and provide feedback in one round-trip
- **Dynamic UI updates** -- toggle states, show/hide elements, update content
- **Real-time displays** -- combine `${{ }}` bindings with Cargo for live data
- **Server-authoritative interactions** -- anything where the server should decide what happens next

For interactions that need instant feedback without a server round-trip (e.g., optimistic UI updates), Spaceport provides Document Data on the client side, which can be combined with Transmissions for a hybrid approach.

## What's Next

- **[Transmissions API Reference](transmissions-api.md)** -- complete syntax for all Transmission formats, target values, and the `t` data object
- **[Transmissions Internals](transmissions-internals.md)** -- how bindings, reactions, the Catch proxy, and WebSocket updates work under the hood
- **[Transmissions Examples](transmissions-examples.md)** -- real-world patterns from production Spaceport applications
