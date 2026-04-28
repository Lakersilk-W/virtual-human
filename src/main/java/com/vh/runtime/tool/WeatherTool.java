package com.vh.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 天气查询工具. 用 wttr.in 免 key 接口.
 *
 * <p>响应示例 (j1 格式片段):
 * <pre>
 * {
 *   "current_condition": [{
 *     "temp_C": "18", "FeelsLikeC": "16", "humidity": "65",
 *     "windspeedKmph": "12",
 *     "weatherDesc": [{"value":"Partly cloudy"}],
 *     "lang_zh": [{"value":"局部多云"}]
 *   }],
 *   "nearest_area": [{ "areaName":[{"value":"Shanghai"}] }]
 * }
 * </pre>
 */
@Slf4j
@Component
public class WeatherTool {

    private final RestClient http = createClient();
    private final ObjectMapper objectMapper;

    public WeatherTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static RestClient createClient() {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(5000);
        rf.setReadTimeout(15000);
        return RestClient.builder().requestFactory(rf).build();
    }

    @Tool("查询指定城市的当前天气. 城市名建议用英文, 如 Beijing/Shanghai/Hangzhou; 中文也可被识别.")
    public String getWeather(@P("城市名, 如 Beijing 或 上海") String city) {
        log.info("Tool getWeather invoked: city={}", city);
        try {
            // wttr.in 返回 Content-Type: application/text (不规范),
            // 直接 body(JsonNode.class) 找不到 converter; 先取 String 再 Jackson 自己解.
            String body = http.get()
                    .uri("https://wttr.in/{city}?format=j1", city)
                    .header("User-Agent", "curl/7.79.1")
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                return "查询失败: 无响应";
            }

            JsonNode json = objectMapper.readTree(body);

            JsonNode current = json.path("current_condition").path(0);
            JsonNode area = json.path("nearest_area").path(0);

            String areaName = area.path("areaName").path(0).path("value").asText("");
            String descZh = current.path("lang_zh").path(0).path("value").asText("");
            String descEn = current.path("weatherDesc").path(0).path("value").asText("");
            String tempC = current.path("temp_C").asText("");
            String feels = current.path("FeelsLikeC").asText("");
            String humidity = current.path("humidity").asText("");
            String wind = current.path("windspeedKmph").asText("");

            String desc = !descZh.isEmpty() ? descZh : descEn;
            return String.format(
                    "城市: %s, 天气: %s, 气温: %s°C, 体感: %s°C, 湿度: %s%%, 风速: %s km/h",
                    areaName.isEmpty() ? city : areaName,
                    desc, tempC, feels, humidity, wind);
        } catch (Exception e) {
            log.warn("Weather query failed for {}: {}", city, e.toString());
            return "查询 " + city + " 天气时出错: " + e.getMessage();
        }
    }
}
