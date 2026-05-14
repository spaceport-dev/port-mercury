# CLI API Reference

This document provides a complete reference for Spaceport's CLI commands and the `Command` class, which powers all terminal output, input, and formatting throughout the framework.


## CLI Command Reference

### --start

```
java -jar spaceport.jar --start <manifest-path>
java -jar spaceport.jar --start --no-manifest
```

| Argument | Required | Description |
|---|---|---|
| `<manifest-path>` | Yes (unless `--no-manifest`) | Path to a `.spaceport` YAML configuration file |
| `--no-manifest` | No | Use built-in default configuration instead of a file |

**Behavior:** Starts the Spaceport application server. Loads configuration, connects to CouchDB, compiles source modules, and starts the Jetty HTTP/WebSocket server.

**Returns:** Does not return; runs until process termination.


### --create-port

```
java -jar spaceport.jar --create-port
```

No additional arguments. Launches the interactive scaffolding wizard.

**Behavior:** Runs the 8-step `Onboarding.createPort()` process. Creates directories, copies template files, generates a manifest, and configures the database.

**Returns:** Exits with `System.exit(0)` on completion or CTRL+C.


### --migrate

```
java -jar spaceport.jar --migrate <manifest-path>
java -jar spaceport.jar --migrate --no-manifest
```

| Argument | Required | Description |
|---|---|---|
| `<manifest-path>` | Yes (unless `--no-manifest`) | Path to a `.spaceport` YAML configuration file |
| `--no-manifest` | No | Use built-in default configuration |

**Behavior:** Loads configuration, connects to CouchDB, scans the migrations directory for `.groovy` files, and presents an interactive selection menu. The chosen migration is executed via a `GroovyScriptEngine` with a `Cargo` instance available as `report` in the binding.

**Returns:** Exits with `System.exit(0)` on completion.


### --db

```
java -jar spaceport.jar --db <manifest-path>
```

**Status:** Not yet implemented. Displays "Not yet implemented. Exiting."


### --help

```
java -jar spaceport.jar --help
```

**Behavior:** Prints the Spaceport ASCII logo, version string, and usage summary listing all commands.


### --color

```
java -jar spaceport.jar --color
```

**Behavior:** Calls `Command.showColorCombinations()` to render all available colors and their pairwise combinations in the terminal.


---


## The Command Class

**Package:** `spaceport.bridge`
**File:** `src/main/groovy/spaceport/bridge/Command.groovy`

The `Command` class provides all terminal I/O for the Spaceport CLI. All methods are static. It handles colored output, styled prompts, structured debugging, and terminal control.


### Output Methods

#### `Command.print(String text)`

Writes text to the console without a trailing newline. Color codes in bracket notation (e.g., `[BOLD]`, `[RED]`) are replaced with ANSI escape sequences before printing.

```groovy
Command.print('[GREEN]Loading...[RESET]')
```

#### `Command.println()`

Prints an empty line.

#### `Command.println(String text, String operation = null)`

Prints text with a trailing newline. If called within an active operation (see `startOperation`), the output is indented and colored according to the operation's settings.

```groovy
Command.println('Hello, Spaceport!')
Command.println('[BOLD]Important message[RESET]')
```

If `text` starts with `\n`, leading indentation is stripped via `.stripIndent()`.

#### `Command.printHeader(String text)`

Prints a styled section header with a star icon and magenta/black background colors.

```groovy
Command.printHeader('Initializing Storage')
// Output: ★ ░ Initializing Storage
```

#### `Command.printParagraph(String text)`

Prints multi-line text with consistent indentation. Leading/trailing whitespace and newlines are cleaned. Each line is indented by 2 spaces.

```groovy
Command.printParagraph("""
    This is a paragraph that will be
    cleaned up and indented properly.
""")
```

#### `Command.printBox(String text)`

Prints text inside a Unicode box border. Lines are padded or truncated to fit within the configured terminal width.

```groovy
Command.printBox("""
    Debug mode is enabled. This may expose
    sensitive information to the console.
""")
```

The box width is determined by the `Command.width` static variable (default: 50, clamped between 20 and 50).

#### `Command.printAtPosition(String text, def x, def y)`

Prints text at a specific terminal position (1-based row and column), then restores the cursor to its original position. Uses ANSI cursor save/restore sequences.

```groovy
Command.printAtPosition('Status: OK', 1, 1)
```


### Input Methods

#### `Command.promptInput(String prompt)`

Displays a bordered input box with the given prompt and reads a single line of text from the console. The prompt is padded to a minimum of 25 characters.

```groovy
String name = Command.promptInput('Enter a name:')
```

**Returns:** The user's input as a `String`.

#### `Command.promptPasswordInput(String prompt)`

Identical to `promptInput` but uses `System.console().readPassword()` to mask the input. The input field displays asterisks (`*************`).

```groovy
String password = Command.promptPasswordInput('CouchDB Password:')
```

**Returns:** The password as a `String`.

#### `Command.promptMultiInput(String prompt, List<String> options)`

Displays a bordered selection box with arrow-key navigation (macOS/Linux) or numbered text input (Windows). The prompt is padded to a minimum of 25 characters.

