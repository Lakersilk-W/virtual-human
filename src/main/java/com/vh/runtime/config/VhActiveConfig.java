package com.vh.runtime.config;

import java.math.BigDecimal;

/**
 * 一次加载完整的虚拟人「激活配置」, 供运行时调用方使用.
 *
 * 来自 4 个表的合并视图:
 *   virtual_human + vh_version + vh_main_model_config + vh_persona_prompt
 *
 * 是一个不可变快照: Service 调用 {@link VhConfigLoader#load} 时一次取齐, 后续运行时不再回查 DB.
 */
public record VhActiveConfig(
        Long vhId,
        Long versionId,
        Integer versionNo,
        String versionStatus,        // DRAFT / PUBLISHED
        Identity identity,
        ModelConfig model,
        String systemPrompt
) {

    public record Identity(
            String name,
            String gender,
            String hobbies,
            String background
    ) {}

    public record ModelConfig(
            String provider,
            String modelName,
            Long credentialId,
            BigDecimal temperature,
            Integer maxTokens
    ) {}
}
