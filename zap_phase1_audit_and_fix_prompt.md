# Zap Java Port — Phase 1 Audit, Fix & Polish Prompt

> You are a senior Java engineer conducting a mandatory pre-Phase-2 audit of the Zap Java + GraalVM Native Image project. Your job is twofold: (1) audit every deliverable against the Phase 1 specification and (2) implement everything that is missing, incomplete, or substandard so that the repo scores 10/10 on every criterion before Phase 2 begins. Do not proceed to Phase 2 logic. Do not skip deliverables. Work through this document top to bottom, in order.

---

## Context: What Phase 1 Was Supposed to Produce

Phase 1 of the Zap Java port must produce a GraalVM native binary that:
- Starts cold in under 100 ms on Linux
- Responds correctly to `--version` (prints `zap 0.1.0`) and `--help`
- Has a working config loading system that returns defaults when no config file exists
- Has a working SQLite schema that is created on first run
- Has correct platform directory resolution for Linux (XDG), macOS, and Windows
- Passes all unit tests (`mvn verify` zero failures)
- Compiles to native image without fallback (`mvn package -Pnative` passes)
- Has a clean, professional repository structure

The tech stack is: **Java 21, Quarkus 3.x, picocli (via quarkus-picocli extension), sqlite-jdbc 3.45.x (Xerial), jackson-dataformat-toml 2.17.x, AssertJ 3.26.x, JUnit 5.**

---

## Audit Checklist — Work Through Every Item

For each item below: check if it exists and is correct. If it does not exist or is wrong, implement it fully and correctly. Do not leave any item as "partially done."

---

### SECTION 1 — Repository Root Files

**1.1 `pom.xml`**

Verify the following are all true. Fix anything that is not:

- `groupId` is `com.zapproxy`, `artifactId` is `zap`, `version` is `0.1.0`
- `<java.version>21</java.version>` property is set and used in `maven-compiler-plugin`
- `quarkus.platform.version` is `3.11.0` exactly (do not use latest floating version)
- The BOM import is present: `io.quarkus.platform:quarkus-bom:3.11.0` as `pom` scope `import` in `dependencyManagement`
- The following extensions/dependencies are present with **exact versions pinned**:
  - `io.quarkus:quarkus-picocli` (version managed by BOM — no explicit version needed)
  - `io.quarkus:quarkus-jackson` (version managed by BOM)
  - `org.xerial:sqlite-jdbc:3.45.3.0` (explicit version — NOT managed by BOM)
  - `com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.17.1` (explicit)
  - `io.quarkus:quarkus-junit5` scope `test` (version managed by BOM)
  - `org.assertj:assertj-core:3.26.0` scope `test` (explicit)
- The `quarkus-maven-plugin` is present in `<build><plugins>`
- A `native` profile exists that sets `<quarkus.native.enabled>true</quarkus.native.enabled>`
- There are NO `<version>` tags on quarkus-managed dependencies (they inherit from the BOM)
- The `maven-compiler-plugin` has `<release>21</release>` set (not `<source>` and `<target>`)
- There are no snapshot dependencies
- There are no `LATEST` or `RELEASE` version strings

If any of the above is missing or wrong, fix the `pom.xml` to match exactly. Show the full corrected `pom.xml`.

---

**1.2 `.gitignore`**

Check that `.gitignore` contains ALL of the following entries. Add any that are missing:

```
target/
*.db
*.db-journal
native-image-agent-output/
.idea/
*.iml
.DS_Store
*.class
*.jar
!*-sources.jar
.quarkus/
```

---

**1.3 `README.md`**

The README must be professional and complete for a 10/10 repo. It must contain:

- Project name and one-line description: "Java + GraalVM Native Image port of bitan-del/zap — a high-performance CLI proxy that filters command output to save 60–90% of AI tokens."
- A prerequisites section listing: GraalVM JDK 21 (with `native-image` on PATH), Maven 3.9+, Git
- A "Build" section with two subsections:
  - "JVM mode (development)": `mvn verify`
  - "Native binary": `mvn package -Pnative` then `./target/zap-runner --version`
- A "Usage" section with: `zap --help`, `zap --version`
- A "Project Structure" section with the package tree
- A "Phase Status" section showing Phase 1 as complete and Phases 2–5 as pending
- A "Contributing" section pointing to the CONTRIBUTING.md
- A link to the original Rust implementation

If the README is missing, sparse, or unprofessional, rewrite it fully.

---

**1.4 `CONTRIBUTING.md`**

Must exist at the repo root. Must contain:
- Prerequisites (same as README)
- How to run tests: `mvn verify`
- How to build native image: `mvn package -Pnative`
- Code style: "Java 21, follow existing patterns, no wildcard imports, all public API must have Javadoc"
- How to add a new command filter (stub for future phases): "See Phase 3 of the implementation plan — not yet implemented"
- PR checklist: tests pass, native image builds, no new reflection added without updating `reflect-config.json`

---

**1.5 `ARCHITECTURE.md`**

Must exist at the repo root. Must contain a text description and ASCII diagram of the Phase 1 architecture:

