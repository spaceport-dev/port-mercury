# Manifest Configuration API Reference

Complete reference for all configuration keys recognized by Spaceport in the manifest file (`config.spaceport`). All keys are optional -- unspecified keys inherit their default values.


## Application Identity

### `spaceport name`

**Type:** `String`
**Default:** Name of the current working directory

A human-readable identifier for your application. Used internally for path resolution (via `Spaceport.getSpaceportFile()`) and displayed in logs.

```yaml
spaceport name: my-application
```

### `spaceport root`

**Type:** `String`
**Default:** Absolute path of the current working directory

The base directory for resolving all relative paths in the configuration. All relative paths for source modules, static assets, stowaways, migrations, and logging are resolved relative to this directory.

```yaml
spaceport root: /var/www/my-application
```


## Host Settings

### `host`

**Type:** `Map`

Controls how the Jetty server binds to the network.

### `host.address`

**Type:** `String`
**Default:** `127.0.0.1`

The network interface to bind to.

| Value | Effect |
|---|---|
| `127.0.0.1` | Accept connections from localhost only |
| `0.0.0.0` | Accept connections from any network interface |
| Specific IP | Bind to that specific interface |

```yaml
host:
  address: 0.0.0.0
```

### `host.port`

**Type:** `Integer`
**Default:** `10000`

The TCP port for incoming HTTP and WebSocket connections.

```yaml
host:
  port: 8080
```


## Debug Mode

### `debug`

**Type:** `Boolean`
**Default:** `true`

Toggles development mode. When `true`:

- Source modules are hot-reloaded on file changes
- Directory listings are enabled for static asset paths
- More verbose error messages are displayed
- Static asset file-mapped buffers are disabled (for live editing)
- The `spaceport-uuid` cookie is not restricted to HTTPS

When `false`:

- Hot-reloading is disabled
- Directory listings are disabled
- File-mapped buffers are enabled for static assets (better performance)
- The `spaceport-uuid` cookie is set with the `Secure` flag

**Always set to `false` in production.**

```yaml
debug: false
```


## Memory Cores

### `memory cores`

**Type:** `Map`

Database connection configuration. Spaceport uses the term "memory cores" for data storage backends.

### `memory cores.main`

**Type:** `Map`

The primary database connection, used by the Documents system, Cargo persistence, and client management.

### `memory cores.main.type`

**Type:** `String`
**Default:** `couchdb`

The database type. Currently only `couchdb` is supported.

### `memory cores.main.address`

**Type:** `String`
**Default:** `http://127.0.0.1:5984`

The full URL of the CouchDB server.

```yaml
memory cores:
  main:
    address: http://db.example.com:5984
```

### `memory cores.main.username`

**Type:** `String`
**Default:** None (anonymous access)

CouchDB authentication username.

### `memory cores.main.password`

**Type:** `String`
**Default:** None (anonymous access)

CouchDB authentication password. Use environment variable substitution to avoid storing credentials in the file.

```yaml
memory cores:
  main:
    address: ${ COUCHDB_URL }
    username: ${ COUCHDB_USER }
    password: ${ COUCHDB_PASSWORD }
```

You can define additional named memory cores for connecting to multiple databases:

```yaml
memory cores:
  main:
    address: http://127.0.0.1:5984
  analytics:
    address: http://analytics-db:5984
    username: reader
    password: ${ ANALYTICS_DB_PASSWORD }
```


## Source Modules

### `source modules`

**Type:** `Map`

Configuration for Groovy source module loading.

### `source modules.paths`

**Type:** `List<String>`
**Default:** `[ 'modules/*' ]`

Directories to scan for `.groovy` source files. Paths are resolved relative to `spaceport root`.

The trailing `*` wildcard enables recursive subdirectory scanning:

```yaml
# Only loads .groovy files directly in modules/ (no subdirectories)
source modules:
  paths:
    - modules/

# Loads .groovy files in modules/ AND all subdirectories
source modules:
  paths:
    - modules/*
```

Multiple paths are supported:

