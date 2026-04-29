package com.vh.runtime.memory;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 客户端封装 (W3.D18+).
 *
 * <h3>collection 设计</h3>
 * <pre>
 * episode_vectors {
 *   id      INT64 (auto, primary)
 *   user_id INT64 (filter)
 *   conv_id INT64 (filter, 召回时排除当前会话)
 *   vector  FLOAT_VECTOR(512)  -- BGE-small-zh-v15
 * }
 * </pre>
 * IVF_FLAT + IP (内积, BGE 已 normalize, 等价 cosine).
 *
 * <h3>启动行为</h3>
 * 应用启动时调 {@link #ensureCollection}, 不存在就建 + 建索引 + load.
 * 应用关闭时 {@link #close} 优雅断开. 连不上 Milvus 不会启动失败 (warn + 后续操作短路).
 */
@Slf4j
@Component
public class MilvusEpisodeStore {

    public static final String COLLECTION = "episode_vectors";
    public static final int VECTOR_DIM = EmbeddingConfig.EMBEDDING_DIM;

    public static final String FIELD_ID = "id";
    public static final String FIELD_USER_ID = "user_id";
    public static final String FIELD_CONV_ID = "conv_id";
    public static final String FIELD_VECTOR = "vector";

    private final String host;
    private final int port;
    private MilvusServiceClient client;
    private boolean ready = false;

    public MilvusEpisodeStore(@Value("${vh.milvus.host:localhost}") String host,
                              @Value("${vh.milvus.port:19530}") int port) {
        this.host = host;
        this.port = port;
    }

    @PostConstruct
    public void init() {
        try {
            this.client = new MilvusServiceClient(
                    ConnectParam.newBuilder()
                            .withHost(host).withPort(port)
                            .build());
            ensureCollection();
            ready = true;
            log.info("Milvus episode store ready at {}:{} collection={}", host, port, COLLECTION);
        } catch (Exception e) {
            log.warn("Milvus init failed at {}:{} ({}). Episodic 层将短路, 不影响主对话.",
                    host, port, e.toString());
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ignore) {}
        }
    }

    public boolean isReady() {
        return ready;
    }

    private void ensureCollection() {
        R<Boolean> exists = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        if (Boolean.TRUE.equals(exists.getData())) {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION).build());
            return;
        }

        FieldType id = FieldType.newBuilder()
                .withName(FIELD_ID).withDataType(DataType.Int64)
                .withPrimaryKey(true).withAutoID(true).build();
        FieldType userId = FieldType.newBuilder()
                .withName(FIELD_USER_ID).withDataType(DataType.Int64).build();
        FieldType convId = FieldType.newBuilder()
                .withName(FIELD_CONV_ID).withDataType(DataType.Int64).build();
        FieldType vec = FieldType.newBuilder()
                .withName(FIELD_VECTOR).withDataType(DataType.FloatVector)
                .withDimension(VECTOR_DIM).build();

        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withDescription("Episodic memory chunks (BGE-small-zh-v15, 512 dim, IP)")
                .addFieldType(id).addFieldType(userId).addFieldType(convId).addFieldType(vec)
                .build());

        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFieldName(FIELD_VECTOR)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.IP)
                .withExtraParam("{\"nlist\":128}")
                .withSyncMode(true)
                .build());

        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());

        log.info("Created Milvus collection {} with IVF_FLAT/IP index, dim={}",
                COLLECTION, VECTOR_DIM);
    }

    /**
     * 插入一条 episode 向量, 返回 Milvus 给的 auto-id (用作 memory_episode.milvus_id).
     */
    public Long insert(long userId, long convId, float[] vector) {
        if (!ready) return null;
        List<Long> userCol = List.of(userId);
        List<Long> convCol = List.of(convId);
        List<List<Float>> vecCol = new ArrayList<>(1);
        List<Float> v = new ArrayList<>(vector.length);
        for (float f : vector) v.add(f);
        vecCol.add(v);

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field(FIELD_USER_ID, userCol),
                new InsertParam.Field(FIELD_CONV_ID, convCol),
                new InsertParam.Field(FIELD_VECTOR, vecCol));

        var res = client.insert(InsertParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withFields(fields)
                .build());
        if (res.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus insert failed: {}", res.getMessage());
            return null;
        }
        List<Long> ids = res.getData().getIDs().getIntId().getDataList();
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 向量召回: 同 user_id, 排除当前 conv_id, top-k 内积相似度.
     * 返回的是 Milvus 主键列表, 调用方拿去查 {@code memory_episode} 反取文本.
     */
    public List<Long> search(long userId, long excludeConvId, float[] queryVector, int topK) {
        if (!ready) return Collections.emptyList();
        List<Float> q = new ArrayList<>(queryVector.length);
        for (float f : queryVector) q.add(f);

        String expr = "user_id == " + userId + " && conv_id != " + excludeConvId;

        R<SearchResults> resp = client.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.IP)
                .withVectors(List.of(q))
                .withVectorFieldName(FIELD_VECTOR)
                .withTopK(topK)
                .withExpr(expr)
                .withOutFields(List.of(FIELD_ID))
                .withParams("{\"nprobe\":16}")
                .build());

        if (resp.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus search failed: {}", resp.getMessage());
            return Collections.emptyList();
        }
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        var ids = wrapper.getIDScore(0);
        List<Long> result = new ArrayList<>(ids.size());
        for (var hit : ids) {
            result.add(hit.getLongID());
        }
        return result;
    }
}
