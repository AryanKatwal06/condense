package com.condense.filter.strategy;

import com.condense.filter.pipeline.DocumentSession;
import com.condense.filter.pipeline.EmissionSink;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.StageSession;
import com.condense.filter.pipeline.Streamability;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class StateMachineStrategy implements FilterStage {

    public enum Action { EMIT, DISCARD, COLLECT }

    public record Transition(
        String fromState,
        Predicate<String> trigger,
        Action action,
        String nextState
    ) {}

    private final List<Transition> transitions;
    private final String initialState;
    private final boolean hasCollect;

    private StateMachineStrategy(List<Transition> transitions, String initialState) {
        this.transitions = transitions;
        this.initialState = initialState;
        this.hasCollect = transitions.stream().anyMatch(t -> t.action() == Action.COLLECT);
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        if (input == null || input.isEmpty()) {
            return StageResult.continueWith("");
        }
        List<String> lines = input.lines().toList();
        List<String> processed = process(lines);
        return StageResult.continueWith(String.join("\n", processed));
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

    @Override
    public Streamability streamability() {
        return hasCollect ? Streamability.DOCUMENT : Streamability.ORDER_LOCAL;
    }

    @Override
    public StageSession openSession() {
        if (hasCollect) {
            return new DocumentSession(this);
        }
        return new Session();
    }

    private final class Session implements StageSession {
        private String state = initialState;

        @Override
        public void acceptDocument(String text, EmissionSink sink, FilterContext context) {
            StageResult result = process(text, context);
            sink.emitDocument(result.output());
        }

        @Override
        public void feedLine(String line, EmissionSink sink, FilterContext context) {
            String value = line != null ? line : "";
            boolean transitioned = false;
            for (Transition t : transitions) {
                if (t.fromState().equals(state) && t.trigger().test(value)) {
                    if (t.action() == Action.EMIT) {
                        sink.emit(value);
                    }
                    state = t.nextState();
                    transitioned = true;
                    break;
                }
            }
            if (!transitioned) {
                for (Transition t : transitions) {
                    if (t.fromState().equals(state + ":default")) {
                        if (t.action() == Action.EMIT) {
                            sink.emit(value);
                        }
                        break;
                    }
                }
            }
        }

        @Override
        public void endOfInput(EmissionSink sink, FilterContext context) {
            // EMIT lines already flushed
        }
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
            return on(fromState, pattern, action, nextState, BoundedRegex.TIMEOUT_MS);
        }

        /** On matching line in {@code fromState} with bounded timeout: apply {@code action}, move to {@code nextState}. */
        public Builder on(String fromState, Pattern pattern, Action action, String nextState, long timeoutMillis) {
            transitions.add(new Transition(fromState, line -> BoundedRegex.find(pattern, line), action, nextState));
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