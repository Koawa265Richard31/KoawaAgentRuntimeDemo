package com.koawa.agent.agent.planner;

import com.koawa.agent.agent.domain.AgentAction;
import com.koawa.agent.agent.domain.AgentState;

import java.util.List;
import java.util.Objects;

/** Test planner that returns a fixed action sequence. */
public final class ScriptedAgentPlanner implements AgentPlanner {

    private final List<AgentAction> actions;

    public ScriptedAgentPlanner(List<AgentAction> actions) {
        this.actions = List.copyOf(
                Objects.requireNonNull(actions, "actions cannot be null")
        );
    }

    @Override
    public AgentAction plan(AgentState state) {
        Objects.requireNonNull(state, "state cannot be null");
        int currentStep = state.getCurrentStep();
        if (currentStep < 0) {
            throw new IllegalArgumentException(
                    "currentStep cannot be negative: " + currentStep
            );
        }
        if (currentStep >= actions.size()) {
            throw new IllegalStateException(
                    "No scripted actions for step: " + currentStep
            );
        }
        return actions.get(currentStep);
    }
}
