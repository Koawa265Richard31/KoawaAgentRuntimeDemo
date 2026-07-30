package com.koawa.agent.agent.checkpoint.snapshot;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionPermit;
import com.koawa.agent.agent.domain.AgentTaskSnapshot;

/**
 * Writes an existing checkpoint only while the presented execution permit
 * remains authoritative.
 */
public interface AgentFencedCheckpointWriter {

    AgentTaskSnapshot save(
            AgentTaskSnapshot snapshot,
            long expectedRevision,
            AgentExecutionPermit permit
    );
}
