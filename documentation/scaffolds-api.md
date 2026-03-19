# Scaffolds API Reference

This document details the `--create-port` command and its interactive setup process for generating a new Spaceport project.


## Running the Command

```bash
java -jar spaceport.jar --create-port
```

The command launches an interactive terminal program that guides you through 8 sequential steps. Each step collects configuration information and builds a manifest file. At the end, the command generates a `.spaceport` manifest file, creates directory structures, and copies scaffold template files from the Spaceport JAR's bundled resources.

You can exit at any time by pressing `CTRL+C`. On macOS and Linux, terminal settings are automatically restored on exit.


## The 8-Step Interactive Process

### Step 1: Spaceport Root

**Header:** `Spaceport Root`

The Spaceport Root is the parent directory that holds one or more individual Spaceport applications. Multiple Spaceports sharing a root can share resources like static assets.

**Behavior:**

1. The CLI checks for existing `.spaceport-root` marker files in three locations:
   - The current working directory
   - `~/spaceport/`
   - `/opt/spaceport/`
2. If existing roots are found, they are listed as options alongside "Specify a location..."
3. If no roots are found, the options are "Current directory" and "Specify a location..."
4. If the specified directory does not exist, you are prompted to create it
5. A `.spaceport-root` marker file is created in the chosen directory if one does not already exist

**Output:** Sets `spaceport root` in the manifest.


### Step 2: Spaceport Identification

**Header:** `Spaceport Identification`

Choose a unique name for your Spaceport. Names must contain only `A-Z`, `a-z`, `0-9`, and hyphens (`-`). Spaces are not allowed.

**Behavior:**

1. The CLI generates 5 random name suggestions in the format `port-<CelestialBody>-<Identifier>` (e.g., `port-Europa-42K`, `port-Titan-817`)
2. The celestial body is randomly chosen from a list of planets, dwarf planets, and moons
3. The identifier is a random 3-character string mixing digits and uppercase letters
4. You can select a generated name or choose "Specify a name..."
5. If your custom name does not start with `port-`, the prefix is added automatically
6. Spaces are replaced with hyphens; non-alphanumeric characters (except hyphens) are stripped

**Output:** Sets `spaceport name` in the manifest.


### Step 3: Spaceport Access (Host and Port)

**Header:** `Spaceport Access`

Configure the network address and port that Spaceport will use for HTTP and WebSocket connections.

**Behavior:**

1. Default option: `127.0.0.1:80`
2. Alternative: "Specify an address..."
3. If the address includes a colon, the port is parsed from it (e.g., `0.0.0.0:3000`)
4. If the address has no port, a separate port prompt appears
5. Port input is validated to be numeric

**Output:** Sets `host.port` and `host.address` in the manifest.


### Step 4: Database Connection

**Header:** `Database Connection`

Connect to CouchDB for data persistence. CouchDB must be installed and running.

**Behavior:**

1. Default option: `127.0.0.1:5984`
2. Alternative: "Specify an address..."
3. `http://` is prepended if no protocol is specified
4. The CLI attempts an HTTP connection to validate the address
5. On failure, you are prompted to re-enter the address
6. After a successful connection, you enter CouchDB admin credentials (username and password)
7. Password input is masked (displayed as `*************`)
8. You are asked whether to save the password in the manifest file
9. The CLI authenticates against CouchDB and retries on failure

**Output:** Sets `memory cores.main.address`, `memory cores.main.username`, and optionally `memory cores.main.password` in the manifest.


### Step 5: Spaceport Administrator

**Header:** `Spaceport Administrator`

Create or select an administrator account for managing the Spaceport.

**Behavior:**

1. The CLI checks the `users` database in CouchDB (creating it if necessary)
2. A CouchDB view (`user-views/admin-list`) is registered to find existing administrators
3. Existing administrators are listed as options, along with "Specify a new user..."
4. For a new user:
   - Enter a username (alphanumeric only)
   - Enter and confirm a password
   - A `ClientDocument` is created with the `spaceport-administrator` permission
5. For an existing non-admin user, you can promote them to administrator

**Output:** Creates or configures an administrator in the CouchDB `users` database.


### Step 6: Static Assets

**Header:** `Static Assets`

Configure static file serving routes.

**Behavior:**

