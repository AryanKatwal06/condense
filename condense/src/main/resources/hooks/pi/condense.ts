// # Installed by: condense init
// Tool: Pi
// Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
// Deny matching shell/terminal tools. Never rewrite+allow.

const CONDENSE_COMMANDS = "git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle".split(" ");

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
  const bare = command.split(/\s+/)[0].split(/[\\/]/).pop() || "";
  if (shellish && CONDENSE_COMMANDS.includes(bare)) {
    return {
      permissionDecision: "deny",
      permissionDecisionReason: `Use "condense ${command}" instead to get filtered, token-efficient output.`,
    };
  }
}
