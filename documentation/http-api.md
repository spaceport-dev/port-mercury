# HTTP Client — API Reference

**Package:** `spaceport.communications.http`

```groovy
import spaceport.communications.http.HTTP
```

The `HTTP` class provides static methods for making outbound HTTP requests. It uses a shared Apache HttpComponents `CloseableHttpClient` instance internally.

---

## Methods

All methods share the same return format and accept an optional `options` map.

### `HTTP.get(String url, Map options = [:])`

Sends an HTTP GET request.

```groovy
def response = HTTP.get('https://api.example.com/users')
```

- **Parameters:** `url` — the target URL; `options` — see [Options](#options).
- **Returns:** [Response Map](#response-map).
- **Supported options:** `queryParams`, `headers`, `connectTimeout`, `socketTimeout`, `connectionRequestTimeout`, `acceptUntrustedCerts`.

---

### `HTTP.post(String url, Map options = [:])`

Sends an HTTP POST request.

```groovy
def response = HTTP.post('https://api.example.com/users', [
    body: [name: 'Alice', email: 'alice@example.com'],
    headers: ['Authorization': 'Bearer token123']
])
```

- **Parameters:** `url`; `options` — see [Options](#options).
- **Returns:** [Response Map](#response-map).
- **Supported options:** All options including `body` and `contentType`.

---

### `HTTP.put(String url, Map options = [:])`

Sends an HTTP PUT request. Supports the same options as `post`.

```groovy
def response = HTTP.put('https://api.example.com/users/42', [
    body: [name: 'Alice Smith']
])
```

---

### `HTTP.delete(String url, Map options = [:])`

Sends an HTTP DELETE request. Does **not** support `body` or `contentType` options (HTTP DELETE has no entity).

```groovy
def response = HTTP.delete('https://api.example.com/users/42', [
    headers: ['Authorization': 'Bearer token123']
])
```

---

### `HTTP.patch(String url, Map options = [:])`

Sends an HTTP PATCH request. Supports the same options as `post`.

```groovy
def response = HTTP.patch('https://api.example.com/users/42', [
    body: [status: 'active']
])
```

---

## Options

Pass options as a Map in the second argument to any method.

| Option | Type | Default | Methods | Description |
|:-------|:-----|:--------|:--------|:------------|
| `body` | `Map`, `List`, or `String` | `null` | POST, PUT, PATCH | Request body. Maps and Lists are automatically serialized to JSON. Strings are sent as-is. |
| `contentType` | `String` | Auto-detected | POST, PUT, PATCH | MIME type (e.g., `'application/json'`, `'application/x-www-form-urlencoded'`). Defaults to `application/json` for Map/List bodies, `text/plain` for strings. |
| `queryParams` | `Map` | `null` | All | URL query parameters. Values that are Collections are expanded into multiple parameters with the same key. |
| `headers` | `Map` | `null` | All | Request headers as key-value pairs. |
| `connectTimeout` | `int` | `10000` (10s) | All | Connection timeout in milliseconds. |
| `socketTimeout` | `int` | `30000` (30s) | All | Socket/read timeout in milliseconds — how long to wait for response data. |
| `connectionRequestTimeout` | `int` | `10000` (10s) | All | Timeout for obtaining a connection from the pool. |
| `acceptUntrustedCerts` | `boolean` | `false` | All | If `true`, uses a separate HTTP client that accepts untrusted SSL certificates. Useful for development against self-signed certs. |

### Body Serialization

The `body` option determines both the request entity and the default content type:

| Body type | Serialization | Default Content-Type |
|:----------|:-------------|:---------------------|
| `Map` | JSON via `JsonBuilder` | `application/json; charset=UTF-8` |
| `List` | JSON via `JsonBuilder` | `application/json; charset=UTF-8` |
| `String` | Sent as-is | `text/plain; charset=UTF-8` |

You can override the content type with the `contentType` option:

```groovy
HTTP.post('https://api.example.com/data', [
    body: 'name=Alice&email=alice@example.com',
    contentType: 'application/x-www-form-urlencoded'
])
```

### Query Parameter Expansion

Collection values in `queryParams` are expanded into multiple parameters with the same key:

```groovy
HTTP.get('https://api.example.com/search', [
    queryParams: [tag: ['groovy', 'java', 'web']]
])
// Produces: ?tag=groovy&tag=java&tag=web
```

---

## Response Map

All methods return a `Map` with the following keys:

| Key | Type | Description |
|:----|:-----|:------------|
| `statusCode` | `int` | The HTTP status code. Returns `-1` if the request failed before receiving a response (e.g., connection timeout). |
| `body` | `String` | The response body as a string, or `null` if the response has no entity. |
| `headers` | `Map` | Response headers. When a header appears multiple times (e.g., `Set-Cookie`), the value is a `List` of strings. |
| `error` | `Exception` | The exception if the request failed, or `null` on success. |

### Checking for Success

```groovy
def response = HTTP.get('https://api.example.com/data')

if (response.error) {
    println "Request failed: ${response.error.message}"
} else if (response.statusCode == 200) {
    def data = response.body.jsonMap()
} else {
    println "Unexpected status: ${response.statusCode}"
}
```

### Parsing JSON Responses

Use the `.jsonMap()` or `.jsonList()` String enhancements to parse response bodies:

```groovy
def response = HTTP.get('https://api.example.com/users')
def users = response.body.jsonList()    // List of Maps

def response2 = HTTP.get('https://api.example.com/user/42')
def user = response2.body.jsonMap()     // Map
```

---

## Complete Examples

### GET with Authentication and Timeout

```groovy
def response = HTTP.get('https://api.example.com/orders', [
    queryParams: [status: 'pending', limit: '50'],
    headers: [
        'Authorization': 'Bearer ' + apiToken,
        'Accept': 'application/json'
    ],
    connectTimeout: 5000,
    socketTimeout: 15000
])

if (response.statusCode == 200) {
    def orders = response.body.jsonList()
    orders.each { order ->
        println "${order.id}: ${order.total.money()}"
    }
}
```

### POST with JSON Body

```groovy
def response = HTTP.post('https://hooks.slack.com/services/xxx', [
    body: [text: "New order received: #${orderId}"]
])
```

### PUT to Update a Resource

```groovy
def response = HTTP.put("https://api.example.com/users/${userId}", [
    body: [name: newName, email: newEmail],
    headers: ['Authorization': 'Bearer ' + token]
])

if (response.statusCode == 200) {
    println "User updated"
}
```

### DELETE with Query Parameters

```groovy
def response = HTTP.delete("https://api.example.com/cache", [
    queryParams: [key: cacheKey],
    headers: ['X-Admin-Key': adminKey]
])
```

### Error Handling Pattern

```groovy
def callExternalApi(String endpoint, Map params = [:]) {
    def response = HTTP.get(endpoint, [
        queryParams: params,
        connectTimeout: 5000,
        socketTimeout: 10000
    ])

    if (response.error) {
        Command.error("API request failed: ${response.error.message}")
        return null
    }

    if (response.statusCode != 200) {
        Command.error("API returned status ${response.statusCode}: ${response.body}")
        return null
    }

    return response.body.jsonMap()
}
```

### Using Inside a Route Handler

```groovy
@Alert('on /api/weather hit')
static _weather(HttpResult r) {
    def city = r.context.data.city ?: 'New York'
    def response = HTTP.get('https://api.weather.example.com/current', [
        queryParams: [city: city, units: 'imperial'],
        headers: ['X-API-Key': Spaceport.config.'weather.api.key'],
        connectTimeout: 5000,
        socketTimeout: 10000
    ])

    if (response.statusCode == 200) {
        def weather = response.body.jsonMap()
        r.writeToClient([success: true, weather: weather])
    } else {
        r.writeToClient([success: false, error: 'Weather service unavailable'])
    }
}
```

---

## See Also

- [HTTP Client Overview](http-overview.md) — what it is and when to use it
- [Routing Examples](routing-examples.md) — more patterns for using HTTP in route handlers
- [Class Enhancements](class-enhancements-api.md) — `.jsonMap()`, `.jsonList()`, and other String methods
