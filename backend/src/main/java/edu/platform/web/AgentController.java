package edu.platform.web;

import edu.platform.security.AuthUser;
import edu.platform.security.CurrentUsers;
import edu.platform.agent.AgentService;
import edu.platform.web.dto.ChatRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 接口：对话、工具清单、评测集。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public AgentService.AgentResponse chat(@RequestBody ChatRequest request) {
        return agentService.chat(request.sessionId(), request.message(), CurrentUsers.require());
    }

    /** 已放行（无需登录），只返回工具元信息，不含任何业务数据。 */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        String role = CurrentUsers.find().map(AuthUser::role).orElse(null);
        return agentService.tools(role);
    }

    @GetMapping("/eval")
    public AgentService.EvalResult eval() {
        return agentService.evaluate();
    }
}
