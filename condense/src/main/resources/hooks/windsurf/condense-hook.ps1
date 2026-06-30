# Installed by: condense init
# Tool: Windsurf
# Do not edit manually — run `condense init -g` to reinstall
#
# This hook intercepts shell commands before Windsurf executes them.

$ErrorActionPreference = "Stop"

$condenseCommandsStr = "git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"
$condenseCommands = $condenseCommandsStr -split '\s+'

try {
    $inputStr = $input | Out-String
    if ([string]::IsNullOrWhiteSpace($inputStr)) {
        exit 0
    }

    $inputJson = $inputStr | ConvertFrom-Json -ErrorAction Stop

    $cmd = $null
    if ($null -ne $inputJson.tool_info -and $null -ne $inputJson.tool_info.command) {
        $cmd = $inputJson.tool_info.command
    } elseif ($null -ne $inputJson.command) {
        $cmd = $inputJson.command
    } elseif ($null -ne $inputJson.tool_input -and $null -ne $inputJson.tool_input.command) {
        $cmd = $inputJson.tool_input.command
    }

    if ([string]::IsNullOrWhiteSpace($cmd)) {
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
        Write-Output "Use `"condense <command>`" instead to get filtered, token-efficient output."
        exit 2
    }
} catch {
    # Fail-open safety: if anything goes wrong, silently allow
}

exit 0
