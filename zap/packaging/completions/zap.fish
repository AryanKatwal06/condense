# Fish completion for zap

# Disable file completion by default
complete -c zap -f

# Global options
complete -c zap -s v -l verbose       -d 'Increase verbosity'
complete -c zap -s u -l ultra-compact -d 'Maximum compression mode'
complete -c zap -l version            -d 'Show version'
complete -c zap -l help               -d 'Show help'

# Subcommands
complete -c zap -n '__fish_use_subcommand' -a gain   -d 'Show token savings analytics'
complete -c zap -n '__fish_use_subcommand' -a init   -d 'Install AI tool hooks'
complete -c zap -n '__fish_use_subcommand' -a config -d 'Read and write configuration'

# Proxy commands
for cmd in git cargo pytest go npm docker kubectl aws ls grep rg find cat make mvn gradle
  complete -c zap -n '__fish_use_subcommand' -a $cmd -d "Proxy $cmd"
end

# gain options
complete -c zap -n '__fish_seen_subcommand_from gain' -l graph   -d '30-day bar chart'
complete -c zap -n '__fish_seen_subcommand_from gain' -l history -d 'Last N commands' -r
complete -c zap -n '__fish_seen_subcommand_from gain' -l scope   -d 'Scope' -r -a 'global project'
complete -c zap -n '__fish_seen_subcommand_from gain' -l daily   -d 'Per-day table'
complete -c zap -n '__fish_seen_subcommand_from gain' -l weekly  -d 'Per-week table'
complete -c zap -n '__fish_seen_subcommand_from gain' -l top     -d 'Top N commands' -r
complete -c zap -n '__fish_seen_subcommand_from gain' -l since   -d 'Last N days' -r
complete -c zap -n '__fish_seen_subcommand_from gain' -l all     -d 'All-time data'
complete -c zap -n '__fish_seen_subcommand_from gain' -l format  -d 'Output format' -r -a 'text json'

# init options
complete -c zap -n '__fish_seen_subcommand_from init' -s g -l global  -d 'Install all hooks'
complete -c zap -n '__fish_seen_subcommand_from init' -l show         -d 'Show hook status'
complete -c zap -n '__fish_seen_subcommand_from init' -l remove       -d 'Remove all hooks'
complete -c zap -n '__fish_seen_subcommand_from init' -l tool         -d 'Specific tool' -r \
  -a 'claude-code cursor gemini windsurf copilot cline'

# config options
complete -c zap -n '__fish_seen_subcommand_from config' -l list  -d 'Print full config'
complete -c zap -n '__fish_seen_subcommand_from config' -l get   -d 'Get key' -r \
  -a 'tee.enabled tee.mode hooks.exclude_commands'
complete -c zap -n '__fish_seen_subcommand_from config' -l set   -d 'Set key=value' -r
complete -c zap -n '__fish_seen_subcommand_from config' -l reset -d 'Reset to defaults'
