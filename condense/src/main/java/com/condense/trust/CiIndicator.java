package com.condense.trust;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Detects a genuine CI environment. A lone project-trust env var is not enough
 * (that would be armed by {@code .envrc} / direnv).
 */
public final class CiIndicator {

    public static final String TRUST_PROJECT_FILTERS = "CONDENSE_TRUST_PROJECT_FILTERS";
    public static final String TRUST_PROJECT_CAPABILITIES = "CONDENSE_TRUST_PROJECT_CAPABILITIES";

    public static final List<String> CI_VARIABLES = List.of(
        "CI",
        "GITHUB_ACTIONS",
        "GITLAB_CI",
        "CIRCLECI",
        "TRAVIS",
        "BUILDKITE",
        "TF_BUILD",
        "JENKINS_URL",
        "TEAMCITY_VERSION"
    );

    private CiIndicator() {}

    public static boolean isTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized);
    }

    public static boolean isCi(Function<String, String> env) {
        if (env == null) {
            return false;
        }
        for (String name : CI_VARIABLES) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCi() {
        return isCi(System::getenv);
    }

    /**
     * Project-filter hatch is armed only when the Condense flag is truthy
     * <em>and</em> a listed CI indicator is present.
     */
    public static boolean projectFiltersHatchArmed(Function<String, String> env) {
        return isCi(env) && isTruthy(env.apply(TRUST_PROJECT_FILTERS));
    }

    public static boolean projectFiltersHatchArmed() {
        return projectFiltersHatchArmed(System::getenv);
    }

    /**
     * Capabilities granted by the hatch. Default is {@link Capability#REDUCE} only.
     * Extra caps in {@link #TRUST_PROJECT_CAPABILITIES} are ignored unless CI is present.
     */
    public static Set<Capability> hatchCapabilities(Function<String, String> env) {
        Set<Capability> caps = new java.util.LinkedHashSet<>();
        caps.add(Capability.REDUCE);
        if (!isCi(env)) {
            return caps;
        }
        caps.addAll(Capability.parseCsv(env.apply(TRUST_PROJECT_CAPABILITIES)));
        return caps;
    }

    public static Set<Capability> hatchCapabilities() {
        return hatchCapabilities(System::getenv);
    }
}
