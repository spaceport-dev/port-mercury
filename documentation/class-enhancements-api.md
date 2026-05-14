# Class Enhancements API Reference

This document provides a complete reference of every method added by Spaceport's metaclass enhancement system, organized by target class. All enhancements are defined in `spaceport.engineering.MetaClassEnhancements` and applied at startup via `MetaClassEnhancements.enhance()`.


## String Methods

### `clean()`

Sanitizes HTML content using the Jsoup library. The sanitization level is controlled by the `cleanType` property on the string.

**Signature:** `String clean()`

**Returns:** `String` -- The sanitized string.

**cleanType values:**

| Value | Behavior |
|:---|:---|
| `'none'` (default) | Strips all HTML tags, leaving only text content. |
| `'simpleText'` | Allows basic text formatting: `<b>`, `<i>`, `<em>`, `<strong>`. |
| `'simple'` | Allows `simpleText` tags plus `<br>`, `<u>`, `<s>`, `<strike>`. |
| `'simpleWithImages'` | Allows `simple` tags plus `<img>` with `src` attribute (http, https, data protocols). |
| `'basic'` | Jsoup's `Safelist.basic()` -- common inline and block elements. |
| `'basicWithImages'` | Jsoup's `Safelist.basicWithImages()` -- basic plus image tags. |
| `'relaxed'` | Jsoup's `Safelist.relaxed()` plus `<img>` with height, src, width attributes (http, https, data protocols). |

```groovy
// Default: strip all HTML
"<b>Hello</b> <script>alert('xss')</script>".clean()
// Returns: "Hello alert('xss')"

// Allow simple formatting
def input = "<b>Bold</b> and <script>bad</script>"
input.cleanType = 'simple'
input.clean()
// Returns: "<b>Bold</b> and bad"
```

---

### `surroundWith(String prefix, String suffix)`

Wraps the string with the given prefix and suffix.

**Signature:** `String surroundWith(String prefix, String suffix)`

**Returns:** `String`

```groovy
"test".surroundWith("->", "<-")
// Returns: "->test<-"
```

---

### `surroundWith(String fix)`

Wraps the string with the given string. If the argument looks like an HTML tag (including tags with attributes), it automatically generates the corresponding closing tag.

**Signature:** `String surroundWith(String fix)`

**Returns:** `String`

```groovy
"content".surroundWith("<div class='main'>")
// Returns: "<div class='main'>content</div>"

"hello".surroundWith("**")
// Returns: "**hello**"
```

---

### `escape()`

Converts HTML special characters to their entity equivalents.

**Signature:** `String escape()`

**Returns:** `String`

**Conversions:** `&` to `&amp;`, `<` to `&lt;`, `>` to `&gt;`, `"` to `&quot;`, `'` to `&apos;`

```groovy
"<script>alert('xss')</script>".escape()
// Returns: "&lt;script&gt;alert(&apos;xss&apos;)&lt;/script&gt;"
```

---

### `unescape()`

Converts HTML entities back to their character equivalents. The reverse of `escape()`.

**Signature:** `String unescape()`

**Returns:** `String`

```groovy
"&lt;b&gt;bold&lt;/b&gt;".unescape()
// Returns: "<b>bold</b>"
```

---

### `quote()`

Wraps the string in double quotes, escaping any internal double quotes.

**Signature:** `String quote()`

**Returns:** `String`

```groovy
'He said "hello"'.quote()
// Returns: '"He said \"hello\""'
```

---

### `strip(String regex)`

Removes all occurrences of the given regex pattern from the string.

**Signature:** `String strip(String regex)`

**Returns:** `String`

```groovy
"Hello 123 World 456".strip(/\d+/)
// Returns: "Hello  World "
```

---

### `encode()`

URL-encodes the string using UTF-8 encoding.

**Signature:** `String encode()`

**Returns:** `String`

```groovy
"hello world&foo=bar".encode()
// Returns: "hello+world%26foo%3Dbar"
```

---

### `decode()`

URL-decodes the string using UTF-8 encoding. The reverse of `encode()`.

**Signature:** `String decode()`

**Returns:** `String`

```groovy
"hello+world%26foo%3Dbar".decode()
// Returns: "hello world&foo=bar"
```

---

### `toBase64()`

Encodes the string to Base64.

**Signature:** `String toBase64()`

**Returns:** `String`

```groovy
"Hello, World!".toBase64()
// Returns: "SGVsbG8sIFdvcmxkIQ=="
```

---

### `fromBase64()`

Decodes a Base64-encoded string. The reverse of `toBase64()`.

**Signature:** `String fromBase64()`

**Returns:** `String`

