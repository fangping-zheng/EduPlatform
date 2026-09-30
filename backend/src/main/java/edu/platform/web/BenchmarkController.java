package edu.platform.web;

import edu.platform.service.BenchmarkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 性能基准接口（简历量化数字的来源）。需要登录，任意角色可调用。
 *
 * <p>两个接口都在隔离数据集上运行，任何人跑出来的数字一致，可复现、可验证。
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    /** N+1 vs 聚合实现：真实 SQL 条数与耗时对比。 */
    @GetMapping("/stats")
    public BenchmarkService.StatsBenchmark stats(
            @RequestParam(required = false, defaultValue = "BENCH-STATS") String courseId) {
        return benchmarkService.stats(courseId);
    }

    /** 并发选课压测：threads 个线程抢 capacity 个名额，验证不超卖。 */
    @GetMapping("/select")
    public BenchmarkService.SelectBenchmark select(
            @RequestParam(required = false, defaultValue = "BENCH-SELECT") String courseId,
            @RequestParam(required = false, defaultValue = "50") int threads,
            @RequestParam(required = false, defaultValue = "20") int capacity) {
        return benchmarkService.select(courseId, threads, capacity);
    }
}