```
zap --version / --help
        │
        ▼
  ZapMain.java (QuarkusApplication)
        │
        ▼
  ZapRootCommand (picocli @Command)
        │
        ├── VersionProvider → reads /com/zapproxy/version.properties
        ├── ConfigLoader → reads ~/.config/zap/config.toml (or platform equivalent)
        │     └── ZapConfig (record) + TeeMode (enum)
        ├── PlatformDirs → resolves config/data dirs per OS
        └── TrackingRepository → creates SQLite schema at ~/.local/share/zap/zap.db
```

Must also contain a table of all Phase 1 files and their responsibilities.

---

### SECTION 2 — Source Files

Work through every source file. For each one: check it exists, check it compiles, check it is complete and correct. Implement anything missing.

---

**2.1 `src/main/resources/application.properties`**

Must contain exactly:

```properties
quarkus.log.level=WARN
quarkus.log.console.enable=true
quarkus.log.console.format=%s%n
quarkus.native.resources.includes=**/*.toml,**/*.properties,hooks/**
quarkus.banner.enabled=false
```

`quarkus.banner.enabled=false` is critical — the Quarkus startup banner must not appear in CLI output. Fix it if missing.

---

**2.2 `src/main/resources/com/zapproxy/version.properties`**

Must exist at exactly this classpath path. Must contain:

```properties
version=0.1.0
```

Check: does `src/main/java/com/zapproxy/VersionProvider.java` reference this path as `/com/zapproxy/version.properties`? If the path is wrong in either place, fix both.

---

**2.3 `src/main/java/com/zapproxy/ZapMain.java`**

Must exist and must be correct. The correct implementation is:

```java
package com.zapproxy;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

@QuarkusMain
public class ZapMain implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Override
    public int run(String... args) {
        return new CommandLine(new ZapRootCommand(), factory)
            .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                cmd.getErr().println("zap: error: " + ex.getMessage());
                return 1;
            })
            .setCaseInsensitiveEnumValuesAllowed(true)
            .execute(args);
    }
}
```

Verify: `@QuarkusMain` annotation is present. `CommandLine.IFactory` is injected (not newed up). `setCaseInsensitiveEnumValuesAllowed(true)` is set. If any of these are missing, fix the file.

---

**2.4 `src/main/java/com/zapproxy/ZapRootCommand.java`**

Must exist and must be correct. The correct implementation is:

```java
package com.zapproxy;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "zap",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    description = {
        "High-performance CLI proxy that filters command output to save 60-90%% AI tokens.",
        "",
        "Zap sits between your AI coding assistant and the shell, filtering noisy",
        "command output so the AI receives a compact, dense summary instead of",
        "thousands of raw lines.",
        "",
        "Run 'zap gain' to see how many tokens you have saved."
    },
    synopsisHeading = "%nUsage: ",
    descriptionHeading = "%nDescription:%n",
    optionListHeading = "%nOptions:%n",
    commandListHeading = "%nCommands:%n",
    footer = {
        "",
        "Examples:",
        "  zap git status          # Filtered git status",
        "  zap cargo test          # Test failures only",
        "  zap pytest              # Failures + summary line",
        "  zap gain                # Token savings report",
        "  zap init -g             # Install AI tool hooks"
    }
)
public class ZapRootCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(
        names = {"-v", "--verbose"},
        description = "Increase verbosity. Use -v, -vv, or -vvv."
    )
    boolean[] verbose = new boolean[0];

    @Option(
        names = {"-u", "--ultra-compact"},
        description = "Maximum compression: ASCII icons, inline format."
    )
    boolean ultraCompact;

    @Override
    public void run() {
        // No subcommand given — print help.
        spec.commandLine().usage(System.out);
    }

    /** Returns verbosity level: 0 (silent), 1, 2, or 3 (most verbose). */
    public int verbosityLevel() {
        return verbose == null ? 0 : Math.min(verbose.length, 3);
    }
}
```

Verify: `@Spec` field is present. `verbosityLevel()` method exists. Footer with examples exists. `description` array uses `%%` for literal `%`. Fix if anything is missing.

---

**2.5 `src/main/java/com/zapproxy/VersionProvider.java`**

Must exist. The correct implementation is:

```java
package com.zapproxy;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the application version from {@code /com/zapproxy/version.properties}
 * bundled in the JAR/native image. Never hardcodes the version string.
 */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/zapproxy/version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Fall through to default
        }
        String version = props.getProperty("version", "unknown");
        return new String[]{
            "zap " + version,
            "Java " + System.getProperty("java.version"),
            "Built with GraalVM Native Image"
        };
    }
}
```

Note: returns three lines — version, Java version, build method. This is more informative than a single line and makes it easy to verify native vs JVM mode.

---

**2.6 `src/main/java/com/zapproxy/core/PlatformDirs.java`**

