package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 巡检 SQL 规则。
 *
 * 字段：
 *   chapter_id / query_key / query_sql / query_description_zh / query_description_en
 *   enabled / sort_order
 *
 * 约束：UNIQUE(chapter_id, query_key)；
 * 外键 chapter_id → inspection_chapter(id) ON DELETE CASCADE。
 *
 * 注意：种子 JSON 中该字段名为 key（而非 query_key），故用 @JsonProperty 覆盖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionQuery {

    private Long id;
    private Long chapterId;

    /** 规则唯一键，同一章节内不可重复 */
    @JsonProperty("key")
    private String queryKey;

    @JsonProperty("sql")
    private String querySql;

    @JsonProperty("desc_zh")
    private String queryDescriptionZh;

    @JsonProperty("desc_en")
    private String queryDescriptionEn;

    private Integer enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
