package com.condense.filter.strategy;

import com.condense.annotation.DeclarativeStage;
import com.condense.filter.pipeline.DocumentSession;
import com.condense.filter.pipeline.EmissionSink;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.StageSession;
import com.condense.filter.pipeline.Streamability;
import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.filter.pipeline.config.StageFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@DeclarativeStage(aliases = {"state_machine", "state-machine"}, capability = "REWRITE", factory = "fromDef")
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

    public static FilterStage fromDef(FilterOverrideConfig.StageDef stageDef) {
        String initialState = stageDef.initialState() != null ? stageDef.initialState().trim() : "START";
        Builder builder = builder(initialState);
        if (stageDef.transitions() != null) {
            for (FilterOverrideConfig.TransitionDef t : stageDef.transitions()) {
                if (t.fromState() == null || t.pattern() == null || t.nextState() == null) {
                    continue;
                }
                Pattern p = Pattern.compile(t.pattern());
                builder.on(t.fromState().trim(), p, parseAction(t.action()), t.nextState().trim(),
                    StageFactory.REGEX_TIMEOUT_MS);
            }
        }
        if (stageDef.defaultActions() != null) {
            for (Map.Entry<String, String> entry : stageDef.defaultActions().entrySet()) {
                builder.defaultAction(entry.getKey().trim(), parseAction(entry.getValue()));
            }
        }
        return builder.build();
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        if (stage.initialState() == null || stage.initialState().isBlank()) {
            errors.add(location + ": 'initial_state' must not be empty");
        }
        if (stage.transitions() != null) {
            if (stage.transitions().size() > StageFactory.MAX_TRANSITIONS_COUNT) {
                errors.add(location + ": 'transitions' count exceeds maximum allowed limit of "
                    + StageFactory.MAX_TRANSITIONS_COUNT);
            }
            for (int tIdx = 0; tIdx < stage.transitions().size(); tIdx++) {
                FilterOverrideConfig.TransitionDef t = stage.transitions().get(tIdx);
                String tLoc = location + ".transitions[" + tIdx + "]";
                if (t.fromState() == null || t.fromState().isBlank()) {
                    errors.add(tLoc + ": 'from_state' must not be empty");
                }
                if (t.nextState() == null || t.nextState().isBlank()) {
                    errors.add(tLoc + ": 'next_state' must not be empty");
                }
                if (t.pattern() == null || t.pattern().isBlank()) {
                    errors.add(tLoc + ": 'pattern' must not be empty");
                } else if (t.pattern().length() > StageFactory.MAX_PATTERN_LENGTH) {
                    errors.add(tLoc + ": 'pattern' regex exceeds maximum allowed length of "
                        + StageFactory.MAX_PATTERN_LENGTH + " characters");
                } else {
                    try {
                        Pattern.compile(t.pattern());
                    } catch (java.util.regex.PatternSyntaxException e) {
                        errors.add(tLoc + ": Invalid regex in 'pattern': " + e.getMessage());
                    }
                }
                if (t.action() != null && !t.action().isBlank()) {
                    try {
                        Action.valueOf(t.action().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add(tLoc + ": Invalid 'action': '" + t.action()
                            + "'. Allowed: EMIT, DISCARD, COLLECT");
                    }
                }
            }
        }
        if (stage.defaultActions() != null) {
            for (Map.Entry<String, String> entry : stage.defaultActions().entrySet()) {
                String act = entry.getValue();
                if (act != null && !act.isBlank()) {
                    try {
                        Action.valueOf(act.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add(location + ".default_actions['" + entry.getKey()
                            + "']: Invalid action: '" + act + "'. Allowed: EMIT, DISCARD, COLLECT");
                    }
                }
            }
        }
    }

    private static Action parseAction(String actionStr) {
        if (actionStr == null || actionStr.isBlank()) {
            return Action.EMIT;
        }
        try {
            return Action.valueOf(actionStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Action.EMIT;
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