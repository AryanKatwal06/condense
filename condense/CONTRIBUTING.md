# Contributing to Condense

Thank you for your interest in contributing to Condense! This guide explains how to add
new command filters, run tests, and submit pull requests.

## Prerequisites

- GraalVM JDK 21 with `native-image` on PATH
- Maven 3.9+
- Git
- (Linux only for static builds) `musl-tools`: `sudo apt-get install musl-tools`

## Development Setup

```bash
git clone https://github.com/YOUR_ORG/condense.git
cd condense
mvn verify          # builds, tests, confirms everything works
```

## Running Tests

```bash
mvn test                          # unit tests (JVM)
mvn verify                        # full build + test suite
mvn package -Pnative -DskipTests  # native image build (takes 2-5 minutes)
```

## Adding a New Command Filter

Adding support for a new command (e.g. `helm`) takes four steps:

### 1. Create the filter class

```java
// src/main/java/com/condenseproxy/filter/cloud/HelmFilter.java
package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilter("helm")
@ApplicationScoped
public class HelmFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        // Your filtering logic here
        String raw = result.stdout();
        String filtered = /* compress output */;
        return FilterResult.of(raw, filtered);
    }
}
```

**Rules for implementations**:
- Always return `FilterResult.passthrough(result.combined())` on non-zero exit
  (unless your filter specifically handles failures, like test runners)
- Never throw — wrap parsing logic in try/catch and fall back to passthrough
- Never modify the exit code — that's the caller's job
- Keep the class stateless — one instance is reused for all invocations

### 2. Create fixture files

```
src/test/resources/fixtures/helm/typical.txt   — real helm output (copy from terminal)
src/test/resources/fixtures/helm/failure.txt   — failed command output
```

### 3. Write tests

```java
// src/test/java/com/condenseproxy/filter/cloud/HelmFilterTest.java
package com.condense.filter.cloud;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HelmFilterTest extends FilterTestSupport {

    private HelmFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new HelmFilter(); config = CondenseConfig.defaults(); }

    @Test
    void typicalOutput_isCompressed() throws Exception {
        FilterResult r = filter.apply("helm",
            success(fixture("helm", "typical")), config, 0, false);
        assertCompressed(r);
    }

    @Test
    void failureOutput_isPassedThrough() {
        FilterResult r = filter.apply("helm",
            failure(1, "Error: no releases found"), config, 0, false);
        assertPassthrough(r);
    }
}
```

### 4. Add to reflect-config.json

Add this entry to `src/main/resources/META-INF/native-image/reflect-config.json`:
```json
{ "name": "com.condense.filter.cloud.HelmFilter",
  "allDeclaredConstructors": true, "allDeclaredMethods": true }
```

### 5. Verify and submit

```bash
mvn verify                            # all tests must pass
mvn package -Pnative -DskipTests      # native image must build
./target/condense-runner helm list         # smoke test with a real helm install
```

Then open a pull request. The PR template will ask you to confirm:
- [ ] Filter class implemented with `@CommandFilter` and `@ApplicationScoped`
- [ ] Fixture files created with real command output
- [ ] Tests written covering typical + failure cases
- [ ] reflect-config.json updated
- [ ] `mvn verify` passes
- [ ] Native image builds without fallback

## Code Style

- Java 21, no wildcard imports
- All public methods have Javadoc
- Records for data carriers (`ExecutionResult`, `FilterResult`, etc.)
- Try-with-resources for all SQL and I/O
- `@ApplicationScoped` for CDI beans, never `@Singleton`

## Pull Request Process

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/helm-filter`
3. Implement, test, and verify (see above)
4. Open a PR against `main`
5. CI must be green (JVM tests + native image build)
6. One approving review required

## Reporting Issues

Use GitHub Issues for:
- Bug reports (include `condense --version` output and steps to reproduce)
- Feature requests (new command filters, analytics features)

For security issues, see [SECURITY.md](SECURITY.md).
