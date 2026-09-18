package com.dbnav.inspection;

import com.dbnav.config.ConfigJsonMapper;
import com.dbnav.config.DbNavProperties;
import com.dbnav.inspection.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;

/**
 * 巡检配置服务：模板 / 章节 / 规则库 / 基线 的增删改查 + 种子导入 + 修改留痕。
 *
 * 对应界面上的三个模块：
 *   巡检配置管理  模板 → 章节，两级 ON DELETE CASCADE，只描述报告骨架
 *   规则引擎      规则库（规则的唯一存放处）+ 章节绑定，规则可跨模板复用
 *   基线配置管理  参数推荐值与阈值，按库类型独立维护
 *
 * 另外：
 *   - 种子导入：表为空时从 rules.json / templates.json / baselines.json 装载；
 *     模板在、绑定空（老库升级）时只重建绑定，不动用户已有的模板与章节
 *   - 默认模板互斥：同 db_type 内至多一个 is_default=1
 *   - 预置保护：is_preset=1 的模板禁止改名与删除；PRESET 规则的 key 与库类型不可改
 *   - 删除保护：被章节引用的规则默认拒绝删除，需 force=true
 *   - 修改留痕：所有写操作落 inspection_history
 */
@Slf4j
@Service
public class InspectionConfigService {

    private final JdbcTemplate jdbc;
    private final DbNavProperties properties;
    private final ObjectMapper mapper = ConfigJsonMapper.get();

