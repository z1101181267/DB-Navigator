package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 配置基线。
 *
 * 对齐 RaccoonX 的 inspection_baseline 表：
 *   db_type / param_name / query_sql / operator / expected_value
 *   expected_value_min / expected_value_max / risk_level
 *   description_zh / description_en / enabled
 *
 * 约束：UNIQUE(db_type, param_name)。
 * operator 取值：= > < >= <= != BETWEEN LIKE
 * risk_level 取值：LOW / MEDIUM / HIGH / CRITICAL
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionBaseline {

    private Long id;
    private String dbType;
    private String paramName;

    /** 采集该参数值的 SQL */
    private String querySql;

    /** 比较运算符 */
    private String operator;

    /** 期望值（标量，或 LIKE 的模式串） */
    private String expectedValue;

    /** BETWEEN 下界 */
    private Double expectedValueMin;

    /** BETWEEN 上界 */
    private Double expectedValueMax;

    private String riskLevel;
    private String descriptionZh;
    private String descriptionEn;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
