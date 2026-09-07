#!/usr/bin/env bash
# Installed by: condense init
# Tool: Windsurf
# Do not edit manually — run `condense init -g` to reinstall
#
# This hook intercepts shell commands before Windsurf executes them.
# Commands matching CONDENSE_COMMANDS are routed through `condense` for output compression.

CONDENSE_COMMANDS="{{CONDENSE_COMMANDS}}"

PY_BIN="$(command -v python3 2>/dev/null || command -v python 2>/dev/null || echo python3)"

"$PY_BIN" -c '
import sys, json

def extract_shell_command(root):
    if not isinstance(root, dict):
        return None
    cmd = root.get("tool_info", {}).get("command")
    if cmd: return cmd
    cmd = root.get("command")
    if cmd: return cmd
    cmd = root.get("tool_input", {}).get("command")
    if cmd: return cmd
    return None

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

command = extract_shell_command(data)

if not command or not isinstance(command, str):
    sys.exit(0)

command = command.strip()
if not command:
    sys.exit(0)

condense_commands = set("'"$CONDENSE_COMMANDS"'".split())

def analyze_command(cmd, condense_set):
    chars = []
    for c in cmd:
        if c in ("\n", "\r"):
            chars.append(c)
        elif c.isspace() or c in ("\u00a0", "\u2007", "\u202f", "\ufeff"):
            chars.append(" ")
        else:
            chars.append(c)
    sanitized = "".join(chars)

    segments = []
    curr = []
    in_single = False
    in_double = False
    escaped = False
    i = 0
    length = len(sanitized)

    while i < length:
        c = sanitized[i]
        if escaped:
            curr.append(c)
            escaped = False
            i += 1
            continue
        if c == "\\" and not in_single:
            escaped = True
            curr.append(c)
            i += 1
            continue
        if c == "\x27" and not in_double:
            in_single = not in_single
            curr.append(c)
            i += 1
            continue
        if c == "\"" and not in_single:
            in_double = not in_double
            curr.append(c)
            i += 1
            continue

        if not in_single and not in_double:
            if c == "<" and i + 1 < length and sanitized[i+1] == "<":
                return True, ["heredoc"]
            if c in (";", "\n", "\r"):
                seg = "".join(curr).strip()
                if seg: segments.append(seg)
                curr = []
                if c == "\r" and i + 1 < length and sanitized[i+1] == "\n":
                    i += 1
                i += 1
                continue
            if c == "&":
                seg = "".join(curr).strip()
                if seg: segments.append(seg)
                curr = []
                if i + 1 < length and sanitized[i+1] == "&":
                    i += 1
                i += 1
                continue
            if c == "|":
                seg = "".join(curr).strip()
                if seg: segments.append(seg)
                curr = []
                if i + 1 < length and sanitized[i+1] in ("|", "&"):
                    i += 1
                i += 1
                continue

        curr.append(c)
        i += 1

    if escaped or in_single or in_double:
        return True, ["unclosed quote or escape"]

    seg = "".join(curr).strip()
    if seg: segments.append(seg)

    if not segments:
        return False, []

    matched = []
    has_ambiguity = False

    def normalize_name(raw):
        if not raw: return ""
        s = raw.strip()
        if (s.startswith("\"") and s.endswith("\"") and len(s) >= 2) or (s.startswith("\x27") and s.endswith("\x27") and len(s) >= 2):
            s = s[1:-1]
        s = s.replace("\\", "/")
        if "/" in s: s = s.split("/")[-1]
        if s.startswith("\\"): s = s[1:]
        s = s.strip().lower()
        for ext in (".exe", ".cmd", ".bat"):
            if s.endswith(ext):
                s = s[:-len(ext)]
                break
        return s

    for s in segments:
        if "`" in s or "$(" in s:
            words = [normalize_name(w) for w in s.replace("(", " ").replace(")", " ").replace("`", " ").replace("$", " ").split()]
            for w in words:
                if w in condense_set:
                    matched.append(w)
            has_ambiguity = True
            continue

        words = s.split()
        idx = 0
        while idx < len(words):
            w = words[idx]
            if w in ("<", ">", ">>", "2>", "1>", "2>&1", "&>") or w.startswith(("<", ">", "2>", "1>", "&>")):
                idx += 1
                if w in ("<", ">", ">>", "2>", "1>", "2>&1", "&>") and idx < len(words):
                    idx += 1
                continue
            if "=" in w and not w.startswith("="):
                idx += 1
                continue
            break

        while idx < len(words):
            base = normalize_name(words[idx])
            if base in ("eval", "exec", "source", "."):
                has_ambiguity = True
                break
            if base in ("sudo", "env", "nohup", "time", "command", "builtin"):
                idx += 1
                while idx < len(words):
                    opt = words[idx]
                    if opt in ("<", ">", ">>", "2>", "1>", "2>&1", "&>") or opt.startswith(("<", ">", "2>", "1>", "&>")):
                        idx += 1
                        if opt in ("<", ">", ">>", "2>", "1>", "2>&1", "&>") and idx < len(words):
                            idx += 1
                    elif opt.startswith("-"):
                        idx += 1
                        if opt in ("-u", "-C", "-g") and idx < len(words) and not words[idx].startswith("-"):
                            idx += 1
                    elif "=" in opt and not opt.startswith("="):
                        idx += 1
                    else:
                        break
            else:
                break

        while idx < len(words):
            w = words[idx]
            if w in ("<", ">", ">>", "2>", "1>", "2>&1", "&>") or w.startswith(("<", ">", "2>", "1>", "&>")):
                idx += 1
                if w in ("<", ">", ">>", "2>", "1>", "2>&1", "&>") and idx < len(words):
                    idx += 1
                continue
            break

        if idx < len(words):
            cmd_name = normalize_name(words[idx])
            if cmd_name in condense_set:
                matched.append(cmd_name)

    if matched:
        return True, matched
    if has_ambiguity:
        return True, ["ambiguous syntax"]
    return False, []

deny, matched_items = analyze_command(command, condense_commands)

if deny:
    print("Use \"condense <command>\" instead to get filtered, token-efficient output.")
    sys.exit(2)
else:
    sys.exit(0)
'
