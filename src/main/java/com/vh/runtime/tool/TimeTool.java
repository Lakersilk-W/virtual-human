package com.vh.runtime.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 时间查询工具.
 *
 * <p>纯本地, 无网络调用.
 */
@Slf4j
@Component
public class TimeTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss EEEE", Locale.SIMPLIFIED_CHINESE);

    @Tool("查询指定时区的当前时间. 时区如 'Asia/Shanghai' / 'UTC' / 'America/New_York'. 不传或传空走 Asia/Shanghai.")
    public String getCurrentTime(@P("IANA 时区 ID, 例 Asia/Shanghai") String timezone) {
        log.info("Tool getCurrentTime invoked: timezone={}", timezone);
        ZoneId zone;
        try {
            zone = (timezone == null || timezone.isBlank())
                    ? ZoneId.of("Asia/Shanghai")
                    : ZoneId.of(timezone);
        } catch (Exception e) {
            return "时区无效: " + timezone + " (示例: Asia/Shanghai, UTC, America/New_York)";
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        return String.format("当前时间 [%s]: %s", zone.getId(), now.format(FMT));
    }
}