```groovy
"SGVsbG8sIFdvcmxkIQ==".fromBase64()
// Returns: "Hello, World!"
```

---

### `kebab()`

Converts the string to kebab-case. Handles camelCase, PascalCase, acronyms, spaces, underscores, and hyphens.

**Signature:** `String kebab()`

**Returns:** `String`

```groovy
"HelloWorld".kebab()       // "hello-world"
"myURLAddress".kebab()     // "my-url-address"
"hello_world".kebab()      // "hello-world"
"Already-Kebab".kebab()    // "already-kebab"
```

---

### `snake()`

Converts the string to snake_case. Handles camelCase, PascalCase, acronyms, spaces, underscores, and hyphens.

**Signature:** `String snake()`

**Returns:** `String`

```groovy
"HelloWorld".snake()       // "hello_world"
"myURLAddress".snake()     // "my_url_address"
"Hello World".snake()      // "hello_world"
```

---

### `slugify()`

Converts the string to a URL-friendly slug. Lowercases, transliterates common accented characters, replaces non-alphanumeric characters with hyphens, and trims leading/trailing hyphens.

**Signature:** `String slugify()`

**Returns:** `String`

```groovy
"Hello, World!".slugify()          // "hello-world"
"Cafe au lait".slugify()           // "cafe-au-lait"
"Uber die Brucke".slugify()        // "uber-die-brucke"
```

**Transliterated characters:** a-accents, e-accents, i-accents, o-accents, u-accents, c-cedilla, n-tilde.

---

### `titleCase()`

Converts the string to Title Case. Handles camelCase, PascalCase, acronyms, underscores, hyphens, and spaces.

**Signature:** `String titleCase()`

**Returns:** `String`

```groovy
"hello_world".titleCase()    // "Hello World"
"helloWorld".titleCase()     // "Hello World"
"URLAddress".titleCase()     // "Url Address"
"my-kebab-case".titleCase()  // "My Kebab Case"
```

---

### `phone()`

Formats a numeric string as a US phone number. Handles 10-digit and 11-digit (with leading `1`) numbers.

**Signature:** `String phone()`

**Returns:** `String`

```groovy
"5551234567".phone()      // "(555) 123-4567"
"15551234567".phone()     // "+1 (555) 123-4567"
"123".phone()             // "123" (returned unchanged)
```

---

### `money()` (String)

Parses a currency-formatted string into a `BigDecimal`. Removes dollar signs, commas, and whitespace before conversion.

**Signature:** `BigDecimal money()`

**Returns:** `BigDecimal`

```groovy
'$1,234.56'.money()    // 1234.56 (BigDecimal)
'1299.95'.money()      // 1299.95 (BigDecimal)
```

---

### `time()` (String)

Parses an HTML5 `datetime-local` form string (`yyyy-MM-dd'T'HH:mm`) into epoch milliseconds.

**Signature:** `Long time()`

**Returns:** `Long` -- Epoch milliseconds.

```groovy
"2025-10-28T14:30".time()  // epoch milliseconds for that date/time
```

---

### `jsonList()`

Parses a JSON array string into a `List`.

**Signature:** `List jsonList()`

**Returns:** `List`

```groovy
'[1, 2, 3]'.jsonList()              // [1, 2, 3]
'["a", "b"]'.jsonList()             // ["a", "b"]
```

---

### `jsonMap()`

Parses a JSON object string into a `Map`.

**Signature:** `Map jsonMap()`

**Returns:** `Map`

```groovy
'{"name":"Groovy","version":4}'.jsonMap()
// Returns: [name: "Groovy", version: 4]
```

---

### `js()`

Wraps the string in HTML `<script>` tags.

**Signature:** `String js()`

**Returns:** `String`

```groovy
"alert('hello')".js()
// Returns: "<script type='text/javascript'>alert('hello')</script>"
```

---

### `if(Closure c)` (String)

Returns the string if the closure evaluates to true; otherwise returns an empty string. Useful for conditional rendering in templates.

**Signature:** `String if(Closure c)`

**Returns:** `String`

```groovy
"<a href='/admin'>Admin</a>".if { user.isAdmin() }
// Returns the link if user is admin, empty string otherwise
```

---

### `roll()`

Evaluates tabletop dice notation and returns the result. Supports the format `NdM+X` where N is the number of dice, M is the number of sides, and X is an optional modifier.

**Signature:** `Integer roll()`

**Returns:** `Integer` or `null` if the format is invalid.

```groovy
"2d6+3".roll()    // random result: 5 to 15
"d20".roll()      // random result: 1 to 20
"3d8-2".roll()    // random result: 1 to 22
```


## Number Methods

