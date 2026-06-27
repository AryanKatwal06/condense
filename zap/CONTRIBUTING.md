# Contributing to Zap (Java Port)

Thank you for your interest in contributing to Zap!

## Prerequisites

- **GraalVM JDK 21** with `native-image` on PATH
- **Maven 3.9+**
- **Git**

## Development Workflow

### Running Tests

```bash
mvn verify
```

All tests must pass before submitting a PR.

### Building the Native Image

```bash
mvn package -Pnative
./target/zap-runner --version
```

The native binary must build without fallback warnings.

## Code Style

- **Java 21** — use modern Java features (records, sealed classes, switch expressions, text blocks)
- Follow existing patterns in the codebase
- No wildcard imports (`import java.util.*` is forbidden)
- All public API must have Javadoc
- Use `org.jboss.logging.Logger` for logging (not SLF4J directly)

## Adding a New Command Filter

> **Note:** See Phase 3 of the implementation plan — not yet implemented.

When Phase 3 begins, adding a new filter will follow this process:

1. Create `src/main/java/com/zapproxy/filters/YourCommandFilter.java` implementing `FilterStrategy`
2. Annotate with `@CommandFilter("your command")`
3. Add fixture files in `src/test/resources/fixtures/your-command/`
4. Write tests using `FixtureTestSupport`
5. Run `mvn verify` to confirm all tests pass

## PR Checklist

Before submitting a pull request, verify:

- [ ] `mvn verify` passes with zero test failures
- [ ] `mvn package -Pnative -DskipTests` builds without fallback warning
- [ ] No new reflection added without updating `reflect-config.json`
- [ ] All new public API has Javadoc
- [ ] No wildcard imports
- [ ] No hardcoded version strings in Java source
