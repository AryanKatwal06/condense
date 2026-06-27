package com.zapproxy.filter.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Generic line-by-line state machine for structured command output.
 *
 * <p>Used by test runner filters (pytest, cargo test) that have well-defined
 * sections: header → collection → running → summary. Each state decides
 * whether to EMIT, COLLECT, or DISCARD the current line.
 *
 * <p>Usage:
 * <pre>{@code
 * var machine = new StateMachineStrategy.Builder()
 *     .state("HEADER",  line -> line.startsWith("==="), Action.DISCARD, "COLLECT")
 *     .state("COLLECT", line -> line.startsWith("FAILED"), Action.EMIT, "COLLECT")
 *     .build();
 * List<String> output = machine.process(lines);
 * }</pre>
 */
public final class StateMachineStrategy {

    public enum Action { EMIT, DISCARD, COLLECT }

    public record Transition(
        String fromState,
        Predicate<String> trigger,
        Action action,
        String nextState
    ) {}

    private final List<Transition> transitions;
    private final String initialState;

    private StateMachineStrategy(List<Transition> transitions, String initialState) {
        this.transitions = transitions;
        this.initialState = initialState;
    }

    /**
     * Processes lines through the state machine and returns the EMIT lines.
     */
    public List<String> process(List<String> lines) {
        List<String> output = new ArrayList<>();
        String state = initialState;

        for (String line : lines) {
            boolean transitioned = false;
            for (Transition t : transitions) {
                if (t.fromState().equals(state) && t.trigger().test(line)) {
                    if (t.action() == Action.EMIT) output.add(line);
                    state = t.nextState();
                    transitioned = true;
                    break;
                }
            }
            // Default action for current state (no matching trigger)
            if (!transitioned) {
                // Find default for current state (trigger = always-false sentinel)
                for (Transition t : transitions) {
                    if (t.fromState().equals(state + ":default")) {
                        if (t.action() == Action.EMIT) output.add(line);
                        break;
                    }
                }
            }
        }
        return output;
    }

    public static Builder builder(String initialState) {
        return new Builder(initialState);
    }

    public static final class Builder {
        private final List<Transition> transitions = new ArrayList<>();
        private final String initialState;

        public Builder(String initialState) {
            this.initialState = initialState;
        }

        /** On matching line in {@code fromState}: apply {@code action}, move to {@code nextState}. */
        public Builder on(String fromState, Pattern pattern, Action action, String nextState) {
            transitions.add(new Transition(fromState, line -> pattern.matcher(line).find(),
                action, nextState));
            return this;
        }

        /** Default action for all non-matching lines in {@code state}. */
        public Builder defaultAction(String state, Action action) {
            transitions.add(new Transition(state + ":default", line -> false, action, state));
            return this;
        }

        public StateMachineStrategy build() {
            return new StateMachineStrategy(transitions, initialState);
        }
    }
}