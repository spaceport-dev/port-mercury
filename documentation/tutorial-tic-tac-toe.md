# Tutorial: Build a Tic-Tac-Toe Game

This tutorial walks you through building a complete, playable Tic-Tac-Toe game in Spaceport. Along the way, you will learn four foundational concepts:

- **Alerts** -- Spaceport's event system for routing and lifecycle hooks
- **Launchpad templates** -- server-side HTML rendering with embedded Groovy
- **Server actions** -- Groovy closures triggered by client-side events like clicks
- **Transmissions** -- server-driven responses that update the browser

By the end, you will have a working two-player game running in the browser, built with roughly 60 lines of Groovy and 40 lines of HTML.

## Prerequisites

This tutorial assumes you have:

- Completed the [Developer Onboarding Guide](developer-onboarding.md) and have a working Java installation
- CouchDB installed and running (required by Spaceport at startup)
- A text editor and a terminal

No prior Spaceport experience is needed beyond the onboarding setup.

---

## Step 1: Create the Project Structure

A Spaceport application needs very little scaffolding. Create a project folder with three subdirectories and download the framework JAR:

```bash
mkdir -p my-tictactoe-app/modules
mkdir -p my-tictactoe-app/launchpad/parts
mkdir -p my-tictactoe-app/launchpad/elements
cd my-tictactoe-app
curl -L https://spaceport.com.co/builds/spaceport-latest.jar -o spaceport.jar
```

Your project should look like this:

```
my-tictactoe-app/
  spaceport.jar
  modules/
  launchpad/
    parts/
    elements/
```

Here is what each folder does:

- **`modules/`** -- holds your Groovy source files (called "Source Modules"). Spaceport automatically discovers and loads every `.groovy` file placed here.
- **`launchpad/parts/`** -- holds `.ghtml` template files that define your UI.
- **`launchpad/elements/`** -- holds reusable Server Element definitions (not needed for this tutorial, but the folder is part of the standard structure).

---

## Step 2: Define the Game State

Create a file named `Game.groovy` inside the `modules/` folder. This single Source Module will contain all of the server-side code for the game: state, logic, and routing.

Start with the class declaration and the variables that represent the game board:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.*
import spaceport.launchpad.Launchpad

class Game {

    // Game State
    static List<String> board         // 9 cells: 'X', 'O', or '' (empty)
    static String currentPlayer       // 'X' or 'O'
    static String winner              // 'X', 'O', 'Draw', or null (game in progress)

}
```

These are `static` fields, which means they are shared across all requests. For this tutorial, the board acts as a simple in-memory "database" -- every visitor sees the same game. (The "Taking It Further" section at the end discusses how to support multiple independent games.)

---

## Step 3: Add the Game Logic

Still inside the `Game` class, add three methods that control how the game plays:

```groovy
    // Sets the game back to its starting state
    static void resetGame() {
        board = (0..8).collect { '' }   // 9 empty strings
        currentPlayer = 'X'
        winner = null
    }

    // Processes a player clicking on a cell
    static void makeMove(int index) {
        if (winner == null && board[index] == '') {
            board[index] = currentPlayer
            checkWinner()
            if (winner == null) {
                currentPlayer = (currentPlayer == 'X' ? 'O' : 'X')
            }
        }
    }

    // Checks all win conditions and updates the 'winner' field
    static void checkWinner() {
        def winningLines = [
            [0, 1, 2], [3, 4, 5], [6, 7, 8],  // rows
            [0, 3, 6], [1, 4, 7], [2, 5, 8],  // columns
            [0, 4, 8], [2, 4, 6]               // diagonals
        ]
        for (line in winningLines) {
            def a = line[0]; def b = line[1]; def c = line[2]
            if (board[a] && board[a] == board[b] && board[a] == board[c]) {
                winner = board[a]
                return
            }
        }
        if (!board.contains('')) winner = 'Draw'
    }
```

The logic is straightforward:

- `resetGame()` fills the board with 9 empty strings and sets X to go first.
- `makeMove()` places the current player's mark if the cell is empty and the game is not over, then checks for a winner and switches turns.
- `checkWinner()` iterates through all eight winning lines (3 rows, 3 columns, 2 diagonals). If no winner is found and no empty cells remain, it declares a draw.

---

## Step 4: Connect to Spaceport with Alerts

Now wire the game into the framework. Add the following below the game logic methods, still inside the `Game` class:

```groovy
    // Spaceport Integration
    static def launchpad = new Launchpad()

    @Alert('on initialize')
    static _init(Result r) {
        resetGame()
    }

    @Alert('on / hit')
    static _renderGame(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r)
    }