Must exist and be correct. Verify the following:
- Class is `@ApplicationScoped`
- `getConfigDir()` uses `XDG_CONFIG_HOME` on Linux, `~/Library/Application Support/zap` on macOS, `%APPDATA%\zap` on Windows, falls back to `~/.config/zap`
- `getDataDir()` uses `XDG_DATA_HOME` on Linux, `~/Library/Application Support/zap` on macOS, `%APPDATA%\zap` on Windows, falls back to `~/.local/share/zap`
- `getConfigFile()` returns `getConfigDir().resolve("config.toml")`
- `getDatabaseFile()` returns `getDataDir().resolve("zap.db")`
- All directories are created with `Files.createDirectories()` in a try/catch that logs a WARN and does NOT throw
- OS detection uses `System.getProperty("os.name").toLowerCase()` with `.contains("mac")` and `.contains("win")`

The correct implementation is:

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves platform-appropriate configuration and data directories.
 *
 * <ul>
 *   <li>Linux: XDG_CONFIG_HOME/zap (config), XDG_DATA_HOME/zap (data),
 *       falling back to ~/.config/zap and ~/.local/share/zap</li>
 *   <li>macOS: ~/Library/Application Support/zap (both)</li>
 *   <li>Windows: %APPDATA%\zap (both)</li>
 * </ul>
 */
@ApplicationScoped
public class PlatformDirs {

    private static final Logger log = Logger.getLogger(PlatformDirs.class);

    /** Config directory. Created on first access if it does not exist. */
    public Path getConfigDir() {
        return ensureDir(resolveConfigBase());
    }

    /** Data directory. Created on first access if it does not exist. */
    public Path getDataDir() {
        return ensureDir(resolveDataBase());
    }

    /** Path to config.toml inside the config directory. */
    public Path getConfigFile() {
        return getConfigDir().resolve("config.toml");
    }

    /** Path to zap.db inside the data directory. */
    public Path getDatabaseFile() {
        return getDataDir().resolve("zap.db");
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Path resolveConfigBase() {
        String os = os();
        if (os.contains("mac")) {
            return home("Library", "Application Support", "zap");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null
                ? Path.of(appData, "zap")
                : home("AppData", "Roaming", "zap");
        }
        // Linux / Unix
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank())
            ? Path.of(xdg, "zap")
            : home(".config", "zap");
    }

