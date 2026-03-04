package com.bupt.cqy.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class AuditReport {
    private String summary;
    private String detailedAnalysis;
    private String securityIssues;
    private String recommendations;
    private String roadmap;
}
