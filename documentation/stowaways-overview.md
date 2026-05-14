# Stowaway JARs Overview

## What Are Stowaways?

Stowaways are external JAR files that Spaceport loads into the application classpath at startup. They provide a straightforward way to include third-party Java or Groovy libraries in your Spaceport application without bundling them inside the Spaceport JAR itself.

Common use cases for stowaways include:

- **Third-party libraries** such as database drivers, HTTP clients, or utility libraries (e.g., Apache Commons, Google Guava)
- **Shared code** packaged as JARs that multiple Spaceport applications use
- **Custom compiled libraries** that you maintain separately from your source modules

Stowaways are loaded once at startup and remain available for the entire application lifecycle, including across source module hot-reloads. This is an important distinction: while source modules can be reloaded on the fly, stowaway JARs persist in a parent classloader that survives those reloads.


## When to Use Stowaways

Use stowaways when you need to bring in external dependencies that are distributed as JAR files. If you have Groovy or Java source code that you want Spaceport to compile and manage for you, use [source modules](source-modules-overview.md) instead.

Stowaways are the right choice when:

- The dependency is a pre-compiled JAR (not source code you want hot-reloaded)
- The library needs to be available to all of your source modules
- You want the library to persist across source module reloads


## How to Set Up Stowaways

### 1. Create a Directory for Your JAR Files

By default, Spaceport looks for a `stowaways/` directory at the root of your project. Place your JAR files there:

```
my-app/
  config.spaceport
  stowaways/
    gson-2.10.jar
    commons-lang3-3.14.jar
  source/
    MyModule.groovy
```

### 2. Configuration

Stowaways are enabled by default in Spaceport's configuration. The default configuration is:

```yaml
stowaways:
  enabled: true
  paths:
    - "stowaways/*"
```

The `*` at the end of the path tells Spaceport to scan recursively, including subdirectories. If the default `stowaways/` directory does not exist, Spaceport silently continues without error -- you only need to create it when you have JARs to load.

### 3. Using Custom Paths

You can configure multiple directories and mix recursive and non-recursive scanning:

```yaml
stowaways:
  enabled: true
  paths:
    - "stowaways/*"
    - "lib/external/"
    - "/opt/shared-libs/*"
```

- Paths ending with `*` scan recursively through all subdirectories
- Paths without `*` scan only the immediate directory
- Relative paths are resolved from the Spaceport project root
- Absolute paths are used as-is

### 4. Disabling Stowaways

If you do not need external JARs, you can disable the feature entirely:

```yaml
stowaways:
  enabled: false
```


## Startup Order

Stowaways are loaded early in the Spaceport startup sequence, specifically **before source modules are compiled**. This means all classes and libraries from your stowaway JARs are available for `import` in your source module code.

The relevant portion of the startup sequence is:

1. Configuration loaded
2. Database storage initialized
3. **Stowaway JARs loaded**
4. Source modules compiled and loaded
5. Communications array (HTTP server) initialized


## Error Handling

Spaceport distinguishes between the default configuration and custom paths when handling errors:

- **Default configuration** (`stowaways/*`): If the `stowaways/` directory does not exist, Spaceport silently continues. This allows new projects to work without creating an empty directory.
- **Custom paths**: If a user-defined directory does not exist, Spaceport prints an error and halts startup. This strict behavior prevents silent failures when you have explicitly configured a JAR directory that should exist.
- **Non-directory paths**: If a configured path points to a file rather than a directory, Spaceport halts with an error regardless of configuration type.


## Next Steps

- See the [Stowaway JARs API Reference](stowaways-api.md) for the full configuration specification and classloader details.
- Learn about [Source Modules](source-modules-overview.md), which can import classes from your stowaway JARs.
- Review the [Manifest Configuration](manifest-overview.md) for other configuration options.
