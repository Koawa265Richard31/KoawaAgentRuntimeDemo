package com.koawa.agent.infra.chat;

import com.koawa.agent.framework.convention.ChatRequest;

public interface LLMService {

    String chat(ChatRequest request);
}
