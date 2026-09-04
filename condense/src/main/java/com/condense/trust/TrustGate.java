package com.condense.trust;

import com.condense.core.PlatformDirs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether a project override file may run. Review writes the store;
 * proxied commands never prompt.
 */
@ApplicationScoped
public class TrustGate {

    public static final String SKIP_HINT =
        "condense: skipped untrusted project filter override (.condense/filters.toml). Review with: condense config trust";

    public record Result(TrustDecision decision, String reason) {
        public boolean apply() {
            return decision == TrustDecision.APPLY;
        }
    }

    private final TrustStore store;
    private final Function<String, String> env;

    private static final class Holder {
        private static final TrustGate INSTANCE = new TrustGate();
    }

    public static TrustGate standalone() {
        return Holder.INSTANCE;
    }

    public TrustGate() {
        this(new PlatformDirs());
    }

    @Inject
    public TrustGate(PlatformDirs platformDirs) {
        this(new TrustStore(platformDirs), System::getenv);
    }

    public TrustGate(PlatformDirs platformDirs, Function<String, String> env) {
        this(new TrustStore(platformDirs), env);
    }

    public TrustGate(TrustStore store, Function<String, String> env) {
        this.store = store;
        this.env = env != null ? env : System::getenv;
    }

    public TrustStore store() {
        return store;
    }

    /**
     * Decide from bytes already read (same buffer that will be hashed).
     */
    public Result decide(Path canonicalFile, byte[] bytes, Set<Capability> requiredCaps) {
        Set<Capability> required = requiredCaps == null || requiredCaps.isEmpty()
            ? EnumSet.of(Capability.REDUCE)
            : requiredCaps;
        String hash = TrustStore.sha256Hex(bytes);

        if (CiIndicator.projectFiltersHatchArmed(env)) {
            Set<Capability> granted = CiIndicator.hatchCapabilities(env);
            if (Capability.grantsCover(granted, required)) {
                return new Result(TrustDecision.APPLY, "ci-hatch");
            }
            return new Result(TrustDecision.SKIP, "ci-hatch-missing-capability");
        }

        if (canonicalFile == null) {
            return new Result(TrustDecision.SKIP, "untrusted");
        }

        var record = store.find(canonicalFile);
        if (record.isEmpty()) {
            return new Result(TrustDecision.SKIP, "untrusted");
        }
        TrustRecord trusted = record.get();
        if (trusted.sha256() == null || !trusted.sha256().equalsIgnoreCase(hash)) {
            return new Result(TrustDecision.SKIP, "hash-mismatch");
        }
        Set<Capability> granted = Capability.parseAll(trusted.capabilities());
        if (granted.isEmpty()) {
            granted = EnumSet.of(Capability.REDUCE);
        }
        if (!Capability.grantsCover(granted, required)) {
            return new Result(TrustDecision.SKIP, "missing-capability");
        }
        return new Result(TrustDecision.APPLY, "trusted");
    }

    /**
     * Persist trust for the supplied buffer. Does not re-read the file.
     */
    public void accept(Path canonicalFile, byte[] bytes, Set<Capability> grants) {
        Set<Capability> effective = grants == null || grants.isEmpty()
            ? EnumSet.of(Capability.REDUCE)
            : grants;
        List<String> tokens = effective.stream().map(Capability::token).toList();
        store.put(new TrustRecord(
            TrustStore.canonicalize(canonicalFile).toString(),
            TrustStore.sha256Hex(bytes),
            tokens,
            Instant.now().toString()
        ));
    }

    public void revoke(Path canonicalFile) {
        store.remove(canonicalFile);
    }

    public List<TrustRecord> status() {
        return store.all();
    }
}
