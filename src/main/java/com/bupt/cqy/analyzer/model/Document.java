package com.bupt.cqy.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Document {
    private final String filePath;
    private final String content;
    private final String language;
}