### `money()` (Number)

Formats the number as a currency string using the default JVM locale.

**Signature:** `String money()`

**Returns:** `String`

```groovy
1299.95.money()    // "$1,299.95" (US locale)
0.5.money()        // "$0.50"
```

---

### `hours()`

Converts the number (as hours) to milliseconds.

**Signature:** `Long hours()`

**Returns:** `Long`

```groovy
2.hours()     // 7200000
0.5.hours()   // 1800000
```

---

### `minutes()`

Converts the number (as minutes) to milliseconds.

**Signature:** `Long minutes()`

**Returns:** `Long`

```groovy
30.minutes()    // 1800000
1.5.minutes()   // 90000
```

---

### `seconds()`

Converts the number (as seconds) to milliseconds.

**Signature:** `Long seconds()`

**Returns:** `Long`

```groovy
10.seconds()    // 10000
```

---

### `days()`

Converts the number (as days) to milliseconds.

**Signature:** `Long days()`

**Returns:** `Long`

```groovy
7.days()    // 604800000
```

---

### `time()` (Number)

Formats epoch milliseconds as a 12-hour time string with AM/PM.

**Signature:** `String time()`

**Returns:** `String` -- Format: `hh:mm:ss a`

```groovy
System.currentTimeMillis().time()    // "02:30:45 PM"
```

---

### `date()`

Formats epoch milliseconds as a human-readable date string.

**Signature:** `String date()`

**Returns:** `String` -- Format: `MMMM dd, yyyy`

```groovy
System.currentTimeMillis().date()    // "March 01, 2026"
```

---

### `dateRaw()`

Formats epoch milliseconds as a compact date string.

**Signature:** `String dateRaw()`

**Returns:** `String` -- Format: `MM/dd/yyyy`

```groovy
System.currentTimeMillis().dateRaw()    // "03/01/2026"
```

---

### `dateTime()`

Formats epoch milliseconds as a full date and time string with timezone.

**Signature:** `String dateTime()`

**Returns:** `String` -- Format: `MMMM dd, yyyy hh:mm a z`

```groovy
System.currentTimeMillis().dateTime()    // "March 01, 2026 02:30 PM EST"
```

---

### `dateTimeRaw()`

Formats epoch milliseconds as a compact date and time string with timezone.

**Signature:** `String dateTimeRaw()`

**Returns:** `String` -- Format: `MM/dd/yyyy hh:mm a z`

```groovy
System.currentTimeMillis().dateTimeRaw()    // "03/01/2026 02:30 PM EST"
```

---

### `relativeTime()`

Returns a human-friendly description of the time difference between the number (epoch milliseconds) and the current time. Automatically handles both past and future timestamps.

**Signature:** `String relativeTime()`

**Returns:** `String`

**Past values:**

| Time Difference | Output |
|:---|:---|
| Less than 1 minute | `"just now"` |
| 1 to 5 minutes | `"a few minutes ago"` |
| 5 minutes to 1 hour | `"less than an hour ago"` |
| 1 to 24 hours | `"N hour(s) ago"` |
| 1 to 7 days | `"N day(s) ago"` |
| 1 to 4 weeks | `"N week(s) ago"` |
| 1 to 12 months | `"N month(s) ago"` |
| Over 1 year | `"N year(s) ago"` |

**Future values:**

| Time Difference | Output |
|:---|:---|
| Less than 1 minute | `"in a moment"` |
| 1 to 5 minutes | `"in a few minutes"` |
| 5 minutes to 1 hour | `"in less than an hour"` |
| 1 to 24 hours | `"in N hour(s)"` |
| 1 to 7 days | `"in N day(s)"` |
| 1 to 4 weeks | `"in N week(s)"` |
| 1 to 12 months | `"in N month(s)"` |
| Over 1 year | `"in N year(s)"` |

```groovy
(System.currentTimeMillis() - 30000).relativeTime()         // "just now"
(System.currentTimeMillis() - 3.hours()).relativeTime()      // "3 hours ago"
(System.currentTimeMillis() + 2.days()).relativeTime()       // "in 2 days"
```

---

### `ordinal()`

Returns the ordinal string representation of the number (1st, 2nd, 3rd, etc.). Correctly handles the special cases for 11th, 12th, and 13th.

**Signature:** `String ordinal()`

**Returns:** `String`

```groovy
1.ordinal()      // "1st"
2.ordinal()      // "2nd"
3.ordinal()      // "3rd"
4.ordinal()      // "4th"
11.ordinal()     // "11th"
12.ordinal()     // "12th"
13.ordinal()     // "13th"
22.ordinal()     // "22nd"
103.ordinal()    // "103rd"
```

