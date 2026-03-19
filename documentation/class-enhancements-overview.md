# Class Enhancements Overview

Spaceport enhances standard Groovy classes with dozens of additional methods designed to make common web development tasks more expressive and concise. These enhancements add methods to `String`, `Number`, `Integer`, `List`, `Map`, `Collection`, `Object`, and `Closure`, providing shortcuts for HTML sanitization, string formatting, date/time manipulation, JSON serialization, reactive updates, and more.


## What Metaclass Enhancements Are

Groovy's metaclass system allows methods to be added to existing classes at runtime. Spaceport takes advantage of this by injecting a curated set of utility methods into core types when the framework starts up. Once applied, these methods are available everywhere in your Spaceport application -- in route handlers, migration scripts, Launchpad templates, and any other Groovy code running in the Spaceport runtime.

For example, after enhancements are applied, any `String` in your application gains methods like `.kebab()`, `.clean()`, `.encode()`, and `.slugify()`. Any `Number` gains methods like `.money()`, `.hours()`, `.relativeTime()`, and `.ordinal()`. These are not separate utility classes you need to import; they are methods directly on the objects you are already working with.


## Why They Exist

Web application development involves a large amount of repetitive string manipulation, date formatting, HTML escaping, and data serialization. Rather than requiring developers to import utility classes or write helper functions for these common tasks, Spaceport makes them available as natural method calls on the types you already use.

This design philosophy has several benefits:

- **Readability.** Code like `timestamp.relativeTime()` or `"Hello World".kebab()` reads naturally and communicates intent immediately.
- **Discoverability.** Methods are attached to the types where you would naturally look for them.
- **Template friendliness.** In Launchpad `.ghtml` templates, you often need to format values inline. Enhanced methods keep templates clean: `${ price.money() }` rather than `${ formatCurrency(price) }`.
- **Consistency.** Every Spaceport developer has the same set of tools available on the same types, reducing the need for project-specific utility libraries.


## How They Are Applied

All enhancements are defined in a single class: `spaceport.engineering.MetaClassEnhancements`. When Spaceport starts up -- whether as a full application server or in migration mode -- it calls `MetaClassEnhancements.enhance()`. This method uses Groovy's `metaClass` mechanism to attach new methods to standard types.

The enhancements are applied once, early in the startup process, before any user code runs. This means they are available in:

- Route handlers and alert methods
- Launchpad templates (`.ghtml` files)
- Migration scripts
- Server Elements
- Any other Groovy code executing within the Spaceport runtime


## Categories of Enhancements

The enhancements cover several broad areas:

### String Manipulation and Formatting
Case conversion (`kebab`, `snake`, `titleCase`, `slugify`), wrapping (`surroundWith`, `quote`), pattern removal (`strip`), and phone number formatting (`phone`).

### HTML, Web, and Security
HTML sanitization (`clean` with configurable safety levels), HTML entity escaping (`escape`, `unescape`), URL encoding/decoding (`encode`, `decode`), Base64 conversion (`toBase64`, `fromBase64`), JavaScript wrapping (`js`), and CDATA embedding (`cdata`).

### Data Serialization
JSON output (`json` on List, Map, and Collection), JSON parsing (`jsonList`, `jsonMap`), and currency parsing (`money` on String).

### Time and Date Operations
Duration conversion (`hours`, `minutes`, `seconds`, `days`), date/time formatting (`time`, `date`, `dateRaw`, `dateTime`, `dateTimeRaw`, `formTime`), relative time descriptions (`relativeTime`), and form time parsing (`time` on String).

### Numeric Utilities
Currency formatting (`money` on Number), ordinal representation (`ordinal`), random number generation (`random` on Integer), and random ID generation (`randomID`).

### Collection Handling
Temporary items with auto-removal (`snap`), fluent addition (`including`), random selection (`random`), string aggregation (`combine`), and HTML sanitization for list contents (`clean` on List).

### Reactive System Integration
Manual update triggers (`_update`, `_forceUpdate`) that notify connected clients when reactive data changes. These work with Spaceport's Launchpad reactivity system.

### Conditional Utilities
Conditional rendering (`if` on String and List), membership checking (`isPresent`).


## A Quick Example

Here is a taste of how class enhancements simplify typical web application code:

```groovy
// String formatting
"HelloWorld".kebab()         // "hello-world"
"user_name".titleCase()      // "User Name"
"Cafe au lait".slugify()     // "cafe-au-lait"

// HTML safety
userInput.clean()            // strips all HTML tags
"<script>".escape()          // "&lt;script&gt;"

// Time and dates
def deadline = System.currentTimeMillis() + 2.days() + 4.hours()
deadline.relativeTime()      // "in 2 days"
deadline.dateTime()          // "March 03, 2026 10:30 AM EST"

// Currency
1299.95.money()              // "$1,299.95"
'$1,299.95'.money()          // 1299.95 (BigDecimal)

// JSON
[name: 'Rocket', speed: 'fast'].json()   // '{"name":"Rocket","speed":"fast"}'

// Collections
def items = ['a', 'b', 'c']
items.random()               // random element
items.combine { "<li>${it}</li>" }  // "<li>a</li><li>b</li><li>c</li>"
```


## See Also

- [Class Enhancements API Reference](class-enhancements-api.md) -- Complete reference of every enhancement method, organized by type.
- [Class Enhancements Internals](class-enhancements-internals.md) -- How the metaclass enhancement system works under the hood.
