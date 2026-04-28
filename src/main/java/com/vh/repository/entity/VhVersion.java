package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("vh_version")
public class VhVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long vhId;
    private Integer versionNo;
    /** DRAFT / PUBLISHED / ARCHIVED */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
}
