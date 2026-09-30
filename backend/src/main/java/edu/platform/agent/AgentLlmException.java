package edu.platform.agent;

/** 大模型调用失败（网络 / 超时 / 非 2xx / JSON 解析失败）。由 AgentService 捕获后降级到 fallback。 */
public class AgentLlmException extends RuntimeException {

    public AgentLlmException(String message) {
        super(message);
    }

    public AgentLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