```

This introduces **Alerts**, Spaceport's unified event system. Instead of a separate router configuration file, you annotate methods with `@Alert` and a human-readable event string. Spaceport discovers these annotations automatically when it loads your Source Module.

Two alerts are at work here:

1. **`@Alert('on initialize')`** -- fires once when the application starts. The handler calls `resetGame()` so the board is ready before any HTTP requests arrive.

2. **`@Alert('on / hit')`** -- fires whenever a browser requests the root URL (`/`). The handler uses Launchpad's assemble/launch pattern: `assemble()` selects which template files make up the page, and `launch()` renders them into the HTTP response.

The `HttpResult r` parameter gives you access to the request and response. Launchpad uses it internally to write the rendered HTML back to the browser.

For a deeper look at the Alerts system, see the [Alerts Overview](alerts-overview.md).

---

## Step 5: The Complete `Game.groovy`

Before moving on to the template, here is the full source module with all sections combined:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.*
import spaceport.launchpad.Launchpad

class Game {

    //
    // Game State

    static List<String> board
    static String currentPlayer
    static String winner

    //
    // Game Logic

    static void resetGame() {
        board = (0..8).collect { '' }
        currentPlayer = 'X'
        winner = null
    }

    static void makeMove(int index) {
        if (winner == null && board[index] == '') {
            board[index] = currentPlayer
            checkWinner()
            if (winner == null) {
                currentPlayer = (currentPlayer == 'X' ? 'O' : 'X')
            }
        }
    }

    static void checkWinner() {
        def winningLines = [
                [0, 1, 2], [3, 4, 5], [6, 7, 8],
                [0, 3, 6], [1, 4, 7], [2, 5, 8],
                [0, 4, 8], [2, 4, 6]
        ]
        for (line in winningLines) {
            def a = line[0]; def b = line[1]; def c = line[2]
            if (board[a] && board[a] == board[b] && board[a] == board[c]) {
                winner = board[a]
                return
            }
        }
        if (!board.contains('')) winner = 'Draw'
    }

    //
    // Spaceport Integration

    static def launchpad = new Launchpad()

    @Alert('on initialize')
    static _init(Result r) {
        resetGame()
    }

    @Alert('on / hit')
    static _renderGame(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r)
    }

}
```

---

## Step 6: Create the Template -- Boilerplate and Styling

Create a file named `index.ghtml` inside `launchpad/parts/`. This is a Launchpad template -- an HTML file with embedded Groovy code that runs on the server before the page is sent to the browser.

Start with the HTML structure and CSS:

```html
<!DOCTYPE html>
<html>
<head>
    <title>Spaceport Tic-Tac-Toe</title>
    <script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
    <style>
        @view-transition { navigation: auto; }
        body   { user-select: none; font-family: sans-serif; display: grid; place-content: center; text-align: center; background-color: black; color: white; gap: 40px; }
        .board { display: grid; grid-template-columns: repeat(3, 100px); gap: 5px; background-color: white; border: 5px solid white; border-radius: 18px; }
        .cell  { aspect-ratio: 1/1; background-color: black; font-size: 3em; display: grid; place-content: center; cursor: pointer; border-radius: 10px; }
        .cell:hover { outline: 5px solid dodgerblue; }
        .status { background-color: white; color: black; border-radius: 10px; font-size: 1.5em; height: 50px; display: grid; place-content: center; }
        button  { background-color: black; font-size: 1em; padding: 10px 20px; cursor: pointer; color: white; border: 5px solid white; border-radius: 10px; }
        button:hover { outline: 5px solid dodgerblue; }
    </style>
</head>
<body>
    <h1>Spaceport <br> Tic-Tac-Toe</h1>

    /// Template content goes here (next steps)

</body>
</html>
```

Two things to note:

- **The HUD-Core script** is required for server actions and reactive bindings to work. This lightweight JavaScript library handles WebSocket connections and DOM updates. See the [Launchpad Overview](launchpad-overview.md) for more details on this requirement.
- **`///` (triple-slash comments)** are server-side comments. They are stripped during template processing and never appear in the HTML sent to the browser. You can use them anywhere in a `.ghtml` file -- inside HTML, CSS, JavaScript, or Groovy code blocks.

---

## Step 7: Display the Game Status

Inside the `<body>`, after the `<h1>`, add a status bar that shows whose turn it is or who won. This section introduces two pieces of Launchpad template syntax.

