# Scaffolds Overview

Scaffolds in Spaceport define the organizational structure of your project -- the arrangement of source modules, templates, static assets, configuration files, and other directories that make up your application. Rather than imposing a single rigid layout, Spaceport supports multiple scaffolding approaches that scale from single-file prototypes to full-featured production applications.

The specific layout of your scaffold is primarily governed by your manifest file (`config.spaceport`), which tells Spaceport where to find each component. However, Spaceport also supports manifest-free operation for quick prototyping.


## Scaffold Types

The `--create-port` CLI command offers three built-in scaffold types, each designed for a different level of project complexity.

### Mercury

Mercury is the minimal scaffold. It creates a single source module (`App.groovy`) in a `modules/` directory. This is the right choice when you want to start from a clean slate and build up your project structure organically.

**What Mercury creates:**

```
[spaceport-root]/[spaceport-name]/
  modules/
    App.groovy
```

Mercury is ideal for APIs, simple services, microservices, and projects where you know your requirements and want to build structure incrementally.


### Pioneer

Pioneer adds Launchpad templating on top of the Mercury base. It creates multiple source modules (`App.groovy`, `Router.groovy`, `Monitor.groovy`) and a set of Launchpad template files. Use Pioneer when your project needs server-rendered HTML pages but does not require user accounts beyond the Spaceport administrator.

**What Pioneer creates:**

```
[spaceport-root]/[spaceport-name]/
  modules/
    App.groovy
    Router.groovy
    Monitor.groovy
  launchpad/
    parts/
      index.ghtml
      about.ghtml
      contact.ghtml
    elements/
```

Pioneer suits marketing sites, dashboards, documentation portals, and similar projects that serve pages without a user login system.


### Voyager

Voyager is the most comprehensive scaffold. It includes everything Pioneer offers plus a user account system with login, registration, and an administrator panel. It also organizes source modules into subdirectories and includes example document types.

**What Voyager creates:**

```
[spaceport-root]/[spaceport-name]/
  modules/
    App.groovy
    Router.groovy
    Monitor.groovy
    Login.groovy
    Account/
    Product/
    datatypes/
      Message.groovy
  launchpad/
    parts/
      home/
        index.ghtml
        about.ghtml
        contact.ghtml
      user/
        login.ghtml
        user.ghtml
        register.ghtml
      admin/
        admin.ghtml
    elements/
```

Voyager is the right choice for full-featured web applications that need user authentication, admin panels, and a structured codebase from day one.


## Zero-Config Prototyping with --no-manifest

For the fastest possible start, Spaceport supports running without any manifest file at all. Using `--start --no-manifest`, Spaceport boots with default settings and loads source modules from a `modules/` directory in the current working directory.

```bash
java -jar spaceport.jar --start --no-manifest
```

In this mode, all you need is:

```
modules/
  App.groovy
```

Spaceport will bind to `127.0.0.1:10000`, connect to CouchDB at `http://127.0.0.1:5984`, enable debug mode, and serve static assets from `assets/` at the `/assets/` URL path.

**Limitations of --no-manifest mode:**

- Source module paths use the default `modules/*` only
- No custom host or port configuration
- No custom database credentials (relies on CouchDB defaults)
- No environment variable substitution
- No custom configuration keys for application-specific settings

For anything beyond quick prototyping, create a manifest file to take full control of your application's configuration.


## Choosing the Right Approach

| Situation | Recommended Approach |
|---|---|
| Testing an idea or learning Spaceport | `--no-manifest` |
| API or service with no UI | Mercury scaffold |
| Site with pages but no user accounts | Pioneer scaffold |
| Full application with login and admin | Voyager scaffold |
| Existing project structure to replicate | Manual scaffold with custom manifest |
| Team project needing conventions | Port-Mercury starter kit |

The `--create-port` command walks you through an interactive setup that configures your manifest, creates directories, copies default assets, sets up your database connection, and creates an administrator account -- all before writing a single line of application code.


## Starter Kits vs. CLI Scaffolds

In addition to the CLI-generated scaffolds, Spaceport offers downloadable starter kits:

- **Port-Echo** is a minimal starter with just a manifest file and a single source module. It mirrors what the Mercury scaffold creates but comes as a ready-to-clone repository.
- **Port-Mercury** is a full-featured starter kit with routing, authentication, Launchpad templates, migrations, static assets, and extensive inline documentation. It demonstrates real-world Spaceport patterns.

Starter kits are pre-built and ready to run. CLI scaffolds are generated interactively and tailored to your specific choices during the `--create-port` process. Both produce valid Spaceport project structures.


## See Also

- [Scaffolds API Reference](scaffolds-api.md) -- Detailed walkthrough of the `--create-port` interactive process
- [Scaffolds Internals](scaffolds-internals.md) -- How the Onboarding system works under the hood
- [CLI Overview](cli-overview.md) -- All Spaceport CLI commands
- [Manifest Overview](manifest-overview.md) -- How the configuration file works
