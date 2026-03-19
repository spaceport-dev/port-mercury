# Migrations Examples

This document provides practical migration patterns and recipes drawn from real Spaceport projects. Each example demonstrates a common task you may need to perform when setting up or maintaining a Spaceport instance.


## Create a Spaceport Administrator

This is the most common migration, included by default in both the Port-Mercury starter kit and the MadAve-Collab production application. It ensures the `users` database exists, then either creates a new administrator account or promotes an existing user.

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
    if (!Spaceport.main_memory_core.containsDatabase('users')) {
        Spaceport.main_memory_core.createDatabase('users')
        success("Created 'users' database.")
    }

    // Prompt for a username
    def username, password

    username = promptInput('Enter a new username (default: administrator)')
    if (!username) username = 'administrator'

    // Check if the user already exists
    def user

    if (Document.exists(username, 'users')) {

        user = ClientDocument.getClientDocument(username)

        if (user.hasPermission('spaceport-administrator')) {
            success("User ${username} already exists and is already an administrator.")
            return
        }

        error("User already exists. Promote instead?")
        def promote = promptInput('Promote user to administrator? (y/(n))')

        if (promote == 'y' || promote == 'Y') {
            user.addPermission('spaceport-administrator')
            success("User ${username} promoted to administrator.")
            return
        } else {
            error("User already exists. Exiting.")
            return
        }
    }

    // Prompt for a password with confirmation
    while (!password) {
        password = promptInput('Enter a new password')
        if (password != promptInput('Re-enter password to confirm')) {
            error('Passwords do not match. Please try again.')
            password = null
        }
    }

    // Create the administrator account
    user = ClientDocument.createNewClientDocument(username, password)

    if (user) {
        user.addPermission('spaceport-administrator')
        success("Created new administrator: ${username}")
    } else {
        error("Failed to create new administrator.")
    }
}
```

**Key patterns demonstrated:**
- Checking for database existence before creating it (idempotency).
- Checking for document existence before creating it.
- Handling the "already exists" case by offering promotion.
- Password confirmation loop.
- Using `success()` and `error()` to communicate outcomes.


## Change an Existing User's Password

A focused migration for resetting a user's password. This is useful when an administrator has lost access or a password rotation is required.

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document
import spaceport.personnel.ClientDocument

Command.with {

    printBox("""
    This migration will change the password for an existing user
    in the 'users' database.
    """)

    // Verify the users database exists
    if (!Spaceport.main_memory_core.containsDatabase('users')) {
        error("'users' database does not exist. Exiting.")
        return
    }

    // Prompt for the target username
    def username = promptInput('Enter the username whose password you want to change')
    if (!username) {
        error('No username entered. Exiting.')
        return
    }

    // Verify the user exists
    if (!Document.exists(username, 'users')) {
        error("User ${username} does not exist in 'users' database. Exiting.")
        return
    }

    // Prompt for new password with confirmation
    def password = ''
    while (!password) {
        password = promptInput('Enter a new password')
        if (password != promptInput('Re-enter password to confirm')) {
            error('Passwords do not match. Please try again.')
            password = ''
        }
    }

    // Update the password
    def user = ClientDocument.getClientDocument(username)
    if (user) {
        user.changePassword(password)
        success("Password for user ${username} has been changed.")
    } else {
        error("Failed to update password for user ${username}.")
    }
}
```

**Key patterns demonstrated:**
- Validating prerequisites early (database exists, user exists) before performing any changes.
- Exiting gracefully with `error()` when preconditions are not met.


## Create a Database

A minimal migration that creates one or more databases. Useful during initial environment setup.

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command

Command.with {

    printBox("""
    This migration creates the application databases.
    """)

    def databases = ['products', 'orders', 'inventory']

    databases.each { dbName ->
        if (!Spaceport.main_memory_core.containsDatabase(dbName)) {
            Spaceport.main_memory_core.createDatabase(dbName)
            success("Created '${dbName}' database.")
        } else {
            success("'${dbName}' database already exists. Skipping.")
        }
    }
}
```

**Key patterns demonstrated:**
- Iterating over a list of required databases.
- Idempotent creation with existence checks.


## Seed Data into a Database

A migration that populates a database with initial records. This pattern is common for lookup tables, default configuration, or demo data.

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document

Command.with {

    printBox("""
    This migration seeds the 'categories' database with
    default product categories.
    """)

    def dbName = 'categories'

    // Ensure database exists
    if (!Spaceport.main_memory_core.containsDatabase(dbName)) {
        Spaceport.main_memory_core.createDatabase(dbName)
        success("Created '${dbName}' database.")
    }

    // Define seed data
    def categories = [
        [id: 'electronics', name: 'Electronics', sortOrder: 1],
        [id: 'clothing',    name: 'Clothing',    sortOrder: 2],
        [id: 'books',       name: 'Books',       sortOrder: 3],
    ]

    // Insert each category if it does not already exist
    categories.each { cat ->
        if (!Document.exists(cat.id, dbName)) {
            def doc = Document.get(cat.id, dbName)
            doc.fields.name = cat.name
            doc.fields.sortOrder = cat.sortOrder
            doc.save()
            success("Created category: ${cat.name}")
        } else {
            success("Category '${cat.name}' already exists. Skipping.")
        }
    }
}
```

