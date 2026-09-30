package edu.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议的极简客户端（JDK 自带 HttpClient + Jackson，不引第三方 HTTP 库）。
 *
 * <p>只做一件事：把 messages + tools 发到 {@code POST {baseUrl}/chat/completions}，
 * 解析出 {@code choices[0].message} 里的 tool_calls 或 content，以及 usage 的 token 用量。
 * 任何异常都抛 {@link AgentLlmException}，由 AgentService 降级到 fallback（不让接口 500）。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public LlmClient(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(3, props.getTimeoutSeconds())))
                .build();
    }

    public record ToolCallRequest(String id, String name, String arguments) {
    }

    public record LlmReply(String content, List<ToolCallRequest> toolCalls, int promptTokens, int completionTokens) {
    }

    public LlmReply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AgentLlmException("请求体序列化失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(props.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(3, props.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AgentLlmException("调用大模型失败：" + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AgentLlmException("大模型返回非 2xx：" + response.statusCode() + " "
                    + abbreviate(response.body()));
        }
        try {
            return parse(response.body());
        } catch (AgentLlmException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentLlmException("响应解析失败：" + e.getMessage(), e);
        }
    }

    private LlmReply parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AgentLlmException("响应缺少 choices：" + abbreviate(body));
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.hasNonNull("content") ? message.get("content").asText() : null;

        List<ToolCallRequest> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                String id = call.path("id").asText("");
                JsonNode function = call.path("function");
                toolCalls.add(new ToolCallRequest(
                        id,
                        function.path("name").asText(""),
                        function.hasNonNull("arguments") ? function.get("arguments").asText() : "{}"));
            }
        }

        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        log.debug("LLM 返回：content={} toolCalls={} tokens={}/{}", content, toolCalls.size(), prompt, completion);
        return new LlmReply(content, toolCalls, prompt, completion);
    }

    /** 把工具声明转成 OpenAI function 格式。 */
    public Map<String, Object> toFunctionTool(ToolSpec spec) {
        ObjectNode function = mapper.createObjectNode();
        function.put("name", spec.name());
        function.put("description", spec.description());
        function.set("parameters", spec.parameters());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.deepseek.com/v1";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "(空)";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