On macOS/Linux:
- Up/Down arrow keys move the selection
- Enter confirms the selection
- Terminal is put into raw input mode (`stty -icanon -echo`) during selection
- Terminal settings are restored afterward (`stty sane`)

On Windows (or non-Unix systems), falls back to `promptMultiInputSimple`, which accepts typed option names or numbers.

```groovy
String choice = Command.promptMultiInput('Choose a database:', ['CouchDB', 'Skip'])
```

**Returns:** The selected option as a `String`.


### Debugging Methods

#### `Command.debug(String text)`

Prints a debug message prefixed with source file name, line number, and method name. Only produces output when `Spaceport.inDebugMode` is `true`.

```groovy
Command.debug('Connection pool initialized')
// Output: ✚ ░ (MyModule.groovy:42) init Connection pool initialized
```

The caller information is extracted from the current thread's stack trace, finding the first `.groovy` frame that is not `Command.groovy`.

#### `Command.debug(def ...objects)`

Varargs overload that calls `.toString()` on each argument and delegates to `debug(String)`.

#### `Command.success(String text)`

Prints a success message prefixed with a green checkmark icon and caller information.

```groovy
Command.success('Migration completed successfully')
// Output: ✔ ░ (Migration.groovy:85) runMigration Migration completed successfully
```

#### `Command.error(String text)`

Prints an error message prefixed with a red blinking X icon, a terminal bell character (`\u0007`), and caller information.

```groovy
Command.error('Failed to connect to database')
// Output: ✕ ░ (Database.groovy:23) connect Failed to connect to database
```


### Operation Methods (Nested Debug Output)

Operations create nested, indented, colored output blocks for tracking multi-step processes.

#### `Command.startOperation(String message, String color)`

Starts a new operation with an auto-generated numeric ID.

```groovy
def opId = Command.startOperation('Processing request', 'BLUE')
```

**Returns:** The operation ID (auto-incrementing `String`).

#### `Command.startOperation(def operation, String message, String color)`

Starts a new operation with a specified ID.

```groovy
Command.startOperation('my-op', 'Connecting to DB', 'CYAN')
```

**Behavior:**
- Records the current indent level and thread ID
- Increments the indent level for subsequent `println()` calls
- Prints a start marker: `┌────── my-op : Connecting to DB`
- All subsequent `println()` calls are indented with `│ ` characters
- Safety check: if indent exceeds 25, it resets to 0

#### `Command.endOperation(def operation)`

Ends a named operation and restores the indent level.

```groovy
Command.endOperation('my-op')
```

**Behavior:**
- Prints an end marker: `└────── End of my-op`
- Restores indent to the level saved when the operation started (self-correcting if inner operations leaked)
- Removes the operation from the tracking map

**Thread safety:** The `operations` map is a `ConcurrentHashMap`, and `startOperation`/`endOperation` are `synchronized`.


### Terminal Width

#### `Command.readWidth()`

Detects the terminal width by sending ANSI cursor position sequences. Only works on macOS and Unix-like systems. Sets `Command.width` to the detected value, clamped between 20 and 50.

#### `Command.width`

Static variable controlling the dialog/box width. Default: `50`.


---


## Color System

The `Command` class uses a bracket-notation color system. Any string passed to `Command.print()` or `Command.println()` can contain color codes in square brackets, which are replaced with ANSI escape sequences before output.

```groovy
Command.println('[BOLD][RED]Error:[RESET] Something went wrong')
```

### Text Styles

| Code | Effect | ANSI |
|---|---|---|
| `[RESET]` | Reset all formatting | `\u001B[0m` |
| `[BOLD]` | Bold text | `\u001B[1m` |
| `[BOLD_OFF]` | Disable bold | `\u001B[22m` |
| `[DIM]` | Dimmed text | `\u001B[2m` |
| `[DIM_OFF]` | Disable dim | `\u001B[22m` |
| `[ITALIC]` | Italic text | `\u001B[3m` |
| `[ITALIC_OFF]` | Disable italic | `\u001B[23m` |
| `[UNDERLINE]` | Underlined text | `\u001B[4m` |
| `[UNDERLINE_OFF]` | Disable underline | `\u001B[24m` |
| `[BLINK]` | Blinking text | `\u001B[5m` |
| `[FAST_BLINK]` | Fast blinking text | `\u001B[6m` |
| `[BLINK_OFF]` | Disable blink | `\u001B[25m` |
| `[REVERSE]` | Swap foreground/background | `\u001B[7m` |
| `[REVERSE_OFF]` | Disable reverse | `\u001B[27m` |
| `[INVISIBLE]` | Hidden text | `\u001B[8m` |
| `[INVISIBLE_OFF]` | Disable invisible | `\u001B[28m` |
| `[STRIKETHROUGH]` | Strikethrough text | `\u001B[9m` |
| `[STRIKETHROUGH_OFF]` | Disable strikethrough | `\u001B[29m` |

### Foreground Colors (Bright)

