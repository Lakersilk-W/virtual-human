package com.vh.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("virtual_human")
public class VirtualHuman {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long ownerId;
    private String name;
    private String gender;
    private String hobbies;
    private String background;

    private Long draftVersionId;
    private Long publishedVersionId;

    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
