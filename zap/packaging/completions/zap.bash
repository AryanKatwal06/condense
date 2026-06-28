# Bash completion for zap
# Install: source /path/to/zap.bash
#          or: cp zap.bash /etc/bash_completion.d/zap

_zap_completion() {
  local cur prev words
  COMPREPLY=()
  cur="${COMP_WORDS[COMP_CWORD]}"
  prev="${COMP_WORDS[COMP_CWORD-1]}"

  # Top-level subcommands
  local subcommands="gain init config"

  # Commands zap can proxy
  local proxy_commands="git cargo pytest go npm npx docker kubectl aws ls grep rg find cat make mvn gradle vitest jest eslint tsc ruff pip golangci-lint"

  # Top-level options
  local global_opts="-v --verbose -u --ultra-compact --version --help"

  case "$prev" in
    zap)
      COMPREPLY=($(compgen -W "${subcommands} ${proxy_commands} ${global_opts}" -- "$cur"))
      return 0
      ;;
    gain)
      COMPREPLY=($(compgen -W "--graph --history --scope --daily --weekly --top --since --all --format --help" -- "$cur"))
      return 0
      ;;
    init)
      COMPREPLY=($(compgen -W "-g --global --show --remove --tool --help" -- "$cur"))
      return 0
      ;;
    config)
      COMPREPLY=($(compgen -W "--list --get --set --reset --help" -- "$cur"))
      return 0
      ;;
    --scope)
      COMPREPLY=($(compgen -W "global project" -- "$cur"))
      return 0
      ;;
    --format)
      COMPREPLY=($(compgen -W "text json" -- "$cur"))
      return 0
      ;;
    --tool)
      COMPREPLY=($(compgen -W "claude-code cursor gemini windsurf copilot cline" -- "$cur"))
      return 0
      ;;
    --get|--set)
      COMPREPLY=($(compgen -W "tee.enabled tee.mode hooks.exclude_commands" -- "$cur"))
      return 0
      ;;
  esac

  COMPREPLY=($(compgen -W "${subcommands} ${proxy_commands} ${global_opts}" -- "$cur"))
  return 0
}

complete -F _zap_completion zap
