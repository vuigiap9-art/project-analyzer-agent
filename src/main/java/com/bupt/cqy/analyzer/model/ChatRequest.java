package com.bupt.cqy.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String question;
    /**
     * 当前会话所属的项目索引 ID（后端按项目隔离向量库）。
     * 为空时将尝试使用最近一次构建的项目（或返回错误，视接口而定）。
     */
    private String projectId;
    /**
     * 对话会话 ID，用于隔离并持久化多轮记忆。
     */
    private String sessionId;
}
