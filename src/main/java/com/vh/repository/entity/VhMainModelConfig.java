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
@TableName(value = "vh_main_model_config", autoResultMap = true)
public class VhMainModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vhVersionId;
    private String provider;
    private String modelName;
    private Long credentialId;
    private BigDecimal temperature;
    private Integer maxTokens;
    private BigDecimal topP;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extraParams;
}
