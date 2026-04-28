package com.vh.runtime.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vh.repository.entity.VhMainModelConfig;
import com.vh.repository.entity.VhPersonaPrompt;
import com.vh.repository.entity.VhVersion;
import com.vh.repository.entity.VirtualHuman;
import com.vh.repository.mapper.VhMainModelConfigMapper;
import com.vh.repository.mapper.VhPersonaPromptMapper;
import com.vh.repository.mapper.VhVersionMapper;
import com.vh.repository.mapper.VirtualHumanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 加载虚拟人激活版本的完整配置.
 *
 * <h3>版本选择策略</h3>
 * - {@link Channel#PUBLISHED}: 用 published_version_id, 没发布过就抛异常
 * - {@link Channel#DRAFT}:     用 draft_version_id (调试/测试用)
 *
 * <h3>未来扩展</h3>
 * - 加缓存(本机 Caffeine), 因为同一 vhId 的对话期间不会变;
 *   发布时主动失效 (W4 再做)
 * - 加权限校验 (W4 加多租户隔离时)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VhConfigLoader {

    private final VirtualHumanMapper virtualHumanMapper;
    private final VhVersionMapper vhVersionMapper;
    private final VhMainModelConfigMapper vhMainModelConfigMapper;
    private final VhPersonaPromptMapper vhPersonaPromptMapper;

    public VhActiveConfig load(Long vhId, Channel channel) {
        VirtualHuman vh = virtualHumanMapper.selectById(vhId);
        if (vh == null || vh.getDeletedAt() != null) {
            throw new IllegalArgumentException("Virtual human not found: " + vhId);
        }

        Long versionId = switch (channel) {
            case PUBLISHED -> {
                if (vh.getPublishedVersionId() == null) {
                    throw new IllegalStateException("VH " + vhId + " has no published version");
                }
                yield vh.getPublishedVersionId();
            }
            case DRAFT -> {
                if (vh.getDraftVersionId() == null) {
                    throw new IllegalStateException("VH " + vhId + " has no draft version");
                }
                yield vh.getDraftVersionId();
            }
        };

        VhVersion version = vhVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalStateException("Version not found: " + versionId);
        }

        VhMainModelConfig modelCfg = vhMainModelConfigMapper.selectOne(
                Wrappers.<VhMainModelConfig>lambdaQuery()
                        .eq(VhMainModelConfig::getVhVersionId, versionId));
        if (modelCfg == null) {
            throw new IllegalStateException(
                    "Missing main model config for version " + versionId);
        }

        VhPersonaPrompt persona = vhPersonaPromptMapper.selectOne(
                Wrappers.<VhPersonaPrompt>lambdaQuery()
                        .eq(VhPersonaPrompt::getVhVersionId, versionId));
        if (persona == null) {
            throw new IllegalStateException(
                    "Missing persona prompt for version " + versionId);
        }

        return new VhActiveConfig(
                vh.getId(),
                version.getId(),
                version.getVersionNo(),
                version.getStatus(),
                new VhActiveConfig.Identity(
                        vh.getName(), vh.getGender(),
                        vh.getHobbies(), vh.getBackground()),
                new VhActiveConfig.ModelConfig(
                        modelCfg.getProvider(),
                        modelCfg.getModelName(),
                        modelCfg.getCredentialId(),
                        modelCfg.getTemperature(),
                        modelCfg.getMaxTokens()),
                persona.getSystemPrompt()
        );
    }

    public enum Channel {
        PUBLISHED, DRAFT
    }
}
