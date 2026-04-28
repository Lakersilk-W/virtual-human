package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.List;

@Data
@TableName(value = "vh_intent", autoResultMap = true)
public class VhIntent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vhVersionId;
    private String intentCode;
    private String intentName;
    private String description;

    /** few-shot 例句列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> examples;

    private Integer sortOrder;
}
