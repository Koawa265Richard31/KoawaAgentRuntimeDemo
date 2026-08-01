CREATE TABLE agent_conversation_head (
    conversation_scope_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128),
    next_turn_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_agent_conversation_head_scope
        UNIQUE NULLS NOT DISTINCT (conversation_id, user_id),
    CONSTRAINT chk_agent_conversation_head_conversation_id
        CHECK (conversation_id ~ '[^[:space:]]'),
    CONSTRAINT chk_agent_conversation_head_user_id
        CHECK (user_id IS NULL OR user_id ~ '[^[:space:]]'),
    CONSTRAINT chk_agent_conversation_head_sequence
        CHECK (next_turn_sequence >= 0)
);

CREATE TABLE agent_conversation_turn (
    conversation_scope_id BIGINT NOT NULL,
    turn_sequence BIGINT NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    terminal_step_index INTEGER NOT NULL,
    input_type VARCHAR(32) NOT NULL,
    source_interrupt_id VARCHAR(128),
    input_content TEXT NOT NULL,
    output_type VARCHAR(32) NOT NULL,
    output_content TEXT NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_agent_conversation_turn
        PRIMARY KEY (conversation_scope_id, turn_sequence),
    CONSTRAINT uq_agent_conversation_turn_identity
        UNIQUE (task_id, terminal_step_index),
    CONSTRAINT fk_agent_conversation_turn_head
        FOREIGN KEY (conversation_scope_id)
        REFERENCES agent_conversation_head (conversation_scope_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_agent_conversation_turn_sequence
        CHECK (turn_sequence > 0),
    CONSTRAINT chk_agent_conversation_turn_step
        CHECK (terminal_step_index >= 0),
    CONSTRAINT chk_agent_conversation_turn_task_id
        CHECK (task_id ~ '[^[:space:]]'),
    CONSTRAINT chk_agent_conversation_turn_input_content
        CHECK (input_content ~ '[^[:space:]]'),
    CONSTRAINT chk_agent_conversation_turn_output_content
        CHECK (output_content ~ '[^[:space:]]'),
    CONSTRAINT chk_agent_conversation_turn_output_type
        CHECK (output_type IN ('FINAL_ANSWER', 'ASK_CLARIFICATION')),
    CONSTRAINT chk_agent_conversation_turn_input_source
        CHECK (
            (input_type = 'ORIGINAL_QUESTION'
                AND source_interrupt_id IS NULL)
            OR
            (input_type = 'INTERRUPT_REPLY'
                AND source_interrupt_id IS NOT NULL
                AND source_interrupt_id ~ '[^[:space:]]')
        )
);