```yaml
source modules:
  paths:
    - modules/*
    - lib/shared/*
    - plugins/
```

**Note:** The `paths` key is replaced entirely (not merged) when specified in the manifest. If you define `source modules.paths`, it completely overrides the default.


## Static Assets

### `static assets`

**Type:** `Map`

Configuration for serving static files (CSS, JavaScript, images, fonts).

### `static assets.paths`

**Type:** `Map<String, String>`
**Default:** `{ '/assets/' : 'assets/*' }`

Maps URL paths (keys) to filesystem directories (values). The wildcard `*` in the filesystem path enables serving files from subdirectories.

```yaml
static assets:
  paths:
    /assets/ : assets/*
    /public/ : public/*
    /downloads/ : files/downloads/
```

Without the wildcard, only files directly in the mapped directory are served:

```yaml
# Serves assets/style.css but NOT assets/css/style.css
static assets:
  paths:
    /assets/ : assets/

# Serves both assets/style.css and assets/css/style.css
static assets:
  paths:
    /assets/ : assets/*
```

Filesystem paths are resolved relative to `spaceport root`. If an absolute path is provided, it is used as-is.

Static assets are checked before dynamic routes. If a request matches both a static file and a route handler, the static file is served.

**Note:** The `paths` key is replaced entirely when specified.

### `static assets.buffer`

**Type:** `Boolean`
**Default:** Inverse of `debug` (i.e., `true` when `debug` is `false`)

Controls whether Jetty uses memory-mapped file buffers for serving static files. When enabled, files are memory-mapped for faster serving. When disabled, files are read from disk on each request (useful during development when files change frequently).

```yaml
static assets:
  buffer: true
```


## Stowaways

### `stowaways`

**Type:** `Map`

Configuration for loading external JAR dependencies.

### `stowaways.enabled`

**Type:** `Boolean`
**Default:** `true`

Toggle stowaway JAR loading on or off.

### `stowaways.paths`

**Type:** `List<String>`
**Default:** `[ 'stowaways/*' ]`

Directories to scan for `.jar` files. Wildcards enable subdirectory scanning. Paths are resolved relative to `spaceport root`.

```yaml
stowaways:
  enabled: true
  paths:
    - stowaways/*
    - lib/jars/*
```

Stowaway JARs are loaded into the classpath at startup, before source modules are compiled. This makes third-party Java/Groovy libraries available to your source modules.


## Logging

### `logging`

**Type:** `Map`

Configuration for application logging.

### `logging.enabled`

**Type:** `Boolean`
**Default:** `false`

Toggle logging on or off.

### `logging.path`

**Type:** `String`
**Default:** `log/`

Directory where Spaceport writes log files. Resolved relative to `spaceport root`. When logging is enabled, Spaceport creates daily log files named `YYYY-MM-DD.log`.

```yaml
logging:
  enabled: true
  path: /var/log/myapp/
```


## Migrations

### `migrations`

**Type:** `Map`

Configuration for database migration scripts.

### `migrations.path`

**Type:** `String`
**Default:** `migrations/`

Directory containing `.groovy` migration scripts. Resolved relative to `spaceport root`.

```yaml
migrations:
  path: db/migrations/
```

Migration scripts are executed via `--migrate` and are not loaded during normal `--start` operation.


## HTTP Configuration

These settings control the behavior of the `HttpRequestHandler`, which processes all HTTP requests. They are read at handler initialization time.

### `http.methods.allow`

**Type:** `List<String>`
**Default:** `[ 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS' ]`

Whitelist of allowed HTTP methods. Requests using methods not in this list receive a `405 Method Not Allowed` response. Use `'*'` to allow all methods.

```yaml
http:
  methods:
    allow:
      - GET
      - POST
```

### `http.auto process options`

**Type:** `Boolean`
**Default:** `true`

When `true`, `OPTIONS` and `HEAD` requests are automatically responded to with a `200` status and the appropriate CORS headers, without invoking any Alert handlers. Set to `false` if you need custom handling for preflight requests.

```yaml
http:
  auto process options: false
```

### `http.cache control`

