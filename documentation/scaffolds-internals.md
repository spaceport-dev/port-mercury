# Scaffolds Internals

This document explains how the `--create-port` scaffolding system works internally, covering the `Onboarding` class, template resource loading, signal handling, and manifest file generation.


## Entry Point

When `--create-port` is passed as the first argument to Spaceport's `main()` method in `Spaceport.groovy`, the static method `Onboarding.createPort()` is called:

```groovy
// In Spaceport.main()
else if (args[0] == '--create-port') {
    Onboarding.createPort()
    return
}
```

This is invoked before any configuration loading, database connection, or server startup occurs. The onboarding process is entirely self-contained.


## The Onboarding Class

**Package:** `spaceport.bridge`
**File:** `src/main/groovy/spaceport/bridge/Onboarding.groovy`

The `Onboarding` class has two modes of operation: a static entry point (`createPort()`) and instance-level procedures that perform each step.

### Class Structure

```groovy
class Onboarding {
    static createPort()          // Main entry point (static)

    def isWindows                // Platform detection flag
    def procedures               // Ordered map of step name -> closure
    def manifest                 // Configuration map built during the process

    // Procedure closures (instance methods):
    def root                     // Step 1: Spaceport Root
    def identify                 // Step 2: Naming
    def host                     // Step 3: Host/Port
    def database                 // Step 4: CouchDB connection
    def administrator            // Step 5: Admin account
    def defineAssets             // Step 6: Static assets
    def debugMode                // Step 7: Debug toggle
    def moduleCreation           // Step 8: Scaffold selection
}
```

### Constructor

The constructor performs a single check -- detecting whether the operating system is Windows:

```groovy
Onboarding() {
    isWindows = System.getProperty('os.name').toLowerCase().contains('windows')
}
```

This flag is used throughout the class to conditionally handle terminal features that differ between Windows and Unix-like systems (macOS/Linux).

### Procedure Execution

The procedures are defined as a `LinkedHashMap` (preserving insertion order) mapping display names to closures:

```groovy
def procedures = [
    'Spaceport Root'             : { root() },
    'Spaceport Identification'   : { identify() },
    'Spaceport Access'           : { host() },
    'Database Connection'        : { database() },
    'Spaceport Administrator'    : { administrator() },
    'Static Assets'              : { defineAssets() },
    'Debug Mode'                 : { debugMode() },
    'Module Creation'            : { moduleCreation() }
]
```

The `createPort()` method iterates through these entries sequentially:

```groovy
for (procedure in onboarding.procedures.entrySet()) {
    Command.printHeader(procedure.key)
    procedure.value.call()
    Command.println()
}
```

Each procedure reads input from the user and populates the `manifest` map, which starts as:

```groovy
def manifest = [
    'spaceport name'  : null,
    'spaceport root'  : null,
    'host'            : [ 'port': null, 'address': null ],
    'memory cores'    : [ 'main': [ 'address': null, 'username': null, 'password': null ] ],
    'source modules'  : [:],
    'static assets'   : [:],
    'debug'           : null
]
```


## Signal Handling

On Unix-like systems, `Onboarding.createPort()` registers a `SIGINT` (CTRL+C) handler using `sun.misc.Signal`:

```groovy
if (!onboarding.isWindows) {
    Signal.handle(new Signal("INT"), { signal ->
        Runtime.getRuntime().exec(
            ["/bin/sh", "-c", "stty sane < /dev/tty"] as String[]
        ).waitFor()
        Command.println()
        Command.println(Command.ansiControlCodes['CLEAR_LINE'] +
            'Exiting CLI. [REVERSE] Mission aborted [RESET]')
        System.exit(0)
    })
}
```

This is necessary because the `Command.promptMultiInput()` and `Command.promptInput()` methods modify terminal settings (disabling line buffering and echo via `stty -icanon` and `stty -echo`). Without the signal handler, pressing CTRL+C during input would leave the terminal in a broken state. The handler runs `stty sane` to restore normal terminal behavior before exiting.


## Template Files from JAR Resources

Scaffold template files are bundled inside the Spaceport JAR under the `/scaffolds/` resource directory, organized by scaffold type:

```
/scaffolds/
  Mercury/
    modules/
      App.groovy
  Pioneer/
    modules/
      App.groovy
      Router.groovy
      Monitor.groovy
    launchpad/
      parts/
        index.ghtml
        about.ghtml
        contact.ghtml
  Voyager/
    modules/
      App.groovy
      Router.groovy
      Monitor.groovy
      Login.groovy
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
```

Template files are loaded using `Spaceport.class.getResourceAsStream(resource)` and copied to the destination using `java.nio.file.Files.copy()` with `StandardCopyOption.REPLACE_EXISTING`.

The resource path is converted to a destination path by stripping the `/scaffolds/<ScaffoldType>/` prefix:

