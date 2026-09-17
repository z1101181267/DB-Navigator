package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 巡检执行记录（表头）。
 *
 * 一次巡检 = 一行 run，下挂：
 *   · queries    —— 逐条规则的执行结果（{@link InspectionRunQuery}）
 *   · baselines  —— 逐条基线的判定结果（{@link InspectionRunBaseline}）
 *
 * status 取值：
 *   SUCCESS  全部规则执行成功
 *   PARTIAL  部分规则失败（仍产出了可用结果）
 *   FAILED   整体失败（连不上库、模板不存在等），此时 errorMsg 有值
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionRun {

    private Long id;

    private Long dataSourceId;
    private String dataSourceName;
    private String dbType;

    private Long templateId;
    private String templateName;

    private String status;              // SUCCESS / PARTIAL / FAILED
    private String triggerSource;       // MANUAL / SCHEDULED

    private Integer totalQueries;
    private Integer okQueries;
    private Integer failedQueries;

    private Integer totalBaselines;
    private Integer baselinesPass;
    private Integer baselinesFail;
    private Integer baselinesUnchecked;

    /** 合规率 = pass / (pass + fail) × 100，未采集不计入分母 */
    private Double compliancePct;

    /** 不合规项按风险等级归集：{"HIGH": 2, "MEDIUM": 1} */
    private Map<String, Integer> riskSummary;

    /** 整体失败原因 */
    private String errorMsg;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String executedBy;

    /** 详情查询时填充 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<InspectionRunQuery> queries;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<InspectionRunBaseline> baselines;
}
