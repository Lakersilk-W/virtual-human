package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Map;

@Data
@TableName(value = "vh_persona_prompt", autoResultMap = true)
public class VhPersonaPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vhVersionId;
    private String systemPrompt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> variables;
}
