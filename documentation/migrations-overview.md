# Migrations Overview

Migrations in Spaceport are interactive, CLI-driven Groovy scripts used to prepare, configure, or maintain a Spaceport instance without fully booting the application server. They provide a lightweight runtime with access to your configuration manifest and connected database, making them the standard tool for tasks like creating databases, seeding data, managing user accounts, and performing one-off maintenance operations.


## What Migrations Are

A Spaceport migration is a standalone Groovy script that lives in your project's `migrations/` directory. When you run the `--migrate` CLI command, Spaceport loads your configuration manifest, connects to the database, and presents an interactive menu listing all available migration scripts. You select one to execute, and it runs in a small command-line environment with full access to Spaceport's database APIs and an interactive prompt system for gathering user input.

Migrations are not automatically ordered, versioned, or tracked the way they are in many other frameworks. There is no migration history table and no concept of "up" and "down" operations. Each migration is an independent script that you choose to run when you need it. This design favors simplicity and flexibility -- you write a script for a specific task, run it when the time comes, and the script itself is responsible for checking preconditions and behaving safely if run more than once.


## How Migrations Differ from Other Frameworks

If you have experience with Rails migrations, Django migrations, or Flyway, Spaceport's approach will feel different in several important ways:

- **No automatic ordering.** Migrations are not numbered or timestamped. The CLI presents them as a flat list, and you pick the one you want to run.
- **No migration history.** Spaceport does not track which migrations have been run. Scripts should be written to be idempotent -- safe to execute repeatedly.
- **Interactive by default.** Migrations can prompt the operator for input (usernames, passwords, confirmation of destructive actions). This makes them well suited for setup tasks that require human decisions.
- **Full database access.** Migration scripts use the same core Spaceport APIs (`Document`, `ClientDocument`, `Spaceport.main_memory_core`) that your application code uses. There is no separate migration DSL.
- **Minimal runtime.** The migration environment only loads core Spaceport classes, metaclass enhancements, and a database connection. **Your project's source modules are not loaded** -- migrations cannot reference classes, services, or utilities defined in your `modules/` directory. All logic must be self-contained within the script itself.
- **CLI environment.** Migrations run inside `Command.with { }`, giving access to formatted output helpers (`printBox`, `success`, `error`) and input prompts (`promptInput`, `promptMultiInput`).


## When to Use Migrations

Migrations are the right tool when you need to:

- **Set up a new environment.** Create required databases, seed initial data, and configure administrator accounts when deploying to a new server.
- **Manage user accounts.** Create, promote, or modify user records and permissions outside the running application.
- **Perform database maintenance.** Register CouchDB views, update document structures, or clean up stale data.
- **Make repeatable, scriptable changes.** Any operation that should be tracked in source control and re-runnable across environments (local, staging, production) is a good candidate for a migration.

Migrations are typically run while the server is stopped, but they can also be executed while the server is running if your workflow requires it.


## A Simple Example

The most common migration included with Spaceport projects is `CreateSpaceportAdministrator.groovy`, which ensures the `users` database exists and creates an administrator account:

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document
import spaceport.personnel.ClientDocument

Command.with {

    printBox("""
    This migration will create a default Spaceport administrator
    and a 'users' database if they do not already exist.
    """)

    // Ensure the users database exists
    Spaceport.main_memory_core.createDatabaseIfNotExists('users')

    // Prompt for credentials
    def username = promptInput('Enter a new username (default: administrator)')
    if (!username) username = 'administrator'

    // Create the user
    def password = ''
    while (!password) {
        password = promptInput('Enter a new password')
        if (password != promptInput('Re-enter password to confirm')) {
            error('Passwords do not match. Please try again.')
            password = null
        }
    }

    def user = ClientDocument.createNewClientDocument(username, password)
    if (user) {
        user.addPermission('spaceport-administrator')
        success("Created new administrator: ${username}")
    } else {
        error("Failed to create new administrator.")
    }
}
```

This script demonstrates the core migration pattern: explain what will happen, check preconditions, prompt for input, perform database operations, and report the outcome.


## Running a Migration

From the command line, pass your configuration manifest path with the `--migrate` flag:

```bash
java -jar spaceport.jar --migrate config.spaceport
```

Spaceport will load the manifest, connect to the configured database, and display an interactive menu of `.groovy` files found in the migrations directory. Select a script to execute it.

If the database is not reachable, the CLI will prompt you to continue without a connection or exit.


## What's Available (and What's Not)

The migration runtime is deliberately minimal. Spaceport loads only what's needed for database operations and CLI interaction:

**Available:**
- Core Spaceport classes (`Document`, `ClientDocument`, `Cargo`, `CouchHandler`)
- Metaclass enhancements (`.clean()`, `.combine{}`, `.json()`, etc.)
- The `Command` CLI helpers (`printBox`, `promptInput`, `success`, `error`, etc.)
- The database connection (`Spaceport.main_memory_core`)
- Your merged configuration (`Spaceport.config`)
- Standard Groovy and Java libraries

**Not available:**
- Your project's source modules (`modules/` directory is not loaded)
- Stowaway JARs (third-party libraries from `stowaways/` are not loaded)
- The HTTP server, routing, alerts, or Launchpad
- Any classes or utilities defined in your application code

This means migration scripts must be **self-contained**. If you have a helper method in your project that formats user data, you cannot call it from a migration -- you need to inline that logic or write it as a closure within the script. This is by design: migrations should work independently of your application code so they can run even when the application itself is broken or mid-refactor.

If you need setup logic that depends on your project's classes -- registering CouchDB views, initializing app-specific state, or ensuring databases exist based on your data model -- use `@Alert('on initialize')` or `@Alert('on initialized')` in your source modules instead. These alerts fire every time the server starts (and on hot-reload), so they have full access to your project code. This is the established pattern: for example, a `Document` subclass can ensure its own database and views exist each time the application boots:

```groovy
@Alert('on initialized')
static _init(Result r) {
    Spaceport.main_memory_core.createDatabaseIfNotExists('products')

    ViewDocument.get('views', 'products').setViewIfNeeded('all', '''
        function(doc) { emit(doc._id, null); }
    ''')
}
```

Use migrations for tasks that happen **outside** the running application -- creating administrator accounts, resetting passwords, seeding data that requires operator input -- and `on initialize` for setup that should happen **every time the server starts**.


## Best Practices

- **Write idempotent scripts.** Check whether resources exist before creating them. A migration that can be safely re-run is far more useful than one that fails on second execution.
- **Validate preconditions early.** Check database connectivity and required collections at the top of your script, before performing any modifications.
- **Be explicit about what the script does.** Use `printBox()` at the start to explain the migration's purpose, and `success()` / `error()` to communicate outcomes clearly.
- **Keep migrations focused.** One concern per script. If you need to perform multiple unrelated tasks, write separate migration files.
- **Provide sensible defaults.** Handle empty input gracefully. For automated environments, consider reading values from environment variables with `System.getenv()`.


## See Also

- [Migrations API Reference](migrations-api.md) -- Complete reference for the `--migrate` command, script structure, and available APIs.
- [Migrations Examples](migrations-examples.md) -- Practical migration patterns drawn from real Spaceport projects.
