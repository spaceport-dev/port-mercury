# HTTP Client — Overview

Spaceport includes a built-in HTTP client utility for making outbound requests to external APIs, webhooks, and services from your server-side code.

## What It Is

The `HTTP` class is a static utility that wraps Apache HttpComponents, providing a simple Groovy-friendly interface for all standard HTTP methods: GET, POST, PUT, DELETE, and PATCH.

```groovy
import spaceport.communications.http.HTTP

def response = HTTP.get('https://api.example.com/users')
if (response.statusCode == 200) {
    def users = response.body.jsonList()
}
```

## When to Use It

Use the HTTP client whenever your application needs to communicate with external services:

- **Calling third-party APIs** — weather services, payment processors, notification providers
- **Webhook delivery** — notifying other systems when events happen in your application
- **Service integration** — connecting to microservices, CRMs, email providers
- **Data fetching** — pulling data from external sources during initialization or on demand

## Key Features

- **All standard methods** — `get`, `post`, `put`, `delete`, `patch`
- **Automatic JSON serialization** — pass a Map or List as the body and it is serialized to JSON automatically
- **Query parameter handling** — pass a `queryParams` map and the URL is built for you
- **Configurable timeouts** — control connection and socket timeouts per request
- **Consistent response format** — every method returns a Map with `statusCode`, `body`, `headers`, and `error`

## Quick Examples

### GET with Query Parameters

```groovy
def response = HTTP.get('https://api.example.com/search', [
    queryParams: [q: 'spaceport', limit: '10'],
    headers: ['Authorization': 'Bearer ' + apiKey]
])

if (response.statusCode == 200) {
    def results = response.body.jsonMap()
}
```

### POST with JSON Body

```groovy
def response = HTTP.post('https://hooks.example.com/notify', [
    body: [event: 'order_placed', orderId: orderId, total: 49.99],
    headers: ['X-Webhook-Secret': secret]
])
```

When the `body` is a Map or List, the content type is automatically set to `application/json` and the body is serialized via `JsonBuilder`.

## See Also

- [HTTP Client API Reference](http-api.md) — complete method signatures, options, and response format
- [Routing Examples](routing-examples.md) — using HTTP requests inside route handlers
