package com.bupt.cqy.analyzer.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Configuration
public class VectorStoreConfig {

    /**
     * 向量库持久化文件路径，可在 application.properties 中配置：
     * rag.vector-store.persist-path=./data/vector-store.json
     */
    @Value("${rag.vector-store.persist-path:./data/vector-store.json}")
    private String persistPath;

    private InMemoryEmbeddingStore<TextSegment> store;

    /**
     * 嵌入模型：bge-small-zh-v1.5（量化版）
     * - 来源：BAAI（北京智源人工智能研究院）
     * - 维度：512 维
     * - 大小：~25MB（量化后），本地 ONNX 推理，零网络依赖
     * - 优势：vs AllMiniLmL6V2（英文384维），在中文代码注释 + 中文问答场景下语义召回更准确
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    /**
     * 向量库：启动时从文件加载，关闭时持久化到文件。
     * 避免每次重启后必须重新跑 /api/analyze。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        Path path = Path.of(persistPath);
        if (Files.exists(path)) {
            try {
                store = InMemoryEmbeddingStore.fromFile(path);
                log.info("向量库从持久化文件加载完成：{}", path.toAbsolutePath());
                return store;
            } catch (Exception e) {
                log.warn("向量库文件加载失败，将创建新的空向量库。原因: {}", e.getMessage());
            }
        } else {
            log.info("未找到持久化文件，创建新的空向量库。首次使用请调用 /api/analyze 建立索引。");
        }
        store = new InMemoryEmbeddingStore<>();
        return store;
    }

    /**
     * 主动持久化向量库（可在索引完成后立即调用，保证数据安全）。
     * 同时通过 @PreDestroy 在应用关闭时兜底调用一次。
     */
    public void persist() {
        if (store == null)
            return;
        try {
            Path path = Path.of(persistPath);
            Files.createDirectories(path.getParent());
            store.serializeToFile(path);
            log.info("向量库已持久化到：{}", path.toAbsolutePath());
        } catch (Exception e) {
            log.error("向量库持久化失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void persistStore() {
        persist();
    }
}
