package com.bupt.cqy.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ChatResponse {
    private final String answer;
    private final List<String> sources;
}
