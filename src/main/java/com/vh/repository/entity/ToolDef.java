package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工具注册表实体. 类名故意叫 {@code ToolDef} 而不是 {@code Tool},
 * 避免与 LangChain4j 的 {@link dev.langchain4j.agent.tool.Tool @Tool} 注解同名.
 *
 * <p>对应表: {@code tool}
 */
@Data
@TableName(value = "tool", autoResultMap = true)
public class ToolDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务唯一标识, 比如 weather_query; 与 BuiltinToolRegistry 的方法名通过 mapping 桥接 */
    private String code;
    private String name;
    private String description;
    /** HTTP_API / BUILTIN / MCP */
    private String type;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputSchema;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