---

### `formTime()`

Formats epoch milliseconds into the HTML5 `datetime-local` input format, using the system default timezone.

**Signature:** `String formTime()`

**Returns:** `String` -- Format: `yyyy-MM-dd'T'HH:mm`

```groovy
System.currentTimeMillis().formTime()    // "2026-03-01T14:30"
```


## Integer Methods

### `random()` (Integer)

Returns a random integer between 0 (inclusive) and the value (exclusive).

**Signature:** `Integer random()`

**Returns:** `Integer`

```groovy
10.random()     // random number from 0 to 9
100.random()    // random number from 0 to 99
```

---

### `randomID()`

Generates a random hexadecimal ID of the specified length, guaranteed to start with a letter (a-f). Length must be between 1 and 32 inclusive.

**Signature:** `String randomID()`

**Returns:** `String`

**Throws:** `IllegalArgumentException` if the length is less than 1 or greater than 32.

```groovy
8.randomID()     // e.g., "e4b1a0c9"
16.randomID()    // e.g., "a3f8d9c2b1e4567f"
```


## List Methods

### `clean()` (List)

Cleans all `String` items in the list in place using the `cleanType` property. Non-string items are left unchanged.

**Signature:** `List clean()`

**Returns:** `List` -- The same list, modified in place.

```groovy
def inputs = ["<b>Bold</b>", "<script>bad</script>", 42]
inputs.cleanType = 'simple'
inputs.clean()
// inputs is now ["<b>Bold</b>", "bad", 42]
```

---

### `snap(Integer time, Object obj)`

Temporarily adds an item (or all items from a list) to the list, then automatically removes it after the specified number of milliseconds. Triggers `_update()` on removal for reactive updates.

**Signature:** `List snap(Integer time, Object obj)`

**Returns:** `List` -- The modified list.

```groovy
def messages = []
messages.snap(5000, "This disappears in 5 seconds.")

// Add multiple items temporarily
messages.snap(3.seconds(), ["Alert 1", "Alert 2"])
```

---

### `random()` (List)

Returns a random element from the list.

**Signature:** `Object random()`

**Returns:** `Object`

```groovy
['red', 'green', 'blue'].random()    // random color
```

---

### `combine(Closure c)`

Collects each item using the closure and joins the results into a single string.

**Signature:** `String combine(Closure c)`

**Returns:** `String`

```groovy
['a', 'b', 'c'].combine { "<li>${it}</li>" }
// Returns: "<li>a</li><li>b</li><li>c</li>"
```

---

### `combine(Closure sort, Closure collect)`

Sorts the list using the first closure, collects each item using the second closure, and joins the results into a single string.

**Signature:** `String combine(Closure sort, Closure collect)`

**Returns:** `String`

```groovy
[3, 1, 2].combine({ it }, { "<span>${it}</span>" })
// Returns: "<span>1</span><span>2</span><span>3</span>"
```

---

### `including(Object obj)`

Adds an item or all items from another list to this list, returning the modified list for chaining. Returns the list unchanged if `obj` is null/falsy.

**Signature:** `List including(Object obj)`

**Returns:** `List`

```groovy
[1, 2, 3].including(4)              // [1, 2, 3, 4]
[1, 2, 3].including([4, 5, 6])      // [1, 2, 3, 4, 5, 6]
```

---

### `json()` (List)

Serializes the list to a JSON string using Jackson's `ObjectMapper`.

**Signature:** `String json()`

**Returns:** `String`

```groovy
[1, 'two', 3].json()    // '[1,"two",3]'
```

---

### `if(Closure c)` (List)

Returns the first element if the closure evaluates to true, or the second element if false. Useful for conditional selection between two values.

**Signature:** `Object if(Closure c)`

**Returns:** `Object`

```groovy
["Success!", "Failed!"].if { operation.succeeded() }
// Returns "Success!" if true, "Failed!" if false
```


## Map Methods

### `snap(Integer time, String key, Object obj)`

Temporarily adds a key-value pair (or all entries from a map) to this map, then automatically removes it after the specified number of milliseconds. Triggers `_update()` on removal for reactive updates.

**Signature:** `Map snap(Integer time, String key, Object obj)`

**Returns:** `Map` -- The modified map.

```groovy
def flags = [:]
flags.snap(1.minutes(), 'isNew', true)
// 'isNew' key is removed after 1 minute
```

---

### `combine(Closure c)`

Collects each entry using the closure and joins the results into a single string.

**Signature:** `String combine(Closure c)`

**Returns:** `String`

```groovy
[name: 'Alice', role: 'Admin'].combine { "${it.key}: ${it.value}" }
// Returns: "name: Alicerole: Admin"
```

