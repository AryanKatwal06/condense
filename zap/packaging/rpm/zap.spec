Name:           zap
Version:        0.1.0
Release:        1%{?dist}
Summary:        High-performance CLI proxy for AI token savings

License:        Apache-2.0
URL:            https://github.com/YOUR_ORG/zap
Source0:        zap-linux-x64

BuildArch:      x86_64
BuildRequires:  coreutils

%description
Zap filters shell command output to save 60-90% of AI context window tokens.
It sits between your AI coding assistant and the shell, compressing noisy
command output into compact summaries.

%install
mkdir -p %{buildroot}/usr/bin
mkdir -p %{buildroot}/usr/share/man/man1
mkdir -p %{buildroot}/usr/share/bash-completion/completions

install -m 755 %{SOURCE0} %{buildroot}/usr/bin/zap
gzip -c ../../packaging/man/zap.1 > %{buildroot}/usr/share/man/man1/zap.1.gz
install -m 644 ../../packaging/completions/zap.bash \
    %{buildroot}/usr/share/bash-completion/completions/zap

%files
/usr/bin/zap
/usr/share/man/man1/zap.1.gz
/usr/share/bash-completion/completions/zap

%changelog
* Sat Jun 28 2025 Your Name <you@example.com> - 0.1.0-1
- Initial release