```html
    <div class="status">
        <% if (Game.winner) { %>

            ${ "It's a draw!".if { Game.winner == "Draw" }}
            ${ "Player ${ Game.winner } wins!".if { Game.winner != "Draw" }}

        <% } else { %>

            Player ${ Game.currentPlayer }'s Turn

        <% } %>
    </div>
```

**`<% %>` scriptlet blocks** execute Groovy code on the server. Here they create a conditional: if there is a winner, show the result; otherwise, show whose turn it is. Nothing inside `<% %>` is sent to the browser -- it controls what HTML gets generated.

**`${ }` expressions** interpolate a Groovy value into the HTML output. `${ Game.currentPlayer }` outputs `X` or `O`. The `.if { condition }` syntax is a Groovy convenience that returns the string only when the condition is true, and an empty string otherwise.

Because `Game.board`, `Game.currentPlayer`, and `Game.winner` are static fields on the `Game` class, the template can access them directly.

---

## Step 8: Render the Interactive Game Board

Below the status `<div>`, add the board. This is where server actions and transmissions come into play:

```html
    <div class="board">
        <% Game.board.eachWithIndex { cell, i -> %>
        <div class="cell" on-click="${ _{
                Game.makeMove(i)
                return [ '@redirect' : '/' ]
            }}">
            ${ cell }
        </div>
        <% } %>
    </div>
```

There are several concepts working together here:

### The Loop

`<% Game.board.eachWithIndex { cell, i -> %>` iterates over the 9-element board list. For each cell, Launchpad renders a `<div>` with the cell's value (`X`, `O`, or empty) and a click handler. The `<% } %>` closes the loop.

### Server Actions: `_{ }`

The `_{ ... }` syntax inside a `${ }` expression creates a **server action** -- a Groovy closure stored on the server and associated with this specific DOM element. When the user clicks a cell, HUD-Core sends a request to the server, which executes the closure.

Inside the closure:
1. `Game.makeMove(i)` places the current player's mark on the board at position `i`.
2. `return [ '@redirect' : '/' ]` sends a **Transmission** back to the browser.

### Transmissions

The return value of a server action is a Transmission -- an instruction set that tells HUD-Core what to do next. The map `[ '@redirect' : '/' ]` tells the browser to navigate to `/`, which triggers a full page re-render with the updated board state.

This is the Multi-Page Application (MPA) pattern: each move results in a fresh page from the server. The `@view-transition` CSS rule in the `<style>` block provides a smooth fade between page loads so the transition does not feel jarring.

For a thorough treatment of Transmission formats and targets, see the [Transmissions Overview](transmissions-overview.md).

---

## Step 9: Add a Reset Button

Below the board `<div>`, add a button that resets the game using the same server action pattern:

```html
    <button on-click="${ _{
                Game.resetGame()
                return [ '@redirect' : '/' ]
            }}">
        Reset Game
    </button>
```

When clicked, the server action calls `Game.resetGame()`, which clears the board and sets the current player back to X. The `@redirect` Transmission reloads the page to show the empty board.

---

## Step 10: The Complete `index.ghtml`

Here is the finished template with all sections assembled:

```html
<!DOCTYPE html>
<html>
<head>
    <title>Spaceport Tic-Tac-Toe</title>
    <script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
    <style>
        @view-transition { navigation: auto; }
        body   { user-select: none; font-family: sans-serif; display: grid; place-content: center; text-align: center; background-color: black; color: white; gap: 40px; }
        .board { display: grid; grid-template-columns: repeat(3, 100px); gap: 5px; background-color: white; border: 5px solid white; border-radius: 18px; }
        .cell  { aspect-ratio: 1/1; background-color: black; font-size: 3em; display: grid; place-content: center; cursor: pointer; border-radius: 10px; }
        .cell:hover { outline: 5px solid dodgerblue; }
        .status { background-color: white; color: black; border-radius: 10px; font-size: 1.5em; height: 50px; display: grid; place-content: center; }
        button  { background-color: black; font-size: 1em; padding: 10px 20px; cursor: pointer; color: white; border: 5px solid white; border-radius: 10px; }
        button:hover { outline: 5px solid dodgerblue; }
    </style>
</head>
<body>
    <h1>Spaceport <br> Tic-Tac-Toe</h1>

    <div class="status">
        <% if (Game.winner) { %>

            ${ "It's a draw!".if { Game.winner == "Draw" }}
            ${ "Player ${ Game.winner } wins!".if { Game.winner != "Draw" }}

        <% } else { %>

            Player ${ Game.currentPlayer }'s Turn

        <% } %>
    </div>

    <div class="board">
        <% Game.board.eachWithIndex { cell, i -> %>
        <div class="cell" on-click="${ _{
            Game.makeMove(i)
            return [ '@redirect' : '/' ]
        }}">
            ${ cell }
        </div>
        <% } %>
    </div>

    <button on-click="${ _{
        Game.resetGame()
        return [ '@redirect' : '/' ]
    }}">
        Reset Game
    </button>

</body>
</html>
```