---

### `combine(Closure sort, Closure collect)`

Sorts entries using the first closure, collects each entry using the second closure, and joins the results into a single string.

**Signature:** `String combine(Closure sort, Closure collect)`

**Returns:** `String`

```groovy
[c: 3, a: 1, b: 2].combine({ it.key }, { it.value })
// Returns: "123"
```

---

### `including(Map map)`

Adds all entries from the given map to this map, returning the modified map for chaining. Returns the map unchanged if the argument is null/falsy.

**Signature:** `Map including(Map map)`

**Returns:** `Map`

```groovy
[a: 1, b: 2].including([c: 3, d: 4])
// [a: 1, b: 2, c: 3, d: 4]
```

---

### `random()` (Map)

Returns a random `Map.Entry` from the map.

**Signature:** `Map.Entry random()`

**Returns:** `Map.Entry`

```groovy
[a: 1, b: 2, c: 3].random()    // random entry, e.g., b=2
```

---

### `json()` (Map)

Serializes the map to a JSON string using Jackson's `ObjectMapper`.

**Signature:** `String json()`

**Returns:** `String`

```groovy
[name: 'Rocket', speed: 'fast'].json()
// Returns: '{"name":"Rocket","speed":"fast"}'
```


## Collection Methods

### `json()` (Collection)

Serializes any `Collection` to a JSON string using Jackson's `ObjectMapper`.

**Signature:** `String json()`

**Returns:** `String`

```groovy
([1, 2, 3] as Set).json()    // '[1,2,3]'
```


## Object Methods

### `_update(forcedUpdate = false)`

Triggers reactive updates for all Launchpad connections bound to this object. When a reactive variable is modified, calling `_update()` notifies all connected clients whose reactions reference this object.

By default, `_update()` only sends updates when the rendered payload has changed. This avoids unnecessary network traffic.

**Signature:** `void _update(forcedUpdate = false)`

**Parameters:**

| Parameter | Type | Default | Description |
|:---|:---|:---|:---|
| `forcedUpdate` | `boolean` | `false` | If `true`, sends updates regardless of whether the payload changed. |

```groovy
myList.add("new item")
myList._update()    // notify connected clients
```

---

### `_forceUpdate()`

Convenience method that calls `_update(true)`, forcing reactive updates to be sent to all connected clients regardless of whether the rendered value has changed.

**Signature:** `void _forceUpdate()`

```groovy
myObject._forceUpdate()    // force refresh on all clients
```

---

### `isPresent(Collection collection)`

Checks if this object is contained in the given collection. A fluent alternative to `collection.contains(object)`.

**Signature:** `Boolean isPresent(Collection collection)`

**Returns:** `Boolean`

```groovy
5.isPresent([1, 3, 5, 7])      // true
'x'.isPresent(['a', 'b'])      // false
```

---

### `cdata()`

Wraps the object in CDATA tags for safe embedding in XML/HTML contexts. Strings are wrapped directly; non-string objects are converted to JSON first.

**Signature:** `String cdata()`

**Returns:** `String`

```groovy
"some text".cdata()
// Returns: "<!--<![CDATA[some text]]>--!>"

[name: "Test", value: 123].cdata()
// Returns: '<!--<![CDATA[{"name":"Test","value":123}]]>--!>'
```


## Closure Methods

### `callWith(Object delegate, Object... args)`

Sets the closure's delegate to the given object, then calls the closure with the provided arguments.

**Signature:** `Object callWith(Object delegate, Object... args)`

**Returns:** The return value of the closure.

```groovy
def greet = { name -> "Hello, ${name}! I am ${delegate.identity}." }
greet.callWith([identity: 'Spaceport'], 'World')
// Returns: "Hello, World! I am Spaceport."
```


## String Properties

### `cleanType`

A property added to `String` (and `List`) that controls the behavior of the `clean()` method.

**Type:** `String` (nullable)

**Default:** `null` (equivalent to `'none'`)

**Valid values:** `'none'`, `'simpleText'`, `'simple'`, `'simpleWithImages'`, `'basic'`, `'basicWithImages'`, `'relaxed'`

```groovy
def input = "<b>Bold</b> <script>bad</script>"
input.cleanType = 'simple'
input.clean()    // "<b>Bold</b> bad"
```


## See Also

- [Class Enhancements Overview](class-enhancements-overview.md) -- High-level introduction to the enhancement system.
- [Class Enhancements Internals](class-enhancements-internals.md) -- How metaclass enhancements work under the hood.
- [Launchpad Overview](launchpad-overview.md) -- Where many of these enhancements are used in templates.
