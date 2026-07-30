CREATE TABLE agent_execution_lease (
    task_id VARCHAR(128) PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    fencing_token BIGINT NOT NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_execution_lease_task
        FOREIGN KEY (task_id)
        REFERENCES agent_checkpoint (task_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_agent_execution_lease_token
        CHECK (fencing_token > 0)
);

CREATE INDEX idx_agent_execution_lease_expires
    ON agent_execution_lease (lease_expires_at);
