# Tutorial: Meeting Room Booker

This tutorial walks you through building a meeting room booking application with Spaceport. You will start with an in-memory version similar to the Tic-Tac-Toe tutorial, then progressively upgrade it with **Documents** (CouchDB persistence), **Cargo** (reactive data containers), **multiple routes**, **form handling**, and **real-time cross-client updates**.

By the end of this tutorial you will have learned:

- **Documents** -- Persisting data to CouchDB so bookings survive server restarts
- **Custom Document Classes** -- Defining typed data models with business logic
- **CouchDB Views** -- Querying and listing documents
- **Cargo** -- Using reactive data containers for real-time cross-client updates
- **Multiple Routes** -- Handling more than one URL in a single application
- **Form Handling** -- Processing form submissions with server actions
- **Reactive Bindings** -- Updating all connected browsers automatically when data changes

## Prerequisites

Before starting this tutorial, you should have:

- Completed the [Tic-Tac-Toe tutorial](tutorial-tic-tac-toe.md), which covers project scaffolding, source modules, Launchpad templates, server actions, and Transmissions
- Java installed and running
- CouchDB installed and running on `http://127.0.0.1:5984` (the default)

This tutorial builds directly on the concepts from Tic-Tac-Toe. Where that tutorial used static variables for in-memory state and `@redirect` Transmissions for page updates, this tutorial introduces persistent storage and real-time reactivity.

---

## Step 1: Create the Project Structure

Create a new project folder with the same minimal scaffold you used in the Tic-Tac-Toe tutorial.

```
+ /meeting-room-booker
  + modules/
    + documents/
  + launchpad/
    + parts/
    + elements/
```

There is one new subdirectory here: `modules/documents/`. This is where we will place our custom Document class -- a pattern you will see in real Spaceport applications for organizing data models separately from routing logic.

Download the Spaceport JAR into your project root:

```bash
curl -L https://spaceport.com.co/builds/spaceport-latest.jar -o spaceport.jar
```

Your project scaffold should now look like this:

```
+ /meeting-room-booker
  - spaceport.jar
  + modules/
    + documents/
  + launchpad/
    + parts/
    + elements/
```

---

## Step 2: Define the Booking Document

In the Tic-Tac-Toe tutorial, all game state lived in `static` variables -- simple and fast, but lost on every server restart. For a booking system, we need data to persist. This is where **Documents** come in.

A Document is Spaceport's ORM layer for CouchDB. It maps a Groovy object to a JSON document in the database. When you save a Document, it writes to CouchDB. When you retrieve it, you get a Groovy object with typed properties and methods. For a full explanation, see the [Documents Overview](documents-overview.md).

### What Is CouchDB?

CouchDB is a document-oriented database that stores data as JSON. Unlike SQL databases with tables and rows, CouchDB stores self-contained documents that can have any structure. Spaceport uses CouchDB as its primary data store, and the `Document` class is how you interact with it.

### Creating a Custom Document Class

Create a file named `Booking.groovy` inside `modules/documents/`:

```groovy
package documents

import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.computer.memory.physical.Document
import spaceport.computer.memory.physical.ViewDocument

class Booking extends Document {

    // The 'type' property tags this document in CouchDB.
    // Views use it to filter for booking documents specifically.
    def type = 'booking'

    // The 'customProperties' list tells Spaceport which properties
    // to serialize when saving to CouchDB. Properties not listed
    // here (and not in the base Document set) will not be persisted.
    def customProperties = ['room', 'date', 'timeSlot', 'bookedBy']

    // Typed properties with defaults
    String room = ''
    String date = ''
    String timeSlot = ''
    String bookedBy = 'Anonymous'

    // Factory method to create a new booking with a random ID
    static Booking makeNew(String room, String date, String timeSlot, String bookedBy) {
        def booking = getNew('bookings') as Booking
        booking.type = 'booking'
        booking.room = room
        booking.date = date
        booking.timeSlot = timeSlot
        booking.bookedBy = bookedBy
        booking.save()
        return booking
    }
}
```

