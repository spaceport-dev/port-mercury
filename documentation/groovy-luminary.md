# Groovy Luminary -- A Groovy Quick-Reference for Spaceport Developers

Spaceport is built on **Apache Groovy**, a JVM language that compiles to Java bytecode and is fully compatible with Java libraries. You do not need to be a Groovy expert to build with Spaceport, but familiarity with the patterns below will make you productive fast.

This guide covers the subset of Groovy that appears most often in Spaceport applications. For the full language reference, see [groovy-lang.org](https://groovy-lang.org/documentation.html).

---

## Syntax Basics

Groovy removes much of Java's ceremony:

- **Semicolons** are optional. Leave them out.
- **Parentheses** are optional on top-level method calls: `println "Hello"` works fine. Use parentheses for nested calls or when a call has no arguments.
- **Return statements** are optional. The last expression in a method or closure is returned automatically. Use explicit `return` only for early exits.

```groovy
// All three are valid
println("Hello, Spaceport")
println "Hello, Spaceport"

def greet(String name) {
    "Hello, $name"   // returned implicitly
}
```

---

## Variables and Typing

Groovy supports **optional typing**. You can choose dynamic or static declarations depending on context.

| Style | Syntax | When to use |
|---|---|---|
| Dynamic | `def x = 42` | Local variables, quick scripting |
| Typed | `String name = "Kirk"` | Public APIs, method parameters, document schemas |

```groovy
// Dynamic -- type inferred at runtime
def message = "Welcome aboard"

// Typed -- compile-time safety, good for document schemas
String name = "Kirk"
int count = 0
List<String> tags = ["urgent", "review"]
```

In Spaceport source modules, static fields that serve as shared state are typically typed:

```groovy
static Launchpad launchpad = new Launchpad()
static List<String> activeUsers = []
```

---

## Strings and GStrings

Groovy has three string forms:

```groovy
// Single-quoted: plain Java String, no interpolation
def label = 'Hello'

// Double-quoted: GString with interpolation via ${}
def user = "Kirk"
def greeting = "Welcome, ${user}"       // "Welcome, Kirk"
def shortForm = "Welcome, $user"        // also works for simple references

// Triple-quoted: multiline strings (single or double quotes)
def html = """
    <div class="card">
        <h2>${title}</h2>
    </div>
""".stripIndent()
```

GString interpolation is used everywhere in Spaceport -- in Launchpad templates, in JSON manifest generation, and in log messages.

```groovy
// From a Spaceport route handler -- building a response string
return "<p>Thank you for your message, ${name}!</p>"

// Multiline GString for JSON generation
def manifest = """
    {
        "name": "${config.name}",
        "short_name": "${config.'short name' ?: config.name}"
    }
""".stripIndent()
```

---

## Closures

Closures are anonymous blocks of code enclosed in `{ }`. They can be assigned to variables, passed as arguments, and returned from methods. Closures are central to Spaceport -- they power authorization plugs, collection processing, and server actions in templates.

### Basic Syntax

```groovy
// Closure with no parameters
def sayHello = { println "Hello" }
sayHello()

// Single parameter -- use implicit 'it'
def shout = { it.toUpperCase() }
println shout("hello")   // "HELLO"

// Named parameters
def add = { a, b -> a + b }
println add(3, 4)         // 7
```

### Closures as Method Arguments

When the last argument of a method is a closure, it can be placed outside the parentheses:

```groovy
[1, 2, 3].each { println it }

["Kirk", "Spock"].collect { it.toUpperCase() }
// Result: ["KIRK", "SPOCK"]
```

### Closures in Spaceport

Authorization plugs are closures stored in static fields:

```groovy
static def administratorAuthPlug = { HttpResult r ->
    def client = r.client
    if (client.isAuthenticated() && client.document.hasPermission('spaceport-administrator')) {
        return true
    } else {
        def message = 'You must be logged in as an administrator.'
        r.setRedirectUrl('/login?message=' + message.encode())
        return false
    }
}
```

Template server actions are closures that receive a transmission object:

```groovy
def submitForm = { t ->
    def name = t.getString('name')
    return ['#message': "Saved ${name}"]
}
```

---

## Lists

Lists use square-bracket literal syntax and are `java.util.ArrayList` by default.

```groovy
def crew = ["Sisko", "Kira", "Dax"]

crew << "Bashir"          // append with left-shift operator
println crew[0]           // "Sisko" -- first element
println crew[-1]          // "Bashir" -- last element
println crew.size()       // 4
```

### Common Collection Methods

| Method | Purpose | Example |
|---|---|---|
| `each` | Iterate | `list.each { println it }` |
| `collect` | Transform (map) | `list.collect { it.toUpperCase() }` |
| `find` | First match | `list.find { it.startsWith("K") }` |
| `findAll` | All matches | `list.findAll { it.size() > 3 }` |
| `any` | True if any match | `list.any { it == "Kira" }` |
| `every` | True if all match | `list.every { it.size() > 2 }` |
| `sort` | Sort (in-place) | `list.sort { a, b -> a <=> b }` |

In Spaceport, `collect` and `find` appear frequently when working with document data:

```groovy
// Remove a participant from a guestbook
participants.removeIf { it.cookie == cookie }

// Check if a participant exists
boolean hasParticipant(String cookie) {
    return participants.find { it.cookie == cookie } != null
}
```

---

## Maps

Maps use bracket syntax with implicit string keys and are `java.util.LinkedHashMap` by default.

```groovy
def ship = [
    name: "Defiant",
    registry: "NX-74205",
    crew: 50
]

println ship.name            // property-style access
println ship['registry']     // subscript access
ship.commissioned = 2370     // add a new key
```

Maps are the standard way to return structured data from Spaceport server actions:

```groovy
def updateProfile = { t ->
    def name = t.getString('name')
    return [
        '#message': "Profile updated for ${name}",
        'val_name': ""    // clear the input field
    ]
}
```

> **Gotcha**: `ship.class` looks up a map key named `"class"`, not the Java class object. Use `ship.getClass()` if you need type information.

---

## Classes

Groovy classes look like Java classes with less boilerplate. Properties are declared without explicit getters and setters -- Groovy generates them automatically.

```groovy
class Starship {
    String name
    String registry
    int crew
}

def ship = new Starship(name: "Enterprise", registry: "NCC-1701", crew: 430)
println ship.name       // calls getName() under the hood
```

### Static vs. Instance Scope in Spaceport

This distinction is critical in Spaceport source modules:

- **Static** members belong to the class and are shared across all users and requests. Use them for route handlers (`@Alert` methods), shared caches, Launchpad instances, and authorization plugs.
- **Instance** members are created fresh per request. Use them for request-scoped data.

```groovy
class App {
    // Shared across all requests
    static Launchpad launchpad = new Launchpad()
    static List<String> recentSearches = []

    // Route handler -- must be static
    @Alert('on /index.html hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r, 'wrapper.ghtml')
    }
}
```

### Inner Classes and Schemas

Groovy supports static inner classes, which Spaceport uses for document schemas:

```groovy
class Guestbook extends Document {
    static class ParticipantSchema {
        String name
        String email
        Boolean public_email
        String message
        Long time_signed
    }

    List<ParticipantSchema> participants = []
}
```

---

## Annotations

Annotations in Groovy work just like Java annotations. In Spaceport, the most important annotation is `@Alert`, which registers static methods as event handlers:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.HttpResult

class App {
    // Lifecycle event
    @Alert('on initialized')
    static _init(Result r) { /* runs once at startup */ }

    // Route handler
    @Alert('on / hit')
    static _root(HttpResult r) { r.setRedirectUrl('/index.html') }

    // HTTP method-specific route
    @Alert('on /login POST')
    static _login(HttpResult r) { /* handle login form */ }

    // Regex route
    @Alert('~on /([^/]+)\\.html hit')
    static _pages(HttpResult r) {
        def pageName = r.matches[0]
        // ...
    }
}
```

---

## Null Safety

Groovy provides two operators that eliminate most `NullPointerException` scenarios.

### Safe Navigation (`?.`)

If the receiver is null, the expression returns null instead of throwing an exception:

```groovy
def title = config.PWA?.name     // null if PWA is null, not an exception
def city = user?.address?.city   // safe chain
```

### Elvis Operator (`?:`)

A shorthand for "use this value, or fall back to a default if it is null or falsy":

```groovy
def redirect = r.context.data.redirect ?: '/'
def shortName = config.'short name' ?: config.name
def username = name ?: "Guest"
```

Both operators appear constantly in Spaceport route handlers and templates.

---

## Groovy Truth

Groovy evaluates more than just booleans in conditional contexts. The following values are **falsy** (everything else is truthy):

| Value | Falsy? |
|---|---|
| `null` | Yes |
| `false` | Yes |
| `0` or `0.0` | Yes |
| `""` (empty string) | Yes |
| `[]` (empty list) | Yes |
| `[:]` (empty map) | Yes |

This enables concise null-and-empty checks:

```groovy
if (user?.name) {
    // user is not null AND name is not null/empty
}

def items = cart.items
if (items) {
    // items list is not null and not empty
}
```

---

## Regular Expressions

Groovy has native regex support with the `~` operator for patterns and `=~` for matching:

```groovy
def pattern = ~/\d{3}-\d{4}/
def phone = "555-1234"

if (phone =~ pattern) {
    println "Valid phone number"
}
```

In Spaceport, regex is most commonly used in `@Alert` route patterns rather than in application code. Prefix the alert string with `~` to enable regex matching:

```groovy
// Match any .html page and capture the name
@Alert('~on /([^/]+)\\.html hit')
static _pages(HttpResult r) {
    def pageName = r.matches[0]
    launchpad.assemble(["pages/${pageName}.ghtml"]).launch(r, 'wrapper.ghtml')
}

// Match guestbook IDs
@Alert('~on /g/(.*) hit')
static _event(HttpResult r) {
    def guestbookId = r.matches[0]
}
```

---

## The `tap` Method

`tap` configures an object inline and returns the object itself. It is useful for building data structures:

```groovy
def participant = new ParticipantSchema().tap {
    it.name = name.clean()
    it.email = email.clean()
    it.message = message.clean()
    it.time_signed = System.currentTimeMillis()
    it.cookie = cookie.clean()
}
```

---

## Properties with Quoted Keys

Groovy allows map-style property access with quoted keys, which is useful when property names contain spaces or special characters. Spaceport configuration files use this pattern:

```groovy
def config = Spaceport.config.'PWA'
def shortName = config.'short name'
def bgColor = config.'background color' ?: '#ffffff'
```

---

## Spaceport Class Enhancements

Spaceport adds utility methods to standard Groovy/Java classes through metaprogramming:

**String methods**:
- `.clean()` -- sanitize HTML inputs to prevent XSS
- `.kebab()` -- convert to kebab-case (`"User Name"` becomes `"user-name"`)
- `.slugify()` -- convert to URL-safe slug (`"My Post!"` becomes `"my-post"`)
- `.encode()` -- URL-encode a string

**Number methods**:
- `.money()` -- format as currency
- `.minutes()`, `.days()` -- convert to milliseconds

**Conditional rendering**:
- `.if { condition }` -- returns the string if the closure evaluates to true, empty string otherwise

```groovy
"<a href='/admin'>Admin Panel</a>".if { client.isAdmin }
```

---

## Quick Reference Table

| Feature | Syntax | Notes |
|---|---|---|
| Dynamic variable | `def x = 42` | Type inferred at runtime |
| Typed variable | `String s = "hi"` | Compile-time checked |
| GString interpolation | `"Hello, ${name}"` | Double quotes only |
| Multiline string | `"""..."""` | Triple double-quotes for interpolation |
| Closure | `{ x -> x + 1 }` | Implicit `it` for single param |
| List literal | `[1, 2, 3]` | ArrayList by default |
| Map literal | `[key: "val"]` | LinkedHashMap by default |
| Safe navigation | `obj?.property` | Returns null if obj is null |
| Elvis operator | `val ?: default` | Fallback for null/falsy |
| Spaceship operator | `a <=> b` | Returns -1, 0, or 1 |
| Regex alert | `@Alert('~on /pattern hit')` | `~` prefix enables regex |
| Append to list | `list << item` | Left-shift operator |
| Last element | `list[-1]` | Negative index |

---

## Next Steps

Once you are comfortable with the Groovy patterns above, explore the Spaceport-specific systems that put them to work:

- **[Source Modules](source-modules-overview.md)** -- how Groovy classes are organized and loaded
- **[Alerts](alerts-overview.md)** -- routing, lifecycle hooks, and event-driven architecture
- **[Launchpad](launchpad-overview.md)** -- embedding Groovy in HTML templates
- **[Cargo](cargo-overview.md)** -- reactive state management
- **[Class Enhancements](class-enhancements-overview.md)** -- the full list of methods Spaceport adds to standard classes