---

## Step 11: Launch and Play

Your project should now contain exactly two files you created:

```
my-tictactoe-app/
  spaceport.jar
  modules/
    Game.groovy
  launchpad/
    parts/
      index.ghtml
    elements/
```

Open a terminal, navigate to `my-tictactoe-app`, and start the server:

```bash
java -jar spaceport.jar --start --no-manifest
```

The `--no-manifest` flag tells Spaceport to use default settings instead of requiring a configuration file. Once the server finishes starting, open your browser to:

```
http://localhost:10000
```

You should see the Tic-Tac-Toe board. Click cells to place X and O marks, watch the status update to show whose turn it is, and see the winner or draw announcement when the game ends. Use the Reset Game button to start over.

---

## What You Built

With two files and no configuration, you built a fully interactive web application. Here is a recap of the Spaceport concepts you used:

| Concept | Where You Used It | What It Did |
|---|---|---|
| **Source Modules** | `modules/Game.groovy` | Spaceport auto-discovered this file and loaded the `Game` class |
| **Alerts** | `@Alert('on initialize')`, `@Alert('on / hit')` | Hooked into the app lifecycle and HTTP routing with annotations |
| **Launchpad Templates** | `launchpad/parts/index.ghtml` | Rendered HTML with embedded Groovy -- loops, conditionals, expressions |
| **Server Actions** | `_{ Game.makeMove(i) ... }` | Bound Groovy closures to client-side click events |
| **Transmissions** | `return [ '@redirect' : '/' ]` | Instructed the browser to reload the page after each move |

---

## Taking It Further

The game you just built uses the MPA (Multi-Page Application) pattern: each interaction triggers a full page reload. This is a solid starting point, but Spaceport is designed for a hybrid approach that blends MPA simplicity with SPA-like responsiveness. Here are four directions to explore next.

### Externalize Your Styles

Move the `<style>` block into a separate `.css` file and serve it using Spaceport's static asset handling. This lets the browser cache the stylesheet and keeps your template focused on structure. Setting up a [Manifest Configuration](manifest-configuration.md) file at the same time is a natural next step for organizing your project.

### Support Multiple Simultaneous Games

Right now, the board state is stored in `static` fields -- everyone shares one game. To support independent sessions, store the board state per client using Spaceport's docking system. Each browser gets its own `dock` (a session-scoped [Cargo](cargo-overview.md) instance), so you can move the board, current player, and winner into `dock` and have each visitor play their own game.

### Use Transmissions Instead of Redirects

Replace the `@redirect` Transmission with targeted DOM updates. Instead of reloading the entire page after each move, the server action can return a Map that updates only the clicked cell and the status message:

```groovy
_{ t ->
    Game.makeMove(i)
    [
        '> .cell-' + i : Game.board[i],
        '> .status'    : Game.winner ? "Winner: ${Game.winner}" : "Player ${Game.currentPlayer}'s Turn"
    ]
}
```

This eliminates the page reload entirely and makes moves feel instant. See the [Transmissions Overview](transmissions-overview.md) for the full syntax.

### Add Reactive Bindings for Real-Time Multiplayer

For true real-time play, store the game state in a shared [Cargo](cargo-overview.md) container and use `${{ }}` reactive bindings in the template. When one player makes a move, the board updates on the other player's screen automatically via WebSocket -- no polling, no manual refresh. See the [Launchpad API Reference](launchpad-api.md) for the reactive binding syntax.

---

## Related Documentation

- [Alerts Overview](alerts-overview.md) -- the event system used for routing and lifecycle hooks
- [Launchpad Overview](launchpad-overview.md) -- the templating engine, server actions, and reactive bindings
- [Launchpad API Reference](launchpad-api.md) -- complete syntax reference for templates, `_{ }`, `${{ }}`, and `on-*` attributes
- [Transmissions Overview](transmissions-overview.md) -- how server actions communicate DOM updates to the browser
- [Cargo Overview](cargo-overview.md) -- reactive data containers for session state and real-time synchronization
