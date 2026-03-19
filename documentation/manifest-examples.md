# Manifest Configuration Examples

Real-world configuration files and patterns drawn from Spaceport projects.


## Port-Mercury: Full-Featured Starter Kit

The Port-Mercury starter kit demonstrates a typical development configuration with logging, custom application keys, and PWA support.

**File:** `config.spaceport`

```yaml
# Spaceport configuration manifest file (MERCURY)
# See also: https://www.spaceport.com.co/docs/configuration-manifest

spaceport name: port-mercury

# Optional, defaults to the working directory
# spaceport root: /home/jeremy/spaceport
# Or, Windows style
# spaceport root: C:\users\jeremy\spaceport

host:
  address: 127.0.0.1
  port: 10000

debug: true

logging:
  enabled: true
  path: logs/

memory cores:
  main:
    address: 127.0.0.1:5984
    # Optional, will prompt for username/password if not provided here
    # username: admin
    # password: password

source modules:
  paths:
    - modules/*

static assets:
  paths:
    /assets/* : assets/

#
# User-defined configuration nodes
#

PWA:
  enabled: true
  # PWA (Progressive Web App) configuration
  name: Spaceport PWA
  icons:
    - src: /assets/img/icon.svg
      sizes: any
      type: image/svg+xml

  # Optional PWA settings: short name, description, id, start url,
  # theme color, background color, orientation, scope and display
```

**Key patterns in this example:**

- `spaceport root` is commented out, relying on the default (current working directory)
- CouchDB credentials are commented out; Spaceport will prompt for them at startup if authentication is required
- The `PWA` section is a custom configuration key, accessible in code as `Spaceport.config.PWA`
- Static assets use `/assets/*` as the URL path (with wildcard in the URL for broader matching)
- Logging is enabled with a relative path that resolves from the Spaceport root


## MadAve-Collab: Production Application

MadAve-Collab is a production collaborative application that shows how a deployed Spaceport app is configured.

**File:** `config.spaceport`

```yaml
# See: https://www.spaceport.com.co/docs/configuration-manifest

spaceport name: madave-collab
spaceport root: /opt/spaceport/madavecollab

host:
    address: 127.0.0.1
    port: 10000

debug: true

logging:
    enabled: true
    path: logs/

memory cores:
    main:
        address: 127.0.0.1:5984
        username: admin
        password: password

source modules:
    paths:
      - modules/*

static assets:
    paths:
        /assets/ : assets/*

## APP-specific Settings

main routes:
    authenticated: /home/dashboard
    unauthenticated: /login

## You can change this if you are running locally and want the generated
## links to point to a DEV server instead of production.

base url: 'https://staging.collab.madavegroup.com/'
```

**Key patterns in this example:**

- `spaceport root` is set to an absolute path (`/opt/spaceport/madavecollab`), typical for server deployments
- CouchDB credentials are stored directly in the file (suitable for development; use environment variables in production)
- Custom keys `main routes` and `base url` are application-specific configuration
- `main routes.authenticated` and `main routes.unauthenticated` define redirect targets based on login state, accessed in code as `Spaceport.config.'main routes'.authenticated`
- `base url` allows the application to generate correct URLs for different environments


## Minimal Configuration

The smallest useful manifest -- everything else inherits from defaults:

```yaml
host:
  port: 3000
```

This starts Spaceport on port 3000 with all other defaults: localhost binding, CouchDB at `127.0.0.1:5984`, source modules from `modules/*`, debug mode enabled.


## Production Configuration with Environment Variables

A typical production manifest that keeps all secrets in environment variables:

