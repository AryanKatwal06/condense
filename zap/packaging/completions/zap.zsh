#compdef zap
# Zsh completion for zap

_zap() {
  local -a commands proxy_commands

  commands=(
    'gain:Show token savings analytics'
    'init:Install AI tool hooks'
    'config:Read and write configuration'
  )

  proxy_commands=(
    'git:Proxy git commands'
    'cargo:Proxy cargo commands'
    'pytest:Proxy pytest'
    'go:Proxy go commands'
    'npm:Proxy npm commands'
    'docker:Proxy docker commands'
    'kubectl:Proxy kubectl commands'
    'aws:Proxy aws CLI commands'
    'ls:Proxy ls with tree compression'
    'grep:Proxy grep with match summary'
    'rg:Proxy ripgrep with match summary'
    'find:Proxy find with summary'
    'make:Proxy make with error extraction'
    'mvn:Proxy Maven builds'
    'gradle:Proxy Gradle builds'
  )

  _arguments \
    '(-v --verbose)'{-v,--verbose}'[Increase verbosity]' \
    '(-u --ultra-compact)'{-u,--ultra-compact}'[Maximum compression mode]' \
    '--version[Show version]' \
    '--help[Show help]' \
    '1: :->cmd' \
    '*:: :->args'

  case $state in
    cmd)
      _describe 'subcommands' commands
      _describe 'proxy commands' proxy_commands
      ;;
    args)
      case $words[1] in
        gain)
          _arguments \
            '--graph[Show 30-day bar chart]' \
            '--history=[Show last N commands]:N:(10 20 50)' \
            '--scope=[Scope]:scope:(global project)' \
            '--daily[Show per-day table]' \
            '--weekly[Show per-week table]' \
            '--top=[Top N commands]:N:(5 10 20)' \
            '--since=[Last N days]:N:(7 14 30 90)' \
            '--all[All-time data]' \
            '--format=[Output format]:format:(text json)'
          ;;
        init)
          _arguments \
            '(-g --global)'{-g,--global}'[Install for all tools]' \
            '--show[Show hook status]' \
            '--remove[Remove all hooks]' \
            '--tool=[Specific tool]:tool:(claude-code cursor gemini windsurf copilot cline)'
          ;;
        config)
          _arguments \
            '--list[Print full config]' \
            '--get=[Get key value]:key:(tee.enabled tee.mode hooks.exclude_commands)' \
            '--set=[Set key=value]:keyval:()' \
            '--reset[Reset to defaults]'
          ;;
        git)
          _git
          ;;
        *)
          _default
          ;;
      esac
      ;;
  esac
}

_zap "$@"
