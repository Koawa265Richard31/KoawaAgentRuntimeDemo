package com.koawa.agent.agent.checkpoint;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
            AgentTaskSnapshotMapper mapper
    ) {
        return new AgentCheckpointService(store, mapper);
    }
}
