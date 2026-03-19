# Migrations API Reference

This document provides a complete reference for Spaceport's migration system, including the CLI command, script structure, configuration options, available bindings, and all APIs accessible within migration scripts.


## CLI Command

### `--migrate`

Launches the Spaceport Migration Program, an interactive CLI that loads your configuration manifest, connects to the database, and presents a menu of available migration scripts.

**Syntax:**

```bash
java -jar spaceport.jar --migrate <manifest-path>
```

**Parameters:**

| Parameter | Description |
|:---|:---|
| `<manifest-path>` | Path to your `.spaceport` YAML configuration file. |
| `--no-manifest` | Use default configuration without reading a manifest file. |

**Examples:**

```bash
# Run with a specific manifest
java -jar spaceport.jar --migrate config.spaceport

# Run with default configuration (no manifest file)
java -jar spaceport.jar --migrate --no-manifest
```

**Behavior:**

1. Displays the Spaceport banner with the current framework version.
2. Reads and merges the configuration manifest with default values.
3. Replaces environment variable placeholders (`${VAR_NAME}`) in the configuration.
4. Applies metaclass enhancements (`MetaClassEnhancements.enhance()`).
5. Connects to the configured main memory core (CouchDB).
6. If the database connection fails, prompts the user to continue or exit.
7. Scans the migrations directory for `.groovy` files and presents them as a selection menu.
8. Executes the chosen migration script using a `GroovyScriptEngine`.
9. Prints a completion message and exits.

**Signal handling:**

On Unix systems, `CTRL+C` is intercepted to reset terminal settings before exiting. This prevents the terminal from being left in a broken state if the migration is interrupted.


## Configuration

### `migrations.path`

Configures the directory where Spaceport looks for migration scripts.

**In your `.spaceport` manifest:**

```yaml
migrations:
  path: migrations
```

| Option | Type | Default | Description |
|:---|:---|:---|:---|
| `path` | `String` | `migrations` | Directory containing `.groovy` migration scripts. Can be relative (resolved from `spaceport root`) or absolute. |

When omitted, Spaceport defaults to a `migrations/` directory at the root of your project.


## Migration Script Structure

A migration script is a Groovy file placed in the migrations directory. It is executed by a `GroovyScriptEngine` with a binding context that includes a `report` variable (a `Cargo` object).

### Minimal Structure

```groovy
import spaceport.bridge.Command

Command.with {
    printBox("""
    Description of what this migration does.
    """)

    // Perform operations here

    success('Migration complete.')
}
```

### Script Binding

When a migration script is executed, the following variables are available in the script binding:

| Variable | Type | Description |
|:---|:---|:---|
| `report` | `Cargo` | A Cargo object that can be used to collect and output migration results. |

### Import Requirements

