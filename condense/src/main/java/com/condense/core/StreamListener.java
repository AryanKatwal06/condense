package com.condense.core;

/**
 * Optional live consumer for bytes as {@link CommandExecutor} drains a child.
 */
public interface StreamListener {

    void onStdout(byte[] chunk, int length);

    void onStderr(byte[] chunk, int length);

    default void onCapped() {}
}