Let's break down the key pieces:

- **`extends Document`** -- This makes `Booking` a CouchDB-backed object. It inherits `_id`, `_rev`, `fields`, `cargo`, `save()`, `remove()`, and all the other Document capabilities described in the [Documents API Reference](documents-api.md).
- **`def type = 'booking'`** -- A tag that CouchDB views use to filter documents. A single database can hold multiple document types, so the `type` field is how you distinguish bookings from other records.
- **`def customProperties`** -- This list is critical. It tells Spaceport which of your custom properties to include when serializing the document to JSON for CouchDB. Without this, only the base Document properties (`_id`, `_rev`, `fields`, `cargo`, etc.) would be saved.
- **`getNew('bookings')`** -- Creates a new document with a random UUID in the `bookings` database. The `as Booking` cast ensures you get a typed `Booking` object back.

### Setting Up a CouchDB View

To list all bookings, we need a **CouchDB view**. Views are JavaScript functions that CouchDB runs against every document in a database to produce a sorted index. Think of them as pre-computed queries.

Add a view setup method to the `Booking` class, inside the class body, after the `makeNew` method:

```groovy
    // This alert runs once when the application finishes starting up.
    // It creates a CouchDB view for listing all bookings, grouped by date.
    @Alert('on initialized')
    static _setupViews(Result r) {

        // Create the 'bookings' database if it does not exist
        spaceport.Spaceport.main_memory_core.createDatabaseIfNotExists('bookings')

        // Define a view that indexes bookings by date
        ViewDocument.get('views', 'bookings')
            .setViewIfNeeded('by-date', '''
                function(doc) {
                    if (doc.type === 'booking') {
                        emit(doc.date, {
                            room: doc.room,
                            timeSlot: doc.timeSlot,
                            bookedBy: doc.bookedBy
                        });
                    }
                }
            ''')
    }
```

This code does two things at startup:

1. **Creates the `bookings` database** in CouchDB if it does not already exist. Without this, attempts to save documents would fail.
2. **Defines a view** named `by-date` inside a design document called `views`. The JavaScript function runs against every document in the `bookings` database. When it finds one with `type === 'booking'`, it emits the date as the key and the booking details as the value. `setViewIfNeeded` is idempotent -- it only writes to CouchDB if the view does not exist or has changed.

### Complete `modules/documents/Booking.groovy`

```groovy
package documents

import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.Result
import spaceport.computer.memory.physical.Document
import spaceport.computer.memory.physical.ViewDocument

class Booking extends Document {

    def type = 'booking'
    def customProperties = ['room', 'date', 'timeSlot', 'bookedBy']

    String room = ''
    String date = ''
    String timeSlot = ''
    String bookedBy = 'Anonymous'

    static Booking makeNew(String room, String date, String timeSlot, String bookedBy) {
        def booking = getNew('bookings') as Booking
        booking.type = 'booking'
        booking.room = room
        booking.date = date
        booking.timeSlot = timeSlot
        booking.bookedBy = bookedBy
        booking.save()
        return booking
    }

    @Alert('on initialized')
    static _setupViews(Result r) {

        spaceport.Spaceport.main_memory_core.createDatabaseIfNotExists('bookings')

        ViewDocument.get('views', 'bookings')
            .setViewIfNeeded('by-date', '''
                function(doc) {
                    if (doc.type === 'booking') {
                        emit(doc.date, {
                            room: doc.room,
                            timeSlot: doc.timeSlot,
                            bookedBy: doc.bookedBy
                        });
                    }
                }
            ''')
    }
}
```

---

## Step 3: Build the Router with Cargo

Now we need a source module to handle HTTP routing and manage a reactive data container for real-time updates. Create `modules/App.groovy`:

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.*
import spaceport.computer.memory.virtual.Cargo
import spaceport.computer.memory.physical.View
import spaceport.launchpad.Launchpad

