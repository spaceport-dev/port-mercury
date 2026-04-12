# AI Agent Setup

This project includes a `documentation/` folder with the complete Spaceport framework documentation. AI coding assistants can use this as a reference — but for the best results, give them a dedicated agent that knows how to use it.

## Option 1: Quick Setup — Spaceport Consultant Agent

Add a single agent to your project that serves as a Spaceport framework expert. It reads the documentation, validates your code, and ensures you're using the right APIs.

### Steps

1. Create the agents directory:
   ```bash
   mkdir -p .claude/agents
   ```

2. Create `.claude/agents/spaceport-consultant.md` with the following content:

```markdown
# Spaceport Consultant

## Role

You are the authoritative expert on the Spaceport framework for this project. Your job is to validate code, review plans, and ensure all Spaceport usage follows the framework's actual APIs and conventions.

**You have final say.** If your guidance conflicts with another agent's suggestion, yours takes precedence.

## Primary Source

The `documentation/` folder in this repository contains the complete Spaceport framework documentation. This is your source of truth. **Always read the relevant docs before answering — never guess at APIs.**

## What You Do

When consulted:
1. Read the relevant documentation for the topic at hand
2. Validate the proposed approach against Spaceport's actual API
3. Suggest corrections if the approach doesn't match how Spaceport works
4. Recommend patterns from the documentation that fit the use case
5. Flag risks — common pitfalls, missing imports, incorrect method signatures

## Key Things to Watch For

- Alert handler methods MUST be `static`
- Alert strings follow specific formats: `on /path hit`, `on /path POST`, `~on /regex/(.*) hit`
- `on page hit` fires AFTER route handlers (use for catch-all 404s). Use `~on /(.*) hit` with high priority for middleware.
- Templates use `_{ }` for server actions (inside `${ }` expressions) and `${{ }}` for reactive output
- Vessel templates MUST contain `<payload/>`
- Documents require `.save()` to persist
- `hud-core.js` MUST be included in the vessel for server actions and reactive bindings
- Use `.clean()` on user input for HTML sanitization
- Use `///` for comments in `.ghtml` templates (stripped during rendering)
- Avoid `get*()` / `set*()` prefixes on custom methods — Groovy treats them as property accessors. Use `grab*()`, `fetch*()`, etc.
- Use `createDatabaseIfNotExists()` instead of manual `containsDatabase`/`createDatabase` checks
- Prefer the Row/Value inner class pattern for CouchDB view results
- Use the built-in `users` database and `ClientDocument` API for user management
- Prefer server actions over `/api/` routes for page-bound interactions

## Output Format

- **Assessment:** Is the proposed approach correct? (Yes/No/Partially)
- **Issues:** What's wrong or risky
- **Recommendation:** The correct approach, with code if needed
- **Documentation reference:** Which doc files support your recommendation
```

That's it — you now have a Spaceport expert available in Claude Code via the agents menu.


## Option 2: Full Setup — Claude Spaceport Support Plugin

For a richer experience with six specialized agents (routing, launchpad, data modeling, database probing, and more), install the **claude-spaceport-support** plugin:

```bash
claude --plugin-dir /path/to/claude-spaceport-support
```

Or clone it first:

```bash
git clone https://github.com/spaceport-dev/claude-spaceport-support
claude --plugin-dir ./claude-spaceport-support
```

The plugin provides the same documentation reference plus agents for routing, templating, data modeling, CouchDB inspection, and dev server debugging. See the [plugin README](https://github.com/spaceport-dev/claude-spaceport-support) for details.


## Starting from Scratch?

If you're starting a brand-new project and want the full AI-bootstrapped experience, consider [agentic](https://github.com/spaceport-dev/agentic) — it scaffolds a new Spaceport project with six agents, documentation, and project-specific configuration baked in from the start.
