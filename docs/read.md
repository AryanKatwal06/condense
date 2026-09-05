# Source-file reading

`condense read` opens a file with the JDK and returns a token-thrifty view. It is not a command-output filter. `condense cat FILE` still runs the `cat` child and compresses its stdout.

```
condense read Src.java
condense read --level outline --format json App.ts
condense read --stdin --lang java
```

Options must come before `FILE` (the root parser stops at the first positional).

## Levels

| Level | Flag | Behavior |
|---|---|---|
| verbatim | `--level verbatim` | File text as UTF-8. Impersonating provenance lines are quoted. No line-number prefixes. |
| comments | `--level comments` (default) | Strip comments. Remaining lines keep **original** source numbers. |
| outline | `--level outline` or `-u` | Keep declaration-like lines after comment-strip. Same original numbers. |

Unknown extensions use verbatim and print `condense read: unknown language, using verbatim` on stderr. They never default to C-style comments.

JSON is a `data` language. `--level comments` does not invent comment syntax. `--level outline` uses the existing JSON skeleton and does not invent line numbers.

If outline would be empty, the command falls back to comments. If a non-empty file is only comments, the output is `condense[read]` plus `condense[read]: no remaining source after comment-strip`.

## Line numbers

Comments and outline lines look like `  12| public class Foo {`. The number is the original file line, not the output row.

## Path safety

The workspace root is the nearest `.git` ancestor (bounded walk, no symlink follow) or the current directory. `--root DIR` may only **narrow** that root. The file must canonicalize inside the root, be a regular file, stay under the byte cap, and contain no `NUL` in the first 8 KiB.

Default cap is 1 MiB. `--max-bytes` cannot exceed 10 MiB. Oversize and binary files exit 1 with empty stdout. There is no child process, so fail-closed is correct.

`--stdin` requires `--lang`. Size and binary checks still apply.

## Languages

Builtin rules live in `classpath:languages/*.toml`, enumerated by `languages/index.toml`. Runtime never walks the directory. Maven `process-classes` runs `LanguageDefinitionValidator` so `mvn package -Pnative -DskipTests` still fails on a broken language file.

A language file cannot name a Java class. `family` is a hardcoded switch (`c_like`, `hash`, `xml`, `css`, `sql`, `data`, `markdown`, `powershell`). Comment starters fire only outside strings. JSON cannot declare comment syntax.

Project `.condense/languages.toml` is not loaded. A cloned repo cannot change how files are interpreted.

## Provenance and analytics

Comments and outline output starts with `condense[read]`. A source line that is exactly `condense[read]` or `condense[filtered]` becomes `condense[quoted]`. Verbatim is unstamped except for that quoting.

A successful read inserts a `commands` row (`read --level … path`). Persistence failure does not change the printed output or the exit code.

## Native proof

`NativeReadIT` runs through the shipped binary with isolated config/data dirs. It checks the Zap-style `src/**/*` fixture, JSON `"packages/*"`, unknown-extension verbatim, path escape, and a `gain` row.