**Key patterns demonstrated:**
- Defining seed data as a list of maps.
- Using `Document.get()` with a specific ID to create documents with predictable identifiers.
- Setting fields and saving.


## Grant Permissions to an Existing User

A migration for adding specific permissions to a user account without creating a new one.

```groovy
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document
import spaceport.personnel.ClientDocument

Command.with {

    printBox("""
    This migration grants a permission to an existing user.
    """)

    def username = promptInput('Enter the username')
    if (!username) {
        error('No username provided. Exiting.')
        return
    }

    if (!Document.exists(username, 'users')) {
        error("User '${username}' does not exist. Exiting.")
        return
    }

    def permission = promptInput('Enter the permission to grant (e.g., spaceport-administrator)')
    if (!permission) {
        error('No permission provided. Exiting.')
        return
    }

    def user = ClientDocument.getClientDocument(username)

    if (user.hasPermission(permission)) {
        success("User '${username}' already has permission '${permission}'.")
        return
    }

    user.addPermission(permission)
    success("Granted '${permission}' to user '${username}'.")
}
```


## Environment-Aware Migration

A migration that reads configuration from environment variables for automated (non-interactive) deployment pipelines.

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document
import spaceport.personnel.ClientDocument

Command.with {

    printBox("""
    This migration creates an administrator from environment variables.
    Set SPACEPORT_ADMIN_USER and SPACEPORT_ADMIN_PASS before running.
    """)

    def username = System.getenv('SPACEPORT_ADMIN_USER') ?: 'administrator'
    def password = System.getenv('SPACEPORT_ADMIN_PASS')

    if (!password) {
        // Fall back to interactive prompt if env var not set
        password = promptInput('SPACEPORT_ADMIN_PASS not set. Enter a password:')
        if (!password) {
            error('No password provided. Exiting.')
            return
        }
    }

    // Ensure users database exists
    if (!Spaceport.main_memory_core.containsDatabase('users')) {
        Spaceport.main_memory_core.createDatabase('users')
        success("Created 'users' database.")
    }

    if (Document.exists(username, 'users')) {
        success("User '${username}' already exists. Skipping creation.")
        return
    }

    def user = ClientDocument.createNewClientDocument(username, password)
    if (user) {
        user.addPermission('spaceport-administrator')
        success("Created administrator: ${username}")
    } else {
        error("Failed to create administrator.")
    }
}
```

**Key patterns demonstrated:**
- Reading from `System.getenv()` for CI/CD automation.
- Falling back to interactive prompts when environment variables are not set.
- Providing sensible defaults for optional values.


## Data Migration Pattern

A migration that reads existing documents and transforms their data. This is useful when your document schema evolves and existing records need updating.

```groovy
import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document

Command.with {

    printBox("""
    This migration adds a 'status' field to all documents
    in the 'orders' database that do not already have one.
    """)

    def dbName = 'orders'

    if (!Spaceport.main_memory_core.containsDatabase(dbName)) {
        error("'${dbName}' database does not exist. Exiting.")
        return
    }

    // Retrieve all document IDs from the database
    // (Implementation depends on your CouchDB view setup)
    def confirm = promptInput("This will update all orders without a 'status' field. Continue? (y/n)")
    if (confirm != 'y' && confirm != 'Y') {
        error('Aborted.')
        return
    }

    success("Migration would proceed to update documents here.")
    // In practice, you would iterate over documents and update them:
    //
    // documents.each { docId ->
    //     def doc = Document.get(docId, dbName)
    //     if (!doc.fields.status) {
    //         doc.fields.status = 'pending'
    //         doc.save()
    //     }
    // }
}
```

**Key patterns demonstrated:**
- Confirming destructive or bulk operations before proceeding.
- Defensive field checks before modifying documents.


## Best Practices Summary

| Practice | Rationale |
|:---|:---|
| Check existence before creating | Makes scripts safe to re-run (idempotent). |
| Validate preconditions at the top | Fail fast before making any changes. |
| Use `printBox()` to explain intent | The operator knows what will happen before it does. |
| Use `success()` and `error()` for outcomes | Clear communication of what happened. |
| One concern per migration | Easier to maintain, test, and re-run individually. |
| Support environment variables | Enables automated deployment without interactive prompts. |
| Confirm bulk or destructive operations | Prevents accidental data loss. |


## See Also

- [Migrations Overview](migrations-overview.md) -- What migrations are and when to use them.
- [Migrations API Reference](migrations-api.md) -- Complete reference for CLI commands and available APIs.
