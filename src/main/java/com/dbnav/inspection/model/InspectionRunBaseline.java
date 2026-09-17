package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条基线在一次执行中的判定结果。
 *
 * 与 {@link BaselineCheckResult} 的区别：那个是「单值判定」的纯计算结果，
 * 这个是「一次巡检里的一条记录」——额外带上采集行数、违规行数与耗时。
 *
 * 多行结果的语义：基线采集 SQL 可能返回多行（例如 SQL Server 的
 * {@code SELECT name, is_auto_close_on FROM sys.databases} 按库返回一行）。
 * 此时逐行判定，**只要有一行不合规，整条基线即判为不合规**——
 * 因为「有一个库开着 auto_close」就是问题，不能因为其他库正常而放过。
 * violationCount 记录不合规的行数，sampleCount 记录采集到的总行数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionRunBaseline {

    private Long id;
    private Long runId;

    private String paramName;
    private String operator;
    private String expectedValue;

    /** 采集到的值；多行时逗号拼接（过长截断） */
    private String actualValue;

    /** 采集到的行数；0 = 未采集 */
    private Integer sampleCount;
    /** 多行结果中不合规的行数 */
    private Integer violationCount;

    private String riskLevel;

    private Integer isPass;             // 1/0
    private Integer isChecked;          // 1/0；0 = 未采集，不计入合规率分母

    private String message;
    private String descriptionZh;
    private Long elapsedMs;
    private String errorMsg;
}