Migration scripts must explicitly import any Spaceport classes they use. Common imports include:

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document
import spaceport.personnel.ClientDocument
```


## Command Helpers

The `Command` class provides CLI-friendly helper methods accessible inside `Command.with { }` blocks.

### `printBox(String text)`

Prints a formatted banner/box with the given text. Used to describe what a migration will do before it begins.

```groovy
Command.with {
    printBox("""
    This migration creates the products database
    and seeds it with initial catalog data.
    """)
}
```

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `text` | `String` | The text to display inside the formatted box. Supports multi-line strings. |

**Returns:** `void`

---

### `promptInput(String message)`

Displays a prompt and waits for user input. Returns the entered string, or `null`/empty if the user presses Enter without typing.

```groovy
def username = promptInput('Enter a username (default: admin)')
if (!username) username = 'admin'
```

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `message` | `String` | The prompt message displayed to the user. |

**Returns:** `String` -- The user's input, or `null`/empty string if no input was provided.

---

### `promptMultiInput(String message, List options)`

Displays a prompt with a list of selectable options. The user chooses from the available options.

```groovy
def choice = promptMultiInput('Select an action:', ['Create', 'Update', 'Delete'])
```

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `message` | `String` | The prompt message displayed to the user. |
| `options` | `List` | A list of string options to present. |

**Returns:** `String` -- The selected option.

---

### `success(String message)`

Prints a success message with green/emphasized formatting.

```groovy
success("Created 'users' database.")
```

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `message` | `String` | The success message to display. |

**Returns:** `void`

---

### `error(String message)`

Prints an error message with red/emphasized formatting.

```groovy
error("User already exists. Exiting.")
```

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `message` | `String` | The error message to display. |

**Returns:** `void`

---

### `startOperation(String name, String message, String color)`

Marks the beginning of an operation for tracking and display purposes.

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `name` | `String` | A unique identifier for the operation. |
| `message` | `String` | A description displayed to the user. |
| `color` | `String` | ANSI color name for the output (e.g., `'RED'`, `'GREEN'`). |

**Returns:** `void`

---

### `endOperation(String name)`

Marks the completion of an operation previously started with `startOperation`.

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `name` | `String` | The identifier matching the `startOperation` call. |

**Returns:** `void`

---

### `println(String text)`

Prints text to the console. Supports Spaceport ANSI control codes in square bracket notation (e.g., `[BOLD]`, `[RESET]`, `[RED]`).

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `text` | `String` | The text to print. |

**Returns:** `void`

---

### `debug(String message)`

Prints a debug-level message. Debug output is enabled by default in the migration runtime.

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `message` | `String` | The debug message to print. |

**Returns:** `void`

---

### `printHeader(String text)`

Prints a section header with formatting.

**Parameters:**

| Parameter | Type | Description |
|:---|:---|:---|
| `text` | `String` | The header text. |

**Returns:** `void`


## Database APIs

Migration scripts have full access to Spaceport's database layer through the same APIs used in application code.

### `Spaceport.main_memory_core`

The configured CouchDB connection handler (`CouchHandler`), initialized from the `memory cores.main` section of the manifest.

| Method | Returns | Description |
|:---|:---|:---|
| `containsDatabase(String name)` | `boolean` | Checks if a database exists. |
| `createDatabase(String name)` | -- | Creates a new database. |
| `cookie` | `String` | The authentication cookie. `null` if the connection failed. |

**Example:**

```groovy
if (!Spaceport.main_memory_core.containsDatabase('products')) {
    Spaceport.main_memory_core.createDatabase('products')
    success("Created 'products' database.")
}
```

### `Document`

The `spaceport.computer.memory.physical.Document` class provides CouchDB document operations.

| Method | Returns | Description |
|:---|:---|:---|
| `Document.exists(String id, String database)` | `boolean` | Checks if a document with the given ID exists in the specified database. |
| `Document.get(String id, String database)` | `Document` | Fetches an existing document or creates a new one with the given ID. |
| `Document.getNew(String database)` | `Document` | Creates a new document with a random ID. |

**Instance properties and methods:**

| Member | Type | Description |
|:---|:---|:---|
| `fields` | `Map` | The document's primary data map. |
| `cargo` | `Cargo` | Built-in Cargo object for counters, toggles, and sets. |
| `save()` | `void` | Persists the document to CouchDB. |

**Example:**

```groovy
if (!Document.exists('config', 'settings')) {
    def doc = Document.get('config', 'settings')
    doc.fields.initialized = true
    doc.fields.version = '1.0'
    doc.save()
    success("Created configuration document.")
}
```

### `ClientDocument`

The `spaceport.personnel.ClientDocument` class manages user accounts stored in the `users` database.

| Method | Returns | Description |
|:---|:---|:---|
| `ClientDocument.getClientDocument(String username)` | `ClientDocument` | Fetches an existing user document by username. |
| `ClientDocument.createNewClientDocument(String username, String password)` | `ClientDocument` | Creates a new user with a BCrypt-hashed password. |

**Instance methods:**

| Method | Returns | Description |
|:---|:---|:---|
| `hasPermission(String permission)` | `boolean` | Checks if the user has the specified permission. |
| `addPermission(String permission)` | `void` | Grants the specified permission to the user. |
| `changePassword(String newPassword)` | `void` | Updates the user's password (BCrypt-hashed). |

**Example:**

```groovy
def user = ClientDocument.createNewClientDocument('admin', 'secretpassword')
if (user) {
    user.addPermission('spaceport-administrator')
    success("Administrator account created.")
}
```


## Global State Available in Migrations

| Reference | Type | Description |
|:---|:---|:---|
| `Spaceport.config` | `Map` | The merged configuration manifest (defaults + your YAML). |
| `Spaceport.main_memory_core` | `CouchHandler` | The primary database connection. |
| `Spaceport.store` | `Map` | Global store; `_debug` is set to `true` during migrations. |
| `Spaceport.version` | `String` | The current Spaceport framework version. |


## Runtime Environment Details

- **Script engine:** `GroovyScriptEngine`, pointed at the migrations directory. Scripts are compiled and executed at runtime.
- **Metaclass enhancements:** `MetaClassEnhancements.enhance()` is called before any migration runs. All class enhancements (String, Number, List, Map, etc.) are available in migration scripts.
- **Debug mode:** Debug output is enabled by default (`Spaceport.store._debug = true`).
- **Default configuration:** If `--no-manifest` is used, a default configuration is applied with CouchDB at `http://127.0.0.1:5984`, port `10000`, and standard paths.
- **Environment variables:** Placeholders in the form `${VAR_NAME}` in the manifest are replaced with actual environment variable values before the migration runs.


## See Also

- [Migrations Overview](migrations-overview.md) -- High-level introduction to the migration system.
- [Migrations Examples](migrations-examples.md) -- Practical patterns and recipes.
- [Documents API](documents-api.md) -- Full Document class reference.
- [Cargo API](cargo-api.md) -- Cargo data container reference.
