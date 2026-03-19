# Cargo API Reference

## Factory Methods

### `new Cargo()`

Creates an empty local Cargo instance backed by an empty map.

```groovy
def cargo = new Cargo()
```

### `new Cargo(Map initial)`

Creates a local Cargo instance initialized with the contents of `initial`. Nested maps are auto-coerced into child Cargo instances with parent references.

```groovy
def cargo = new Cargo([name: 'Alice', prefs: [theme: 'dark']])
cargo.get('prefs.theme')  // "dark"
```

### `Cargo.fromStore(String name)`

Returns a named singleton Cargo from `Spaceport.store` (a server-wide `ConcurrentHashMap`). If no Cargo exists with that name, one is created and stored. Survives hot-reload but not server restart.

```groovy
def cache = Cargo.fromStore('active-jobs')
```

**Parameters:**
| Parameter | Type | Description |
|---|---|---|
| `name` | `String` | Unique key in the global store |

**Returns:** `Cargo` -- the named singleton instance.

### `Cargo.fromDocument(Document doc)`

Returns a Cargo instance mirrored to the `cargo` field of `doc`. Changes auto-save to CouchDB with a 3ms debounce. If a mirror already exists for this document, the existing instance is returned.

```groovy
def stats = Cargo.fromDocument(Document.get('stats', 'my-db'))
```

**Parameters:**
| Parameter | Type | Description |
|---|---|---|
| `doc` | `Document` | A Spaceport Document instance |

**Returns:** `Cargo` -- a document-mirrored instance.

---

## Path Notation

Most Cargo methods accept a **path** parameter: a dot-separated string that addresses nested values. Each segment between dots becomes a key at one level of nesting.

```groovy
cargo.set('a.b.c', 42)
// Equivalent to: root['a']['b']['c'] = 42
// Intermediate maps are created automatically
```

When a path has only one segment (no dots), it operates on the top-level map directly.

**Quoted keys:** When keys themselves contain dots or special characters, use Groovy's map access syntax rather than path notation:

```groovy
cargo.'my-key'.set('value')
```

---

## Core Data Access

### `get(String path)`

Returns the value at `path`. If the value is a `Map`, it is returned as a nested `Cargo` instance.

```groovy
cargo.get('user.name')  // "Alice"
```

### `get()`

Returns the single stored value when the Cargo holds one top-level entry, or returns the full root map.

### `set(String path, Object value)`

Sets `value` at `path`, creating intermediate maps as needed. Triggers `synchronize()` to push reactive updates.

```groovy
cargo.set('user.profile.bio', 'Rocket scientist')
```

**Returns:** `void`

### `set(Object value)`

Sets a single value at an auto-generated or single key. Triggers `synchronize()`.

### `delete(String path)`

Removes the value at `path`. Triggers `synchronize()`.

```groovy
cargo.delete('user.profile.bio')
```

### `delete()`

Removes the single stored value.

### `exists(String path)`

Returns `true` if a non-null value exists at `path`.

```groovy
if (cargo.exists('user.profile')) { ... }
```

**Returns:** `Boolean`

### `clear()`

Removes all entries from the Cargo. Triggers `synchronize()`.

---

## Typed Getters

These methods retrieve a value at a path and coerce it to the specified type. They return `null` if the path does not exist.

### `getString(String path)`

Returns the value as a `String`.

### `getInteger(String path)`

Returns the value as an `Integer`.

### `getNumber(String path)`

Returns the value as a `Number`.

### `getRoundedInteger(String path)`

Returns the value as an `Integer`, rounding if the stored value is a floating-point number.

### `getBool(String path)`

Returns the value as a `Boolean`.

### `getList(String path)`

Returns the value as a `List`.

---

## Default Value Methods

### `getDefaulted(String path, Object defaultValue)`

Returns the value at `path` if it exists. If it does not exist, **writes** `defaultValue` to `path` and returns it. This is a write-back default -- it modifies the Cargo.

```groovy
def theme = cargo.getDefaulted('prefs.theme', 'light')
// If 'prefs.theme' was missing, it is now set to 'light'
```

### `getOrDefault(String path, Object defaultValue)`

Returns the value at `path` if it exists, otherwise returns `defaultValue` **without** modifying the Cargo. This is a read-only default.

```groovy
def theme = cargo.getOrDefault('prefs.theme', 'light')
// Cargo is unchanged
```

---

## Counter and Toggle Operations

### `inc(String path, Number n = 1)`

Increments the numeric value at `path` by `n`. If the path does not exist, initializes to `n`.

```groovy
cargo.inc('stats.pageViews')       // +1
cargo.inc('stats.score', 10)       // +10
```

### `inc(Number n = 1)`

Increments the single stored value by `n`.

### `dec(String path, Number n = 1)`

Decrements the numeric value at `path` by `n`.

```groovy
cargo.dec('inventory.rockets')     // -1
cargo.dec('balance', 5.50)         // -5.50
```

### `dec(Number n = 1)`

Decrements the single stored value by `n`.

### `toggle(String path)`

Flips a boolean value at `path`. If `true`, becomes `false`; if `false`, becomes `true`.

```groovy
cargo.toggle('settings.darkMode')
```

