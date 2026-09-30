package edu.platform.config;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * 统计当前线程真实执行的 SQL 语句条数。
 *
 * <p>用途：把「N+1 查询」这类性能问题变成可测量的数字，而不是嘴上说说。
 * 基准测试接口会先 reset()，再执行一段业务逻辑，最后读取 count()，
 * 得到的差值就是这段逻辑真正打到数据库的 SQL 条数。
 */
public class SqlCountInspector implements StatementInspector {

    private static final ThreadLocal<int[]> COUNTER = ThreadLocal.withInitial(() -> new int[]{0});

    @Override
    public String inspect(String sql) {
        int[] c = COUNTER.get();
        c[0]++;
        return sql;
    }

    public static void reset() {
        COUNTER.get()[0] = 0;
    }

    public static int count() {
        return COUNTER.get()[0];
    }

    public static void clear() {
        COUNTER.remove();
    }
}
