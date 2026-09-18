package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检章节。
 *
 * 字段：
 *   template_id / chapter_number / chapter_title_zh / chapter_title_en
 *   description / enabled / sort_order
 *
 * 约束：UNIQUE(template_id, chapter_number)；
 * 外键 template_id → inspection_template(id) ON DELETE CASCADE。
 *
 * rules 与 ruleKeys 只在特定场景填充，不落库：
 *   rules    —— 树形查询时回填该章引用的规则（含绑定表里的 sortOrder）
 *   ruleKeys —— 解析种子文件时用，记录该章引用了规则库里的哪些 key
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

    /** 该章引用的规则（来自规则库，按绑定表的 sort_order 排序） */
    private List<InspectionRule> rules;

    /**
     * 种子文件里该章引用的规则 key 列表。
     *
     * 这是**种子解析**用的中间字段，不属于 REST 契约：接口里章节引用了哪些规则
     * 由 rules 表达，ruleKeys 只是 templates.json 的写法。用 WRITE_ONLY 让它在
     * 反序列化（读种子）时照常生效、序列化（返回接口）时不出现在响应体里 ——
     * 否则每个章节都会多带一个恒为 null 的 ruleKeys，客户端还得猜它是什么。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private List<String> ruleKeys;
}
