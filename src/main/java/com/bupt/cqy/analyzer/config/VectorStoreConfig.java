package com.bupt.cqy.analyzer.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class VectorStoreConfig {

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
}
