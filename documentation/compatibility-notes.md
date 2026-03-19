# Compatibility Notes

## Overview

Spaceport runs on the Java Virtual Machine (JVM) and targets broad compatibility across actively supported Java LTS releases. This document covers Java version requirements, JVM configuration flags, CouchDB compatibility, and general guidance for running Spaceport in development and production environments.


## Java Version Compatibility

### Supported Versions

| Use Case | Recommended | Also Supported | Notes |
|---|---|---|---|
| **Production (General)** | Java 11 (LTS) | Java 17 (LTS), Java 8 (Legacy) | Java 11 is the most tested version with Spaceport's core components. |
| **New Long-Lived Projects** | Java 17 (LTS) | Java 11 | Java 17 provides newer language features for teams that want them. |
| **Local Development** | Latest GA release | 11, 17 | Newer releases work for experimentation; verify before deploying. |

> While Java 8 remains functional, plan a migration to Java 11 or 17 for long-term support and security updates.

### Why Java 11 Is the Baseline

Most libraries embedded within the Spaceport JAR are compiled targeting Java 8 or 11 bytecode. Java 11 provides the best balance of maturity, long-term security patch availability, and broad hosting provider support.


## Running on Java 17+

### The `--add-opens` Flag

Starting with Java 9, the Java Platform Module System (JPMS) encapsulated many internal APIs that were previously accessible via reflection. While Java 9 through 11 provided some leniency, later versions have tightened these restrictions.

Spaceport's core does not directly require access to encapsulated JDK internals. However, Groovy's dynamic nature and many common Java libraries rely on deep reflection. When your application code or a dependency attempts to access an encapsulated API, you will see a runtime error:

```
java.lang.reflect.InaccessibleObjectException
```

To resolve this, add the `--add-opens` flag when starting Spaceport:

```bash
java --add-opens=java.base/java.lang=ALL-UNNAMED -jar spaceport.jar --start config.spaceport
```

This flag tells the JVM to open the `java.base/java.lang` package for reflective access by all unnamed modules. This is commonly the minimum flag needed for Groovy-based applications running on Java 17 or later.

Additional `--add-opens` flags may be required depending on which libraries your application uses. If you encounter further `InaccessibleObjectException` errors, the error message will indicate which package needs to be opened.

### Using Non-LTS Feature Releases

Spaceport does not depend on incubator modules, so non-LTS feature releases (e.g., Java 21, 22, 23) typically work without issues. Before deploying on a non-LTS release, perform a quick smoke test:

1. Start the application and check for module system or reflective access warnings in the output.
2. Load a Launchpad template route.
3. Trigger a source module endpoint.
4. Store and retrieve a Document to verify CouchDB connectivity.

If problems appear, fall back to the most recent LTS version.


## CouchDB Compatibility

Spaceport uses Apache CouchDB as its primary data store. Verify that your CouchDB version is compatible with the Spaceport release you are running. Consult the release notes for your specific Spaceport version for tested CouchDB versions.

General guidance:

- CouchDB 2.x and 3.x are the primary targets.
- Ensure CouchDB is accessible from the Spaceport host and that authentication credentials are configured correctly in your manifest file.
- After upgrading CouchDB, verify connectivity by starting Spaceport and confirming it connects without authentication failures.


## JVM Tuning

For most small to medium deployments, the default JVM settings are sufficient. If you notice frequent garbage collection pauses under load or if your source modules maintain large in-memory caches, consider explicit memory sizing:

```bash
java -Xms512m -Xmx1024m -jar spaceport.jar --start config.spaceport
```

Add metrics and monitoring to your application before tuning the JVM. Tune based on observed behavior, not assumptions.


## Verifying Your Runtime Environment

Before deploying to production, confirm your environment:

1. Run `java -version` to verify the vendor and version number.
2. (Optional) Run `echo $JAVA_HOME` to confirm it points to the correct JDK installation.
3. Start Spaceport and confirm in the output that it connects to CouchDB successfully.


## Summary

For maximum production stability, choose **Java 11 or 17**. Test newer feature releases locally, but anchor deployments on an LTS version. When running on Java 17 or later, use the `--add-opens` flag to maintain compatibility with Groovy's reflective features. Keep CouchDB versions aligned with your Spaceport release, and verify the complete startup sequence after any infrastructure changes.


## Related Documentation

- [Spaceport CLI](spaceport-cli-overview.md) -- Managing the application lifecycle
- [Source Modules](source-modules-overview.md) -- Building application logic
- [Documents](documents-overview.md) -- Database interactions with CouchDB
- [Stowaway JARs](stowaways-overview.md) -- Loading external library dependencies