```yaml
spaceport name: my-app
spaceport root: /opt/spaceport/my-app

host:
  address: 0.0.0.0
  port: 80

debug: false

logging:
  enabled: true
  path: /var/log/my-app/

memory cores:
  main:
    type: couchdb
    address: ${ COUCHDB_URL }
    username: ${ COUCHDB_USER }
    password: ${ COUCHDB_PASSWORD }

http:
  cache control: "public, max-age=3600"
  spaceport cookie expiration: 2592000

source modules:
  paths:
    - modules/*

static assets:
  paths:
    /assets/ : assets/*
  buffer: true

stowaways:
  enabled: true
  paths:
    - stowaways/*
```

**Key patterns:**

- `debug: false` disables hot-reloading and verbose errors
- `host.address: 0.0.0.0` accepts connections from any network interface
- Credentials use `${ VAR }` syntax for environment variable substitution
- `static assets.buffer: true` explicitly enables memory-mapped file buffers for performance
- HTTP cache control is set to allow browser caching for 1 hour
- Cookie expiration is reduced to 30 days (from the default 60)

Start with:

```bash
export COUCHDB_URL=http://db.internal:5984
export COUCHDB_USER=app_user
export COUCHDB_PASSWORD=secure-password-here
java -jar spaceport.jar --start config.production.spaceport
```


## Custom Application Configuration

Using the manifest as a central configuration store for application-specific settings:

```yaml
spaceport name: storefront

host:
  port: 8080

debug: false

# Application settings
app name: My Online Store
version: 3.2.1

email:
  smtp host: smtp.example.com
  smtp port: 587
  from address: noreply@mystore.com
  api key: ${ SENDGRID_API_KEY }

storage:
  upload directory: uploads/
  max file size: 52428800
  allowed extensions:
    - jpg
    - png
    - pdf
    - docx

rate limiting:
  requests per minute: 60
  burst allowance: 10

feature flags:
  enable beta features: false
  maintenance mode: false
  new checkout flow: true
```

Access these in source modules:

```groovy
// Simple values
def appName = Spaceport.config.'app name'
def maxSize = Spaceport.config.storage.'max file size'

// Lists
def allowedExts = Spaceport.config.storage.'allowed extensions'
if (fileExtension in allowedExts) {
    // Process upload
}

// Feature flags
if (Spaceport.config.'feature flags'.'maintenance mode') {
    r.writeToClient('<h1>Under Maintenance</h1>')
    return
}

// Nested values
def smtpHost = Spaceport.config.email.'smtp host'
def rateLimit = Spaceport.config.'rate limiting'.'requests per minute'
```


## Multiple Static Asset Directories

Configuring several static asset paths with different URL mappings:

```yaml
static assets:
  paths:
    /assets/ : assets/*
    /public/ : public/*
    /downloads/ : files/downloads/
    /vendor/ : node_modules/
```

Each entry maps a URL path (left) to a filesystem directory (right). The wildcard `*` on the filesystem path enables serving from subdirectories.


## HTTP Security Configuration

Tightening HTTP settings for a production API:

```yaml
http:
  methods:
    allow:
      - GET
      - POST
      - DELETE
      - OPTIONS

  auto process options: true
  cache control: "no-store"
  pragma: "no-cache"
  expires: 0
  allow credentials: true
  allow headers: "Content-Type, Authorization, X-Requested-With"
  spaceport cookie expiration: 86400  # 1 day
```


## Environment-Specific File Organization

A common pattern for managing multiple environments:

```
config.spaceport                  # Development defaults
config.staging.spaceport          # Staging overrides
config.production.spaceport       # Production settings
```

```bash
# Development
java -jar spaceport.jar --start config.spaceport

# Staging
java -jar spaceport.jar --start config.staging.spaceport

# Production
java -jar spaceport.jar --start config.production.spaceport
```

Each file is a complete, standalone configuration. There is no inheritance between manifest files -- each is independently merged with the built-in defaults.


## See Also

- [Manifest Overview](manifest-overview.md) -- How configuration loading works
- [Manifest API Reference](manifest-api.md) -- Complete reference of all configuration keys
- [CLI Overview](cli-overview.md) -- Starting Spaceport with different manifests
