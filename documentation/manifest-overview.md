# Manifest Configuration Overview

The manifest file is Spaceport's central configuration file. Named `config.spaceport` by convention, it defines how your application starts, where it finds source code and assets, and how it connects to services like CouchDB. The manifest is written in YAML (or JSON) and is passed to Spaceport at startup.

```bash
java -jar spaceport.jar --start config.spaceport
```


## How Configuration Loading Works

When Spaceport starts, the configuration goes through a three-stage process:

### 1. Parse the YAML File

Spaceport uses SnakeYAML to parse your manifest file into a Groovy `Map<String, Object>`. Both YAML and JSON formats are accepted, since JSON is valid YAML.

### 2. Deep Merge Over Defaults

Your configuration does not need to be complete. Spaceport maintains a set of built-in defaults, and your manifest is deep-merged on top of them. This means you only need to specify what you want to change.

For example, if your manifest contains only:

```yaml
host:
  port: 3000
```

The result is a complete configuration where `host.port` is `3000`, `host.address` remains `127.0.0.1`, and all other settings retain their defaults.

The deep merge is recursive. Nested maps are merged level by level, so specifying a single key inside a nested section does not erase sibling keys. The one exception is the `paths` key: when a nested map contains a `paths` key, the entire `paths` value is replaced rather than merged. This lets you completely override source module or static asset path lists.

### 3. Environment Variable Substitution

After merging, Spaceport scans every string value in the configuration for `${ VAR_NAME }` placeholders and replaces them with the corresponding system environment variable. This works recursively through all maps, lists, and string values.

```yaml
memory cores:
  main:
    address: ${ COUCHDB_URL }
    password: ${ COUCHDB_PASSWORD }
```

If an environment variable is not set, the placeholder is left as-is and a debug message is logged.


## The Default Configuration

These are the built-in defaults that your manifest merges into:

```yaml
spaceport name: <current directory name>
spaceport root: <current working directory>

host:
  port: 10000
  address: 127.0.0.1

memory cores:
  main:
    type: couchdb
    address: http://127.0.0.1:5984

logging:
  enabled: false
  path: log/

source modules:
  paths:
    - modules/*

static assets:
  paths:
    /assets/ : assets/*

stowaways:
  enabled: true
  paths:
    - stowaways/*

debug: true
```

Note that `spaceport name` defaults to the name of the current working directory (not the directory itself, just its name), and `spaceport root` defaults to the absolute path of the current working directory.


## YAML Format

YAML is the recommended format for manifest files. It eliminates brackets, braces, and most quotation marks, making the configuration easy to read and edit. YAML also supports comments (lines starting with `#`), which JSON does not.

```yaml
# This is a comment
host:
  address: 0.0.0.0
  port: 8080

source modules:
  paths:
    - modules/*
    - lib/*
```

The same configuration in JSON:

```json
{
  "host": {
    "address": "0.0.0.0",
    "port": 8080
  },
  "source modules": {
    "paths": ["modules/*", "lib/*"]
  }
}
```

Spaceport uses spaces in configuration keys (e.g., `spaceport name`, `memory cores`, `source modules`) to prioritize human readability. In Groovy code, these keys are accessed using quoted property syntax: `Spaceport.config.'spaceport name'`.


## Custom Configuration Keys

Any key you add to the manifest becomes accessible in your application code through `Spaceport.config`. This makes the manifest a single source of truth for all application settings -- not just framework configuration.

```yaml
app name: My Application
version: 2.1.0

api keys:
  stripe: ${ STRIPE_SECRET_KEY }

feature flags:
  enable beta features: true
  maintenance mode: false
```

Access in Groovy:

```groovy
def appName = Spaceport.config.'app name'
def stripeKey = Spaceport.config.'api keys'.stripe
def betaEnabled = Spaceport.config.'feature flags'.'enable beta features'
```


## Running Without a Manifest

For quick prototyping, you can skip the manifest entirely:

```bash
java -jar spaceport.jar --start --no-manifest
```

This uses all built-in defaults. Debug mode is enabled, source modules are loaded from `modules/*`, and CouchDB is expected at `http://127.0.0.1:5984`. Production applications should always use an explicit manifest with `debug: false`.


## Environment-Specific Manifests

A common pattern is maintaining separate manifests for different environments:

```
config.spaceport              # Development
config.staging.spaceport      # Staging
config.production.spaceport   # Production
```

```bash
# Development
java -jar spaceport.jar --start config.spaceport

# Production
java -jar spaceport.jar --start config.production.spaceport
```

Combined with environment variable substitution, this lets you keep secrets out of your configuration files and version control.


## See Also

- [Manifest API Reference](manifest-api.md) -- Complete reference of all configuration keys
- [Manifest Examples](manifest-examples.md) -- Real-world configuration files
- [CLI Overview](cli-overview.md) -- How to start Spaceport with a manifest
- [Scaffolds Overview](scaffolds-overview.md) -- How manifests relate to project structure
