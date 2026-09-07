// # Installed by: condense init
// Tool: Pi
// Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
// Deny matching shell/terminal tools. Never rewrite+allow.

const CONDENSE_COMMANDS = new Set("{{CONDENSE_COMMANDS}}".split(/\s+/).filter(Boolean));

function normalizeName(raw: string): string {
  if (!raw) return "";
  let s = raw.trim();
  if ((s.startsWith('"') && s.endsWith('"') && s.length >= 2) ||
      (s.startsWith("'") && s.endsWith("'") && s.length >= 2)) {
    s = s.slice(1, -1);
  }
  s = s.replace(/\\/g, "/");
  if (s.includes("/")) s = s.split("/").pop() || "";
  if (s.startsWith("\\")) s = s.slice(1);
  s = s.trim().toLowerCase();
  for (const ext of [".exe", ".cmd", ".bat"]) {
    if (s.endsWith(ext)) {
      s = s.slice(0, -ext.length);
      break;
    }
  }
  return s;
}

function analyzeCommand(cmd: string, condenseSet: Set<string>): { deny: boolean; matched: string[] } {
  const chars: string[] = [];
  for (let i = 0; i < cmd.length; i++) {
    const c = cmd[i];
    if (c === "\n" || c === "\r") {
      chars.push(c);
    } else if (/\s/.test(c) || c === "\u00A0" || c === "\u2007" || c === "\u202F" || c === "\uFEFF") {
      chars.push(" ");
    } else {
      chars.push(c);
    }
  }
  const sanitized = chars.join("");

  const segments: string[] = [];
  let curr: string[] = [];
  let inSingle = false;
  let inDouble = false;
  let escaped = false;
  let i = 0;
  const length = sanitized.length;

  while (i < length) {
    const c = sanitized[i];
    if (escaped) {
      curr.push(c);
      escaped = false;
      i++;
      continue;
    }
    if (c === "\\" && !inSingle) {
      escaped = true;
      curr.push(c);
      i++;
      continue;
    }
    if (c === "'" && !inDouble) {
      inSingle = !inSingle;
      curr.push(c);
      i++;
      continue;
    }
    if (c === '"' && !inSingle) {
      inDouble = !inDouble;
      curr.push(c);
      i++;
      continue;
    }

    if (!inSingle && !inDouble) {
      if (c === "<" && i + 1 < length && sanitized[i + 1] === "<") {
        return { deny: true, matched: ["heredoc"] };
      }
      if (c === ";" || c === "\n" || c === "\r") {
        const seg = curr.join("").trim();
        if (seg) segments.push(seg);
        curr = [];
        if (c === "\r" && i + 1 < length && sanitized[i + 1] === "\n") i++;
        i++;
        continue;
      }
      if (c === "&") {
        const seg = curr.join("").trim();
        if (seg) segments.push(seg);
        curr = [];
        if (i + 1 < length && sanitized[i + 1] === "&") i++;
        i++;
        continue;
      }
      if (c === "|") {
        const seg = curr.join("").trim();
        if (seg) segments.push(seg);
        curr = [];
        if (i + 1 < length && (sanitized[i + 1] === "|" || sanitized[i + 1] === "&")) i++;
        i++;
        continue;
      }
    }

    curr.push(c);
    i++;
  }

  if (escaped || inSingle || inDouble) {
    return { deny: true, matched: ["unclosed quote or escape"] };
  }

  const seg = curr.join("").trim();
  if (seg) segments.push(seg);

  if (!segments.length) {
    return { deny: false, matched: [] };
  }

  const matched: string[] = [];
  let hasAmbiguity = false;

  for (const s of segments) {
    if (s.includes("`") || s.includes("$(")) {
      const words = s.replace(/[()$`]/g, " ").split(/\s+/).map(normalizeName);
      for (const w of words) {
        if (condenseSet.has(w)) matched.push(w);
      }
      hasAmbiguity = true;
      continue;
    }

    const words = s.split(/\s+/);
    let idx = 0;
    while (idx < words.length) {
      const w = words[idx];
      if (/^(<|>|>>|2>|1>|2>&1|&>)/.test(w)) {
        idx++;
        if (/^(<|>|>>|2>|1>|2>&1|&>)$/.test(w) && idx < words.length) idx++;
        continue;
      }
      if (w.includes("=") && !w.startsWith("=")) {
        idx++;
        continue;
      }
      break;
    }

    while (idx < words.length) {
      const base = normalizeName(words[idx]);
      if (["eval", "exec", "source", "."].includes(base)) {
        hasAmbiguity = true;
        break;
      }
      if (["sudo", "env", "nohup", "time", "command", "builtin"].includes(base)) {
        idx++;
        while (idx < words.length) {
          const opt = words[idx];
          if (/^(<|>|>>|2>|1>|2>&1|&>)/.test(opt)) {
            idx++;
            if (/^(<|>|>>|2>|1>|2>&1|&>)$/.test(opt) && idx < words.length) idx++;
          } else if (opt.startsWith("-")) {
            idx++;
            if (["-u", "-C", "-g"].includes(opt) && idx < words.length && !words[idx].startsWith("-")) {
              idx++;
            }
          } else if (opt.includes("=") && !opt.startsWith("=")) {
            idx++;
          } else {
            break;
          }
        }
      } else {
        break;
      }
    }

    while (idx < words.length) {
      const w = words[idx];
      if (/^(<|>|>>|2>|1>|2>&1|&>)/.test(w)) {
        idx++;
        if (/^(<|>|>>|2>|1>|2>&1|&>)$/.test(w) && idx < words.length) idx++;
        continue;
      }
      break;
    }

    if (idx < words.length) {
      const cmdName = normalizeName(words[idx]);
      if (cmdName && condenseSet.has(cmdName)) {
        matched.push(cmdName);
      }
    }
  }

  if (matched.length > 0) {
    return { deny: true, matched };
  }
  if (hasAmbiguity) {
    return { deny: true, matched: ["ambiguous syntax"] };
  }
  return { deny: false, matched: [] };
}

export default async function condenseHook(event: {
  toolName?: string;
  tool_name?: string;
  command?: string;
  parameters?: { command?: string };
  tool_input?: { command?: string };
}): Promise<{ permissionDecision?: string; permissionDecisionReason?: string } | void> {
  const tool = event.toolName || event.tool_name || "";
  const command = String(
    (event.parameters && event.parameters.command) ||
    (event.tool_input && event.tool_input.command) ||
    event.command ||
    ""
  ).trim();
  if (!command) {
    return;
  }
  const shellish = !tool || /bash|shell|terminal|command/i.test(tool);
  if (!shellish) {
    return;
  }
  const analysis = analyzeCommand(command, CONDENSE_COMMANDS);
  if (analysis.deny) {
    return {
      permissionDecision: "deny",
      permissionDecisionReason: `Use "condense ${command}" instead to get filtered, token-efficient output.`,
    };
  }
}