import documents.Booking

class App {

    static def launchpad = new Launchpad()

    // A Store Cargo instance shared across all clients.
    // When this Cargo changes, all connected browsers with
    // reactive bindings to it will update automatically.
    static Cargo bookingData = Cargo.fromStore('booking-data')

    //
    // Initialization
    //

    @Alert('on initialized')
    static _init(Result r) {
        refreshBookingData()
    }

    // Refreshes the shared Cargo with the latest bookings from CouchDB.
    // Called after every booking change so all clients see the update.
    static void refreshBookingData() {
        def results = View.get('views', 'by-date', 'bookings')
        bookingData.set('bookings', results.rows)
        bookingData.set('lastUpdated', System.currentTimeMillis())
    }

    //
    // Routes
    //

    // Main page -- shows the booking schedule
    @Alert('on / hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r)
    }

    // New booking page -- shows the booking form
    @Alert('on /new hit')
    static _newBooking(HttpResult r) {
        launchpad.assemble(['new-booking.ghtml']).launch(r)
    }
}
```

### What Is Cargo?

In the Tic-Tac-Toe tutorial, state was stored in `static` variables and the page was refreshed on every action. That works, but it means each user has to reload to see changes made by others.

**Cargo** solves this. It is a reactive data container that automatically pushes updates to all connected Launchpad templates when its values change. When you call `bookingData.set('bookings', ...)`, every browser viewing a template that references `bookingData` will see the new data instantly, with no page reload required.

We are using `Cargo.fromStore('booking-data')` here, which creates a **Store Cargo** -- a named singleton shared across the entire server. It survives hot-reloads during development but does not persist to disk (the CouchDB Documents handle persistence). See the [Cargo Overview](cargo-overview.md) for details on all three Cargo modes.

### Multiple Routes

Notice that we now have two `@Alert` handlers for different URLs:

- `'on / hit'` -- serves the main booking schedule at `http://localhost:10000/`
- `'on /new hit'` -- serves the booking form at `http://localhost:10000/new`

This is how Spaceport handles routing. Each URL gets its own `@Alert` handler, and each can assemble different Launchpad templates. See the [Alerts Overview](alerts-overview.md) for the full event string syntax.

---

## Step 4: Create the Main Page Template

Create `launchpad/parts/index.ghtml`. This is the main page that displays all current bookings with real-time updates.

