package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "execution_trace", autoResultMap = true)
public class ExecutionTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    /** 触发本次 trace 链的 user message id, 当前未关联可为空 */
    private Long messageId;

    /** 见 {@link com.vh.runtime.trace.TraceStep} */
    private String step;
    private Integer stepOrder;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> input;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> output;

    private Integer durationMs;
    private String errorMsg;

    private LocalDateTime createdAt;
}
