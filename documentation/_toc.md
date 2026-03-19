# Spaceport Documentation -- Table of Contents

## Getting Started

- [Introduction](_index.md) -- What Spaceport is, key features, quick start guide, and how the documentation is organized.
- [Developer Onboarding](developer-onboarding.md) -- Set up your development environment with Java, CouchDB, and recommended tools.
- [Groovy Luminary Certification](groovy-luminary.md) -- Learn Groovy from basics to advanced concepts, with Spaceport-specific patterns.

## Tutorials

- [Tutorial: Tic-Tac-Toe](tutorial-tic-tac-toe.md) -- Build a complete interactive game in two files to learn routing, templating, and server actions.
- [Tutorial: Meeting Room Booker](tutorial-meeting-room.md) -- Build a business application covering data persistence, sessions, and reactive UI.

## Core Architecture

### Alerts (Event System)

Spaceport's unified publish-subscribe event system for HTTP routing, WebSocket messaging, lifecycle hooks, and custom events.

- [Alerts Overview](alerts-overview.md) -- What Alerts are, event strings, result types, priority, and cancellation.
- [Alerts API Reference](alerts-api.md) -- Complete reference for the `@Alert` annotation, `HttpResult`, `SocketResult`, `Result`, and event string syntax.
- [Alerts Internals](alerts-internals.md) -- How the dispatcher works, handler discovery, threading model, weak references, and hot-reload behavior.
- [Alerts Examples](alerts-examples.md) -- Real-world patterns for routing, middleware, authentication guards, and document event handling.

### Source Modules

Hot-reloadable Groovy classes that contain your application logic.

- [Source Modules Overview](source-modules-overview.md) -- What source modules are, directory structure, auto-discovery, and hot-reload behavior.
- [Source Modules API Reference](source-modules-api.md) -- Configuration options, module lifecycle, class loading rules, and manifest settings.
- [Source Modules Internals](source-modules-internals.md) -- How Spaceport compiles, loads, and reloads Groovy classes at runtime.
- [Source Modules Examples](source-modules-examples.md) -- Patterns for organizing multi-module applications, shared utilities, and module dependencies.

### Routing (HTTP Handling)

HTTP request handling, URL patterns, and response writing.

- [Routing Overview](routing-overview.md) -- How HTTP requests flow through Spaceport, URL matching, and response patterns.
- [Routing API Reference](routing-api.md) -- Complete reference for URL patterns, HTTP methods, query parameters, headers, cookies, and response writing.
- [Routing Internals](routing-internals.md) -- How Jetty dispatches requests into the Alert system, context objects, and the request lifecycle.
- [Routing Examples](routing-examples.md) -- Patterns for REST APIs, file uploads, redirects, error pages, and middleware chains.

## Guides

- [Forms Guide](forms-guide.md) -- Traditional forms vs. server action forms, file uploads, validation, the transmission object, and common patterns.

## Data Management

### Cargo (Reactive Data Containers)

Reactive data structures that bridge server state and client UI.

- [Cargo Overview](cargo-overview.md) -- What Cargo is, session-scoped vs. document-backed Cargo, and reactive binding integration.
- [Cargo API Reference](cargo-api.md) -- Complete reference for `Cargo` methods: get/set, lists, push/pop, combine, JSON serialization, and store operations.
- [Cargo Internals](cargo-internals.md) -- How Cargo tracks changes, notifies Launchpad bindings, and persists to CouchDB.
- [Cargo Examples](cargo-examples.md) -- Patterns for shopping carts, form state, real-time lists, and shared data across sessions.

### Documents (CouchDB ORM)

ORM-style interface for CouchDB document storage and retrieval.

- [Documents Overview](documents-overview.md) -- What Documents are, defining document types, CRUD operations, and views.
- [Documents API Reference](documents-api.md) -- Complete reference for `Document` class methods, field types, views, lifecycle hooks, and database operations.
- [Documents Internals](documents-internals.md) -- How Spaceport communicates with CouchDB, document caching, revision handling, and conflict resolution.
- [Documents Examples](documents-examples.md) -- Patterns for user profiles, content management, relational-style queries, and bulk operations.

### Sessions & Client Management

User authentication, session state, and client tracking.

- [Sessions Overview](sessions-overview.md) -- What sessions and clients are, the `spaceport-uuid` cookie, authentication flow, and session-scoped data.
- [Sessions API Reference](sessions-api.md) -- Complete reference for `Client` class methods, authentication, session data, and WebSocket client tracking.
- [Sessions Internals](sessions-internals.md) -- How Spaceport manages client objects, cookie lifecycle, WebSocket session binding, and memory management.
- [Sessions Examples](sessions-examples.md) -- Patterns for login/logout, role-based access, per-user state, and multi-device session handling.

## UI & Templating

### Launchpad (Templating Engine)

Server-side templating with reactive bindings and server actions.

- [Launchpad Overview](launchpad-overview.md) -- What Launchpad is, GHTML templates, assemble/launch pattern, reactive bindings, and server actions.
- [Launchpad API Reference](launchpad-api.md) -- Complete reference for `Launchpad` class, template syntax, `<g:*>` generators, built-in variables, and transmission API.
- [Launchpad Internals](launchpad-internals.md) -- How template preprocessing, the Catch proxy, reactive binding tracking, and WebSocket update dispatch work.
- [Launchpad Examples](launchpad-examples.md) -- Patterns for page composition, conditional rendering, reactive lists, form handling, and nested templates.

