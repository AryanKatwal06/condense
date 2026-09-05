package com.condense.discover;

/**
 * Hard I/O caps for discovery. Not configurable from TOML.
 */
public record DiscoverLimits(
    int maxProbes,
    int maxReads,
    int maxBytesPerFile,
    int maxTotalBytes
) {
    public static final DiscoverLimits DEFAULT = new DiscoverLimits(64, 8, 64 * 1024, 256 * 1024);
}
