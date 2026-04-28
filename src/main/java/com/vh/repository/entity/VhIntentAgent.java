package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@TableName(value = "vh_intent_agent", autoResultMap = true)
public class VhIntentAgent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vhVersionId;

    /** 意图分类器的模型 (可与主模型不同) */
    private String provider;
    private String modelName;
    private Long credentialId;
    private BigDecimal temperature;
    private Integer maxTokens;

    /** 自定义分类 prompt; null 时走 IntentService 默认模板 */
    private String classifierPrompt;

    /** 没匹配到任何意图时的 fallback 意图 code */
    private String fallbackIntentCode;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extraParams;
}
