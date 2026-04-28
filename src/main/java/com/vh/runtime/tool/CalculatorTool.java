package com.vh.runtime.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * 数学计算工具.
 *
 * <h3>实现选择</h3>
 * 用 Spring 的 {@link SpelExpressionParser} + {@link SimpleEvaluationContext} 求值.
 * <p>{@code SimpleEvaluationContext.forReadOnlyDataBinding()} 是关键——它**禁止类型引用**
 * (即 {@code T(java.lang.Runtime)} 这种), 不允许任意 Java 方法调用, 防止 LLM 生成的
 * 表达式逃逸成 RCE 漏洞.
 *
 * <h3>支持</h3>
 * 加减乘除、括号、整数/小数. 例: {@code 3+5}, {@code (100-25)*2}, {@code 128/4.0}.
 */
@Slf4j
@Component
public class CalculatorTool {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final SimpleEvaluationContext sandboxedCtx =
            SimpleEvaluationContext.forReadOnlyDataBinding().build();

    @Tool("计算一个数学表达式的值. 支持 + - * / 和括号. 例: '3+5', '(100-25)*2', '128/4'.")
    public String calculate(@P("数学表达式字符串") String expression) {
        log.info("Tool calculate invoked: expr={}", expression);
        if (expression == null || expression.isBlank()) {
            return "计算失败: 表达式为空";
        }
        try {
            Object result = parser.parseExpression(expression).getValue(sandboxedCtx);
            return String.format("%s = %s", expression, result);
        } catch (Exception e) {
            log.warn("Calculator failed for '{}': {}", expression, e.toString());
            return String.format("计算失败 '%s': %s", expression, e.getMessage());
        }
    }
}
