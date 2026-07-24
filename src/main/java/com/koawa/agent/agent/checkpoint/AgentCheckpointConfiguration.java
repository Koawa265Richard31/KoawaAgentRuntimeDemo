package com.koawa.agent.agent.checkpoint;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class AgentCheckpointConfiguration {

    @Bean
    public AgentTaskSnapshotJsonCodec agentTaskSnapshotJsonCodec() {
        return new AgentTaskSnapshotJsonCodec();
    }

    @Bean
    public AgentTaskSnapshotMapper agentTaskSnapshotMapper() {
        return new AgentTaskSnapshotMapper();
    }

    @Bean
    public AgentCheckpointStore agentCheckpointStore(
            JdbcTemplate jdbcTemplate,
            AgentTaskSnapshotJsonCodec codec
    ) {
        return new JdbcAgentCheckpointStore(jdbcTemplate, codec);
    }

    @Bean
    public AgentCheckpointService agentCheckpointService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock
    ) {
        return new AgentCheckpointService(store, mapper, clock);
    }

    @Bean
    public PersistentAgentCheckpointLifecycle agentCheckpointLifecycle(
            AgentCheckpointService checkpointService,
            Clock clock
    ) {
        return new PersistentAgentCheckpointLifecycle(
                checkpointService,
                clock);
    }
}
