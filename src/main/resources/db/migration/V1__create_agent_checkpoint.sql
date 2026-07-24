CREATE TABLE agent_checkpoint (
    task_id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128),
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_agent_checkpoint_conversation_updated
    ON agent_checkpoint (conversation_id, updated_at DESC);

CREATE INDEX idx_agent_checkpoint_user_status_updated
    ON agent_checkpoint (user_id, status, updated_at DESC);
