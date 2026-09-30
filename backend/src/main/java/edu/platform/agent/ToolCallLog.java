package edu.platform.agent;

import java.util.Map;

/** 一次真实工具调用的审计记录（无论 llm 还是 fallback 模式都来自真实执行）。 */
public record ToolCallLog(
        String name,
        Map<String, Object> arguments,
        String result,
        long elapsedMs,
        boolean ok,
        String error) {
}
