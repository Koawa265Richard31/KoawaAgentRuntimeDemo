package com.koawa.agent.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Optional;

/**
 * MCP 工具注册表接口
 */
public interface McpToolRegistry {

    /**
     * 注册工具执行器
     *
     * @param executor 工具执行器
     */
    void register(McpToolExecutor executor);

    /**
     * 根据工具 ID 获取执行器
     *
     * @param toolId 工具 ID
     * @return 工具执行器（可能不存在）
     */
    Optional<McpToolExecutor> getExecutor(String toolId);

    /**
     * 获取所有已注册的工具定义
     *
     * @return 工具定义列表
     */
    List<Tool> listAllTools();

}
