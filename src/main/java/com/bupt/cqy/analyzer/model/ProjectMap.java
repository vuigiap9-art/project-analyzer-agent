package com.bupt.cqy.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 轻量级的全局项目地图，用于指导交互式审计流程。
 * 只包含结构化元信息，不包含具体源码内容。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMap {

    private String projectRoot;

    /** 语言 -> 文件数量 */
    private Map<String, Long> languageStats;

    private int totalFiles;

    /** 入口文件候选，例如 *Application.java、main.py 等 */
    private List<String> entryPoints;

    /** 所有受支持的代码文件的简要信息 */
    private List<FileInfo> files;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileInfo {
        private String path;
        private String language;
        private long lineCount;
        /** 简单角色标签，例如 controller/service/dao/test/config 等 */
        private List<String> roles;
    }
}

