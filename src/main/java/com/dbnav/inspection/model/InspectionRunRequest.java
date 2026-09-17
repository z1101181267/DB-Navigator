package com.dbnav.inspection.model;

import lombok.Data;

import java.util.List;

/**
 * 巡检执行请求。
 */
@Data
public class InspectionRunRequest {

    /** 目标数据源（必填） */
    private Long dataSourceId;

    /** 巡检模板；留空则用该数据源库类型的默认模板 */
    private Long templateId;

    /** 只执行启用状态的章节与规则，默认 true */
    private Boolean onlyEnabled;

    /** 是否同时执行基线校验，默认 true */
    private Boolean includeBaselines;

    /** 只跑指定章节号（留空 = 全部） */
    private List<Integer> chapterNumbers;

    /** 执行人标识，用于留痕 */
    private String executedBy;
}
