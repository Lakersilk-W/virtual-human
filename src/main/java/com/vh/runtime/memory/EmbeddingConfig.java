package com.vh.runtime.memory;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地 embedding 模型配置 (W3.D18+).
 *
 * <p>选用 BGE-small-zh-v15 (512 维, ONNX in-process) — 中文语料效果好,
 * 不依赖外部 API key, 首次启动会从 Maven 仓库下载模型 (~100MB) 缓存到本地.
 *
 * <p>替代方案 (有需要时切换):
 * <ul>
 *   <li>OpenAI text-embedding-3-small (1536 维, 需 key)</li>
 *   <li>Qwen via DashScope OpenAI 兼容协议 (1024 维, 需 key)</li>
 *   <li>BGE-large-zh (1024 维, 质量更高但内存/启动开销大)</li>
 * </ul>
 * 切换时只需把这里的 Bean 换实现 + 调整 {@code MilvusEpisodeStore} 里的维度常量.
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    /** BGE-small-zh-v15 输出维度. 切换 embedding 时同步改 MilvusEpisodeStore.VECTOR_DIM. */
    public static final int EMBEDDING_DIM = 512;

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Building EmbeddingModel: bge-small-zh-v15 ({} dim, in-process ONNX)",
                EMBEDDING_DIM);
        return new BgeSmallZhV15EmbeddingModel();
    }
}