| Code | Color | ANSI |
|---|---|---|
| `[WHITE]` | White | `\u001B[37m` |
| `[BLACK]` | Bright Black (dark gray) | `\u001B[90m` |
| `[RED]` | Bright Red | `\u001B[91m` |
| `[GREEN]` | Bright Green | `\u001B[92m` |
| `[YELLOW]` | Bright Yellow | `\u001B[93m` |
| `[BLUE]` | Bright Blue | `\u001B[94m` |
| `[MAGENTA]` | Bright Magenta | `\u001B[95m` |
| `[CYAN]` | Bright Cyan | `\u001B[96m` |
| `[GRAY]` / `[GREY]` | Bright White | `\u001B[97m` |

### Foreground Colors (Dark)

| Code | Color | ANSI |
|---|---|---|
| `[DRED]` | Dark Red | `\u001B[31m` |
| `[DGREEN]` | Dark Green | `\u001B[32m` |
| `[DYELLOW]` | Dark Yellow | `\u001B[33m` |
| `[DBLUE]` | Dark Blue | `\u001B[34m` |
| `[DMAGENTA]` | Dark Magenta | `\u001B[35m` |
| `[DCYAN]` | Dark Cyan | `\u001B[36m` |

### Background Colors (Bright)

| Code | Color | ANSI |
|---|---|---|
| `[BBLACK]` | Bright Black background | `\u001B[100m` |
| `[BRED]` | Bright Red background | `\u001B[101m` |
| `[BGREEN]` | Bright Green background | `\u001B[102m` |
| `[BYELLOW]` | Bright Yellow background | `\u001B[103m` |
| `[BBLUE]` | Bright Blue background | `\u001B[104m` |
| `[BMAGENTA]` | Bright Magenta background | `\u001B[105m` |
| `[BCYAN]` | Bright Cyan background | `\u001B[106m` |
| `[BWHITE]` | Bright White background | `\u001B[107m` |

### Background Colors (Dark)

| Code | Color | ANSI |
|---|---|---|
| `[BDBLACK]` | Dark Black background | `\u001B[40m` |
| `[BDRED]` | Dark Red background | `\u001B[41m` |
| `[BDGREEN]` | Dark Green background | `\u001B[42m` |
| `[BDYELLOW]` | Dark Yellow background | `\u001B[43m` |
| `[BDBLUE]` | Dark Blue background | `\u001B[44m` |
| `[BDMAGENTA]` | Dark Magenta background | `\u001B[45m` |
| `[BDCYAN]` | Dark Cyan background | `\u001B[46m` |
| `[BDWHITE]` | Dark White background | `\u001B[47m` |


### ANSI Control Codes

The `Command.ansiControlCodes` map provides terminal control sequences used internally but also available for direct use:

| Code | Purpose |
|---|---|
| `CLEAR_SCREEN` | Clear entire screen |
| `CLEAR_LINE` | Clear current line |
| `CLEAR_LINE_ABOVE` | Clear line above cursor |
| `CLEAR_LINE_BELOW` | Clear line below cursor |
| `CURSOR_UP` | Move cursor up one line |
| `CURSOR_DOWN` | Move cursor down one line |
| `CURSOR_RIGHT` | Move cursor right one column |
| `CURSOR_LEFT` | Move cursor left one column |
| `CURSOR_SAVE` | Save cursor position |
| `CURSOR_RESTORE` | Restore saved cursor position |
| `CURSOR_POSITION` | Request cursor position report |
| `CURSOR_TO_BEGINNING` | Move cursor to column 1 |
| `CURSOR_TO_END` | Move cursor to rightmost column |
| `CURSOR_HOME` | Move cursor to row 1, column 1 |
| `SCROLL_UP` | Scroll content up |
| `SCROLL_DOWN` | Scroll content down |
| `HIDE_CURSOR` | Make cursor invisible |
| `SHOW_CURSOR` | Make cursor visible |
| `ERASE_SCREEN` | Erase screen and scrollback |
| `ERASE_TO_END_OF_SCREEN` | Erase from cursor to end |
| `ERASE_FROM_START_OF_SCREEN` | Erase from start to cursor |
| `DISABLE_LINE_WRAP` | Disable automatic line wrapping |
| `ENABLE_LINE_WRAP` | Enable automatic line wrapping |
| `INSERT_LINE` | Insert a blank line |
| `DELETE_LINE` | Delete current line |
| `BELL` | Terminal bell (audible alert) |


### Color Processing Methods

#### `Command.applyColors(String text)` (private)

Replaces all bracket-notation color codes in `text` with their ANSI escape sequence equivalents from the `colorMap`. Uses a regex that matches `[KEY]` for all keys in the map.

#### `Command.applyControlCodes(String text)`

Replaces bracket-notation control codes in `text` with their ANSI equivalents from the `ansiControlCodes` map.

#### `Command.showColorCombinations()`

Renders every individual color and every pairwise combination of two colors to the terminal. Used by the `--color` CLI command.


## See Also

- [CLI Overview](cli-overview.md) -- Summary of all CLI commands
- [Scaffolds API Reference](scaffolds-api.md) -- The --create-port interactive process
- [Manifest API Reference](manifest-api.md) -- Configuration file reference
