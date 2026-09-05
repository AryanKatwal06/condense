# Fish completion for condense

# Disable file completion by default
complete -c condense -f

# Global options
complete -c condense -s v -l verbose       -d 'Increase verbosity'
complete -c condense -s u -l ultra-compact -d 'Maximum compression mode'
complete -c condense -l format             -d 'Output format' -r -a 'text json'
complete -c condense -l version            -d 'Show version'
complete -c condense -l help               -d 'Show help'

# Subcommands
complete -c condense -n '__fish_use_subcommand' -a gain   -d 'Show token savings analytics'
complete -c condense -n '__fish_use_subcommand' -a doctor -d 'Diagnose persistence and empty analytics'
complete -c condense -n '__fish_use_subcommand' -a discover -d 'Recommend filters from manifests and lockfiles'
complete -c condense -n '__fish_use_subcommand' -a propose -d 'Propose reviewable project filter overrides'
complete -c condense -n '__fish_use_subcommand' -a explain -d 'Show which filter stages dropped which lines'
complete -c condense -n '__fish_use_subcommand' -a read    -d 'Read a source file with comment-strip or outline'
complete -c condense -n '__fish_use_subcommand' -a init   -d 'Install AI tool hooks'
complete -c condense -n '__fish_use_subcommand' -a config -d 'Read and write configuration'
complete -c condense -n '__fish_use_subcommand' -a mcp    -d 'MCP server over stdio'

# Proxy commands
for cmd in git cargo pytest go npm npx docker kubectl aws ls grep rg find cat make mvn gradle vitest jest eslint tsc ruff pip python mypy dotnet bundle rspec rubocop terraform helm gh glab curl wget psql pnpm next prisma playwright prettier
  complete -c condense -n '__fish_use_subcommand' -a $cmd -d "Proxy $cmd"
end

# gain options
complete -c condense -n '__fish_seen_subcommand_from gain' -l graph   -d '30-day bar chart'
complete -c condense -n '__fish_seen_subcommand_from gain' -l history -d 'Last N commands' -r
complete -c condense -n '__fish_seen_subcommand_from gain' -l scope   -d 'Scope' -r -a 'global project'
complete -c condense -n '__fish_seen_subcommand_from gain' -l daily   -d 'Per-day table'
complete -c condense -n '__fish_seen_subcommand_from gain' -l weekly  -d 'Per-week table'
complete -c condense -n '__fish_seen_subcommand_from gain' -l top     -d 'Top N commands' -r
complete -c condense -n '__fish_seen_subcommand_from gain' -l since   -d 'Last N days' -r
complete -c condense -n '__fish_seen_subcommand_from gain' -l all     -d 'All-time data'
complete -c condense -n '__fish_seen_subcommand_from gain' -l format  -d 'Output format' -r -a 'text json'

complete -c condense -n '__fish_seen_subcommand_from doctor' -l format -d 'Output format' -r -a 'text json'

complete -c condense -n '__fish_seen_subcommand_from discover' -l format -d 'Output format' -r -a 'text json'
complete -c condense -n '__fish_seen_subcommand_from discover' -l root -d 'Narrow workspace root' -r

complete -c condense -n '__fish_seen_subcommand_from propose' -l format -d 'Output format' -r -a 'text json'
complete -c condense -n '__fish_seen_subcommand_from propose' -l root -d 'Narrow workspace root' -r
complete -c condense -n '__fish_seen_subcommand_from propose' -l write -d 'Write filters.toml.proposed only'

complete -c condense -n '__fish_seen_subcommand_from explain' -l format -d 'Output format' -r -a 'text json'
complete -c condense -n '__fish_seen_subcommand_from explain' -l input -d 'Use captured stdout file' -r
complete -c condense -n '__fish_seen_subcommand_from explain' -l stdin -d 'Read captured stdout from stdin'
complete -c condense -n '__fish_seen_subcommand_from explain' -l exit-code -d 'Exit code for --input or --stdin' -r
complete -c condense -n '__fish_seen_subcommand_from explain' -l dropped-limit -d 'Dropped line sample cap' -r

complete -c condense -n '__fish_seen_subcommand_from read' -l level -d 'verbatim, comments, or outline' -r -a 'verbatim comments outline'
complete -c condense -n '__fish_seen_subcommand_from read' -l lang -d 'Language name' -r
complete -c condense -n '__fish_seen_subcommand_from read' -l root -d 'Narrow workspace root' -r
complete -c condense -n '__fish_seen_subcommand_from read' -l max-bytes -d 'Read cap in bytes' -r
complete -c condense -n '__fish_seen_subcommand_from read' -l format -d 'Output format' -r -a 'text json'
complete -c condense -n '__fish_seen_subcommand_from read' -l stdin -d 'Read from standard input'
complete -c condense -n '__fish_seen_subcommand_from read' -s u -l ultra-compact -d 'Same as --level outline'

# init options
complete -c condense -n '__fish_seen_subcommand_from init' -s g -l global  -d 'Install all hooks'
complete -c condense -n '__fish_seen_subcommand_from init' -l show         -d 'Show hook status'
complete -c condense -n '__fish_seen_subcommand_from init' -l remove       -d 'Remove all hooks'
complete -c condense -n '__fish_seen_subcommand_from init' -l tool         -d 'Specific tool' -r \
  -a 'claude-code cursor gemini windsurf copilot cline codex opencode kilo antigravity hermes pi'

# config options
complete -c condense -n '__fish_seen_subcommand_from config' -l list  -d 'Print full config'
complete -c condense -n '__fish_seen_subcommand_from config' -l get   -d 'Get key' -r \
  -a 'tee.enabled tee.mode hooks.exclude_commands'
complete -c condense -n '__fish_seen_subcommand_from config' -l set   -d 'Set key=value' -r
complete -c condense -n '__fish_seen_subcommand_from config' -l reset -d 'Reset to defaults'

complete -c condense -n '__fish_seen_subcommand_from mcp' -l start -d 'Start the MCP server on stdin/stdout'
