package com.dbnav.inspection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检规则（规则库中的一条）。
 *
 * 规则库是规则正文的唯一存放处：一条规则 = 一段采集 SQL + 它归属的库类型。
 * 章节通过 {@code inspection_chapter_rule} 引用规则，因此同一条规则可以被
 * 多个模板的章节复用，改一次所有引用处同时生效。
 *
 * 字段：
 *   rule_key / db_type / rule_name_zh / rule_name_en / rule_sql / category
 *   enabled / source
 *
 * 约束：UNIQUE(rule_key) —— key 全局唯一，跨库类型也不重复。
 *
 * 下面三个字段不落库，只在查询时回填：
 *   sortOrder —— 该规则在「某个章节」里的排序（来自绑定表，按章节查询时才有）
 *   refCount  —— 被多少个章节引用（规则库列表用，判断能否直接删）
 *   usedBy    —— 引用了它的模板名列表，便于在库里看清影响面
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionRule {

    private Long id;

    /** 规则唯一键，全库唯一（如 h2_agg、oracle_version） */
    private String ruleKey;

    /** 归属库类型：规则只对同类型数据库有意义 */
    private String dbType;

    private String ruleNameZh;
    private String ruleNameEn;

    /** 采集 SQL */
    private String ruleSql;

    /** 建议归类章节。仅供规则库按主题筛选，不是硬绑定 —— 章节引用关系以绑定表为准 */
    private String category;

    private Integer enabled;

    /** PRESET=种子导入（不可删） / CUSTOM=界面新建 */
    private String source;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 查询时回填，不落库 ----

    private Integer sortOrder;
    private Integer refCount;
    private List<String> usedBy;
}
