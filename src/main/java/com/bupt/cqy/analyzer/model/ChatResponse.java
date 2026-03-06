package com.bupt.cqy.analyzer.model;

import lombok.Getter;

import java.util.List;

@Getter
public class ChatResponse {
    private final String answer;
    private final String reasoning;
    private final boolean reasonerUsed;
    private final List<String> sources;

    public ChatResponse(String answer, List<String> sources) {
        this(answer, "", false, sources);
    }

    public ChatResponse(String answer, String reasoning, boolean reasonerUsed, List<String> sources) {
        this.answer = answer;
        this.reasoning = reasoning;
        this.reasonerUsed = reasonerUsed;
        this.sources = sources;
    }
}
