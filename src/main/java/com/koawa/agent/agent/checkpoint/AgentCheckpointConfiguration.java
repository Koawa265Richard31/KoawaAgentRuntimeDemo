package com.koawa.agent.agent.checkpoint;

import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseProperties;
import com.koawa.agent.agent.checkpoint.lease.AgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.lease.JdbcAgentExecutionLeaseStore;
import com.koawa.agent.agent.checkpoint.query.AgentTaskQueryService;
import com.koawa.agent.agent.checkpoint.resume.AgentInterruptConsumptionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeClaimService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeExecutionService;
import com.koawa.agent.agent.checkpoint.resume.AgentResumeService;
import com.koawa.agent.agent.checkpoint.resume.AgentSnapshotRecoveryService;
import com.koawa.agent.agent.checkpoint.snapshot.*;
import com.koawa.agent.agent.conversation.JdbcAgentConversationStore;
import com.koawa.agent.agent.runner.AgentLoopRunner;
import com.koawa.agent.agent.service.AgentConversationStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AgentExecutionLeaseProperties.class)
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
    public AgentExecutionLeaseStore agentExecutionLeaseStore(
            JdbcTemplate jdbcTemplate
    ) {
        return new JdbcAgentExecutionLeaseStore(jdbcTemplate);
    }

    @Bean
    public AgentFencedCheckpointWriter agentFencedCheckpointWriter(
            JdbcTemplate jdbcTemplate,
            AgentTaskSnapshotJsonCodec codec
    ) {
        return new JdbcAgentFencedCheckpointWriter(jdbcTemplate, codec);
    }

    @Bean
    @Primary
    public AgentConversationStore agentConversationStore(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcAgentConversationStore(
                jdbcTemplate,
                transactionManager
        );
    }

    @Bean
    public AgentCheckpointService agentCheckpointService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock,
            AgentFencedCheckpointWriter fencedWriter,
            AgentConversationStore conversationStore,
            PlatformTransactionManager transactionManager
    ) {
        return new AgentCheckpointService(
                store,
                mapper,
                clock,
                fencedWriter,
                conversationStore,
                new TransactionTemplate(transactionManager)
        );
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

    @Bean
    public AgentResumeService agentResumeService(
            AgentCheckpointStore store
    ) {
        return new AgentResumeService(store);
    }

    @Bean
    public AgentInterruptConsumptionService agentInterruptConsumptionService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock
    ) {
        return new AgentInterruptConsumptionService(
                store,
                mapper,
                clock
        );
    }

    @Bean
    public AgentSnapshotRecoveryService agentSnapshotRecoveryService(
            AgentCheckpointStore store,
            AgentTaskSnapshotMapper mapper,
            Clock clock,
            AgentCheckpointService checkpointService
    ) {
        return new AgentSnapshotRecoveryService(
                store,
                mapper,
                clock,
                checkpointService
        );
    }

    @Bean
    public AgentResumeClaimService agentResumeClaimService(
            AgentResumeService resumeService,
            AgentInterruptConsumptionService consumptionService,
            AgentSnapshotRecoveryService recoveryService,
            AgentExecutionLeaseStore leaseStore,
            AgentCheckpointService checkpointService,
            AgentExecutionLeaseProperties properties,
            Clock clock
    ) {
        return new AgentResumeClaimService(
                resumeService,
                consumptionService,
                recoveryService,
                leaseStore,
                checkpointService,
                clock,
                properties.getLeaseDuration(),
                properties.getRenewInterval()
        );
    }

    @Bean
    public AgentResumeExecutionService agentResumeExecutionService(
            AgentResumeClaimService claimService,
            AgentLoopRunner runner
    ) {
        return new AgentResumeExecutionService(
                claimService,
                runner
        );
    }

    @Bean
    public AgentTaskQueryService agentTaskQueryService(
            AgentCheckpointStore store
    ) {
        return new AgentTaskQueryService(store);
    }
}
