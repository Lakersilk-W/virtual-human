package com.vh.runtime.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台内置工具注册表.
 *
 * <h3>注册方式</h3>
 * 反射扫描 {@code @Tool} 注解的方法,
 * 用 {@link ToolSpecifications} 派生 spec, 用 {@link DefaultToolExecutor} 派生执行器.
 *
 * <h3>双索引</h3>
 * <ul>
 *   <li>按 LC4j 工具名 (Java 方法名) 索引: 模型回吐 ToolExecutionRequest 时用这个</li>
 *   <li>按 DB tool.code 索引: AgentRouter 按 vh_intent.bound_tool_id → tool.code 来取工具</li>
 * </ul>
 * 两者通过 {@link #DB_CODE_TO_METHOD} 映射桥接, 加新工具时同步加一行.
 */
@Slf4j
@Component
public class BuiltinToolRegistry {

    /** DB tool.code → @Tool 方法名. 加新工具时在这里同步. */
    private static final Map<String, String> DB_CODE_TO_METHOD = Map.of(
            "weather_query", "getWeather",
            "calculator",    "calculate",
            "current_time",  "getCurrentTime"
    );

    @Getter
    private final List<ToolSpecification> specifications = new ArrayList<>();
    private final Map<String, ToolSpecification> specByMethodName = new HashMap<>();
    private final Map<String, ToolExecutor> executorByMethodName = new HashMap<>();

    public BuiltinToolRegistry(WeatherTool weatherTool,
                               CalculatorTool calculatorTool,
                               TimeTool timeTool) {
        register(weatherTool);
        register(calculatorTool);
        register(timeTool);
    }

    private void register(Object toolBean) {
        for (Method method : toolBean.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) continue;

            ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(method);
            specifications.add(spec);
            specByMethodName.put(spec.name(), spec);
            executorByMethodName.put(spec.name(), new DefaultToolExecutor(toolBean, method));

            log.info("Registered builtin tool: name={} description='{}'",
                    spec.name(), spec.description());
        }
    }

    /** 按 LC4j 工具名 (Java 方法名) 取执行器. 模型回吐的 ToolExecutionRequest.name() 走这里. */
    public ToolExecutor getExecutor(String toolName) {
        return executorByMethodName.get(toolName);
    }

    /** 按 DB tool.code 取 ToolSpecification (供 AgentRouter / ToolWorker 使用). */
    public ToolSpecification getSpecByDbCode(String dbCode) {
        String methodName = DB_CODE_TO_METHOD.get(dbCode);
        return methodName == null ? null : specByMethodName.get(methodName);
    }

    /** 按 DB tool.code 取 ToolExecutor. */
    public ToolExecutor getExecutorByDbCode(String dbCode) {
        String methodName = DB_CODE_TO_METHOD.get(dbCode);
        return methodName == null ? null : executorByMethodName.get(methodName);
    }

    public boolean hasAny() {
        return !specifications.isEmpty();
    }
}
