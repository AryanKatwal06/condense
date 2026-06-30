#!/usr/bin/env bash
# Installed by: condense init
# Tool: GitHub Copilot CLI
# Do not edit manually — run `condense init -g` to reinstall
#
# This hook intercepts shell commands before GitHub Copilot CLI executes them.
# Commands matching CONDENSE_COMMANDS are routed through `condense` for output compression.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

python3 -c "
import sys, json

CONDENSE_COMMANDS = '${CONDENSE_COMMANDS}'.split()

try:
    data = json.load(sys.stdin)
    if data.get('toolName') != 'bash':
        print(json.dumps({'permissionDecision': 'allow'}))
        sys.exit(0)
    
    tool_args_str = data.get('toolArgs')
    if not tool_args_str:
        print(json.dumps({'permissionDecision': 'allow'}))
        sys.exit(0)
        
    tool_args = json.loads(tool_args_str)
    cmd = tool_args.get('command')
    if not cmd:
        print(json.dumps({'permissionDecision': 'allow'}))
        sys.exit(0)
    
    cmd_name = cmd.strip().split()[0]
    bare_cmd = cmd_name.split('/')[-1]
    
    if bare_cmd in CONDENSE_COMMANDS:
        print(json.dumps({
            'permissionDecision': 'deny',
            'permissionDecisionReason': 'Use \"condense <command>\" instead to get filtered, token-efficient output.'
        }))
        sys.exit(0)

except Exception as e:
    pass

print(json.dumps({'permissionDecision': 'allow'}))
"
