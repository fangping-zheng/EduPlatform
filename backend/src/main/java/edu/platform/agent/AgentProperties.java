package edu.platform.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 配置（application.yml 的 {@code agent.*}）。
 *
 * <p>{@code apiKey} 为空时进入 fallback 模式：不调大模型，但仍然真实调用后端只读工具。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String apiKey = "";

    private String baseUrl = "https://api.deepseek.com/v1";

    private String model = "deepseek-chat";

    private int timeoutSeconds = 30;

    /** 单轮对话里最多允许的「模型 → 工具 → 模型」轮数，防止无限循环烧 token。 */
    private int maxToolRounds = 4;

    /** 单个工具结果回灌给模型前的最大字符数（上下文预算管理）。 */
    private int maxToolResultChars = 2000;
}