    private Path resolveDataBase() {
        String os = os();
        if (os.contains("mac")) {
            return home("Library", "Application Support", "zap");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null
                ? Path.of(appData, "zap")
                : home("AppData", "Roaming", "zap");
        }
        // Linux / Unix
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg != null && !xdg.isBlank())
            ? Path.of(xdg, "zap")
            : home(".local", "share", "zap");
    }

    private static Path home(String... parts) {
        return Path.of(System.getProperty("user.home"), parts);
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase();
    }

    private Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warnf("Could not create directory %s: %s", dir, e.getMessage());
        }
        return dir;
    }
}
```

---

**2.7 `src/main/java/com/zapproxy/core/TeeMode.java`**

Must exist. The correct implementation is:

```java
package com.zapproxy.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Controls when Zap saves raw command output to a tee file.
 *
 * <ul>
 *   <li>{@link #FAILURES} — save only when the command exits non-zero (default)</li>
 *   <li>{@link #ALWAYS} — always save raw output</li>
 *   <li>{@link #NEVER} — never save raw output</li>
 * </ul>
 */
public enum TeeMode {

    FAILURES,
    ALWAYS,
    NEVER;

    @JsonCreator
    public static TeeMode fromString(String value) {
        if (value == null || value.isBlank()) return FAILURES;
        return switch (value.trim().toUpperCase()) {
            case "ALWAYS" -> ALWAYS;
            case "NEVER"  -> NEVER;
            default       -> FAILURES;
        };
    }

    @JsonValue
    public String toValue() {
        return name().toLowerCase();
    }
}
```

---

**2.8 `src/main/java/com/zapproxy/core/ZapConfig.java`**

Must exist. The correct implementation is:

```java
package com.zapproxy.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root configuration record for Zap.
 *
 * <p>Loaded from {@code ~/.config/zap/config.toml} (Linux),
 * {@code ~/Library/Application Support/zap/config.toml} (macOS), or
 * {@code %APPDATA%\zap\config.toml} (Windows).
 *
 * <p>Example config.toml:
 * <pre>
 * [hooks]
 * exclude_commands = ["curl", "playwright"]
 *
 * [tee]
 * enabled = true
 * mode = "failures"
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ZapConfig(

    @JsonProperty("hooks")
    HooksConfig hooks,

    @JsonProperty("tee")
    TeeConfig tee

) {

    /** Returns a config with sensible production defaults. */
    public static ZapConfig defaults() {
        return new ZapConfig(
            new HooksConfig(List.of()),
            new TeeConfig(true, TeeMode.FAILURES)
        );
    }

    /**
     * Configuration for the hook installer.
     *
     * @param excludeCommands commands that should NOT be rewritten through zap,
     *                        even when the hook is active (e.g. "curl", "playwright")
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HooksConfig(

        @JsonProperty("exclude_commands")
        List<String> excludeCommands

    ) {
        /** No-arg constructor for Jackson deserialization. */
        public HooksConfig() {
            this(List.of());
        }

        @Override
        public List<String> excludeCommands() {
            return excludeCommands != null ? excludeCommands : List.of();
        }
    }

    /**
     * Configuration for the tee (raw output dump) system.
     *
     * @param enabled whether the tee system is active at all
     * @param mode    when to save raw output ({@link TeeMode})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeeConfig(

        @JsonProperty("enabled")
        boolean enabled,

        @JsonProperty("mode")
        TeeMode mode

    ) {
        /** No-arg constructor for Jackson deserialization. */
        public TeeConfig() {
            this(true, TeeMode.FAILURES);
        }

        @Override
        public TeeMode mode() {
            return mode != null ? mode : TeeMode.FAILURES;
        }
    }
}
```

---

**2.9 `src/main/java/com/zapproxy/core/ConfigLoader.java`**

Must exist. The correct implementation is:

```java
package com.zapproxy.core;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link ZapConfig} from the platform-specific config file.
 *
 * <p>If the config file does not exist, {@link ZapConfig#defaults()} is returned.
 * If the config file exists but cannot be parsed, a warning is logged and defaults
 * are returned. The config is cached after the first load.
 */
@ApplicationScoped
public class ConfigLoader {

    private static final Logger log = Logger.getLogger(ConfigLoader.class);
    private static final TomlMapper TOML = new TomlMapper();

    @Inject
    PlatformDirs platformDirs;

    private volatile ZapConfig cached;

    /**
     * Loads and returns the config. Cached after first call.
     * Thread-safe via double-checked locking.
     */
    public ZapConfig load() {
        if (cached != null) return cached;
        synchronized (this) {
            if (cached != null) return cached;
            cached = loadFromDisk();
        }
        return cached;
    }

    /** Clears the config cache. Used in tests. */
    void invalidateCache() {
        synchronized (this) {
            cached = null;
        }
    }

    private ZapConfig loadFromDisk() {
        Path configFile = platformDirs.getConfigFile();
        if (!Files.exists(configFile)) {
            log.debugf("No config file at %s; using defaults", configFile);
            return ZapConfig.defaults();
        }
        try {
            ZapConfig loaded = TOML.readValue(configFile.toFile(), ZapConfig.class);
            log.debugf("Loaded config from %s", configFile);
            return loaded != null ? loaded : ZapConfig.defaults();
        } catch (IOException e) {
            log.warnf("Failed to parse config at %s: %s — using defaults", configFile, e.getMessage());
            return ZapConfig.defaults();
        }
    }
}
```

---

**2.10 `src/main/java/com/zapproxy/core/TrackingRepository.java`**

Must exist with correct implementation. Verify:
- `@ApplicationScoped` annotation is present
- Connection is established **lazily** (not in constructor or `@PostConstruct`)
- Schema `CREATE TABLE IF NOT EXISTS` runs on first connection
- Both indexes are created
- `insert()` fails silently on SQL error (logs WARN, does not throw)
- `countAll()` exists for testing
- `@PreDestroy` closes the connection

The correct implementation:

```java
package com.zapproxy.core;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Persists command execution records to a local SQLite database for
 * analytics reporting via {@code zap gain}.
 *
 * <p>The database is created automatically on first use at the path
 * returned by {@link PlatformDirs#getDatabaseFile()}. Analytics failures
 * are non-fatal — they log a warning and never propagate to the caller.
 */
@ApplicationScoped
public class TrackingRepository {

    private static final Logger log = Logger.getLogger(TrackingRepository.class);

    private static final String CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS commands (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            ts         INTEGER NOT NULL,
            command    TEXT    NOT NULL,
            project    TEXT,
            cwd        TEXT,
            raw_tokens INTEGER NOT NULL,
            out_tokens INTEGER NOT NULL,
            exec_ms    INTEGER NOT NULL
        )
        """;

    private static final String CREATE_IDX_TS =
        "CREATE INDEX IF NOT EXISTS idx_commands_ts ON commands(ts)";

    private static final String CREATE_IDX_PROJECT =
        "CREATE INDEX IF NOT EXISTS idx_commands_project ON commands(project)";

    private static final String INSERT = """
        INSERT INTO commands(ts, command, project, cwd, raw_tokens, out_tokens, exec_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    @Inject
    PlatformDirs platformDirs;

    private Connection connection;

    /**
     * Records a command execution.
     *
     * @param command    full command string, e.g. "git status"
     * @param project    12-char hex fingerprint of the project directory
     * @param cwd        absolute path of the working directory
     * @param rawTokens  estimated token count of the raw output
     * @param outTokens  estimated token count of the filtered output
     * @param execMs     wall-clock execution time in milliseconds
     */
    public void insert(String command, String project, String cwd,
                       int rawTokens, int outTokens, long execMs) {
        try {
            try (PreparedStatement ps = connection().prepareStatement(INSERT)) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, command);
                ps.setString(3, project);
                ps.setString(4, cwd);
                ps.setInt(5, rawTokens);
                ps.setInt(6, outTokens);
                ps.setLong(7, execMs);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warnf("Failed to record analytics for '%s': %s", command, e.getMessage());
        }
    }

    /**
     * Returns the total number of recorded commands.
     * Used in tests and by {@code zap gain}.
     */
    public long countAll() {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM commands")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.warnf("Failed to count commands: %s", e.getMessage());
            return 0L;
        }
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debugf("SQLite connection closed");
            } catch (SQLException e) {
                log.warnf("Failed to close SQLite connection: %s", e.getMessage());
            }
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + platformDirs.getDatabaseFile().toAbsolutePath();
            connection = DriverManager.getConnection(url);
            initSchema();
        }
        return connection;
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(CREATE_TABLE);
            st.executeUpdate(CREATE_IDX_TS);
            st.executeUpdate(CREATE_IDX_PROJECT);
        }
    }
}
```

---

### SECTION 3 — GraalVM Native Image Configuration

**3.1 `src/main/resources/META-INF/native-image/native-image.properties`**

Must exist with:

```properties
Args = --no-fallback \
       -H:+ReportExceptionStackTraces \
       --initialize-at-build-time=org.slf4j \
       --initialize-at-build-time=org.jboss.logging
