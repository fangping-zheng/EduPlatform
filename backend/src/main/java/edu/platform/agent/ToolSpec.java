package edu.platform.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具声明（给模型看的元信息 + 给服务端执行的处理函数）。
 *
 * <p>{@code readOnly} 恒为 true：写操作（选课、提交作业、评分）**刻意不暴露**给模型，
 * 模型只能通过受控的只读工具读取真实业务数据，无法越权修改数据 —— 这是本 Agent 的设计边界。
 *
 * <p>{@code handler} 只允许调用已有的 service，不在工具里自己写 SQL。
 */
public record ToolSpec(
        String name,
        String description,
        String role,
        boolean readOnly,
        ObjectNode parameters,
        Function<Map<String, Object>, Object> handler) {
}
