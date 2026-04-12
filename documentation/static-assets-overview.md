# Static Assets — Overview

Static assets are files that Spaceport serves directly to the browser without any server-side processing. CSS stylesheets, JavaScript files, images, fonts, and other resources that don't change per-request all fall into this category. Spaceport delivers them as-is through Jetty's built-in resource handling, bypassing the Alert pipeline entirely.

## How It Works

When Spaceport starts, it reads the `static assets` section of your `config.spaceport` manifest and registers a Jetty servlet handler for each configured path mapping. These handlers sit in front of the HTTP request handler in the server's handler chain, so static files are resolved before any `@Alert` route handler gets a chance to run.

Each mapping pairs a **URL path** (what the browser requests) with a **filesystem path** (where the files live on disk):

```yaml
static assets:
  paths:
    /assets/ : assets/*
```

This tells Spaceport: "Serve files from the `assets/` directory at the URL prefix `/assets/`, including subdirectories."

## Configuring Asset Paths

### The Manifest Entry

Static asset configuration lives in the `static assets` section of `config.spaceport`:

```yaml
static assets:
  paths:
    /assets/ : assets/*
```

- **Key** (`/assets/`) — The URL path prefix. Browsers request files under this path.
- **Value** (`assets/*`) — The filesystem directory to serve from. Relative paths are resolved from the Spaceport root.

You can define multiple mappings:

```yaml
static assets:
  paths:
    /css/    : styles/*
    /js/     : scripts/*
    /images/ : media/img/*
```

### Default Configuration

If you omit the `static assets` section entirely, Spaceport uses this default:

```yaml
static assets:
  paths:
    /assets/ : assets/*
```

This means a file at `assets/logo.svg` is accessible at `http://localhost:10000/assets/logo.svg` with no configuration needed -- just create an `assets/` directory in your project root.

## Flat vs. Recursive Serving

The trailing asterisk (`*`) on the filesystem path controls whether subdirectories are accessible:

**With the wildcard** (`assets/*`) — Recursive mode. Files in subdirectories are served. A request for `/assets/css/styles.css` resolves to `assets/css/styles.css` on disk.

**Without the wildcard** (`assets/`) — Flat mode. Only files directly inside the directory are served. Subdirectory access returns a 404. A request for `/assets/logo.svg` works, but `/assets/css/styles.css` does not.

```yaml
# Recursive: serves assets/css/styles.css, assets/img/logo.svg, etc.
static assets:
  paths:
    /assets/ : assets/*

# Flat: only serves files directly in assets/ (no subdirectories)
static assets:
  paths:
    /assets/ : assets/
```

In practice, most applications use the wildcard. Flat mode is useful when you want to expose a single directory of files without risking unintended access to nested content.

## URL Mapping

URL resolution follows a straightforward pattern. The URL path prefix is stripped, and the remainder maps to a file path within the configured directory:

| URL Requested | Manifest Entry | File Served |
|---|---|---|
| `/assets/logo.svg` | `/assets/ : assets/*` | `assets/logo.svg` |
| `/assets/css/main.css` | `/assets/ : assets/*` | `assets/css/main.css` |
| `/css/reset.css` | `/css/ : styles/*` | `styles/reset.css` |

### Relative vs. Absolute Filesystem Paths

- **Relative paths** (e.g., `assets/`) are resolved from the Spaceport root directory (set by `spaceport root` in the manifest, or the working directory if unset).
- **Absolute paths** (e.g., `/var/www/static/`) are used as-is. This is useful when assets are stored outside the project directory.

A filesystem path of `/` is a special case -- it resolves to the Spaceport root directory itself. Avoid this configuration unless you have a specific reason, as it exposes the entire project root.

## Static vs. Dynamic Route Precedence

Static asset handlers are registered before the HTTP request handler in Jetty's handler chain. This means if a request URL matches both a static file and a dynamic `@Alert` route, the static file wins.

For example, if you have a file at `assets/about.html` mapped to `/assets/`, and also an `@Alert('on /assets/about.html hit')` handler, the static file is served and the alert handler never fires.

In most applications this is not a concern because static assets use a distinct URL prefix (like `/assets/`) that doesn't overlap with your application routes.

## Real-World Patterns

### Guestbook (Simple Application)

The Guestbook project uses the default configuration -- no `static assets` section in its manifest. Its flat `assets/` directory contains CSS, JavaScript, and images served at `/assets/`:

```
assets/
  spacewalk.css
  print.css
  hud-core-1.0.67.min.js
  qrcode.min.js
  logo.svg
```

### MadAve-Collab (Production Application)

MadAve-Collab uses recursive serving to organize assets into subdirectories:

```yaml
static assets:
  paths:
    /assets/ : assets/*
```

```
assets/
  css/
    _root.css
    ui.css
    forms.css
    spacing.css
    secure.css
    insecure.css
  img/
    logo-full.svg
    favicon.svg
    agency-logos/
      MAC.svg
      MAG.svg
  icons/
    home.svg
    search.svg
    card-icons/
      jobs.svg
      users.svg
```

This is the most common pattern: a single `assets/` directory with `css/`, `img/`, and `icons/` subdirectories, served recursively via the `*` wildcard.

### Port Mercury (Starter Template)

Port Mercury keeps assets minimal with just an `img/` subdirectory:

```yaml
static assets:
  paths:
    /assets/ : assets/*
```

```
assets/
  img/
    spacegal.svg
    icon.svg
    error.png
```

## Performance: File Map Buffering

In production (when `debug` is `false`), Spaceport enables Jetty's memory-mapped file buffer. This maps static files directly into memory, allowing the operating system to serve them with minimal I/O overhead.

In debug mode, this feature is disabled so you can edit CSS, JavaScript, and other files and see changes immediately without restarting the server.

If you need to update assets on a running production server without a restart, you can explicitly disable buffering:

```yaml
static assets:
  buffer: false
  paths:
    /assets/ : assets/*
```

## What's Next

- **[Static Assets API Reference](static-assets-api.md)** — Configuration options, FlatResourceServlet details, and URL resolution rules.
- **[Routing Overview](routing-overview.md)** — How HTTP requests flow through Spaceport, including how static assets fit into the handler chain.
- **[Manifest Configuration](manifest-configuration.md)** — Full reference for `config.spaceport` settings.