---

## List Operations

### `append(String path, Object value)`

Appends `value` to the list at `path`. Creates the list if it does not exist.

```groovy
cargo.append('log.entries', [time: now, msg: 'Started'])
```

### `remove(String path, Object value)`

Removes the first occurrence of `value` from the list at `path`.

### `setNext(Object value)`

Adds `value` with an auto-incrementing integer key. Useful for building ordered collections without managing keys manually.

```groovy
def messages = Cargo.fromDocument(doc)
messages.setNext([from: 'Alice', text: 'Hello'])
messages.setNext([from: 'Bob', text: 'Hi there'])
// Keys: '0', '1', '2', ...
```

---

## Set Operations

### `addToSet(String path, Object value)`

Adds `value` to a set (list with uniqueness) at `path`. If `value` already exists, it is not added again.

```groovy
cargo.addToSet('tags', 'groovy')
cargo.addToSet('tags', 'groovy')  // no duplicate
```

### `takeFromSet(String path, Object value)`

Removes `value` from the set at `path`.

### `contains(String path, Object value)`

Returns `true` if the collection at `path` contains `value`.

```groovy
if (cargo.contains('tags', 'groovy')) { ... }
```

**Returns:** `Boolean`

---

## Collection Operations

These methods operate on the Cargo's top-level entries (or on the entries of a nested Cargo accessed via path).

### `keys()`

Returns a `Set` of all top-level keys.

### `values()`

Returns a `Collection` of all top-level values.

### `size()`

Returns the number of top-level entries.

### `entrySet()`

Returns the set of `Map.Entry` objects.

### `isEmpty()`

Returns `true` if the Cargo has no entries.

### `asBoolean()`

Returns `true` if the Cargo is non-empty. Allows Groovy truthiness checks:

```groovy
if (cargo) { println 'has data' }
```

---

## Iteration and Transformation

### `each(Closure c)`

Iterates over all entries, passing each key-value pair to the closure.

```groovy
cargo.each { key, value ->
    println "$key = $value"
}
```

### `collect(Closure c)`

Transforms each entry using the closure and returns the results as a list.

```groovy
def names = cargo.collect { k, v -> v.name }
```

### `combine(Closure c)`

Merges entries using a combining closure.

### `map()`

Returns the underlying root map.

### `sort(Closure c)`

Sorts entries using the provided comparator closure.

```groovy
def sorted = cargo.sort { a, b -> a.value <=> b.value }
```

### `reverse()`

Returns entries in reverse order.

### `first()`

Returns the first entry.

### `last()`

Returns the last entry.

---

## Filtering and Searching

### `find(Closure c)`

Returns the first entry matching the closure predicate.

```groovy
def admin = cargo.find { k, v -> v.role == 'admin' }
```

### `findKey(Closure c)`

Returns the key of the first entry matching the predicate.

### `findAll(Closure c)`

Returns all entries matching the closure predicate as a new collection.

```groovy
def active = cargo.findAll { k, v -> v.active }
```

### `findAllKeys(Closure c)`

Returns all keys whose entries match the predicate.

### `count(Closure c)`

Returns the number of entries matching the predicate.

### `any(Closure c)`

Returns `true` if at least one entry matches the predicate.

### `every(Closure c)`

Returns `true` if all entries match the predicate.

---

## Pagination

### `paginate(int page, int size)`

Returns a subset of entries for the given page number and page size.

```groovy
def page1 = cargo.paginate(1, 10)  // entries 1-10
def page2 = cargo.paginate(2, 10)  // entries 11-20
```

### `paginate(int page, int size, Closure sort)`

Paginates with a custom sort applied before slicing.

### `paginate(int page, int size, Closure sort, Closure collect)`

Paginates with a custom sort and a transformation closure applied to each result.

```groovy
def page = cargo.paginate(1, 20,
    { a, b -> b.value.date <=> a.value.date },  // sort newest first
    { k, v -> [id: k, title: v.title] }          // transform
)
```

---

## Serialization

### `toString()`

Returns a string representation of the Cargo contents.

### `toJSON()`

Returns a compact JSON string.

```groovy
println cargo.toJSON()
// {"name":"Alice","score":42}
```

### `toPrettyJSON()`

Returns a formatted, human-readable JSON string.

```groovy
println cargo.toPrettyJSON()
// {
//   "name": "Alice",
//   "score": 42
// }
```

---

## Properties

| Property | Type | Description |
|---|---|---|
| `_id` | `String` | Unique instance identifier, generated via `12.randomID()` |
| `root` | `Map` | The underlying data map |
| `document` | `Document` | The mirrored document (if created via `fromDocument`), otherwise `null` |
| `parent` | `Cargo` | Parent Cargo instance for nested sub-cargos, otherwise `null` |

---

## See Also

- [Cargo Overview](cargo-overview.md) -- high-level introduction
- [Cargo Internals](cargo-internals.md) -- implementation details and reactive mechanism
- [Cargo Examples](cargo-examples.md) -- real-world usage patterns
- [Documents API](documents-api.md) -- the Document class that integrates with Cargo
- [Launchpad Overview](launchpad-overview.md) -- reactive templates that consume Cargo data