    public InspectionConfigService(JdbcTemplate jdbc, DbNavProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    // ==================================================================
    // RowMapper
    // ==================================================================

    private static final RowMapper<InspectionTemplate> TPL_MAPPER = (rs, n) -> InspectionTemplate.builder()
            .id(rs.getLong("id"))
            .dbType(rs.getString("db_type"))
            .templateNameZh(rs.getString("template_name_zh"))
            .templateNameEn(rs.getString("template_name_en"))
            .version(rs.getString("version"))
            .description(rs.getString("description"))
            .isDefault(rs.getInt("is_default"))
            .isPreset(rs.getInt("is_preset"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .updatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .build();

    private static final RowMapper<InspectionChapter> CH_MAPPER = (rs, n) -> InspectionChapter.builder()
            .id(rs.getLong("id"))
            .templateId(rs.getLong("template_id"))
            .chapterNumber(rs.getInt("chapter_number"))
            .chapterTitleZh(rs.getString("chapter_title_zh"))
            .chapterTitleEn(rs.getString("chapter_title_en"))
            .description(rs.getString("description"))
            .enabled(rs.getInt("enabled"))
            .sortOrder(rs.getInt("sort_order"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .updatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .build();

    /**
     * 规则查询的统一前缀：总是带上 ref_count（被多少章节引用）。
     * 放在常量里是为了避免「某处 SELECT * 漏了这一列、RowMapper 读不到」这类错。
     */
    private static final String RULE_SELECT =
            "SELECT r.*, (SELECT COUNT(*) FROM inspection_chapter_rule cr "
                    + "WHERE cr.rule_id = r.id) AS ref_count FROM inspection_rule r ";

    private static final RowMapper<InspectionRule> RULE_MAPPER = (rs, n) -> InspectionRule.builder()
            .id(rs.getLong("id"))
            .ruleKey(rs.getString("rule_key"))
            .dbType(rs.getString("db_type"))
            .ruleNameZh(rs.getString("rule_name_zh"))
            .ruleNameEn(rs.getString("rule_name_en"))
            .ruleSql(rs.getString("rule_sql"))
            .category(rs.getString("category"))
            .enabled(rs.getInt("enabled"))
            .source(rs.getString("source"))
            .refCount(rs.getInt("ref_count"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .updatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .build();

    private static final RowMapper<InspectionBaseline> BL_MAPPER = (rs, n) -> InspectionBaseline.builder()
            .id(rs.getLong("id"))
            .dbType(rs.getString("db_type"))
            .paramName(rs.getString("param_name"))
            .querySql(rs.getString("query_sql"))
            .operator(rs.getString("operator"))
            .expectedValue(rs.getString("expected_value"))
            .expectedValueMin(rs.getObject("expected_value_min", Double.class))
            .expectedValueMax(rs.getObject("expected_value_max", Double.class))
            .riskLevel(rs.getString("risk_level"))
            .descriptionZh(rs.getString("description_zh"))
            .descriptionEn(rs.getString("description_en"))
            .enabled(rs.getInt("enabled"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .updatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .build();

    // ==================================================================
    // 模板
    // ==================================================================

    public List<InspectionTemplate> listTemplates(String dbType) {
        if (dbType != null && !dbType.isBlank()) {
            return jdbc.query("SELECT * FROM inspection_template WHERE db_type=? "
                    + "ORDER BY is_default DESC, id", TPL_MAPPER, dbType);
        }
        return jdbc.query("SELECT * FROM inspection_template ORDER BY db_type, is_default DESC, id", TPL_MAPPER);
    }

    public Optional<InspectionTemplate> findTemplate(Long id) {
        List<InspectionTemplate> r = jdbc.query(
                "SELECT * FROM inspection_template WHERE id=?", TPL_MAPPER, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /**
     * 取模板树：模板 → 章节 → 规则，只返回启用的节点（可选）。
     */
    public Optional<InspectionTemplate> getTemplateTree(Long id, boolean onlyEnabled) {
        Optional<InspectionTemplate> opt = findTemplate(id);
        if (opt.isEmpty()) {
            return opt;
        }
        InspectionTemplate tpl = opt.get();

        List<InspectionChapter> chapters = jdbc.query(
                "SELECT * FROM inspection_chapter WHERE template_id=? "
                        + (onlyEnabled ? "AND enabled=1 " : "")
                        + "ORDER BY chapter_number, sort_order", CH_MAPPER, id);

        for (InspectionChapter ch : chapters) {
            ch.setRules(listChapterRules(ch.getId(), onlyEnabled));
        }
        tpl.setChapters(chapters);
        return Optional.of(tpl);
    }

    /** 取某 db_type 的默认模板树 */
    public Optional<InspectionTemplate> getDefaultTemplateTree(String dbType, boolean onlyEnabled) {
        List<InspectionTemplate> r = jdbc.query(
                "SELECT * FROM inspection_template WHERE db_type=? AND is_default=1 LIMIT 1",
                TPL_MAPPER, dbType);
        return r.isEmpty() ? Optional.empty() : getTemplateTree(r.get(0).getId(), onlyEnabled);
    }

    @Transactional
    public InspectionTemplate createTemplate(InspectionTemplate t) {
        if (t.getTemplateNameZh() == null || t.getTemplateNameZh().isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (t.getDbType() == null || t.getDbType().isBlank()) {
            throw new IllegalArgumentException("db_type 不能为空");
        }
        try {
            jdbc.update("""
                    INSERT INTO inspection_template
                        (db_type, template_name_zh, template_name_en, version, description, is_default, is_preset)
                    VALUES (?,?,?,?,?,?,?)
                    """, t.getDbType(), t.getTemplateNameZh(), t.getTemplateNameEn(),
                    t.getVersion() == null ? "v1" : t.getVersion(), t.getDescription(),
                    nz(t.getIsDefault()), 0);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("同类型下模板名已存在：" + t.getTemplateNameZh());
        }

        InspectionTemplate created = jdbc.query(
                "SELECT * FROM inspection_template WHERE db_type=? AND template_name_zh=?",
                TPL_MAPPER, t.getDbType(), t.getTemplateNameZh()).get(0);

        if (nz(t.getIsDefault()) == 1) {
            setDefaultTemplate(created.getId());
        }
        recordHistory("inspection_template", created.getId(), "INSERT", null,
                Map.of("dbType", created.getDbType(), "name", created.getTemplateNameZh()));
        return created;
    }

    @Transactional
    public InspectionTemplate updateTemplate(Long id, InspectionTemplate patch) {
        InspectionTemplate old = findTemplate(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));

        boolean isPreset = nz(old.getIsPreset()) == 1;
        // 预置模板保护：禁止改名与改版本
        String newName = (isPreset || patch.getTemplateNameZh() == null || patch.getTemplateNameZh().isBlank())
                ? old.getTemplateNameZh() : patch.getTemplateNameZh();
        String newVersion = (isPreset || patch.getVersion() == null || patch.getVersion().isBlank())
                ? old.getVersion() : patch.getVersion();

        jdbc.update("""
                UPDATE inspection_template SET
                    template_name_zh=?, template_name_en=?, version=?, description=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, newName,
                patch.getTemplateNameEn() != null ? patch.getTemplateNameEn() : old.getTemplateNameEn(),
                newVersion,
                patch.getDescription() != null ? patch.getDescription() : old.getDescription(),
                id);

        if (patch.getIsDefault() != null && nz(patch.getIsDefault()) == 1) {
            setDefaultTemplate(id);
        }
        recordHistory("inspection_template", id, "UPDATE",
                Map.of("name", old.getTemplateNameZh(), "version", String.valueOf(old.getVersion())),
                Map.of("name", newName, "version", String.valueOf(newVersion)));
        return findTemplate(id).orElseThrow();
    }

    /**
     * 把指定模板设为该 db_type 的默认（先清空同类型其他默认）。
     */
    @Transactional
    public void setDefaultTemplate(Long id) {
        InspectionTemplate t = findTemplate(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));
        jdbc.update("UPDATE inspection_template SET is_default=0 WHERE db_type=?", t.getDbType());
        jdbc.update("UPDATE inspection_template SET is_default=1 WHERE id=?", id);
        recordHistory("inspection_template", id, "UPDATE", null, Map.of("isDefault", 1));
    }

    /**
     * 删除模板（级联删除章节与规则）。预置模板默认禁止删除。
     */
    @Transactional
    public void deleteTemplate(Long id, boolean force) {
        InspectionTemplate t = findTemplate(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));
        if (nz(t.getIsPreset()) == 1 && !force) {
            throw new IllegalArgumentException("预置模板不允许删除（如需强制删除请传 force=true）");
        }
        jdbc.update("DELETE FROM inspection_template WHERE id=?", id);
        recordHistory("inspection_template", id, "DELETE",
                Map.of("dbType", t.getDbType(), "name", t.getTemplateNameZh()), null);
    }

    // ==================================================================
    // 章节
    // ==================================================================

    public List<InspectionChapter> listChapters(Long templateId) {
        return jdbc.query("SELECT * FROM inspection_chapter WHERE template_id=? "
                + "ORDER BY chapter_number, sort_order", CH_MAPPER, templateId);
    }

    @Transactional
    public InspectionChapter createChapter(InspectionChapter c) {
        if (c.getTemplateId() == null) {
            throw new IllegalArgumentException("templateId 不能为空");
        }
        if (c.getChapterTitleZh() == null || c.getChapterTitleZh().isBlank()) {
            throw new IllegalArgumentException("章节标题不能为空");
        }
        int number = c.getChapterNumber() != null ? c.getChapterNumber()
                : nextChapterNumber(c.getTemplateId());
        try {
            jdbc.update("""
                    INSERT INTO inspection_chapter
                        (template_id, chapter_number, chapter_title_zh, chapter_title_en,
                         description, enabled, sort_order)
                    VALUES (?,?,?,?,?,?,?)
                    """, c.getTemplateId(), number, c.getChapterTitleZh(), c.getChapterTitleEn(),
                    c.getDescription(), c.getEnabled() == null ? 1 : c.getEnabled(),
                    c.getSortOrder() == null ? 0 : c.getSortOrder());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("该模板下章节号已存在：" + number);
        }
        InspectionChapter created = jdbc.query(
                "SELECT * FROM inspection_chapter WHERE template_id=? AND chapter_number=?",
                CH_MAPPER, c.getTemplateId(), number).get(0);
        recordHistory("inspection_chapter", created.getId(), "INSERT",
                null, Map.of("title", created.getChapterTitleZh(), "number", number));
        return created;
    }

    @Transactional
    public InspectionChapter updateChapter(Long id, InspectionChapter patch) {
        InspectionChapter old = jdbc.query(
                "SELECT * FROM inspection_chapter WHERE id=?", CH_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));

        jdbc.update("""
                UPDATE inspection_chapter SET
                    chapter_title_zh=?, chapter_title_en=?, description=?,
                    enabled=?, sort_order=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                patch.getChapterTitleZh() != null ? patch.getChapterTitleZh() : old.getChapterTitleZh(),
                patch.getChapterTitleEn() != null ? patch.getChapterTitleEn() : old.getChapterTitleEn(),
                patch.getDescription() != null ? patch.getDescription() : old.getDescription(),
                patch.getEnabled() != null ? patch.getEnabled() : old.getEnabled(),
                patch.getSortOrder() != null ? patch.getSortOrder() : old.getSortOrder(),
                id);

        recordHistory("inspection_chapter", id, "UPDATE",
                Map.of("title", old.getChapterTitleZh(), "enabled", nz(old.getEnabled())),
                Map.of("title", patch.getChapterTitleZh() != null ? patch.getChapterTitleZh() : old.getChapterTitleZh(),
                        "enabled", patch.getEnabled() != null ? patch.getEnabled() : nz(old.getEnabled())));
        return jdbc.query("SELECT * FROM inspection_chapter WHERE id=?", CH_MAPPER, id).get(0);
    }

    @Transactional
    public void deleteChapter(Long id) {
        InspectionChapter old = jdbc.query(
                "SELECT * FROM inspection_chapter WHERE id=?", CH_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));
        jdbc.update("DELETE FROM inspection_chapter WHERE id=?", id);
        recordHistory("inspection_chapter", id, "DELETE",
                Map.of("title", old.getChapterTitleZh(), "number", nz(old.getChapterNumber())), null);
    }

    private int nextChapterNumber(Long templateId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(chapter_number), 0) FROM inspection_chapter WHERE template_id=?",
                Integer.class, templateId);
        return (max == null ? 0 : max) + 1;
    }

    // ==================================================================
    // 规则库（规则引擎）
    //
    // 规则正文只存在 inspection_rule 一张表里；章节通过 inspection_chapter_rule
    // 引用规则。因此「改规则」和「章节用哪些规则」是两件事：
    //   · 改 rule_sql        → 所有引用它的模板同时生效
    //   · 解绑               → 只删绑定行，规则留在库里
    //   · 删规则             → 级联清掉所有引用，因此默认拒绝，需 force=true
    // ==================================================================

    public List<InspectionRule> listRules(String dbType, String category,
                                          Boolean enabled, String keyword) {
        StringBuilder sql = new StringBuilder(RULE_SELECT + "WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (dbType != null && !dbType.isBlank()) {
            sql.append("AND r.db_type=? ");
            args.add(dbType);
        }
        if (category != null && !category.isBlank()) {
            sql.append("AND r.category=? ");
            args.add(category);
        }
        if (enabled != null) {
            sql.append("AND r.enabled=? ");
            args.add(enabled ? 1 : 0);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append("AND (LOWER(r.rule_key) LIKE ? OR LOWER(r.rule_name_zh) LIKE ? "
                    + "OR LOWER(r.rule_sql) LIKE ?) ");
            String kw = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append("ORDER BY r.db_type, r.category, r.rule_key");

        List<InspectionRule> rules = jdbc.query(sql.toString(), RULE_MAPPER, args.toArray());
        fillUsedBy(rules);
        return rules;
    }

    public Optional<InspectionRule> findRule(Long id) {
        List<InspectionRule> r = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, id);
        if (r.isEmpty()) {
            return Optional.empty();
        }
        fillUsedBy(r);
        return Optional.of(r.get(0));
    }

    /** 规则库里出现过哪些归类章节，供筛选下拉用 */
    public List<String> listRuleCategories(String dbType) {
        if (dbType != null && !dbType.isBlank()) {
            return jdbc.queryForList("SELECT DISTINCT category FROM inspection_rule "
                    + "WHERE category IS NOT NULL AND db_type=? ORDER BY category", String.class, dbType);
        }
        return jdbc.queryForList("SELECT DISTINCT category FROM inspection_rule "
                + "WHERE category IS NOT NULL ORDER BY category", String.class);
    }

    /** 规则库总览：总数、按库类型、按归类章节、启用/停用、零引用条数 */
    public Map<String, Object> ruleStats() {        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM inspection_rule", Integer.class));
        out.put("enabled", jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_rule WHERE enabled=1", Integer.class));
        out.put("disabled", jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_rule WHERE enabled=0", Integer.class));
        out.put("unbound", jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_rule r WHERE NOT EXISTS "
                        + "(SELECT 1 FROM inspection_chapter_rule cr WHERE cr.rule_id = r.id)",
                Integer.class));

        Map<String, Integer> byType = new LinkedHashMap<>();
        jdbc.query("SELECT db_type, COUNT(*) AS cnt FROM inspection_rule "
                        + "GROUP BY db_type ORDER BY db_type",
                rs -> { byType.put(rs.getString("db_type"), rs.getInt("cnt")); });
        out.put("byDbType", byType);

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        jdbc.query("SELECT COALESCE(category,'(未归类)') AS cat, COUNT(*) AS cnt "
                        + "FROM inspection_rule GROUP BY cat ORDER BY cnt DESC, cat",
                rs -> { byCategory.put(rs.getString("cat"), rs.getInt("cnt")); });
        out.put("byCategory", byCategory);
        return out;
    }

    @Transactional
    public InspectionRule createRule(InspectionRule r) {
        validateRule(r);
        try {
            jdbc.update("""
                    INSERT INTO inspection_rule
                        (rule_key, db_type, rule_name_zh, rule_name_en, rule_sql,
                         category, enabled, source)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, r.getRuleKey(), r.getDbType(), r.getRuleNameZh(), r.getRuleNameEn(),
                    r.getRuleSql(), r.getCategory(),
                    r.getEnabled() == null ? 1 : r.getEnabled(),
                    r.getSource() == null ? "CUSTOM" : r.getSource());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("规则 key 已存在：" + r.getRuleKey());
        }
        Long newId = jdbc.queryForObject(
                "SELECT id FROM inspection_rule WHERE rule_key=?", Long.class, r.getRuleKey());
        recordHistory("inspection_rule", newId, "INSERT", null,
                Map.of("key", r.getRuleKey(), "dbType", r.getDbType()));
        return reloadRule(newId);
    }

    @Transactional
    public InspectionRule updateRule(Long id, InspectionRule patch) {
        InspectionRule old = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("规则不存在: " + id));

        // 预置规则的 key 与库类型不允许改：改了等于换了一条规则，引用它的章节会莫名其妙
        boolean preset = "PRESET".equalsIgnoreCase(old.getSource());
        String newKey = (preset || patch.getRuleKey() == null || patch.getRuleKey().isBlank())
                ? old.getRuleKey() : patch.getRuleKey();
        String newType = (preset || patch.getDbType() == null || patch.getDbType().isBlank())
                ? old.getDbType() : patch.getDbType();

        String newSql = patch.getRuleSql() != null ? patch.getRuleSql() : old.getRuleSql();
        String newName = patch.getRuleNameZh() != null ? patch.getRuleNameZh() : old.getRuleNameZh();
        if (newSql == null || newSql.isBlank()) {
            throw new IllegalArgumentException("规则 SQL 不能为空");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }

        try {
            jdbc.update("""
                    UPDATE inspection_rule SET
                        rule_key=?, db_type=?, rule_name_zh=?, rule_name_en=?, rule_sql=?,
                        category=?, enabled=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """, newKey, newType, newName,
                    patch.getRuleNameEn() != null ? patch.getRuleNameEn() : old.getRuleNameEn(),
                    newSql,
                    patch.getCategory() != null ? patch.getCategory() : old.getCategory(),
                    patch.getEnabled() != null ? patch.getEnabled() : old.getEnabled(),
                    id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("规则 key 已存在：" + newKey);
        }

        int refs = countRuleRefs(id);
        recordHistory("inspection_rule", id, "UPDATE",
                Map.of("key", old.getRuleKey(), "enabled", nz(old.getEnabled())),
                Map.of("key", newKey,
                        "enabled", patch.getEnabled() != null ? patch.getEnabled() : nz(old.getEnabled()),
                        "refs", refs));
        return reloadRule(id);
    }

    /**
     * 删除规则。被章节引用时默认拒绝 —— 直接删会静默改变多个模板的报告内容，
     * 传 force=true 表示「我知道会影响哪些章节，仍然删」。
     */
    @Transactional
    public void deleteRule(Long id, boolean force) {
        InspectionRule old = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("规则不存在: " + id));

        List<String> usedBy = ruleUsedBy(id);
        if (!usedBy.isEmpty() && !force) {
            throw new IllegalArgumentException("该规则正被 " + usedBy.size()
                    + " 个章节引用（" + String.join("、", usedBy)
                    + "），删除会影响这些模板的报告。如确认删除请传 force=true");
        }
        jdbc.update("DELETE FROM inspection_rule WHERE id=?", id);
        recordHistory("inspection_rule", id, "DELETE",
                Map.of("key", old.getRuleKey(), "usedBy", String.join(",", usedBy)), null);
    }

    @Transactional
    public InspectionRule setRuleEnabled(Long id, boolean enabled) {
        InspectionRule old = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("规则不存在: " + id));
        jdbc.update("UPDATE inspection_rule SET enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                enabled ? 1 : 0, id);
        recordHistory("inspection_rule", id, "UPDATE",
                Map.of("key", old.getRuleKey(), "enabled", nz(old.getEnabled())),
                Map.of("key", old.getRuleKey(), "enabled", enabled ? 1 : 0));
        return reloadRule(id);
    }

    /** 批量启停某库类型下的全部规则 */
    @Transactional
    public int setRulesEnabled(String dbType, boolean enabled) {
        int n = jdbc.update("UPDATE inspection_rule SET enabled=?, updated_at=CURRENT_TIMESTAMP "
                + "WHERE db_type=?", enabled ? 1 : 0, dbType);
        recordHistory("inspection_rule", 0, "UPDATE", null,
                Map.of("dbType", dbType, "bulkEnabled", enabled ? 1 : 0, "affected", n));
        return n;
    }

    private void validateRule(InspectionRule r) {
        if (r.getRuleKey() == null || r.getRuleKey().isBlank()) {
            throw new IllegalArgumentException("规则 key 不能为空");
        }
        if (r.getDbType() == null || r.getDbType().isBlank()) {
            throw new IllegalArgumentException("db_type 不能为空");
        }
        if (r.getRuleNameZh() == null || r.getRuleNameZh().isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (r.getRuleSql() == null || r.getRuleSql().isBlank()) {
            throw new IllegalArgumentException("规则 SQL 不能为空");
        }
    }

    /** 取一条规则并补齐 usedBy，供写操作返回统一的形状 */
    private InspectionRule reloadRule(Long id) {
        InspectionRule r = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, id).get(0);
        fillUsedBy(List.of(r));
        return r;
    }

    private int countRuleRefs(Long ruleId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_chapter_rule WHERE rule_id=?", Integer.class, ruleId);
        return n == null ? 0 : n;
    }

    private List<String> ruleUsedBy(Long ruleId) {
        return jdbc.queryForList(
                "SELECT t.template_name_zh || ' / 第' || c.chapter_number || '章 ' || c.chapter_title_zh "
                        + "FROM inspection_chapter_rule cr "
                        + "JOIN inspection_chapter c ON c.id = cr.chapter_id "
                        + "JOIN inspection_template t ON t.id = c.template_id "
                        + "WHERE cr.rule_id=? ORDER BY t.template_name_zh, c.chapter_number",
                String.class, ruleId);
    }

    /** 批量回填「被谁引用」，避免列表接口对每条规则各查一次 */
    private void fillUsedBy(List<InspectionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        Map<Long, List<String>> index = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (InspectionRule r : rules) {
            ids.add(r.getId());
            index.put(r.getId(), new ArrayList<>());
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        // 列名不要写 "cr.rule_id" 这种带表别名的形式：ResultSet 按列标签取数，
        // 而 H2 会把标签转成大写（RULE_ID），带别名的 "cr.rule_id" 在标签表里查不到，
        // 报 Column "cr.rule_id" not found —— 看着像 SQL 写错，其实是取列名的方式不对。
        jdbc.query("SELECT cr.rule_id, t.template_name_zh "
                        + "FROM inspection_chapter_rule cr "
                        + "JOIN inspection_chapter c ON c.id = cr.chapter_id "
                        + "JOIN inspection_template t ON t.id = c.template_id "
                        + "WHERE cr.rule_id IN (" + placeholders + ") "
                        + "ORDER BY t.template_name_zh",
                rs -> {
                    List<String> names = index.get(rs.getLong("rule_id"));
                    String name = rs.getString("template_name_zh");
                    if (names != null && !names.contains(name)) {
                        names.add(name);
                    }
                }, ids.toArray());
        for (InspectionRule r : rules) {
            r.setUsedBy(index.getOrDefault(r.getId(), List.of()));
        }
    }

    // ---------------- 章节 ↔ 规则 绑定 ----------------

    /** 某章引用的规则（含该章内的排序） */
    public List<InspectionRule> listChapterRules(Long chapterId) {
        return listChapterRules(chapterId, false);
    }

    /**
     * @param onlyEnabled true 时只返回启用的规则（巡检执行走这条路径）
     */
    public List<InspectionRule> listChapterRules(Long chapterId, boolean onlyEnabled) {
        List<InspectionRule> rules = jdbc.query(
                "SELECT r.*, cr.sort_order AS bind_order, "
                        + "(SELECT COUNT(*) FROM inspection_chapter_rule x WHERE x.rule_id = r.id) AS ref_count "
                        + "FROM inspection_chapter_rule cr "
                        + "JOIN inspection_rule r ON r.id = cr.rule_id "
                        + "WHERE cr.chapter_id=? "
                        + (onlyEnabled ? "AND r.enabled=1 " : "")
                        + "ORDER BY cr.sort_order, cr.id", (rs, n) -> {
                    InspectionRule r = RULE_MAPPER.mapRow(rs, n);
                    if (r != null) {
                        r.setSortOrder(rs.getInt("bind_order"));
                    }
                    return r;
                }, chapterId);
        fillUsedBy(rules);
        return rules;
    }

    @Transactional
    public void bindRule(Long chapterId, Long ruleId, Integer sortOrder) {
        if (chapterId == null || ruleId == null) {
            throw new IllegalArgumentException("chapterId 与 ruleId 均不能为空");
        }
        jdbc.query("SELECT * FROM inspection_chapter WHERE id=?", CH_MAPPER, chapterId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterId));
        InspectionRule rule = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, ruleId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + ruleId));

        int order = sortOrder != null ? sortOrder : nextBindOrder(chapterId);
        try {
            // 绑定表没有 enabled 列：一条规则只有一个启停开关（在规则库里），
            // 某个模板不想跑就解绑 —— 避免「规则启用了但绑定停用了」这种双重否定
            jdbc.update("INSERT INTO inspection_chapter_rule (chapter_id, rule_id, sort_order) "
                    + "VALUES (?,?,?)", chapterId, ruleId, order);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("该章节已引用规则：" + rule.getRuleKey());
        }
        recordHistory("inspection_chapter_rule", chapterId, "INSERT", null,
                Map.of("chapterId", chapterId, "rule", rule.getRuleKey(), "sortOrder", order));
    }

    @Transactional
    public void unbindRule(Long chapterId, Long ruleId) {
        InspectionRule rule = jdbc.query(RULE_SELECT + "WHERE r.id=?", RULE_MAPPER, ruleId)
                .stream().findFirst().orElse(null);
        int n = jdbc.update("DELETE FROM inspection_chapter_rule WHERE chapter_id=? AND rule_id=?",
                chapterId, ruleId);
        if (n == 0) {
            throw new IllegalArgumentException("该章节未引用此规则");
        }
        recordHistory("inspection_chapter_rule", chapterId, "DELETE",
                Map.of("chapterId", chapterId, "rule", rule == null ? String.valueOf(ruleId) : rule.getRuleKey()),
                null);
    }

    /** 重排某章内规则的执行顺序 */
    @Transactional
    public int reorderChapterRules(Long chapterId, List<Long> ruleIds) {
        if (ruleIds == null) {
            throw new IllegalArgumentException("ruleIds 不能为空");
        }
        int n = 0;
        int order = 0;
        for (Long rid : ruleIds) {
            n += jdbc.update("UPDATE inspection_chapter_rule SET sort_order=? "
                    + "WHERE chapter_id=? AND rule_id=?", order++, chapterId, rid);
        }
        recordHistory("inspection_chapter_rule", chapterId, "UPDATE", null,
                Map.of("chapterId", chapterId, "reordered", n));
        return n;
    }

    private int nextBindOrder(Long chapterId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), -1) FROM inspection_chapter_rule WHERE chapter_id=?",
                Integer.class, chapterId);
        return (max == null ? -1 : max) + 1;
    }

    // ==================================================================
    // 配置基线
    // ==================================================================

    public List<InspectionBaseline> listBaselines(String dbType) {
        if (dbType != null && !dbType.isBlank()) {
            return jdbc.query("SELECT * FROM inspection_baseline WHERE db_type=? ORDER BY param_name",
                    BL_MAPPER, dbType);
        }
        return jdbc.query("SELECT * FROM inspection_baseline ORDER BY db_type, param_name", BL_MAPPER);
    }

    public Optional<InspectionBaseline> findBaseline(Long id) {
        List<InspectionBaseline> r = jdbc.query(
                "SELECT * FROM inspection_baseline WHERE id=?", BL_MAPPER, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** 各库基线条数统计 */
    public Map<String, Integer> countBaselinesByType() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT db_type, COUNT(*) AS cnt FROM inspection_baseline GROUP BY db_type ORDER BY db_type",
                rs -> { out.put(rs.getString("db_type"), rs.getInt("cnt")); });
        return out;
    }

    /** 各库规则库条数统计（去重后的规则数，不是引用数） */
    public Map<String, Integer> countRulesByType() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT db_type, COUNT(*) AS cnt FROM inspection_rule GROUP BY db_type ORDER BY db_type",
                rs -> { out.put(rs.getString("db_type"), rs.getInt("cnt")); });
        return out;
    }

    /**
     * 各库的章节引用数统计。
     * 注意与 countRulesByType 的区别：一条规则被 3 个章节引用就是 3 条绑定，
     * 但规则库里只算 1 条。两个数字都报，才不会把「共享」误读成「重复」。
     */
    public Map<String, Integer> countBindingsByType() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT t.db_type, COUNT(*) AS cnt FROM inspection_chapter_rule cr "
                        + "JOIN inspection_chapter c ON c.id = cr.chapter_id "
                        + "JOIN inspection_template t ON t.id = c.template_id "
                        + "GROUP BY t.db_type ORDER BY t.db_type",
                rs -> { out.put(rs.getString("db_type"), rs.getInt("cnt")); });
        return out;
    }

    @Transactional
    public InspectionBaseline createBaseline(InspectionBaseline b) {
        validateBaseline(b);
        try {
            jdbc.update("""
                    INSERT INTO inspection_baseline
                        (db_type, param_name, query_sql, operator, expected_value,
                         expected_value_min, expected_value_max, risk_level,
                         description_zh, description_en, enabled)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, b.getDbType(), b.getParamName(), b.getQuerySql(), b.getOperator(),
                    b.getExpectedValue(), b.getExpectedValueMin(), b.getExpectedValueMax(),
                    b.getRiskLevel(), b.getDescriptionZh(), b.getDescriptionEn(),
                    b.getEnabled() == null ? 1 : b.getEnabled());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    "该类型下参数基线已存在：" + b.getDbType() + " / " + b.getParamName());
        }
        InspectionBaseline created = jdbc.query(
                "SELECT * FROM inspection_baseline WHERE db_type=? AND param_name=?",
                BL_MAPPER, b.getDbType(), b.getParamName()).get(0);
        recordHistory("inspection_baseline", created.getId(), "INSERT", null,
                Map.of("dbType", created.getDbType(), "param", created.getParamName(),
                        "expected", String.valueOf(created.getExpectedValue())));
        return created;
    }

    @Transactional
    public InspectionBaseline updateBaseline(Long id, InspectionBaseline patch) {
        InspectionBaseline old = findBaseline(id)
                .orElseThrow(() -> new IllegalArgumentException("基线不存在: " + id));

        String op = patch.getOperator() != null ? patch.getOperator() : old.getOperator();
        String expected = patch.getExpectedValue() != null ? patch.getExpectedValue() : old.getExpectedValue();
        Double min = patch.getExpectedValueMin() != null ? patch.getExpectedValueMin() : old.getExpectedValueMin();
        Double max = patch.getExpectedValueMax() != null ? patch.getExpectedValueMax() : old.getExpectedValueMax();

        if ("BETWEEN".equalsIgnoreCase(op) && (min == null || max == null)) {
            throw new IllegalArgumentException("operator=BETWEEN 时必须提供 expectedValueMin 与 expectedValueMax");
        }

        jdbc.update("""
                UPDATE inspection_baseline SET
                    query_sql=?, operator=?, expected_value=?, expected_value_min=?,
                    expected_value_max=?, risk_level=?, description_zh=?, description_en=?,
                    enabled=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                patch.getQuerySql() != null ? patch.getQuerySql() : old.getQuerySql(),
                op, expected, min, max,
                patch.getRiskLevel() != null ? patch.getRiskLevel() : old.getRiskLevel(),
                patch.getDescriptionZh() != null ? patch.getDescriptionZh() : old.getDescriptionZh(),
                patch.getDescriptionEn() != null ? patch.getDescriptionEn() : old.getDescriptionEn(),
                patch.getEnabled() != null ? patch.getEnabled() : old.getEnabled(),
                id);

        recordHistory("inspection_baseline", id, "UPDATE",
                Map.of("param", old.getParamName(), "operator", String.valueOf(old.getOperator()),
                        "expected", String.valueOf(old.getExpectedValue())),
                Map.of("param", old.getParamName(), "operator", op, "expected", String.valueOf(expected)));
        return findBaseline(id).orElseThrow();
    }

    @Transactional
    public void deleteBaseline(Long id) {
        InspectionBaseline old = findBaseline(id)
                .orElseThrow(() -> new IllegalArgumentException("基线不存在: " + id));
        jdbc.update("DELETE FROM inspection_baseline WHERE id=?", id);
        recordHistory("inspection_baseline", id, "DELETE",
                Map.of("dbType", old.getDbType(), "param", old.getParamName()), null);
    }

    /** 批量启停某类型下的全部基线 */
    @Transactional
    public int setBaselinesEnabled(String dbType, boolean enabled) {
        int n = jdbc.update("UPDATE inspection_baseline SET enabled=?, updated_at=CURRENT_TIMESTAMP "
                + "WHERE db_type=?", enabled ? 1 : 0, dbType);
        recordHistory("inspection_baseline", 0, "UPDATE", null,
                Map.of("dbType", dbType, "bulkEnabled", enabled ? 1 : 0, "affected", n));
        return n;
    }

    private void validateBaseline(InspectionBaseline b) {
        if (b.getDbType() == null || b.getDbType().isBlank()) {
            throw new IllegalArgumentException("db_type 不能为空");
        }
        if (b.getParamName() == null || b.getParamName().isBlank()) {
            throw new IllegalArgumentException("参数名不能为空");
        }
        Set<String> ops = Set.of("=", ">", "<", ">=", "<=", "!=", "BETWEEN", "LIKE");
        String op = b.getOperator() == null ? "=" : b.getOperator().toUpperCase(Locale.ROOT);
        if (!ops.contains(op)) {
            throw new IllegalArgumentException("operator 非法：" + b.getOperator()
                    + "，允许值 " + ops);
        }
        if ("BETWEEN".equals(op) && (b.getExpectedValueMin() == null || b.getExpectedValueMax() == null)) {
            throw new IllegalArgumentException("operator=BETWEEN 时必须提供 expectedValueMin 与 expectedValueMax");
        }
        Set<String> levels = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        String lv = b.getRiskLevel() == null ? "MEDIUM" : b.getRiskLevel().toUpperCase(Locale.ROOT);
        if (!levels.contains(lv)) {
            throw new IllegalArgumentException("risk_level 非法：" + b.getRiskLevel()
                    + "，允许值 " + levels);
        }
        b.setOperator(op);
        b.setRiskLevel(lv);
    }

    // ==================================================================
    // 修改留痕
    // ==================================================================

    public void recordHistory(String tableName, long recordId, String action,
                              Map<String, Object> oldVal, Map<String, Object> newVal) {
        try {
            jdbc.update("""
                    INSERT INTO inspection_history
                        (table_name, record_id, action, old_value, new_value, modified_by)
                    VALUES (?,?,?,?,?,?)
                    """, tableName, recordId, action,
                    oldVal == null ? null : mapper.writeValueAsString(oldVal),
                    newVal == null ? null : mapper.writeValueAsString(newVal),
                    "console");
        } catch (Exception e) {
            log.warn("记录巡检配置修改历史失败: {} #{} {}", tableName, recordId, action, e);
        }
    }

    public List<Map<String, Object>> listHistory(int limit) {
        return jdbc.query("SELECT * FROM inspection_history ORDER BY modified_at DESC, id DESC LIMIT ?",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("tableName", rs.getString("table_name"));
                    m.put("recordId", rs.getLong("record_id"));
                    m.put("action", rs.getString("action"));
                    m.put("oldValue", rs.getString("old_value"));
                    m.put("newValue", rs.getString("new_value"));
                    m.put("modifiedBy", rs.getString("modified_by"));
                    m.put("modifiedAt", rs.getTimestamp("modified_at"));
                    return m;
                }, limit);
    }

    // ==================================================================
    // 种子导入
    // ==================================================================

    /**
     * 导入默认巡检配置。三类数据各自判空、各自幂等：
     *
     *   inspection_rule          空 → 从 rules.json 导入规则库
     *   inspection_template      空 → 从 templates.json 导入模板与章节，并按 rule_keys 建绑定
     *   两者都在、但绑定为空    → 老库升级场景：模板是旧的（规则还内嵌在章节下），
     *                            此时用 templates.json 里的 rule_keys 重新把绑定补出来
     *
     * 最后一种情况是「规则从章节里搬进规则库」这次改动的升级路径：
     * 不删用户的模板与章节，只把「章节引用了哪些规则」这层关系重建出来。
     */
    @Transactional
    public Map<String, Object> seedIfEmpty() {
        Map<String, Object> stat = new LinkedHashMap<>();
        // 两个口径分开报：Imported 是「本次导入了多少」，Total 是「现在库里有多少」。
        // 混在一起会让人误读 —— 第二次启动时 Imported 全是 0，看起来像种子丢了。
        stat.put("templatesImported", 0);
        stat.put("rulesImported", 0);
        stat.put("bindingsImported", 0);
        stat.put("baselinesImported", 0);

        boolean rulesEmpty = countOf("inspection_rule") == 0;
        boolean templatesEmpty = countOf("inspection_template") == 0;
        boolean bindingsEmpty = countOf("inspection_chapter_rule") == 0;

        // 顺序不能反：先有规则库，模板的 rule_keys 才有东西可指
        if (rulesEmpty) {
            stat.put("rulesImported", loadRuleSeed());
        }
        if (templatesEmpty) {
            stat.put("templatesImported", loadTemplateSeed());
        } else if (bindingsEmpty) {
            stat.put("bindingsImported", rebuildBindingsFromSeed());
        }
        if (countOf("inspection_baseline") == 0) {
            stat.put("baselinesImported", loadBaselineSeed());
        }

        stat.put("templatesTotal", countOf("inspection_template"));
        stat.put("chaptersTotal", countOf("inspection_chapter"));
        stat.put("rulesTotal", countOf("inspection_rule"));
        stat.put("bindingsTotal", countOf("inspection_chapter_rule"));
        stat.put("baselinesTotal", countOf("inspection_baseline"));
        return stat;
    }

    private int countOf(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private int loadRuleSeed() {
        try {
            Map<String, Object> root = readSeed(properties.getInspectionRules());
            List<InspectionRule> rules = mapper.convertValue(root.get("rules"),
                    new TypeReference<List<InspectionRule>>() {});
            if (rules == null || rules.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (InspectionRule r : rules) {
                if (r.getSource() == null) {
                    r.setSource("PRESET");
                }
                validateRule(r);
                try {
                    jdbc.update("""
                            INSERT INTO inspection_rule
                                (rule_key, db_type, rule_name_zh, rule_name_en, rule_sql,
                                 category, enabled, source)
                            VALUES (?,?,?,?,?,?,?,?)
                            """, r.getRuleKey(), r.getDbType(), r.getRuleNameZh(), r.getRuleNameEn(),
                            r.getRuleSql(), r.getCategory(),
                            r.getEnabled() == null ? 1 : r.getEnabled(), r.getSource());
                    count++;
                } catch (org.springframework.dao.DuplicateKeyException dup) {
                    log.debug("规则已存在，跳过：{}", r.getRuleKey());
                }
            }
            log.info("巡检规则库种子导入完成：{} 条", count);
            return count;
        } catch (Exception e) {
            log.error("巡检规则库种子导入失败", e);
            return 0;
        }
    }

    private int loadTemplateSeed() {
        try {
            Map<String, Object> root = readSeed(properties.getInspectionTemplates());
            List<InspectionTemplate> templates = mapper.convertValue(root.get("templates"),
                    new TypeReference<List<InspectionTemplate>>() {});
            if (templates == null || templates.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (InspectionTemplate t : templates) {
                jdbc.update("""
                        INSERT INTO inspection_template
                            (db_type, template_name_zh, template_name_en, version,
                             description, is_default, is_preset)
                        VALUES (?,?,?,?,?,?,?)
                        """, t.getDbType(), t.getTemplateNameZh(), t.getTemplateNameEn(),
                        t.getVersion() == null ? "v1" : t.getVersion(), t.getDescription(),
                        nz(t.getIsDefault()), nz(t.getIsPreset()));

                Long tplId = jdbc.queryForObject(
                        "SELECT id FROM inspection_template WHERE db_type=? AND template_name_zh=?",
                        Long.class, t.getDbType(), t.getTemplateNameZh());
                count++;

                if (t.getChapters() == null) {
                    continue;
                }
                for (InspectionChapter c : t.getChapters()) {
                    jdbc.update("""
                            INSERT INTO inspection_chapter
                                (template_id, chapter_number, chapter_title_zh, chapter_title_en,
                                 description, enabled, sort_order)
                            VALUES (?,?,?,?,?,?,?)
                            """, tplId, c.getChapterNumber(), c.getChapterTitleZh(),
                            c.getChapterTitleEn(), c.getDescription(),
                            c.getEnabled() == null ? 1 : c.getEnabled(),
                            c.getSortOrder() == null ? c.getChapterNumber() : c.getSortOrder());

                    Long chId = jdbc.queryForObject(
                            "SELECT id FROM inspection_chapter WHERE template_id=? AND chapter_number=?",
                            Long.class, tplId, c.getChapterNumber());
                    bindRuleKeys(chId, c.getRuleKeys());
                }
            }
            log.info("巡检模板种子导入完成：{} 个模板", count);
            return count;
        } catch (Exception e) {
            log.error("巡检模板种子导入失败", e);
            return 0;
        }
    }

    /**
     * 老库升级：模板与章节都还在（规则以前内嵌在章节里），只把绑定关系补出来。
     * 按 (db_type, 模板名) 与 (模板, 章节号) 匹配，不新建任何模板或章节。
     */
    private int rebuildBindingsFromSeed() {
        try {
            Map<String, Object> root = readSeed(properties.getInspectionTemplates());
            List<InspectionTemplate> templates = mapper.convertValue(root.get("templates"),
                    new TypeReference<List<InspectionTemplate>>() {});
            if (templates == null) {
                return 0;
            }
            int count = 0;
            for (InspectionTemplate t : templates) {
                List<Long> tplIds = jdbc.queryForList(
                        "SELECT id FROM inspection_template WHERE db_type=? AND template_name_zh=?",
                        Long.class, t.getDbType(), t.getTemplateNameZh());
                if (tplIds.isEmpty() || t.getChapters() == null) {
                    continue;
                }
                Long tplId = tplIds.get(0);
                for (InspectionChapter c : t.getChapters()) {
                    List<Long> chIds = jdbc.queryForList(
                            "SELECT id FROM inspection_chapter WHERE template_id=? AND chapter_number=?",
                            Long.class, tplId, c.getChapterNumber());
                    if (chIds.isEmpty()) {
                        continue;
                    }
                    count += bindRuleKeys(chIds.get(0), c.getRuleKeys());
                }
            }
            log.info("巡检章节↔规则绑定重建完成：{} 条", count);
            return count;
        } catch (Exception e) {
            log.error("巡检章节↔规则绑定重建失败", e);
            return 0;
        }
    }

    /** 按 rule_key 建绑定；已绑定的跳过，库里查不到的 key 记一条 warn 不中断 */
    private int bindRuleKeys(Long chapterId, List<String> ruleKeys) {
        if (ruleKeys == null || ruleKeys.isEmpty()) {
            return 0;
        }
        int order = nextBindOrder(chapterId);
        int n = 0;
        for (String key : ruleKeys) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM inspection_rule WHERE rule_key=?", Long.class, key);
            if (ids.isEmpty()) {
                log.warn("模板引用了规则库里不存在的 key，已跳过：{}", key);
                continue;
            }
            try {
                jdbc.update("INSERT INTO inspection_chapter_rule (chapter_id, rule_id, sort_order) "
                        + "VALUES (?,?,?)", chapterId, ids.get(0), order++);
                n++;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 已经绑过了（重复导入），跳过
            }
        }
        return n;
    }

    private Map<String, Object> readSeed(String location) throws Exception {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        Resource res = loader.getResource(location);
        try (InputStream is = res.getInputStream()) {
            return mapper.readValue(is, new TypeReference<Map<String, Object>>() {});
        }
    }

    private int loadBaselineSeed() {
        try {
            DefaultResourceLoader loader = new DefaultResourceLoader();
            Resource res = loader.getResource(properties.getInspectionBaselines());
            Map<String, List<InspectionBaseline>> map;
            try (InputStream is = res.getInputStream()) {
                Map<String, Object> root = mapper.readValue(is, new TypeReference<Map<String, Object>>() {});
                map = mapper.convertValue(root.get("baselines"),
                        new TypeReference<Map<String, List<InspectionBaseline>>>() {});
            }
            if (map == null || map.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (Map.Entry<String, List<InspectionBaseline>> e : map.entrySet()) {
                String dbType = e.getKey();
                for (InspectionBaseline b : e.getValue()) {
                    b.setDbType(dbType);
                    validateBaseline(b);
                    try {
                        jdbc.update("""
                                INSERT INTO inspection_baseline
                                    (db_type, param_name, query_sql, operator, expected_value,
                                     expected_value_min, expected_value_max, risk_level,
                                     description_zh, description_en, enabled)
                                VALUES (?,?,?,?,?,?,?,?,?,?,1)
                                """, b.getDbType(), b.getParamName(), b.getQuerySql(),
                                b.getOperator(), b.getExpectedValue(), b.getExpectedValueMin(),
                                b.getExpectedValueMax(), b.getRiskLevel(),
                                b.getDescriptionZh(), b.getDescriptionEn());
                        count++;
                    } catch (org.springframework.dao.DuplicateKeyException dup) {
                        log.debug("基线已存在，跳过：{} / {}", dbType, b.getParamName());
                    }
                }
            }
            log.info("配置基线种子导入完成：{} 条", count);
            return count;
        } catch (Exception e) {
            log.error("配置基线种子导入失败", e);
            return 0;
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
