package edu.platform;

import edu.platform.service.BenchmarkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N+1 优化的实测数字：跑隔离数据集（30 学生 × 4 作业），用 {@code SqlCountInspector}
 * 读真实 SQL 条数，断言优化实现确实把查询数降下来了。
 */
@SpringBootTest
@ActiveProfiles("test")
class StatsNPlusOneTest {

    @Autowired
    private BenchmarkService benchmarkService;

    @Test
    void optimizedImplementationRunsFarFewerQueries() {
        BenchmarkService.StatsBenchmark result = benchmarkService.stats("BENCH-TEST");

        BenchmarkService.Block naive = result.naive();
        BenchmarkService.Block optimized = result.optimized();

        System.out.println("=== StatsNPlusOneTest（隔离数据集 " + result.fixture().students()
                + " 学生 × " + result.fixture().assignments() + " 作业）===");
        System.out.println("naive     queryCount = " + naive.queryCount()
                + "，elapsedMs = " + naive.elapsedMs() + "，" + naive.runs() + " 次平均");
        System.out.println("optimized queryCount = " + optimized.queryCount()
                + "，elapsedMs = " + optimized.elapsedMs() + "，" + optimized.runs() + " 次平均");
        System.out.println("speedup              = " + result.speedup() + "×");
        System.out.println("减少查询次数          = " + (naive.queryCount() - optimized.queryCount()) + " 条");
        System.out.println("两种实现统计到的提交总数是否一致 = "
                + (naive.totalSubmissions() == optimized.totalSubmissions())
                + "（naive=" + naive.totalSubmissions() + "，optimized=" + optimized.totalSubmissions() + "）");

        assertEquals(naive.totalSubmissions(), optimized.totalSubmissions(),
                "两种实现的统计结果必须一致，否则说明优化改变了语义");
        assertTrue(optimized.queryCount() < naive.queryCount(),
                "优化实现必须比 naive 少查 SQL：optimized=" + optimized.queryCount()
                        + "，naive=" + naive.queryCount());
        assertTrue(optimized.queryCount() <= 5,
                "优化实现应稳定在个位数查询：实际 " + optimized.queryCount());
        assertTrue(naive.queryCount() > 100,
                "naive 实现应随数据量线性增长（预期 120+ 条）：实际 " + naive.queryCount());
    }
}
