package com.bupt.cqy.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class CodeSnippet {
    private final String path;
    private final String language;
    private final String code;
}