### HUD-Core.js (Client-Side Library)

Lightweight JavaScript library for WebSocket communication, event binding, and DOM patching.

- [HUD-Core Overview](hud-core-overview.md) -- What HUD-Core does, how to include it, and its role in the reactive update pipeline.
- [HUD-Core API Reference](hud-core-api.md) -- Complete reference for client-side APIs, WebSocket lifecycle, event binding, DOM operations, and configuration.
- [HUD-Core Internals](hud-core-internals.md) -- How HUD-Core manages the WebSocket connection, processes server messages, and patches the DOM.
- [HUD-Core Examples](hud-core-examples.md) -- Patterns for custom client-side behavior, manual WebSocket messaging, and integration with third-party JavaScript.

### Transmissions (Client-Server Communication)

The data pipeline that carries client-side data to server actions and returns responses.

- [Transmissions Overview](transmissions-overview.md) -- What transmissions are, how form data and element values flow to server closures, and response types.
- [Transmissions API Reference](transmissions-api.md) -- Complete reference for transmission objects, data accessors, response formats, and DOM targeting.
- [Transmissions Internals](transmissions-internals.md) -- How HUD-Core serializes client data, the WebSocket message protocol, and server-side deserialization.
- [Transmissions Examples](transmissions-examples.md) -- Patterns for form submission, multi-element updates, file handling, and redirect responses.

### Server Elements (Reusable Components)

Encapsulated, reusable full-stack components with their own templates and server logic.

- [Server Elements Overview](server-elements-overview.md) -- What server elements are, how to define and use them, and component composition.
- [Server Elements API Reference](server-elements-api.md) -- Complete reference for element definition syntax, attributes, slots, and lifecycle.
- [Server Elements Internals](server-elements-internals.md) -- How Spaceport discovers, compiles, and renders server element templates.
- [Server Elements Examples](server-elements-examples.md) -- Patterns for navigation bars, form components, data tables, and nested element composition.

### Static Assets

Serving images, CSS, JavaScript, fonts, and other static files.

- [Static Assets Overview](static-assets-overview.md) -- How Spaceport serves static files, directory conventions, and MIME type handling.
- [Static Assets API Reference](static-assets-api.md) -- Configuration options, caching, path resolution, and custom asset handling.

## Developer Tools

### Spaceport CLI

Command-line interface for starting, managing, and scaffolding Spaceport applications.

- [CLI Overview](cli-overview.md) -- Available commands, flags, and common workflows.
- [CLI API Reference](cli-api.md) -- Complete reference for all CLI commands, arguments, and configuration flags.

### Scaffolds

Project structure templates and starter kits for new applications.

- [Scaffolds Overview](scaffolds-overview.md) -- What scaffolds are, available starter kits (Port-Echo, Port-Mercury), and project directory structure.
- [Scaffolds API Reference](scaffolds-api.md) -- Configuration options, directory conventions, and customization.
- [Scaffolds Internals](scaffolds-internals.md) -- How the `--create` command generates project structure and default files.

### Manifest Configuration

The `.spaceport` configuration file that controls application startup and behavior.

- [Manifest Overview](manifest-overview.md) -- What the manifest file is, key configuration sections, and common settings.
- [Manifest API Reference](manifest-api.md) -- Complete reference for all manifest keys, values, and their effects.
- [Manifest Examples](manifest-examples.md) -- Configuration patterns for development, production, multi-module, and custom setups.

### Migrations

One-off scripts for database maintenance, data transformations, and administrative tasks.

- [Migrations Overview](migrations-overview.md) -- What migrations are, when to use them, and how to run them via the CLI.
- [Migrations API Reference](migrations-api.md) -- Complete reference for migration script structure, available APIs, and execution context.
- [Migrations Examples](migrations-examples.md) -- Patterns for database seeding, schema evolution, data cleanup, and bulk operations.

### Class Enhancements

Groovy metaclass extensions that add utility methods to standard types.

- [Class Enhancements Overview](class-enhancements-overview.md) -- What class enhancements are, available extensions like `.clean()`, `.quote()`, `.if()`, and `.combine()`.
- [Class Enhancements API Reference](class-enhancements-api.md) -- Complete reference for all enhanced methods, their signatures, and behavior.
- [Class Enhancements Internals](class-enhancements-internals.md) -- How Spaceport registers metaclass extensions and when they are available.

### Stowaway JARs

Managing external Java library dependencies.

- [Stowaways Overview](stowaways-overview.md) -- What stowaway JARs are, how to include external libraries, and classpath management.
- [Stowaways API Reference](stowaways-api.md) -- Configuration options, directory conventions, and classloader behavior.

### HTTP Client

Built-in utility for making outbound HTTP requests to external APIs and services.

- [HTTP Client Overview](http-overview.md) -- What the HTTP client is, when to use it, and quick examples.
- [HTTP Client API Reference](http-api.md) -- Complete reference for all methods, options, response format, and error handling.

## Reference

- [Compatibility Notes](compatibility-notes.md) -- Java version compatibility, JVM flags, and platform-specific configuration.
- [Logging Overview](logging-overview.md) -- Application logging configuration and output.
- [Ignition Scripts Overview](ignition-scripts-overview.md) -- Startup scripts that run during application initialization.