**Type:** `String`
**Default:** `"no-cache, no-store, must-revalidate"`

The value of the `Cache-Control` HTTP response header. Applied to all HTTP responses.

```yaml
http:
  cache control: "public, max-age=3600"
```

### `http.pragma`

**Type:** `String`
**Default:** `"no-cache"`

The value of the `Pragma` HTTP response header (HTTP 1.0 cache control).

```yaml
http:
  pragma: "no-cache"
```

### `http.expires`

**Type:** `Long`
**Default:** `0`

The `Expires` header value in milliseconds. When `0`, the response is immediately stale. When greater than `0`, the expiry is set to the current time plus this value.

```yaml
http:
  expires: 3600000  # 1 hour in milliseconds
```

### `http.allow credentials`

**Type:** `Boolean`
**Default:** `true`

Controls the `Access-Control-Allow-Credentials` response header. Set to `true` to allow cookies and credentials in cross-origin requests.

```yaml
http:
  allow credentials: true
```

### `http.allow headers`

**Type:** `String`
**Default:** `"Content-Type"`

The value of the `Access-Control-Allow-Headers` response header. Specifies which HTTP headers are allowed in cross-origin requests.

```yaml
http:
  allow headers: "Content-Type, Authorization, X-Requested-With"
```

### `http.spaceport cookie expiration`

**Type:** `Integer`
**Default:** `5184000` (86400 * 60 = 60 days, in seconds)

The max-age of the `spaceport-uuid` session tracking cookie, in seconds. This cookie identifies clients across requests and is used by the session and client management system.

```yaml
http:
  spaceport cookie expiration: 2592000  # 30 days
```


## Custom Configuration Keys

Any key not listed above is treated as a custom application configuration value. Custom keys are accessible via `Spaceport.config` in your source modules.

```yaml
# Custom keys
app name: My Application
version: 2.1.0

email:
  smtp host: smtp.example.com
  smtp port: 587
  from address: noreply@example.com

feature flags:
  enable beta features: true
  maintenance mode: false
```

Access in Groovy:

```groovy
def appName = Spaceport.config.'app name'
def smtpHost = Spaceport.config.email.'smtp host'
def betaEnabled = Spaceport.config.'feature flags'.'enable beta features'
```

Custom keys participate in the same deep merge and environment variable substitution as built-in keys.


## Environment Variable Substitution

Any string value in the manifest can contain `${ VAR_NAME }` placeholders. After the deep merge, Spaceport recursively processes all values and replaces these placeholders with the corresponding `System.getenv()` values.

```yaml
memory cores:
  main:
    address: ${ COUCHDB_URL }
    password: ${ COUCHDB_PASSWORD }

api keys:
  stripe: ${ STRIPE_SECRET_KEY }
```

- Whitespace inside the braces is trimmed: `${ VAR }` and `${VAR}` both work
- If the environment variable is not set, the placeholder remains in the string unchanged
- Substitution works in maps, lists, and plain string values
- Non-string values (numbers, booleans, null) are not processed


## Configuration Merge Behavior

The deep merge follows these rules:

1. **Maps:** Merged recursively. Keys present in both your config and defaults are merged; keys only in one are kept.
2. **`paths` keys:** Replaced entirely, not merged. This lets you completely override path lists.
3. **Non-map values:** Your value replaces the default (string, number, boolean, list).
4. **Keys only in defaults:** Retained as-is.
5. **Keys only in your config:** Added to the result (this is how custom keys work).

```groovy
// Simplified merge logic from Spaceport.deepMerge()
if (baseValue instanceof Map && overrideValue instanceof Map) {
    if (key == 'paths') {
        result[key] = overrideValue          // Replace paths entirely
    } else {
        result[key] = deepMerge(base, override)  // Recursive merge
    }
} else {
    result[key] = overrideValue              // Override wins
}
```


## See Also

- [Manifest Overview](manifest-overview.md) -- How configuration loading works
- [Manifest Examples](manifest-examples.md) -- Real-world configuration files
- [CLI Overview](cli-overview.md) -- Starting Spaceport with a manifest
