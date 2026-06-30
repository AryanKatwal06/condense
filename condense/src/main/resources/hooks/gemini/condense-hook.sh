#!/usr/bin/env bash
# Installed by: condense init -g
# Tool: Gemini CLI
# Do not edit manually — run `condense init -g` to reinstall

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

# The Gemini CLI captures stderr, but not stdout for debug logging.
# Any stdout breaks parsing entirely. Silence is Mandatory.

python3 -c "
import sys, json

CONDENSE_COMMANDS = '${CONDENSE_COMMANDS}'.split()

def extract_command(data):
    # ASSUMPTION: The shell command is in one of these fields.
    # Fallback to empty string if not found.
    # Try tool_input.command (Claude Code style)
    if 'tool_input' in data and isinstance(data['tool_input'], dict) and 'command' in data['tool_input']:
        return data['tool_input']['command']
    # Try parameters.command (Cline style)
    if 'parameters' in data and isinstance(data['parameters'], dict) and 'command' in data['parameters']:
        return data['parameters']['command']
    # Try top-level command
    if 'command' in data:
        return data['command']
    return ''

try:
    data = json.load(sys.stdin)
    cmd = extract_command(data)
    if not cmd:
        print(json.dumps({'decision': 'allow'}))
        sys.exit(0)
    
    cmd_name = cmd.strip().split()[0]
    bare_cmd = cmd_name.split('/')[-1]
    
    if bare_cmd in CONDENSE_COMMANDS:
        print(json.dumps({
            'decision': 'deny',
            'reason': 'Use \"condense <command>\" instead to get filtered, token-efficient output.',
            'systemMessage': 'Routing through condense for compressed output.'
        }))
        sys.exit(0)
        
except Exception as e:
    # Any error -> log to stderr, allow hook
    print(f'Error processing Gemini hook: {e}', file=sys.stderr)

print(json.dumps({'decision': 'allow'}))
"