```html
<%@ page import="spaceport.computer.memory.virtual.Cargo" %>
<!DOCTYPE html>
<html>
<head>
    <title>Meeting Room Booker</title>
    <script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
    <style>
        @view-transition { navigation: auto; }
        html     { font-size: 14px; }
        body     { user-select: none; font-family: sans-serif; max-width: 700px; margin: 0 auto; padding: 40px; background-color: #1a1a2e; color: white; }
        h1       { text-align: center; margin-bottom: 10px; }
        .subtitle { text-align: center; color: #8888aa; margin-bottom: 40px; }
        table    { width: 100%; border-collapse: collapse; margin-top: 20px; }
        th       { background-color: #16213e; padding: 12px 15px; text-align: left; font-weight: normal; color: #8888aa; text-transform: uppercase; font-size: 0.85em; letter-spacing: 0.05em; }
        td       { padding: 12px 15px; border-bottom: 1px solid #16213e; }
        .room-a  { color: #e74c3c; font-weight: bold; }
        .room-b  { color: #3498db; font-weight: bold; }
        .empty   { color: #555; font-style: italic; }
        .actions { display: flex; gap: 15px; justify-content: center; margin: 30px 0; }
        .btn     { background-color: #4a90e2; color: white; border: none; padding: 12px 24px; border-radius: 8px; cursor: pointer; font-size: 1em; text-decoration: none; display: inline-block; }
        .btn:hover { background-color: #357abd; }
        .btn-danger { background-color: #e74c3c; }
        .btn-danger:hover { background-color: #c0392b; }
        .status  { text-align: center; padding: 20px; background-color: #16213e; border-radius: 10px; margin-top: 20px; }
    </style>
</head>
<body>

    <h1>Meeting Room Booker</h1>
    <p class="subtitle">Real-time booking powered by Spaceport</p>

    <div class="actions">
        <a href="/new" class="btn">New Booking</a>
        <button class="btn btn-danger" on-click="${ _{
            App.clearAllBookings()
            return ['@redirect': '/']
        }}">Clear All</button>
    </div>

    /// Reactive binding: this entire block re-renders automatically
    /// when App.bookingData changes on the server.
    ${{
        def bookings = App.bookingData.get('bookings')

        if (!bookings || bookings.size() == 0) {
            return '<div class="status">No bookings yet. Click "New Booking" to get started.</div>'
        }

        def html = '<table><tr><th>Date</th><th>Time Slot</th><th>Room</th><th>Booked By</th><th></th></tr>'

        bookings.each { row ->
            def val = row.value
            def roomClass = val.room == 'Conference A' ? 'room-a' : 'room-b'
            html += """<tr>
                <td>${ row.key }</td>
                <td>${ val.timeSlot }</td>
                <td class="${ roomClass }">${ val.room }</td>
                <td>${ val.bookedBy }</td>
                <td><button class="btn btn-danger" style="padding: 5px 12px; font-size: 0.85em;"
                    on-click="\${ _{
                        App.deleteBooking('${ row.id }')
                    }}">Delete</button></td>
            </tr>"""
        }

        html += '</table>'
        return html
    }}

</body>
</html>
```

### Reactive Bindings Explained

The `${{ }}` syntax (double curly braces) is a **reactive binding**. This is one of the most powerful features in Launchpad and the key difference from the Tic-Tac-Toe tutorial, where we used `@redirect` to force a full page reload on every action.

Here is how it works:

1. When the page first loads, Launchpad evaluates the expression inside `${{ }}` and renders the result as HTML.
2. Launchpad tracks which Cargo instances the expression accessed (in this case, `App.bookingData`).
3. When `App.bookingData` changes anywhere on the server (from any route, any user, any browser), Launchpad re-evaluates the expression and pushes the new HTML to the client via WebSocket.
4. HUD-Core on the client swaps the old content for the new content, with no page reload.

This means if User A creates a booking in one browser, User B sees it appear in their browser instantly. See the [Launchpad Overview](launchpad-overview.md) and [Transmissions Overview](transmissions-overview.md) for more detail on how reactive bindings and Transmissions work under the hood.

---

## Step 5: Create the Booking Form Page

Create `launchpad/parts/new-booking.ghtml`. This page provides a form for creating new bookings.

