#!/usr/bin/env node
// # Installed by: condense init
// Tool: OpenCode
// Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
// Deny matching shell/terminal tools. Never rewrite+allow.

const CONDENSE_COMMANDS = "git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle".split(" ");

const chunks = [];
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => chunks.push(chunk));
process.stdin.on("end", () => {
  let data;
  try {
    data = JSON.parse(chunks.join("") || "{}");
  } catch {
    process.exit(0);
  }
  const tool = data.toolName || data.tool_name || data.tool || "";
  const params = data.parameters || data.tool_input || data.toolInput || {};
  const command = String((params && params.command) || data.command || "").trim();
  if (!command) {
    process.exit(0);
  }
  const shellish = !tool || /bash|shell|terminal|command/i.test(String(tool));
  const bare = command.split(/\s+/)[0].split(/[\\/]/).pop();
  if (shellish && CONDENSE_COMMANDS.includes(bare)) {
    process.stdout.write(JSON.stringify({
      permissionDecision: "deny",
      permissionDecisionReason: 'Use "condense ' + command + '" instead to get filtered, token-efficient output.'
    }));
  }
});
