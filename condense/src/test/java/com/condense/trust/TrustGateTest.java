package com.condense.trust;

import com.condense.core.PlatformDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrustGateTest {

    @TempDir
    Path tempDir;

    @Test
    void untrustedFileIsSkipped() throws Exception {
        TrustGate gate = gate(Map.of());
        Path file = writeProject("a");
        TrustGate.Result result = gate.decide(file.toRealPath(), bytes("a"), Set.of(Capability.REDUCE));
        assertThat(result.decision()).isEqualTo(TrustDecision.SKIP);
        assertThat(result.reason()).isEqualTo("untrusted");
    }

    @Test
    void acceptThenApplyAndRevoke() throws Exception {
        TrustGate gate = gate(Map.of());
        Path file = writeProject("trusted");
        byte[] buf = bytes("trusted");
        gate.accept(file.toRealPath(), buf, Set.of(Capability.REDUCE));

        assertThat(gate.decide(file.toRealPath(), buf, Set.of(Capability.REDUCE)).apply()).isTrue();

        gate.revoke(file.toRealPath());
        assertThat(gate.decide(file.toRealPath(), buf, Set.of(Capability.REDUCE)).apply()).isFalse();
    }

    @Test
    void hashMismatchSkipsEvenWhenPathWasTrusted() throws Exception {
        TrustGate gate = gate(Map.of());
        Path file = writeProject("v1");
        gate.accept(file.toRealPath(), bytes("v1"), Set.of(Capability.REDUCE));
        Files.writeString(file, "v2");

        TrustGate.Result result = gate.decide(file.toRealPath(), bytes("v2"), Set.of(Capability.REDUCE));
        assertThat(result.decision()).isEqualTo(TrustDecision.SKIP);
        assertThat(result.reason()).isEqualTo("hash-mismatch");
    }

    @Test
    void acceptHashesTheSuppliedBufferNotAReread() throws Exception {
        TrustGate gate = gate(Map.of());
        Path file = writeProject("original");
        byte[] displayed = Files.readAllBytes(file);
        Files.writeString(file, "swapped-after-read");
        gate.accept(file.toRealPath(), displayed, Set.of(Capability.REDUCE));

        assertThat(gate.decide(file.toRealPath(), displayed, Set.of(Capability.REDUCE)).apply()).isTrue();
        assertThat(gate.decide(file.toRealPath(), bytes("swapped-after-read"), Set.of(Capability.REDUCE)).apply())
            .isFalse();
    }

    @Test
    void hatchWithoutCiIndicatorIsIgnored() throws Exception {
        TrustGate gate = gate(Map.of(CiIndicator.TRUST_PROJECT_FILTERS, "1"));
        Path file = writeProject("x");
        assertThat(gate.decide(file.toRealPath(), bytes("x"), Set.of(Capability.REDUCE)).apply()).isFalse();
    }

    @Test
    void hatchWithCiAppliesWithoutPersisting() throws Exception {
        Path configDir = tempDir.resolve("config-ci");
        Files.createDirectories(configDir);
        Map<String, String> env = Map.of(
            CiIndicator.TRUST_PROJECT_FILTERS, "1",
            "GITHUB_ACTIONS", "true"
        );
        TrustGate gate = new TrustGate(dirs(configDir), env::get);
        Path file = writeProject("ci");
        assertThat(gate.decide(file.toRealPath(), bytes("ci"), Set.of(Capability.REDUCE)).apply()).isTrue();
        assertThat(gate.status()).isEmpty();
    }

    @Test
    void missingCapabilitySkipsTrustedFile() throws Exception {
        TrustGate gate = gate(Map.of());
        Path file = writeProject("caps");
        gate.accept(file.toRealPath(), bytes("caps"), Set.of(Capability.REDUCE));
        TrustGate.Result result = gate.decide(
            file.toRealPath(), bytes("caps"), Set.of(Capability.RESHAPE));
        assertThat(result.decision()).isEqualTo(TrustDecision.SKIP);
        assertThat(result.reason()).isEqualTo("missing-capability");
    }

    private TrustGate gate(Map<String, String> env) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Map<String, String> copy = new HashMap<>(env);
        return new TrustGate(dirs(configDir), copy::get);
    }

    private Path writeProject(String body) throws Exception {
        Path file = tempDir.resolve("proj/.condense/filters.toml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static PlatformDirs dirs(Path configDir) {
        return new PlatformDirs() {
            @Override
            public Path resolveConfigDir() {
                return configDir;
            }

            @Override
            public Path getConfigDir() {
                return configDir;
            }
        };
    }
}