```html
<!DOCTYPE html>
<html>
<head>
    <title>New Booking - Meeting Room Booker</title>
    <script defer src='https://cdn.jsdelivr.net/gh/spaceport-dev/hud-core.js@latest/hud-core.min.js'></script>
    <style>
        @view-transition { navigation: auto; }
        html     { font-size: 14px; }
        body     { user-select: none; font-family: sans-serif; max-width: 500px; margin: 0 auto; padding: 40px; background-color: #1a1a2e; color: white; }
        h1       { text-align: center; margin-bottom: 30px; }
        form     { display: flex; flex-direction: column; gap: 20px; }
        label    { display: flex; flex-direction: column; gap: 6px; color: #8888aa; font-size: 0.9em; text-transform: uppercase; letter-spacing: 0.05em; }
        input, select { padding: 12px; border-radius: 8px; border: 2px solid #16213e; background-color: #0f3460; color: white; font-size: 1em; }
        input:focus, select:focus { outline: none; border-color: #4a90e2; }
        .btn-row { display: flex; gap: 15px; justify-content: center; margin-top: 10px; }
        .btn     { background-color: #4a90e2; color: white; border: none; padding: 12px 24px; border-radius: 8px; cursor: pointer; font-size: 1em; text-decoration: none; display: inline-block; }
        .btn:hover { background-color: #357abd; }
        .btn-secondary { background-color: #16213e; }
        .btn-secondary:hover { background-color: #1a2744; }
        #result  { text-align: center; padding: 15px; border-radius: 8px; min-height: 20px; }
        .success { background-color: #27ae60; }
        .error   { background-color: #e74c3c; }
    </style>
</head>
<body>

    <h1>New Booking</h1>

    <form on-submit="${ _{ t ->
        /// The 't' parameter is the Transmission object.
        /// It contains all form field values, accessible by name.
        def room = t.room.toString()
        def date = t.date.toString()
        def timeSlot = t.getString('timeSlot')
        def bookedBy = t.getString('bookedBy')?.clean() ?: 'Anonymous'

        /// Validate required fields
        if (!room || !date || !timeSlot) {
            return ['> #result': '<div class=\"error\">Please fill in all required fields.</div>']
        }

        /// Create the booking Document and persist it to CouchDB
        documents.Booking.makeNew(room, date, timeSlot, bookedBy)

        /// Refresh the shared Cargo so all connected clients see the new booking
        App.refreshBookingData()

        /// Redirect back to the main page
        return ['@redirect': '/']
    }}">

        <label>
            Room
            <select name="room" required>
                <option value="Conference A">Conference A</option>
                <option value="Conference B">Conference B</option>
            </select>
        </label>

        <label>
            Date
            <input type="date" name="date" required>
        </label>

        <label>
            Time Slot
            <select name="timeSlot" required>
                <option value="9:00 AM - 10:00 AM">9:00 AM - 10:00 AM</option>
                <option value="10:00 AM - 11:00 AM">10:00 AM - 11:00 AM</option>
                <option value="11:00 AM - 12:00 PM">11:00 AM - 12:00 PM</option>
                <option value="12:00 PM - 1:00 PM">12:00 PM - 1:00 PM</option>
                <option value="1:00 PM - 2:00 PM">1:00 PM - 2:00 PM</option>
                <option value="2:00 PM - 3:00 PM">2:00 PM - 3:00 PM</option>
                <option value="3:00 PM - 4:00 PM">3:00 PM - 4:00 PM</option>
                <option value="4:00 PM - 5:00 PM">4:00 PM - 5:00 PM</option>
            </select>
        </label>

        <label>
            Your Name
            <input type="text" name="bookedBy" placeholder="Enter your name" value="">
        </label>

        <div id="result"></div>

        <div class="btn-row">
            <a href="/" class="btn btn-secondary">Cancel</a>
            <button type="submit" class="btn">Book Room</button>
        </div>

    </form>

</body>
</html>
```

### Form Handling Explained

This template introduces `on-submit` with a **Transmission parameter** `t`. When the form is submitted:

1. HUD-Core intercepts the submit event and gathers all form field values.
2. It sends them to the server as a POST request.
3. The server action closure receives them in the `t` parameter, which behaves like a Map with convenience methods.

The key accessors on `t` are:

- `t.room` -- direct property access to the `name="room"` field value
- `t.getString('bookedBy')` -- typed accessor that coerces to String
- `t.clean()` -- sanitizes strings to prevent XSS injection

The closure then calls `Booking.makeNew(...)`, which creates a new CouchDB document, and `App.refreshBookingData()`, which updates the shared Cargo. Finally, it returns `['@redirect': '/']` to send the user back to the main page.

See the [Launchpad API Reference](launchpad-api.md) for the complete list of Transmission object methods and return value formats.

### Map Return Values