```

---

**3.2 `src/main/resources/META-INF/native-image/resource-config.json`**

Must exist with:

```json
{
  "resources": {
    "includes": [
      { "pattern": "com/zapproxy/version\\.properties" },
      { "pattern": "filters/.*\\.toml" },
      { "pattern": "hooks/.*" },
      { "pattern": "application\\.properties" }
    ]
  }
}
```

---

**3.3 `src/main/resources/META-INF/native-image/reflect-config.json`**

Must exist. This is the most critical GraalVM config file. It must contain entries for every class that Jackson needs to deserialize via reflection (the ZapConfig records), sqlite-jdbc JNI classes, and picocli internal classes not auto-registered by the Quarkus extension:

```json
[
  {
    "name": "com.zapproxy.core.ZapConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.ZapConfig$HooksConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.ZapConfig$TeeConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.TeeMode",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.VersionProvider",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "org.sqlite.JDBC",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.core.DB",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.core.NativeDB",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$Pragma",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$TransactionMode",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$DateClass",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$DatePrecision",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$JournalMode",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$SynchronousMode",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$TempStore",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.SQLiteConfig$LockingMode",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

---

**3.4 `src/main/resources/META-INF/native-image/jni-config.json`**

sqlite-jdbc uses JNI to load its native SQLite library. In native image, this requires explicit JNI configuration:

```json
[
  {
    "name": "org.sqlite.core.NativeDB",
    "methods": [
      { "name": "_open_utf8", "parameterTypes": ["byte[]", "int"] },
      { "name": "close" },
      { "name": "interrupt" },
      { "name": "busy_timeout", "parameterTypes": ["int"] },
      { "name": "exec_utf8", "parameterTypes": ["byte[]"] },
      { "name": "changes" },
      { "name": "total_changes" }
    ]
  }
]
```

**Important note**: If the native build fails with `UnsatisfiedLinkError` on SQLite JNI methods, run the native image agent to regenerate the complete JNI config:

```bash
mvn package -DskipTests
java -agentlib:native-image-agent=config-output-dir=native-agent-output \
     -jar target/quarkus-app/quarkus-run.jar --version
# Then merge native-agent-output/jni-config.json into the one above
```

---

### SECTION 4 — Test Files

**4.1 Verify test infrastructure**

Check that `src/test/java/` exists and contains the following test classes. Implement any that are missing:

---

**`PlatformDirsTest.java`**

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PlatformDirsTest {

    @Inject
    PlatformDirs dirs;

    @Test
    void configDirIsCreatedAndIsADirectory() {
        var dir = dirs.getConfigDir();
        assertThat(dir).isNotNull();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void dataDirIsCreatedAndIsADirectory() {
        var dir = dirs.getDataDir();
        assertThat(dir).isNotNull();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void configFileHasCorrectFileName() {
        assertThat(dirs.getConfigFile().getFileName().toString()).isEqualTo("config.toml");
    }

    @Test
    void databaseFileHasCorrectFileName() {
        assertThat(dirs.getDatabaseFile().getFileName().toString()).isEqualTo("zap.db");
    }

    @Test
    void configFileIsInsideConfigDir() {
        assertThat(dirs.getConfigFile()).startsWith(dirs.getConfigDir());
    }

    @Test
    void databaseFileIsInsideDataDir() {
        assertThat(dirs.getDatabaseFile()).startsWith(dirs.getDataDir());
    }
}
```

---

**`ConfigLoaderTest.java`**

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ConfigLoaderTest {

    @Inject
    ConfigLoader loader;

    @Test
    void returnsDefaultsWhenNoConfigFileExists() {
        // In the test environment no config file should exist yet.
        // Even if it does, defaults must not be null.
        var config = loader.load();
        assertThat(config).isNotNull();
        assertThat(config.tee()).isNotNull();
        assertThat(config.tee().enabled()).isTrue();
        assertThat(config.tee().mode()).isEqualTo(TeeMode.FAILURES);
        assertThat(config.hooks()).isNotNull();
        assertThat(config.hooks().excludeCommands()).isNotNull();
    }

    @Test
    void defaultTeeConfigHasCorrectValues() {
        var tee = ZapConfig.defaults().tee();
        assertThat(tee.enabled()).isTrue();
        assertThat(tee.mode()).isEqualTo(TeeMode.FAILURES);
    }

    @Test
    void defaultHooksConfigHasEmptyExcludeList() {
        var hooks = ZapConfig.defaults().hooks();
        assertThat(hooks.excludeCommands()).isEmpty();
    }

    @Test
    void parsesValidTomlFromString() throws IOException {
        // Write a temp config and re-load
        Path tmp = Files.createTempFile("zap-test-config", ".toml");
        Files.writeString(tmp, """
            [hooks]
            exclude_commands = ["curl", "playwright"]

            [tee]
            enabled = false
            mode = "always"
            """);

        try {
            // Directly parse using Jackson TOML mapper — isolated from CDI
            var mapper = new com.fasterxml.jackson.dataformat.toml.TomlMapper();
            ZapConfig config = mapper.readValue(tmp.toFile(), ZapConfig.class);

            assertThat(config.hooks().excludeCommands())
                .containsExactly("curl", "playwright");
            assertThat(config.tee().enabled()).isFalse();
            assertThat(config.tee().mode()).isEqualTo(TeeMode.ALWAYS);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
```

---

**`TeeModeTest.java`**

```java
package com.zapproxy.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeeModeTest {

    @ParameterizedTest
    @CsvSource({
        "failures, FAILURES",
        "FAILURES, FAILURES",
        "Failures, FAILURES",
        "always,   ALWAYS",
        "ALWAYS,   ALWAYS",
        "never,    NEVER",
        "NEVER,    NEVER",
        "garbage,  FAILURES",
        ",         FAILURES"
    })
    void fromStringIsCaseInsensitiveWithSaneDefault(String input, TeeMode expected) {
        assertThat(TeeMode.fromString(input)).isEqualTo(expected);
    }

    @Test
    void toValueIsLowerCase() {
        assertThat(TeeMode.FAILURES.toValue()).isEqualTo("failures");
        assertThat(TeeMode.ALWAYS.toValue()).isEqualTo("always");
        assertThat(TeeMode.NEVER.toValue()).isEqualTo("never");
    }
}
```

---

**`TrackingRepositoryTest.java`**

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackingRepositoryTest {

    @Inject
    TrackingRepository repo;

    @Test
    @Order(1)
    void schemaIsCreatedOnFirstAccess() {
        // countAll() triggers schema creation
        long count = repo.countAll();
        assertThat(count).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @Order(2)
    void insertIncreasesCount() {
        long before = repo.countAll();
        repo.insert("git status", "abc123def456", "/tmp/project", 500, 100, 42L);
        long after = repo.countAll();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @Order(3)
    void insertWithNullProjectDoesNotThrow() {
        long before = repo.countAll();
        repo.insert("ls -la", null, "/tmp", 200, 50, 5L);
        long after = repo.countAll();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @Order(4)
    void insertNeverThrowsEvenWithInvalidData() {
        // Should log WARN, not throw
        assertThat(() -> repo.insert("", null, null, -1, -1, -1L))
            .doesNotThrowAnyException();
    }
}
```

---

**`VersionProviderTest.java`**

```java
package com.zapproxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionProviderTest {

    private final VersionProvider provider = new VersionProvider();

    @Test
    void returnsAtLeastOneLine() throws Exception {
        assertThat(provider.getVersion()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void firstLineStartsWithZap() throws Exception {
        assertThat(provider.getVersion()[0]).startsWith("zap ");
    }

    @Test
    void versionContainsDotSeparatedNumbers() throws Exception {
        String version = provider.getVersion()[0];
        // e.g. "zap 0.1.0"
        assertThat(version).matches("zap \\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void neverReturnsNullOrEmpty() throws Exception {
        var lines = provider.getVersion();
        assertThat(lines).isNotNull().isNotEmpty();
        for (String line : lines) {
            assertThat(line).isNotNull().isNotBlank();
        }
    }
}
```

---

### SECTION 5 — CI Pipeline

**5.1 `.github/workflows/build.yml`**

Must exist. The correct implementation:

```yaml
name: Build & Test

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  jvm-build:
    name: JVM Build & Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up GraalVM JDK 21
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm-community'
          github-token: ${{ secrets.GITHUB_TOKEN }}

      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Build and test (JVM)
        run: mvn verify --no-transfer-progress

  native-build:
    name: Native Image Build
    runs-on: ubuntu-latest
    needs: jvm-build
    steps:
      - uses: actions/checkout@v4

      - name: Set up GraalVM JDK 21
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm-community'
          native-image: 'true'
          github-token: ${{ secrets.GITHUB_TOKEN }}

      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Build native image
        run: mvn package -Pnative -DskipTests --no-transfer-progress

      - name: Smoke test native binary
        run: |
          ./target/zap-runner --version
          ./target/zap-runner --help

      - name: Benchmark cold start (must be under 1s; target <100ms)
        run: |
          for i in 1 2 3 4 5; do
            time ./target/zap-runner --version
          done

      - name: Upload native binary
        uses: actions/upload-artifact@v4
        with:
          name: zap-linux-x64
          path: target/zap-runner
          retention-days: 7
```

---

### SECTION 6 — Placeholder Stubs for Phase 2 Packages

These stub files must exist so the package structure is complete and `mvn verify` compiles cleanly. They are NOT implemented yet — they exist so IDEs, linters, and future agents understand the intended structure.

**`src/main/java/com/zapproxy/commands/.gitkeep`** — empty file to preserve directory in git

**`src/main/java/com/zapproxy/filters/.gitkeep`** — empty file

**`src/main/java/com/zapproxy/hooks/.gitkeep`** — empty file

**`src/main/java/com/zapproxy/analytics/.gitkeep`** — empty file

**`src/main/resources/filters/.gitkeep`** — empty file

**`src/main/resources/hooks/.gitkeep`** — empty file

**`src/test/resources/fixtures/.gitkeep`** — empty file

---

### SECTION 7 — Final Verification Checklist

After implementing every item above, run the following commands in order. Every single one must pass before Phase 1 is declared complete.

**Step 1: Clean build**
```bash
mvn clean verify
```
Expected output: `BUILD SUCCESS`, zero test failures, all tests from sections 4.1–4.5 shown as passing.

**Step 2: Count test assertions**
```bash
mvn test | grep -E "Tests run:|FAILED|ERROR"
```
Expected: Every test class shows 0 failures, 0 errors. Minimum 20 individual test assertions across all test classes.

**Step 3: Native image build**
```bash
mvn package -Pnative -DskipTests
```
Expected: BUILD SUCCESS. The output must NOT contain the line `Warning: Image 'zap-runner' is a fallback image`. If you see "fallback image", the `--no-fallback` flag is not being applied or a class cannot be resolved — fix the reflection config.

**Step 4: Version smoke test**
```bash
./target/zap-runner --version
```
Expected output (three lines):
```
zap 0.1.0
Java 21.x.x (GraalVM)
Built with GraalVM Native Image
```

**Step 5: Help smoke test**
```bash
./target/zap-runner --help
```
Expected: Full help text with description, options, and the "Examples:" footer block. No Quarkus banner. No stack traces.

**Step 6: Startup time benchmark**
```bash
for i in {1..10}; do { time ./target/zap-runner --version; } 2>&1 | grep real; done
```
Expected: Every run is under 0m0.100s (100 ms). If any run exceeds 100 ms, profile and fix before proceeding.

**Step 7: Verify no Quarkus banner**
```bash
./target/zap-runner --version 2>&1 | grep -i quarkus
```
Expected: No output. If "quarkus" appears anywhere in the output, `quarkus.banner.enabled=false` is not set correctly.

**Step 8: SQLite database creation**
```bash
./target/zap-runner --version
ls -la ~/.local/share/zap/   # Linux
# or: ls -la ~/Library/Application\ Support/zap/   # macOS
```
Expected: `zap.db` exists at the platform data path. (The database is created lazily on first `TrackingRepository` access, which happens during integration tests. If not present, verify the test runs triggered it.)

**Step 9: Repository structure check**
```bash
find src -type f -name "*.java" | sort
find src/main/resources -type f | sort
find .github -type f | sort
```
Expected outputs — verify every file listed below is present:

Java sources:
```
src/main/java/com/zapproxy/ZapMain.java
src/main/java/com/zapproxy/ZapRootCommand.java
src/main/java/com/zapproxy/VersionProvider.java
src/main/java/com/zapproxy/core/ConfigLoader.java
src/main/java/com/zapproxy/core/PlatformDirs.java
src/main/java/com/zapproxy/core/TeeMode.java
src/main/java/com/zapproxy/core/TrackingRepository.java
src/main/java/com/zapproxy/core/ZapConfig.java
```

Test sources:
```
src/test/java/com/zapproxy/VersionProviderTest.java
src/test/java/com/zapproxy/core/ConfigLoaderTest.java
src/test/java/com/zapproxy/core/PlatformDirsTest.java
src/test/java/com/zapproxy/core/TeeModeTest.java
src/test/java/com/zapproxy/core/TrackingRepositoryTest.java
```

Resources:
```
src/main/resources/application.properties
src/main/resources/com/zapproxy/version.properties
src/main/resources/META-INF/native-image/native-image.properties
src/main/resources/META-INF/native-image/reflect-config.json
src/main/resources/META-INF/native-image/resource-config.json
src/main/resources/META-INF/native-image/jni-config.json
```

CI:
```
.github/workflows/build.yml
```

Root files:
```
.gitignore
README.md
CONTRIBUTING.md
ARCHITECTURE.md
pom.xml
```

**Step 10: No hardcoded version strings in Java source**
```bash
grep -r "0\.1\.0" src/main/java/
```
Expected: zero matches. The version string must only appear in `version.properties`.

---

### SECTION 8 — Common Failure Modes and Their Fixes

This section must be consulted before declaring failure. Work through the relevant entry before escalating.

**Failure: `mvn package -Pnative` produces `WARNING: Image 'zap-runner' is a fallback image`**

Root cause: A class cannot be resolved at build time, so GraalVM fell back to JVM mode. The `--no-fallback` flag should have turned this into a hard error — if it's a warning, the flag is not being applied.

Fix: Verify `native-image.properties` contains `Args = --no-fallback`. Verify the file is at exactly `src/main/resources/META-INF/native-image/native-image.properties`. Verify the native profile in `pom.xml` sets `<quarkus.native.enabled>true</quarkus.native.enabled>`.

---

**Failure: `UnsatisfiedLinkError: no sqlite or sqlitejdbc in java.library.path` in native binary**

Root cause: sqlite-jdbc cannot extract its native `.so` file at runtime in native image mode.

Fix: This is the known sqlite-jdbc + GraalVM issue. The solution for Phase 1 is to use sqlite-jdbc version 3.45.3.0 which includes a native library bundled for the target platform. Add the following to the native profile in `pom.xml`:
```xml
<quarkus.native.additional-build-args>
  --initialize-at-run-time=org.sqlite.core.NativeDB
</quarkus.native.additional-build-args>
```
Then run the native-image-agent (instructions in section 3.4) to regenerate the jni-config.json with all actual JNI method signatures. Commit the generated file.

---

**Failure: `ClassNotFoundException: com.zapproxy.core.ZapConfig` during TOML deserialization in native binary**

Root cause: Jackson uses reflection to instantiate the `ZapConfig` record. The class is not in `reflect-config.json`.

Fix: Verify `reflect-config.json` (section 3.3) contains entries for `ZapConfig`, `ZapConfig$HooksConfig`, `ZapConfig$TeeConfig`, and `TeeMode`. If the entries are present and the error still occurs, run the native-image-agent and merge the generated `reflect-config.json` additions.

---

**Failure: Startup time is 300 ms or more**

Root cause: A CDI bean is performing I/O, network access, or expensive computation in its constructor or `@PostConstruct`.

Fix: Check `ConfigLoader` — it must NOT call `load()` in `@PostConstruct`. Check `TrackingRepository` — it must NOT open the SQLite connection in `@PostConstruct`. Check `PlatformDirs` — it must NOT create directories in constructor. All of these must be lazy (on first use). Add `@PostConstruct void init() {}` as a no-op if Quarkus requires it for bean lifecycle, but do not put work there.

---

**Failure: `./target/zap-runner --version` outputs Quarkus banner text**

Root cause: `quarkus.banner.enabled` is not set to `false`.

Fix: Add `quarkus.banner.enabled=false` to `src/main/resources/application.properties`. Rebuild.

---

**Failure: Tests pass on JVM but fail in native image**

Root cause: Native image tests require `@QuarkusIntegrationTest` annotation, not `@QuarkusTest`. Or a resource file is not included in the native image.

Fix: For integration tests against the native binary, use `@QuarkusIntegrationTest`. For unit tests that run on JVM during `mvn verify`, `@QuarkusTest` is correct. Verify all resource includes in `resource-config.json` and `native-image.properties`.

---

### SECTION 9 — Phase 1 Sign-Off Criteria

Phase 1 is complete when ALL of the following are true simultaneously. There are no partial credits.

| # | Criterion | Verification Command |
|---|---|---|
| 1 | `mvn verify` exits 0 | `mvn verify; echo "Exit: $?"` |
| 2 | All 5 test classes pass with 0 failures | `mvn test \| grep "Tests run"` |
| 3 | Minimum 20 test methods exist | `grep -r "@Test" src/test/ \| wc -l` |
| 4 | Native image builds without fallback warning | `mvn package -Pnative -DskipTests 2>&1 \| grep -i fallback` → no output |
| 5 | `./target/zap-runner --version` prints `zap 0.1.0` | Direct check |
| 6 | `./target/zap-runner --help` prints full help with examples | Direct check |
| 7 | Cold start median under 100 ms | Benchmark loop in section 7, Step 6 |
| 8 | No Quarkus banner in output | `./target/zap-runner --version 2>&1 \| grep -i quarkus` → no output |
| 9 | No hardcoded version strings in Java | `grep -r "0\\.1\\.0" src/main/java/` → no output |
| 10 | All files in section 7 Step 9 checklist exist | `find` commands in Step 9 |
| 11 | `.github/workflows/build.yml` exists and is valid YAML | `yamllint .github/workflows/build.yml` |
| 12 | `README.md`, `CONTRIBUTING.md`, `ARCHITECTURE.md` all exist with substantive content | `wc -l README.md CONTRIBUTING.md ARCHITECTURE.md` → each >30 lines |

When all 12 criteria pass, respond with:

```
PHASE 1 COMPLETE
────────────────
Tests:        [N passing / 0 failing]
Native build: [PASS / no fallback]
Cold start:   [Xms median]
Files:        [N source files / N test files / N resource files]

Ready for Phase 2: Command Execution Engine and GitStatusFilter.
```

---

*Audit prompt version 1.0 — generated from the Zap Java Port 5-Phase Implementation Plan.*
