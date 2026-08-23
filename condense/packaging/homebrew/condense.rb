class Condense < Formula
  desc "AI-focused command output condenser"
  homepage "https://github.com/AryanKatwal06/condense"
  version "1.0.1"

  if OS.mac? && Hardware::CPU.arm?
    url "https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-macos-aarch64"
    # Update this hash when submitting to the package registry
    sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  elsif OS.mac? && Hardware::CPU.intel?
    url "https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-macos-x64"
    sha256 "9a68f45b718607a9b240d9a345c362fe1bae64ad753d70826d73a490dc3d4a0d"
  elsif OS.linux? && Hardware::CPU.arm?
    url "https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-linux-aarch64"
    # Update this hash when submitting to the package registry
    sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  elsif OS.linux? && Hardware::CPU.intel?
    url "https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-linux-x64"
    sha256 "689ca92fb49131c719cae4ed98ab2bbbd1d0daca3e5dd40f253cc1487c9d4e14"
  end

  def install
    bin.install buildpath.children.first => "condense"
  end

  test do
    system "#{bin}/condense", "--version"
  end
end
