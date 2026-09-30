package edu.platform.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间字段序列化：统一输出 ISO-8601（秒精度），例如 {@code 2026-10-08T23:59:00}，与 docs/API.md 的契约一致。
 *
 * <p>这里用代码而不是 application.yml 里的
 * {@code spring.jackson.serialization.write-dates-as-timestamps=false} 配置，
 * 是因为该 yml 属于冻结文件（不得修改）；效果完全相同，并且额外固定了「不输出纳秒」。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer isoLocalDateTimeCustomizer() {
        return builder -> {
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(ISO_SECONDS));
        };
    }
}