Notice the validation error case returns a Map: `['> #result': '<div class=\"error\">...</div>']`. The `> #result` key is a selector-based Transmission instruction -- it tells HUD-Core to replace the innerHTML of the element with `id="result"`. This lets you show error feedback without leaving the page. The Tic-Tac-Toe tutorial only used `@redirect`; this Map format gives you surgical DOM updates.

---

## Step 6: Add Delete and Clear Operations

We referenced `App.clearAllBookings()` and `App.deleteBooking()` in our templates but have not implemented them yet. Add these methods to `modules/App.groovy`:

```groovy
    //
    // Booking Operations
    //

    // Deletes a single booking by document ID
    static void deleteBooking(String id) {
        def booking = Booking.get(id, 'bookings')
        booking.remove()
        refreshBookingData()
    }

    // Deletes all bookings from the database
    static void clearAllBookings() {
        def results = View.get('views', 'by-date', 'bookings')
        results.rows.each { row ->
            def booking = Booking.get(row.id, 'bookings')
            booking.remove()
        }
        refreshBookingData()
    }
```

Notice the pattern: after every data change, we call `refreshBookingData()`. This queries the CouchDB view for the latest bookings and updates the shared Cargo. Because the main page uses a `${{ }}` reactive binding against that Cargo, all connected browsers update automatically.

### Complete `modules/App.groovy`

```groovy
import spaceport.computer.alerts.Alert
import spaceport.computer.alerts.results.*
import spaceport.computer.memory.virtual.Cargo
import spaceport.computer.memory.physical.View
import spaceport.launchpad.Launchpad

import documents.Booking

class App {

    static def launchpad = new Launchpad()
    static Cargo bookingData = Cargo.fromStore('booking-data')

    //
    // Initialization
    //

    @Alert('on initialized')
    static _init(Result r) {
        refreshBookingData()
    }

    static void refreshBookingData() {
        def results = View.get('views', 'by-date', 'bookings')
        bookingData.set('bookings', results.rows)
        bookingData.set('lastUpdated', System.currentTimeMillis())
    }

    //
    // Routes
    //

    @Alert('on / hit')
    static _index(HttpResult r) {
        launchpad.assemble(['index.ghtml']).launch(r)
    }

    @Alert('on /new hit')
    static _newBooking(HttpResult r) {
        launchpad.assemble(['new-booking.ghtml']).launch(r)
    }

    //
    // Booking Operations
    //

    static void deleteBooking(String id) {
        def booking = Booking.get(id, 'bookings')
        booking.remove()
        refreshBookingData()
    }

    static void clearAllBookings() {
        def results = View.get('views', 'by-date', 'bookings')
        results.rows.each { row ->
            def booking = Booking.get(row.id, 'bookings')
            booking.remove()
        }
        refreshBookingData()
    }
}
```

---

## Step 7: Launch the Application

Your complete project structure should look like this:

```
+ /meeting-room-booker
  - spaceport.jar
  + modules/
    - App.groovy
    + documents/
      - Booking.groovy
  + launchpad/
    + parts/
      - index.ghtml
      - new-booking.ghtml
    + elements/
```

Open your terminal, navigate to the project root, and start Spaceport:

```bash
java -jar spaceport.jar --start --no-manifest
```

Open your browser to `http://localhost:10000`. You should see the main booking page with an empty schedule. Click "New Booking" to navigate to the form, fill it in, and submit. The booking appears on the main page.

### Test Real-Time Updates

Open a second browser window (or tab) to `http://localhost:10000`. Now create a booking in one window. The other window should update automatically, without a page reload, thanks to the Cargo reactive binding.

This is the fundamental difference from the Tic-Tac-Toe approach. In that tutorial, every user had to reload the page to see changes. Here, the Cargo system pushes updates over WebSocket to all connected clients.

### Test Persistence

Stop the Spaceport server (Ctrl+C) and restart it. Your bookings are still there because they are stored in CouchDB, not in-memory variables. This is the value of Documents over static fields.

---

## How It All Fits Together

