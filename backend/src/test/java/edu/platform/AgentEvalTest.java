package edu.platform;

import edu.platform.agent.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体工具路由评测：内置 20 条用例（覆盖 14 个工具 + 5 条角色混淆用例），
 * 每条用例用自己声明的 role 跑路由，断言准确率 ≥ 0.9。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentEvalTest {

    @Autowired
    private AgentService agentService;

    @Test
    void toolRoutingAccuracyIsHighEnough() {
        AgentService.EvalResult result = agentService.evaluate();

        System.out.println("=== AgentEvalTest：工具调用准确率 ===");
        for (AgentService.EvalCase c : result.cases()) {
            System.out.println((c.passed() ? "[PASS] " : "[FAIL] ")
                    + "role=" + c.role()
                    + " | 问题：" + c.question()
                    + " | 期望：" + c.expectedTool()
                    + " | 实际：" + c.actualTool()
                    + " | " + c.elapsedMs() + " ms");
        }
        System.out.println("准确率 accuracy = " + result.passed() + "/" + result.total()
                + " = " + result.accuracy());

        long failed = result.cases().stream().filter(c -> !c.passed()).count();
        if (failed > 0) {
            System.out.println("失败用例数 = " + failed);
        }

        assertEquals(20, result.total(), "内置评测集应为 20 条");
        assertTrue(result.accuracy() >= 0.9,
                "工具路由准确率应 ≥ 0.9，实际 " + result.accuracy());
        assertEquals(0, failed, "评测集不应有失败用例");
    }
}
