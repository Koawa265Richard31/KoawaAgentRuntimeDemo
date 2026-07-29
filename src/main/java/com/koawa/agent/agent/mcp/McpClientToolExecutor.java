package com.koawa.agent.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具执行器：通过官方 SDK 的 McpSyncClient 调用远端 MCP Server 暴露的工具
 * 负责工具发现（tools/list）、参数封装、调用结果与异常的标准化处理
 */
@Slf4j
@RequiredArgsConstructor
public class McpClientToolExecutor implements McpToolExecutor {

    private final McpSyncClient mcpClient;
    private final Tool toolDefinition;

    @Override
    public Tool getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public CallToolResult execute(Map<String, Object> parameters) {
        long startMs = System.currentTimeMillis();

        try {
            Map<String, Object> args = parameters != null
                    ? parameters
                    : Map.of();

            CallToolResult result = mcpClient
                    .callTool(
                            new CallToolRequest(toolDefinition.name(), args)
                    );

            log.info(
                    "MCP 远程工具调用完成, "
                            + "toolId={}, parameterCount={}, "
                            + "contentSize={}, elapsedMs={}",
                    toolDefinition.name(),
                    args.size(),
                    result.content() != null
                            ? result.content().size()
                            : 0,
                    System.currentTimeMillis() - startMs
            );

            return result;
        } catch (Exception e) {
            String reason = e.getMessage() != null
                    ? e.getMessage()
                    : e.getClass().getSimpleName();

            int parameterCount = parameters == null
                    ? 0
                    : parameters.size();

            log.warn(
                    "MCP 远程工具调用异常, "
                            + "toolId={}, parameterCount={}, "
                            + "elapsedMs={}, errorType={}",
                    toolDefinition.name(),
                    parameterCount,
                    System.currentTimeMillis() - startMs,
                    e.getClass().getSimpleName()
            );

            return CallToolResult.builder()
                    .content(List.of(new TextContent("远程调用失败: " + reason)))
                    .isError(true)
                    .build();
        }
    }
}
