class Condense < Formula
  desc "AI-focused command output condenser"
  homepage "https://github.com/aryanKatwal06/code-condenser"
  version "1.0.0"

  if OS.mac? && Hardware::CPU.arm?
    url "https://github.com/aryanKatwal06/code-condenser/releases/download/v1.0.0/condense-macos-aarch64"
    sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  elsif OS.mac? && Hardware::CPU.intel?
    url "https://github.com/aryanKatwal06/code-condenser/releases/download/v1.0.0/condense-macos-x64"
    sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  elsif OS.linux? && Hardware::CPU.arm?
    url "https://github.com/aryanKatwal06/code-condenser/releases/download/v1.0.0/condense-linux-aarch64"
    sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  elsif OS.linux? && Hardware::CPU.intel?
    url "https://github.com/aryanKatwal06/code-condenser/releases/download/v1.0.0/condense-linux-x64"
    sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  end

  def install
    bin.install buildpath.children.first => "condense"
  end

  test do
    system "#{bin}/condense", "--version"
  end
end