```groovy
String fileName = Paths.get(resource).toString().replace('/scaffolds/' + module + '/', '')
String destinationPath = Paths.get(location, name, fileName).toString()
```

If a destination file already exists, the user is prompted to confirm replacement.

### Default Asset Resources

The static assets step copies default files from a separate `/default-assets/` resource directory:

```
/default-assets/
  img/
    40X.webp
    50X.webp
  js/
    hud-core.js
  css/
    spacewalk.css
```

These are copied to the `static/` directory under the Spaceport Root (not under the individual Spaceport's directory), making them available to all Spaceports sharing that root.


## Manifest File Generation

Rather than using a YAML serializer, the manifest is generated as a formatted string with embedded comments. This preserves human-readable formatting and provides inline documentation for new users:

```groovy
def payload = """
    ###
    # Spaceport Manifest for ${ onboarding.manifest.'spaceport name' }

    spaceport name: ${ onboarding.manifest.'spaceport name' }
    spaceport root: ${ onboarding.manifest.'spaceport root' }
    ...
"""
```

Static asset paths are appended dynamically:

```groovy
if (onboarding.manifest.'static assets'.'paths'?.size() > 0) {
    onboarding.manifest.'static assets'.'paths'?.each { key, value ->
        payload += "                    ${ key } : '${ value }'\n"
    }
}
```

Source modules are hardcoded to `modules/*` in the generated manifest.

The final payload is stripped of leading indentation with `.stripIndent()` and written to:

```
[spaceport-root]/[spaceport-name]/[spaceport-name].spaceport
```

### Existing Manifest Handling

If a manifest file already exists at the target path:

1. The user is prompted to overwrite ("Yes" or "No")
2. **Overwrite:** The existing file is renamed with a `.bak` extension, and the new manifest is written
3. **Keep existing:** The generated manifest is written to a `.draft` file instead, and the process exits with `System.exit(0)`


## Directory Creation

Each scaffold type creates its own set of directories before copying files:

**Mercury:**
```groovy
new File(Paths.get(location, name, 'modules').toString()).mkdirs()
```

**Pioneer:**
```groovy
new File(Paths.get(location, name, 'modules').toString()).mkdirs()
new File(Paths.get(location, name, 'launchpad', 'parts').toString()).mkdirs()
new File(Paths.get(location, name, 'launchpad', 'elements').toString()).mkdirs()
```

**Voyager:**
```groovy
new File(Paths.get(location, name, 'modules').toString()).mkdirs()
new File(Paths.get(location, name, 'modules', 'Account').toString()).mkdirs()
new File(Paths.get(location, name, 'modules', 'Product').toString()).mkdirs()
new File(Paths.get(location, name, 'launchpad', 'parts').toString()).mkdirs()
new File(Paths.get(location, name, 'launchpad', 'elements').toString()).mkdirs()
```

Note that `mkdirs()` creates parent directories as needed, so the Spaceport directory itself is created as a side effect.


## Database Interaction During Onboarding

The administrator step (Step 5) is the only procedure that directly interacts with CouchDB. It:

1. Initializes `Spaceport.main_memory_core` as a `CouchHandler` instance with the provided credentials
2. Checks for (and creates if needed) a `users` database
3. Registers a CouchDB view document (`user-views`) with an `admin-list` view that maps documents containing the `spaceport-administrator` permission
4. Queries this view to list existing administrators
5. Creates new `ClientDocument` records with the `spaceport-administrator` permission when needed

This means the onboarding process writes to CouchDB (creating the `users` database, view documents, and administrator documents) as a side effect of the setup process. The database must be accessible and running for this step to succeed.


## Relationship to Command Class

All terminal I/O during onboarding flows through the `Command` class (`spaceport.bridge.Command`). The key methods used are:

| Method | Purpose |
|---|---|
| `Command.printHeader(text)` | Section header with styled formatting |
| `Command.printParagraph(text)` | Multi-line explanatory text |
| `Command.printBox(text)` | Bordered text box |
| `Command.promptInput(prompt)` | Single-line text input with styled box |
| `Command.promptPasswordInput(prompt)` | Masked password input |
| `Command.promptMultiInput(prompt, options)` | Arrow-key selection menu (macOS/Linux) or numbered list (Windows) |
| `Command.debug(text)` | Debug-level output (always shown during onboarding since `_debug` is set to `true`) |
| `Command.success(text)` | Success confirmation message |
| `Command.error(text)` | Error message with bell character |

Color codes in strings (e.g., `[BOLD]`, `[REVERSE]`, `[RESET]`) are processed by `Command.applyColors()`, which replaces bracket-enclosed color names with their corresponding ANSI escape sequences.


## See Also

- [Scaffolds Overview](scaffolds-overview.md) -- What scaffolds are and when to use each type
- [Scaffolds API Reference](scaffolds-api.md) -- Step-by-step reference for the --create-port command
- [CLI API Reference](cli-api.md) -- Full Command class API documentation
