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
 * 巡检配置服务：模板 / 章节 / 规则 / 基线的增删改查 + 种子导入 + 修改留痕。
 *
 * 对齐 RaccoonX 的 inspection dal + init_db：
 *   - 三层结构：模板 → 章节 → 规则，两级 ON DELETE CASCADE
 *   - 种子导入：表为空时从 templates.json / baselines.json 装载默认配置
 *   - 默认模板互斥：同 db_type 内至多一个 is_default=1
 *   - 预置保护：is_preset=1 的模板禁止改名与删除
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

    private static final RowMapper<InspectionQuery> Q_MAPPER = (rs, n) -> InspectionQuery.builder()
            .id(rs.getLong("id"))
            .chapterId(rs.getLong("chapter_id"))
            .queryKey(rs.getString("query_key"))
            .querySql(rs.getString("query_sql"))
            .queryDescriptionZh(rs.getString("query_description_zh"))
            .queryDescriptionEn(rs.getString("query_description_en"))
            .enabled(rs.getInt("enabled"))
            .sortOrder(rs.getInt("sort_order"))
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
            ch.setQueries(jdbc.query(
                    "SELECT * FROM inspection_query WHERE chapter_id=? "
                            + (onlyEnabled ? "AND enabled=1 " : "")
                            + "ORDER BY sort_order, id", Q_MAPPER, ch.getId()));
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
    // 规则（巡检 SQL）
    // ==================================================================

    public List<InspectionQuery> listQueries(Long chapterId) {
        return jdbc.query("SELECT * FROM inspection_query WHERE chapter_id=? "
                + "ORDER BY sort_order, id", Q_MAPPER, chapterId);
    }

    @Transactional
    public InspectionQuery createQuery(InspectionQuery q) {
        if (q.getChapterId() == null) {
            throw new IllegalArgumentException("chapterId 不能为空");
        }
        if (q.getQueryKey() == null || q.getQueryKey().isBlank()) {
            throw new IllegalArgumentException("规则 key 不能为空");
        }
        if (q.getQuerySql() == null || q.getQuerySql().isBlank()) {
            throw new IllegalArgumentException("巡检 SQL 不能为空");
        }
        try {
            jdbc.update("""
                    INSERT INTO inspection_query
                        (chapter_id, query_key, query_sql, query_description_zh,
                         query_description_en, enabled, sort_order)
                    VALUES (?,?,?,?,?,?,?)
                    """, q.getChapterId(), q.getQueryKey(), q.getQuerySql(),
                    q.getQueryDescriptionZh(), q.getQueryDescriptionEn(),
                    q.getEnabled() == null ? 1 : q.getEnabled(),
                    q.getSortOrder() == null ? 0 : q.getSortOrder());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("该章节下规则 key 已存在：" + q.getQueryKey());
        }
        InspectionQuery created = jdbc.query(
                "SELECT * FROM inspection_query WHERE chapter_id=? AND query_key=?",
                Q_MAPPER, q.getChapterId(), q.getQueryKey()).get(0);
        recordHistory("inspection_query", created.getId(), "INSERT",
                null, Map.of("key", created.getQueryKey()));
        return created;
    }

    @Transactional
    public InspectionQuery updateQuery(Long id, InspectionQuery patch) {
        InspectionQuery old = jdbc.query("SELECT * FROM inspection_query WHERE id=?", Q_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));

        jdbc.update("""
                UPDATE inspection_query SET
                    query_sql=?, query_description_zh=?, query_description_en=?,
                    enabled=?, sort_order=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                patch.getQuerySql() != null ? patch.getQuerySql() : old.getQuerySql(),
                patch.getQueryDescriptionZh() != null ? patch.getQueryDescriptionZh() : old.getQueryDescriptionZh(),
                patch.getQueryDescriptionEn() != null ? patch.getQueryDescriptionEn() : old.getQueryDescriptionEn(),
                patch.getEnabled() != null ? patch.getEnabled() : old.getEnabled(),
                patch.getSortOrder() != null ? patch.getSortOrder() : old.getSortOrder(),
                id);

        recordHistory("inspection_query", id, "UPDATE",
                Map.of("key", old.getQueryKey(), "enabled", nz(old.getEnabled())),
                Map.of("key", old.getQueryKey(),
                        "enabled", patch.getEnabled() != null ? patch.getEnabled() : nz(old.getEnabled())));
        return jdbc.query("SELECT * FROM inspection_query WHERE id=?", Q_MAPPER, id).get(0);
    }

    @Transactional
    public void deleteQuery(Long id) {
        InspectionQuery old = jdbc.query("SELECT * FROM inspection_query WHERE id=?", Q_MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + id));
        jdbc.update("DELETE FROM inspection_query WHERE id=?", id);
        recordHistory("inspection_query", id, "DELETE", Map.of("key", old.getQueryKey()), null);
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
     * 表为空时导入默认巡检配置。
     * 对齐 RaccoonX 的 init_default_baselines + init_db 默认模板。
     */
    @Transactional
    public Map<String, Object> seedIfEmpty() {
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("templatesLoaded", 0);
        stat.put("chaptersLoaded", 0);
        stat.put("queriesLoaded", 0);
        stat.put("baselinesLoaded", 0);

        Integer tplCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_template", Integer.class);
        boolean templatesEmpty = tplCount == null || tplCount == 0;

        Integer blCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_baseline", Integer.class);
        boolean baselinesEmpty = blCount == null || blCount == 0;

        if (templatesEmpty) {
            stat.put("templatesLoaded", loadTemplateSeed());
        }
        if (baselinesEmpty) {
            stat.put("baselinesLoaded", loadBaselineSeed());
        }

        // 统计实际行数
        stat.put("chaptersLoaded", jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_chapter", Integer.class));
        stat.put("queriesLoaded", jdbc.queryForObject(
                "SELECT COUNT(*) FROM inspection_query", Integer.class));
        return stat;
    }

    private int loadTemplateSeed() {
        try {
            DefaultResourceLoader loader = new DefaultResourceLoader();
            Resource res = loader.getResource(properties.getInspectionTemplates());
            List<InspectionTemplate> templates;
            try (InputStream is = res.getInputStream()) {
                Map<String, Object> root = mapper.readValue(is, new TypeReference<Map<String, Object>>() {});
                templates = mapper.convertValue(root.get("templates"),
                        new TypeReference<List<InspectionTemplate>>() {});
            }
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

                    if (c.getQueries() == null) {
                        continue;
                    }
                    int order = 0;
                    for (InspectionQuery q : c.getQueries()) {
                        jdbc.update("""
                                INSERT INTO inspection_query
                                    (chapter_id, query_key, query_sql, query_description_zh,
                                     query_description_en, enabled, sort_order)
                                VALUES (?,?,?,?,?,?,?)
                                """, chId, q.getQueryKey(), q.getQuerySql(),
                                q.getQueryDescriptionZh(), q.getQueryDescriptionEn(),
                                q.getEnabled() == null ? 1 : q.getEnabled(), order++);
                    }
                }
            }
            log.info("巡检模板种子导入完成：{} 个模板", count);
            return count;
        } catch (Exception e) {
            log.error("巡检模板种子导入失败", e);
            return 0;
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
