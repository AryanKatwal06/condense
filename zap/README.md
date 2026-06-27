# Zap (Java + GraalVM Port)

Java + GraalVM Native Image port of [bitan-del/zap](https://github.com/bitan-del/zap) — a high-performance CLI proxy that filters command output to save 60–90% of AI tokens.

## Prerequisites

- **GraalVM JDK 21** with `native-image` on PATH (`GRAALVM_HOME` must be set)
- **Maven 3.9+**
- **Git**

## Build

### JVM mode (development)

```bash
mvn verify
```

### Native binary

```bash
mvn package -Pnative
./target/zap-runner --version
```

## Usage

```bash
zap --help          # Show help and available commands
zap --version       # Print version information
zap git status      # Filtered git status (Phase 2+)
zap gain            # Token savings report (Phase 4+)
```

## Project Structure

```
com.zapproxy/
├── ZapMain.java                  — QuarkusApplication entry point
├── ZapRootCommand.java           — picocli @Command root
├── VersionProvider.java          — reads version from version.properties
├── commands/                     — picocli subcommands (Phase 2+)
├── core/
│   ├── ConfigLoader.java         — loads config.toml via Jackson TOML
│   ├── ZapConfig.java            — root config record
│   ├── TeeMode.java              — enum: FAILURES | ALWAYS | NEVER
│   ├── PlatformDirs.java         — OS-specific path resolution
│   └── TrackingRepository.java   — SQLite analytics storage
├── filters/                      — command filter strategies (Phase 3+)
├── hooks/                        — AI tool hook installer (Phase 4+)
└── analytics/                    — gain command analytics (Phase 4+)
```

## Phase Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Foundation: skeleton, build pipeline, GraalVM baseline | ✅ Complete |
| 2 | Command Execution Engine and Filter Architecture | ⬜ Pending |
| 3 | All 42 Command Filter Modules | ⬜ Pending |
| 4 | Analytics Engine, Hook Installer, Configuration Polish | ⬜ Pending |
| 5 | Packaging, Cross-Platform Builds, and Release | ⬜ Pending |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines, how to run tests, and how to add new command filters.

## License

See the original [bitan-del/zap](https://github.com/bitan-del/zap) for license information.
