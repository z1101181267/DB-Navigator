package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条巡检规则在一次执行中的结果。
 *
 * status：
 *   OK       执行成功（不代表结果「好」，只代表 SQL 跑通了）
 *   FAILED   SQL 执行报错，errorMsg 有值
 *   SKIPPED  规则被停用或所属章节被停用，未执行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionRunQuery {

    private Long id;
    private Long runId;

    private Integer chapterNumber;
    private String chapterTitle;

    private String queryKey;
    private String querySql;
    private String descriptionZh;

    private String status;              // OK / FAILED / SKIPPED
    private Long elapsedMs;

    private Integer rowCount;
    private List<String> columns;
    /** 结果预览（超出上限时截断，见 truncated） */
    private List<List<Object>> rows;
    /** 1 = rows 被截断，实际行数见 rowCount */
    private Integer truncated;

    private String errorMsg;
}
