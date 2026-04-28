package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * vh_intent ↔ tool 多对多绑定. 同一意图可绑多个工具,
 * 让 ToolWorker 把全部 ToolSpecification 注册给模型, 单轮内由模型决定是否并行 fan-out.
 */
@Data
@TableName("vh_intent_tool")
public class VhIntentTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long intentId;
    private Long toolId;
    private Integer sortOrder;
}
