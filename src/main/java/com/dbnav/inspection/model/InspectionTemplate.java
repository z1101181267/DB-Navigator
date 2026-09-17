package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检模板。
 *
 * 对齐 RaccoonX 的 inspection_template 表：
 *   db_type / template_name_zh / template_name_en / version
 *   description / is_default / is_preset
 *
 * 约束：UNIQUE(db_type, template_name_zh)；同一 db_type 内至多一个 is_default=1。
 * is_preset=1 的预置模板禁止改名与删除。
 *
 * chapters 仅在树形查询与种子解析时填充，列表查询时为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionTemplate {

    private Long id;
    private String dbType;
    private String templateNameZh;
    private String templateNameEn;
    private String version;
    private String description;
    private Integer isDefault;      // 0=否 1=是（同 db_type 内互斥）
    private Integer isPreset;       // 1=预置，禁改名/禁删
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 章节列表（树形响应 / 种子解析用）。
     *
     * 列表查询时不填充 —— 用 NON_NULL 让该键整体不出现，而不是输出 "chapters": null。
     * 只加在本字段上（而非类上），以免连带影响 description / templateNameEn 等
     * 本就可能为 null、且契约上应当保留键的字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<InspectionChapter> chapters;
}
