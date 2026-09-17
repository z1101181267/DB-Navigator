package com.dbnav.inspection.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条基线的校验结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaselineCheckResult {

    private String dbType;
    private String paramName;
    private String operator;
    private String expectedValue;

    /** 从数据库采集到的实际值（未采集时为 null） */
    private String actualValue;

    private String riskLevel;

    /** 是否合规 */
    private boolean pass;

    /** 是否实际采集到了值（false 表示未采集，无法判定） */
    private boolean checked;

    private String descriptionZh;

    /** 人类可读的判定说明 */
    private String message;
}