Here is the data flow for a typical booking operation:

```
User fills out form and clicks "Book Room"
    |
    v
HUD-Core intercepts on-submit, sends form data to server
    |
    v
Server action closure runs:
    1. Booking.makeNew() -- creates a Document in CouchDB
    2. App.refreshBookingData() -- queries the CouchDB view
    3. bookingData.set('bookings', ...) -- updates the Store Cargo
    |
    v
Cargo change triggers Launchpad reactive system:
    - Finds all ${{ }} bindings that reference bookingData
    - Re-evaluates each expression
    - Sends new HTML over WebSocket to every connected client
    |
    v
HUD-Core on each client replaces the old booking table with the new one
```

The key insight is that **CouchDB is the source of truth** (persistent storage), and **Cargo is the broadcast mechanism** (real-time updates). Every mutation follows the pattern: write to CouchDB, then update Cargo, then let Launchpad push the changes.

---

## Concepts Recap: What's New Since Tic-Tac-Toe

| Concept | Tic-Tac-Toe | Meeting Room Booker |
|---|---|---|
| **State storage** | `static` variables (in-memory, lost on restart) | Documents in CouchDB (persistent) |
| **Data model** | Plain fields on a class | Custom `Document` subclass with typed properties |
| **Querying** | Direct field access | CouchDB views with `ViewDocument` and `View` |
| **Updates** | `@redirect` (full page reload) | `${{ }}` reactive bindings via Cargo (real-time, no reload) |
| **Multi-client** | Every user shares static state, must reload to see changes | All clients update automatically via WebSocket |
| **Routes** | Single route (`/`) | Multiple routes (`/`, `/new`) |
| **User input** | Click events only | Form submission with field validation |

---

## Taking It Further

Here are ways to extend this application with more Spaceport features:

- **Add User Sessions** -- Use the session system to track who created each booking. The `client` object is available in every template and route handler, and you can read the `cookies.'spaceport-uuid'` value to identify visitors. See the [Sessions Overview](sessions-overview.md) for authentication and the Dock for per-session state.

- **Prevent Double Bookings** -- Add validation in `Booking.makeNew()` to check whether a room is already booked for a given date and time slot before saving. Query the view with parameters to filter by date and check for conflicts.

- **Use a Vessel Template** -- Both templates currently duplicate the full HTML boilerplate. Extract the shared `<head>`, styles, and `<body>` wrapper into a vessel template (e.g., `wrapper.ghtml`) with a `<payload/>` tag, and pass it as the second argument to `launch()`. See the [Launchpad Overview](launchpad-overview.md) for details on the assemble/launch pattern with vessels.

- **Add Document Cargo for Per-Booking Reactivity** -- Instead of a single Store Cargo for all bookings, you can use `Cargo.fromDocument(booking)` to make individual booking documents reactive. This is useful if you want to add live-updating status fields (e.g., "in progress", "completed") to each booking.

- **Implement a Calendar Grid** -- Replace the table with a visual calendar that shows bookings as colored blocks, similar to the original Meeting Room tutorial's grid layout. Use `<g:each>` generator tags for cleaner iteration in the template.

## Related Documentation

- [Documents Overview](documents-overview.md) -- How CouchDB Documents work in Spaceport
- [Documents API Reference](documents-api.md) -- Complete Document, ViewDocument, and View API
- [Cargo Overview](cargo-overview.md) -- Reactive data containers and the three Cargo modes
- [Alerts Overview](alerts-overview.md) -- The event system powering routing and lifecycle hooks
- [Launchpad Overview](launchpad-overview.md) -- Server-side templating, reactive bindings, and server actions
- [Launchpad API Reference](launchpad-api.md) -- Template syntax, Transmission formats, and `on-*` events
- [Transmissions Overview](transmissions-overview.md) -- How server-driven DOM updates work
- [Sessions Overview](sessions-overview.md) -- Client management, authentication, and the Dock
