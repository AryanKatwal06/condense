# Installed by: condense init
# Tool: Hermes
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall

import json
import sys

CONDENSE_COMMANDS = "{{CONDENSE_COMMANDS}}".split()

def main() -> None:
    try:
        data = json.load(sys.stdin)
    except Exception:
        return
    tool_input = data.get("tool_input") or data.get("parameters") or {}
    command = ""
    if isinstance(tool_input, dict):
        command = str(tool_input.get("command", "")).strip()
    if not command:
        command = str(data.get("command", "")).strip()
    if not command:
        return
    bare = command.split()[0].split("/")[-1]
    if bare in CONDENSE_COMMANDS:
        print(json.dumps({
            "cancel": True,
            "errorMessage": 'Use "condense ' + command + '" instead to get filtered, token-efficient output.',
        }))

if __name__ == "__main__":
    main()
