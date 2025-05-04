import spaceport.Spaceport
import spaceport.bridge.Command
import spaceport.computer.memory.physical.Document
import spaceport.personnel.ClientDocument

/**
 * This migration will create a Spaceport administrator
 * and a 'users' database if they do not already exist,
 * or promote an existing user to 'spaceport-administrator'.
 *
 */

Command.with {

    printBox("""
    This migration will create a default Spaceport administrator
    and a 'users' database if they do not already exist.
    """)

    // Check for database, and create it if it does not exist.

    if (!Spaceport.main_memory_core.containsDatabase('users')) {
        Spaceport.main_memory_core.createDatabase('users')

        success("Created 'users' database.")
    }

    // Get a username and password from the user.

    def username, password

    username = promptInput('Enter a new username (default: administrator)')
    if (!username) username = 'administrator'

    // Check if the user already exists in the database, and promote if necessary.

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

    while (!password) {
        password = promptInput('Enter a new password')
        if (password != promptInput('Re-enter password to confirm')) {
            error('Passwords do not match. Please try again.')
            password = null
        }
    }

    // Create a new ClientDocument for the administrator, and add the 'spaceport-administrator' permission.

    user = ClientDocument.createNewClientDocument(username, password)

    if (user) {
        user.addPermission('spaceport-administrator')
        success("Created new administrator: ${username}")
        return
    } else {
        error("Failed to create new administrator.")
        return
    }

}



