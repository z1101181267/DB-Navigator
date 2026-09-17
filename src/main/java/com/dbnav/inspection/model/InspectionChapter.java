package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检章节。
 *
 * 对齐 RaccoonX 的 inspection_chapter 表：
 *   template_id / chapter_number / chapter_title_zh / chapter_title_en
 *   description / enabled / sort_order
 *
 * 约束：UNIQUE(template_id, chapter_number)；
 * 外键 template_id → inspection_template(id) ON DELETE CASCADE。
 *
 * queries 仅在树形查询与种子解析时填充。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionChapter {

    private Long id;
    private Long templateId;
    private Integer chapterNumber;
    private String chapterTitleZh;
    private String chapterTitleEn;
    private String description;
    private Integer enabled;        // 0=停用 1=启用
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 该章下的巡检 SQL 列表 */
    private List<InspectionQuery> queries;
}
