# Installed by: condense init
# Tool: GitHub Copilot CLI
# Do not edit manually — run `condense init -g` to reinstall
#
# This hook intercepts shell commands before GitHub Copilot CLI executes them.

$ErrorActionPreference = "Stop"

$condenseCommandsStr = "git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"
$condenseCommands = $condenseCommandsStr -split '\s+'

try {
    $inputStr = $input | Out-String
    if ([string]::IsNullOrWhiteSpace($inputStr)) {
        Write-Output '{"permissionDecision": "allow"}'
        exit 0
    }

    $inputJson = $inputStr | ConvertFrom-Json -ErrorAction Stop
    if ($inputJson.toolName -ne "bash") {
        Write-Output '{"permissionDecision": "allow"}'
        exit 0
    }
    
    if ([string]::IsNullOrWhiteSpace($inputJson.toolArgs)) {
        Write-Output '{"permissionDecision": "allow"}'
        exit 0
    }
    
    $toolArgs = $inputJson.toolArgs | ConvertFrom-Json -ErrorAction Stop
    $cmd = $toolArgs.command
    if ([string]::IsNullOrWhiteSpace($cmd)) {
        Write-Output '{"permissionDecision": "allow"}'
        exit 0
    }
    
    # Extract bare command
    $cmdName = ($cmd.Trim() -split '\s+')[0]
    $bareCmd = Split-Path $cmdName -Leaf
    
    # Handle .exe extension on Windows for safety
    if ($bareCmd -match '\.exe$') {
        $bareCmd = $bareCmd -replace '\.exe$', ''
    }
    
    if ($condenseCommands -contains $bareCmd) {
        Write-Output '{"permissionDecision": "deny", "permissionDecisionReason": "Use \"condense <command>\" instead to get filtered, token-efficient output."}'
        exit 0
    }
} catch {
    # Fail-closed safety: if anything goes wrong, silently allow
}

Write-Output '{"permissionDecision": "allow"}'
exit 0
