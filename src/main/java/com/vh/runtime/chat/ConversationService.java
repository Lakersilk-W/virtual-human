package com.vh.runtime.chat;

import com.vh.repository.entity.Conversation;
import com.vh.repository.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 会话生命周期: 新开 / 续接 / 心跳 / 关闭.
 *
 * MVP 期 tenant/user 写死(=1); 后续接入鉴权后从 SecurityContext 取.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final long DEFAULT_TENANT_ID = 1L;
    private static final long DEFAULT_USER_ID = 1L;

    private final ConversationMapper conversationMapper;

    /**
     * 给定 conversationId 则取已存在; 没给就新建一条 PROD 通道的会话.
     */
    @Transactional
    public Conversation getOrCreate(Long conversationId, Long vhId, Long vhVersionId) {
        if (conversationId != null) {
            Conversation existing = conversationMapper.selectById(conversationId);
            if (existing == null) {
                throw new IllegalArgumentException("Conversation not found: " + conversationId);
            }
            if (!existing.getVhId().equals(vhId)) {
                throw new IllegalArgumentException(
                        "Conversation " + conversationId + " belongs to vh " + existing.getVhId()
                        + ", not " + vhId);
            }
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        Conversation conv = new Conversation();
        conv.setTenantId(DEFAULT_TENANT_ID);
        conv.setVhId(vhId);
        conv.setVhVersionId(vhVersionId);
        conv.setUserId(DEFAULT_USER_ID);
        conv.setChannel("PROD");
        conv.setStatus("ACTIVE");
        conv.setCreatedAt(now);
        conv.setLastActiveAt(now);
        conversationMapper.insert(conv);
        log.info("Created conversation {} for vhId={} versionId={}", conv.getId(), vhId, vhVersionId);
        return conv;
    }

    public void touch(Long conversationId) {
        Conversation c = new Conversation();
        c.setId(conversationId);
        c.setLastActiveAt(LocalDateTime.now());
        conversationMapper.updateById(c);
    }
}
