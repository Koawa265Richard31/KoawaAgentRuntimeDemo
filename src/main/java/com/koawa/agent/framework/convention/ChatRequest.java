package com.koawa.agent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用大模型请求对象
 *
 * <p>
 * 用于封装一次完整对话所需的所有上下文与控制参数，作为「统一入参」传给
 * 各种不同厂商 / 协议的大模型接口（如 Ollama、百炼、OpenAI 等），
 * 方便在适配层做统一转换
 * </p>
 *
 * <p>典型使用方式：</p>
 * <pre>
 * ChatRequest req = ChatRequest.builder()
 *     .temperature(0.3)
 *     .maxTokens(512)
 *     .build();
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    /**
     * 完整消息列表
     * <p>
     * 用于直接传入 system/user/assistant 消息序列。
     * 当 messages 非空时，适配层使用该字段构造请求；
     * prompt 会作为额外的 user 消息追加。
     * </p>
     */
    @Default
    private List<ChatMessage> messages = new ArrayList<>();

    // ================== 模型控制参数 ==================

    /**
     * 采样温度参数，取值通常为 0～2
     * <p>
     * 数值越小，输出越稳定、保守；数值越大，生成内容越发散、创造性更强
     * 例如：问答场景可用 0.1～0.5，创作类可用 0.7 以上
     * </p>
     */
    private Double temperature;

    /**
     * nucleus sampling（Top-P）参数
     * <p>
     * 表示从累积概率为 P 的词集合中采样，常与 {@link #temperature} 搭配使用
     * 一般取值在 0.8～0.95 之间，越小越保守
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Double topP;

    /**
     * Top-K 采样参数
     * <p>
     * 表示每一步只从概率最高的 K 个 token 中采样，常与 {@link #temperature}
     * 或 {@link #topP} 搭配使用。K 越小越保守，K 越大越发散
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Integer topK;

    /**
     * 限制模型本次回答最多生成的 token 数量
     * <p>
     * 可用于控制回复长度与成本；若为 {@code null}，则走模型或服务端默认配置
     * </p>
     */
    private Integer maxTokens;

    /**
     * 可选：是否启用「思考模式」开关
     * <p>
     * OpenAI 兼容适配器会将其转换为 enable_thinking 参数。
     * </p>
     */
    private Boolean thinking;

    /**
     * 本次调用允许执行到的绝对截止时间。
     *
     * <p>为 null 时使用底层客户端的默认超时；
     * 非 null 时，客户端应根据当前时间计算剩余预算。</p>
     */
    private Instant deadlineAt;
}
