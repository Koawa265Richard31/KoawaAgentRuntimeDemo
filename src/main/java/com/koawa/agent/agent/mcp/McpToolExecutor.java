package com.koawa.agent.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Map;

/**
 * MCP 工具执行器接口
 */
public interface McpToolExecutor {

    /**
     * 获取工具定义
     *
     * @return 工具元信息（使用官方 SDK 的 Tool）
     */
    Tool getToolDefinition();

    /**
     * 执行工具调用
     *
     * @param parameters 调用参数
     * @return 工具调用结果（使用官方 SDK 的 CallToolResult）
     */
    CallToolResult execute(Map<String, Object> parameters);

    /**
     * 工具 ID（快捷方法）
     */
    default String getToolId() {
        return getToolDefinition().name();
    }
}