1. You are asked whether to enable the `/*/assets/` route, which serves shared assets from a `static/` directory in the Spaceport Root
2. You are asked whether to enable a local `/assets/` route, which serves assets from within your specific Spaceport's directory
3. The CLI creates the necessary directories (`static/img/`, `static/js/`, `static/css/`)
4. Default assets are copied from the Spaceport JAR's bundled resources:
   - `img/40X.webp` -- Error page image for 4XX responses
   - `img/50X.webp` -- Error page image for 5XX responses
   - `js/hud-core.js` -- The client-side reactivity library
   - `css/spacewalk.css` -- Default CSS stylesheet
5. If files already exist, you are asked once whether to overwrite all of them

**Output:** Sets `static assets.paths` in the manifest and creates asset directories/files on disk.


### Step 7: Debug Mode

**Header:** `Debug Mode`

Toggle development mode on or off.

**Behavior:**

1. Simple yes/no prompt
2. When enabled, debug mode activates verbose logging, hot-reloading, and enhanced error output
3. Should be disabled for production deployments

**Output:** Sets `debug` in the manifest (`true` or `false`).


### Step 8: Module Creation (Scaffold Selection)

**Header:** `Module Creation`

Choose which scaffold template to use for your project's initial file structure.

**Options:**

| Scaffold | Description |
|---|---|
| `Mercury` | Single `App.groovy` file in `modules/`. Starting from scratch with basics in place. |
| `Pioneer` | Multiple modules (`App.groovy`, `Router.groovy`, `Monitor.groovy`) plus Launchpad templates (`index.ghtml`, `about.ghtml`, `contact.ghtml`). For sites without user accounts. |
| `Voyager` | Everything in Pioneer plus a user account system (`Login.groovy`, login/registration/admin templates, `datatypes/Message.groovy`). For full applications. |

**Behavior:**

1. You select Mercury, Pioneer, or Voyager
2. A description of the selected scaffold is shown
3. You confirm the selection (or go back to choose again)
4. The CLI creates the appropriate directory structure
5. Template files are copied from the Spaceport JAR's bundled `/scaffolds/` resources
6. If a file already exists, you are asked whether to replace it

**Files copied per scaffold:**

**Mercury:**
- `modules/App.groovy`

**Pioneer:**
- `modules/App.groovy`
- `modules/Router.groovy`
- `modules/Monitor.groovy`
- `launchpad/parts/index.ghtml`
- `launchpad/parts/about.ghtml`
- `launchpad/parts/contact.ghtml`

**Voyager:**
- `modules/App.groovy`
- `modules/Router.groovy`
- `modules/Monitor.groovy`
- `modules/Login.groovy`
- `modules/datatypes/Message.groovy`
- `launchpad/parts/home/index.ghtml`
- `launchpad/parts/home/about.ghtml`
- `launchpad/parts/home/contact.ghtml`
- `launchpad/parts/user/login.ghtml`
- `launchpad/parts/user/user.ghtml`
- `launchpad/parts/user/register.ghtml`
- `launchpad/parts/admin/admin.ghtml`


## Manifest File Generation

After all 8 steps complete, the CLI generates a `.spaceport` manifest file in YAML format at:

```
[spaceport-root]/[spaceport-name]/[spaceport-name].spaceport
```

The generated manifest includes commented sections explaining each configuration block:

```yaml
###
# Spaceport Manifest for port-Europa-42K

spaceport name: port-Europa-42K
spaceport root: /home/user/spaceport/

###
# Enable debug mode to enable verbose logging and enhanced
# debugging features.
debug: true

###
# The host and port
host:
    port: 10000
    address: 127.0.0.1

###
# The memory cores (database connections)
memory cores:
    main:
        address: http://127.0.0.1:5984
        username: admin
        password: mypassword

###
# Static asset paths
static assets:
    paths:
        /*/assets/ : 'static/*'
        /assets/ : 'port-Europa-42K/assets/*'

###
# Source module paths
source modules:
    paths:
        - modules/*
```

**Existing manifest handling:**

- If a `.spaceport` file already exists at the target path, you are prompted to overwrite it
- Choosing "Yes" backs up the existing file with a `.bak` extension before writing the new one
- Choosing "No" writes the generated manifest to a `.draft` file instead and exits


## See Also

- [Scaffolds Overview](scaffolds-overview.md) -- What scaffolds are and when to use each type
- [Scaffolds Internals](scaffolds-internals.md) -- How the Onboarding system works under the hood
- [Manifest API Reference](manifest-api.md) -- Complete manifest configuration reference
- [CLI Overview](cli-overview.md) -- All available CLI commands
