package com.dbnav.inspection;

import com.dbnav.datasource.DataSourceManager;
import com.dbnav.datasource.model.DataSourceInfo;
import com.dbnav.inspection.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 巡检执行器。
 *
 * 一次巡检的完整链路：
 *
 *   1. 解析目标数据源与巡检模板（显式 templateId 优先，否则取该库类型的默认模板）
 *   2. 建立一条连接，按「章节号 → 规则 sort_order」的顺序逐条执行启用规则
 *   3. 同一条连接上采集基线值，多行结果逐行判定
 *   4. 聚合统计并落库到 inspection_run / _query / _baseline 三张表
 *
 * 与 RaccoonX 的对应关系：RaccoonX 把巡检结果直接拼成 HTML 报告返回，
 * 不留历史；这里改成「执行记录 + 明细」的持久化模型，报告由前端渲染，
 * 好处是可以做趋势对比（同一数据源历次合规率）。
 *
 * <h3>错误分层</h3>
 * <ul>
 *   <li><b>请求级错误</b>（dataSourceId 为空、数据源/模板不存在）——
 *       直接抛 {@link IllegalArgumentException}，由控制层转成 4xx，<b>不留执行记录</b>，
 *       因为这类请求根本没有产生一次「执行」。</li>
 *   <li><b>执行级错误</b>（连不上库、模板无章节）——留一条 status=FAILED 的记录，
 *       errorMsg 写明原因。这样「跑了但失败」在历史里是可追溯的。</li>
 *   <li><b>单条错误</b>（某条 SQL 语法不兼容、权限不足）——不影响整体，
 *       该条记 FAILED，其余照跑，整次巡检置为 PARTIAL。</li>
 * </ul>
 *
 * <h3>多行基线语义</h3>
 * 基线采集 SQL 可能按库/按实例返回多行（例如 SQL Server 的
 * {@code SELECT name, is_auto_close_on FROM sys.databases}）。
 * 逐行判定后 <b>只要有一行不合规，整条基线即判为不合规</b>——
 * 有一个库开着 auto_close 就是问题，不能因为其他库正常而放过。
 */
@Slf4j
@Component
public class InspectionRunner {

    /** 结果预览保留的最大行数（超出只保留前 N 行） */
    private static final int PREVIEW_ROWS = 200;

    /** 单条规则最多扫描的行数；达到上限即停止读取并置 truncated=1 */
    private static final int MAX_SCAN_ROWS = 5000;

    /** 基线采集最多读取的行数（基线是「按对象逐行判定」，不需要海量行） */
    private static final int MAX_BASELINE_ROWS = 500;

    /** actualValue 里最多拼接几个采集值，超出用 … 收尾 */
    private static final int VALUE_JOIN_LIMIT = 10;

    /** 落库用的 JSON 序列化器；刻意不用 ConfigJsonMapper（那个是 snake_case，专供配置文件） */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final InspectionConfigService configService;
    private final DataSourceManager dataSourceManager;
    private final BaselineChecker baselineChecker;

    public InspectionRunner(JdbcTemplate jdbc,
                            InspectionConfigService configService,
                            DataSourceManager dataSourceManager,
                            BaselineChecker baselineChecker) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.dataSourceManager = dataSourceManager;
        this.baselineChecker = baselineChecker;
    }

    // ==================================================================
    // 执行入口
    // ==================================================================

    /**
     * 执行一次巡检并落库。
     *
     * @return 已持久化的执行记录（含 queries / baselines 明细）
     */
    public InspectionRun run(InspectionRunRequest req) {
        if (req == null || req.getDataSourceId() == null) {
            throw new IllegalArgumentException("dataSourceId 不能为空");
        }
        DataSourceInfo ds = dataSourceManager.findById(req.getDataSourceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "数据源不存在: " + req.getDataSourceId()));

        boolean onlyEnabled = req.getOnlyEnabled() == null || req.getOnlyEnabled();
        boolean includeBaselines = req.getIncludeBaselines() == null || req.getIncludeBaselines();
        Set<Integer> chapterFilter = req.getChapterNumbers() == null
                ? Set.of() : new HashSet<>(req.getChapterNumbers());

        // ---- 模板：显式指定 > 该库类型默认模板 ----
        InspectionTemplate tpl;
        if (req.getTemplateId() != null) {
            tpl = configService.getTemplateTree(req.getTemplateId(), false)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "巡检模板不存在: " + req.getTemplateId()));
        } else {
            tpl = configService.getDefaultTemplateTree(ds.getDbType(), false)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "库类型 " + ds.getDbType() + " 没有默认巡检模板，请显式指定 templateId"));
        }

        LocalDateTime startedAt = LocalDateTime.now();
        long t0 = System.currentTimeMillis();

        List<InspectionRunQuery> queryResults = new ArrayList<>();
        List<InspectionRunBaseline> baselineResults = new ArrayList<>();
        String fatalError = null;

        Connection conn = null;
        try {
            conn = dataSourceManager.getConnection(ds.getId());
        } catch (Exception e) {
            fatalError = "连接数据源失败：" + rootMessage(e);
            log.warn("巡检无法连接数据源 {} ({}): {}", ds.getName(), ds.getDbType(), rootMessage(e));
        }

        if (conn != null) {
            try {
                executeQueries(conn, tpl, onlyEnabled, chapterFilter, queryResults);
                if (includeBaselines) {
                    executeBaselines(conn, ds.getDbType(), baselineResults);
                }
            } catch (Exception e) {
                fatalError = "巡检执行中断：" + rootMessage(e);
                log.error("巡检执行中断 ds={}", ds.getName(), e);
            } finally {
                closeQuietly(conn);
            }
        }

        // 模板里一条规则都没跑起来 —— 配置问题，直接判整体失败
        if (fatalError == null && queryResults.isEmpty()) {
            fatalError = "模板「" + tpl.getTemplateNameZh() + "」不含任何可执行规则"
                    + (chapterFilter.isEmpty() ? "" : "（章节过滤：" + chapterFilter + "）");
        }

        InspectionRun run = aggregate(ds, tpl, queryResults, baselineResults,
                fatalError, startedAt, System.currentTimeMillis() - t0, req.getExecutedBy());
        persist(run, queryResults, baselineResults);

        run.setQueries(queryResults);
        run.setBaselines(baselineResults);
        log.info("巡检完成 ds={} tpl={} status={} 规则 {}/{} 基线 {}/{} 合规率 {}%",
                ds.getName(), tpl.getTemplateNameZh(), run.getStatus(),
                run.getOkQueries(), run.getTotalQueries(),
                run.getBaselinesPass(), run.getTotalBaselines(), run.getCompliancePct());
        return run;
    }

    // ==================================================================
    // 规则执行
    // ==================================================================

    private void executeQueries(Connection conn, InspectionTemplate tpl, boolean onlyEnabled,
                                Set<Integer> chapterFilter, List<InspectionRunQuery> out) {
        if (tpl.getChapters() == null) {
            return;
        }
        for (InspectionChapter ch : tpl.getChapters()) {
            if (!chapterFilter.isEmpty() && !chapterFilter.contains(ch.getChapterNumber())) {
                continue;
            }
            boolean chapterEnabled = ch.getEnabled() == null || ch.getEnabled() == 1;
            if (ch.getQueries() == null) {
                continue;
            }
            for (InspectionQuery q : ch.getQueries()) {
                boolean queryEnabled = q.getEnabled() == null || q.getEnabled() == 1;

                // onlyEnabled=true 时，停用的章节/规则不执行，但仍记一条 SKIPPED，
                // 让报告能说清「134 条规则里有 3 条是停用的」，而不是静默消失。
                if (onlyEnabled && (!chapterEnabled || !queryEnabled)) {
                    out.add(skipped(ch, q, !chapterEnabled ? "所属章节已停用" : "规则已停用"));
                    continue;
                }
                out.add(executeRule(conn, ch, q));
            }
        }
    }

    private InspectionRunQuery skipped(InspectionChapter ch, InspectionQuery q, String reason) {
        return InspectionRunQuery.builder()
                .chapterNumber(ch.getChapterNumber())
                .chapterTitle(ch.getChapterTitleZh())
                .queryKey(q.getQueryKey())
                .querySql(q.getQuerySql())
                .descriptionZh(q.getQueryDescriptionZh())
                .status("SKIPPED")
                .elapsedMs(0L)
                .rowCount(0)
                .truncated(0)
                .errorMsg(reason)
                .build();
    }

    private InspectionRunQuery executeRule(Connection conn, InspectionChapter ch, InspectionQuery q) {
        long t0 = System.currentTimeMillis();
        InspectionRunQuery.InspectionRunQueryBuilder b = InspectionRunQuery.builder()
                .chapterNumber(ch.getChapterNumber())
                .chapterTitle(ch.getChapterTitleZh())
                .queryKey(q.getQueryKey())
                .querySql(q.getQuerySql())
                .descriptionZh(q.getQueryDescriptionZh());

        String sql = normalizeSql(q.getQuerySql());
        if (sql == null) {
            return b.status("FAILED").elapsedMs(0L).rowCount(0).truncated(0)
                    .errorMsg("规则未配置 SQL").build();
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(Math.min(PREVIEW_ROWS, 500));
            boolean hasResultSet = ps.execute();

            if (!hasResultSet) {
                // DDL / DML：跑通即算 OK，没有结果集
                return b.status("OK")
                        .elapsedMs(System.currentTimeMillis() - t0)
                        .rowCount(0).truncated(0).build();
            }

            try (ResultSet rs = ps.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();

                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(md.getColumnLabel(i));
                }

                List<List<Object>> preview = new ArrayList<>();
                int scanned = 0;
                while (rs.next()) {
                    if (scanned >= MAX_SCAN_ROWS) {
                        break;
                    }
                    if (preview.size() < PREVIEW_ROWS) {
                        List<Object> row = new ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) {
                            Object v = rs.getObject(i);
                            row.add(v == null ? null : String.valueOf(v));
                        }
                        preview.add(row);
                    }
                    scanned++;
                }

                int truncated = (scanned >= MAX_SCAN_ROWS || preview.size() < scanned) ? 1 : 0;
                return b.status("OK")
                        .elapsedMs(System.currentTimeMillis() - t0)
                        .rowCount(scanned)
                        .columns(columns)
                        .rows(preview)
                        .truncated(truncated)
                        .build();
            }

        } catch (Exception e) {
            return b.status("FAILED")
                    .elapsedMs(System.currentTimeMillis() - t0)
                    .rowCount(0).truncated(0)
                    .errorMsg(rootMessage(e))
                    .build();
        }
    }

    // ==================================================================
    // 基线采集与判定
    // ==================================================================

    private void executeBaselines(Connection conn, String dbType, List<InspectionRunBaseline> out) {
        for (InspectionBaseline bl : configService.listBaselines(dbType)) {
            if (bl.getEnabled() != null && bl.getEnabled() == 0) {
                continue;
            }
            out.add(executeBaseline(conn, bl));
        }
    }

    /**
     * 采集一条基线的实际值并判定。
     *
     * 取值列约定：<b>结果集的最后一列</b>。这样三种常见写法都能覆盖——
     * <pre>
     *   SHOW max_connections                  → (Variable_name, Value)      取第 2 列
     *   SELECT name, value FROM v$parameter   → (name, value)               取第 2 列
     *   SELECT @@max_connections              → (1 列)                      取第 1 列
     * </pre>
     */
    private InspectionRunBaseline executeBaseline(Connection conn, InspectionBaseline bl) {
        long t0 = System.currentTimeMillis();
        String expected = describeExpected(bl);
        InspectionRunBaseline.InspectionRunBaselineBuilder b = InspectionRunBaseline.builder()
                .paramName(bl.getParamName())
                .operator(bl.getOperator())
                .expectedValue(expected)
                .riskLevel(bl.getRiskLevel())
                .descriptionZh(bl.getDescriptionZh());

        String sql = normalizeSql(bl.getQuerySql());
        if (sql == null) {
            return b.actualValue(null).sampleCount(0).violationCount(0)
                    .isPass(0).isChecked(0)
                    .message("基线未配置采集 SQL")
                    .elapsedMs(System.currentTimeMillis() - t0)
                    .errorMsg("query_sql 为空")
                    .build();
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(MAX_BASELINE_ROWS);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int valueCol = Math.max(md.getColumnCount(), 1);

                List<String> values = new ArrayList<>();
                int samples = 0;        // 采集到的行数
                int checkedRows = 0;    // 真正判定过的行数（值为 null 的行无法判定）
                int violations = 0;     // 不合规的行数
                String firstViolation = null;

                while (rs.next()) {
                    Object raw = rs.getObject(valueCol);
                    String value = raw == null ? null : String.valueOf(raw);
                    samples++;

                    if (value == null) {
                        continue;   // 该行为 NULL：不算违规，也不计入已判定
                    }
                    checkedRows++;

                    BaselineCheckResult r = baselineChecker.check(bl, value);
                    if (r.isChecked() && !r.isPass()) {
                        violations++;
                        if (firstViolation == null) {
                            firstViolation = value;
                        }
                    }
                    if (values.size() < VALUE_JOIN_LIMIT) {
                        values.add(value);
                    }
                }

                String actual = values.isEmpty() ? null : joinDistinct(values);
                long elapsed = System.currentTimeMillis() - t0;

                if (samples == 0) {
                    return b.actualValue(null).sampleCount(0).violationCount(0)
                            .isPass(0).isChecked(0)
                            .message("未采集到参数值，无法判定（采集 SQL 返回 0 行）")
                            .elapsedMs(elapsed).build();
                }
                if (checkedRows == 0) {
                    return b.actualValue(actual).sampleCount(samples).violationCount(0)
                            .isPass(0).isChecked(0)
                            .message("采集到的 " + samples + " 行取值均为 NULL，无法判定")
                            .elapsedMs(elapsed).build();
                }

                boolean pass = violations == 0;
                return b.actualValue(actual)
                        .sampleCount(samples)
                        .violationCount(violations)
                        .isPass(pass ? 1 : 0)
                        .isChecked(1)
                        .message(describeAggregate(samples, violations, firstViolation, bl, expected))
                        .elapsedMs(elapsed)
                        .build();
            }

        } catch (Exception e) {
            return b.actualValue(null).sampleCount(0).violationCount(0)
                    .isPass(0).isChecked(0)
                    .message("采集失败：" + rootMessage(e))
                    .elapsedMs(System.currentTimeMillis() - t0)
                    .errorMsg(rootMessage(e))
                    .build();
        }
    }

    /** 多行结果的可读说明；单行时直接复用判定器的措辞 */
    private String describeAggregate(int samples, int violations, String firstViolation,
                                     InspectionBaseline bl, String expected) {
        String op = bl.getOperator() == null ? "=" : bl.getOperator().trim().toUpperCase(Locale.ROOT);
        if (samples == 1) {
            return violations == 0
                    ? "合规：" + expected + " 满足 " + op
                    : "不合规：实际值不满足 " + op + " " + expected;
        }
        if (violations == 0) {
            return "合规：共采集 " + samples + " 行，全部满足 " + op + " " + expected;
        }
        return "不合规：共采集 " + samples + " 行，其中 " + violations + " 行不满足 " + op + " "
                + expected + "（首个不合规值：" + firstViolation + "）";
    }

    // ==================================================================
    // 聚合与落库
    // ==================================================================

    private InspectionRun aggregate(DataSourceInfo ds, InspectionTemplate tpl,
                                    List<InspectionRunQuery> queryResults,
                                    List<InspectionRunBaseline> baselineResults,
                                    String fatalError, LocalDateTime startedAt,
                                    long durationMs, String executedBy) {

        int ok = 0, failed = 0;
        for (InspectionRunQuery q : queryResults) {
            if ("OK".equals(q.getStatus())) {
                ok++;
            } else if ("FAILED".equals(q.getStatus())) {
                failed++;
            }
        }

        int pass = 0, fail = 0, unchecked = 0;
        Map<String, Integer> failByRisk = new LinkedHashMap<>();
        for (InspectionRunBaseline bl : baselineResults) {
            if (bl.getIsChecked() == null || bl.getIsChecked() == 0) {
                unchecked++;
            } else if (bl.getIsPass() != null && bl.getIsPass() == 1) {
                pass++;
            } else {
                fail++;
                failByRisk.merge(bl.getRiskLevel() == null ? "UNKNOWN" : bl.getRiskLevel(),
                        1, Integer::sum);
            }
        }

        String status;
        if (fatalError != null) {
            status = "FAILED";
        } else if (failed == 0) {
            status = "SUCCESS";
        } else if (ok > 0) {
            status = "PARTIAL";
        } else {
            status = "FAILED";
        }

        double compliance = (pass + fail) == 0
                ? 0.0
                : Math.round(pass * 1000.0 / (pass + fail)) / 10.0;

        return InspectionRun.builder()
                .dataSourceId(ds.getId())
                .dataSourceName(ds.getName())
                .dbType(ds.getDbType())
                .templateId(tpl.getId())
                .templateName(tpl.getTemplateNameZh())
                .status(status)
                .triggerSource("MANUAL")
                .totalQueries(ok + failed)
                .okQueries(ok)
                .failedQueries(failed)
                .totalBaselines(baselineResults.size())
                .baselinesPass(pass)
                .baselinesFail(fail)
                .baselinesUnchecked(unchecked)
                .compliancePct(compliance)
                .riskSummary(failByRisk)
                .errorMsg(fatalError)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .durationMs(durationMs)
                .executedBy(executedBy == null || executedBy.isBlank() ? "system" : executedBy)
                .build();
    }

    private void persist(InspectionRun run, List<InspectionRunQuery> queries,
                         List<InspectionRunBaseline> baselines) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO inspection_run
                        (data_source_id, data_source_name, db_type, template_id, template_name,
                         status, trigger_source, total_queries, ok_queries, failed_queries,
                         total_baselines, baselines_pass, baselines_fail, baselines_unchecked,
                         compliance_pct, risk_summary, error_msg,
                         started_at, finished_at, duration_ms, executed_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            int i = 1;
            ps.setObject(i++, run.getDataSourceId());
            ps.setString(i++, run.getDataSourceName());
            ps.setString(i++, run.getDbType());
            ps.setObject(i++, run.getTemplateId());
            ps.setString(i++, run.getTemplateName());
            ps.setString(i++, run.getStatus());
            ps.setString(i++, run.getTriggerSource());
            ps.setObject(i++, run.getTotalQueries());
            ps.setObject(i++, run.getOkQueries());
            ps.setObject(i++, run.getFailedQueries());
            ps.setObject(i++, run.getTotalBaselines());
            ps.setObject(i++, run.getBaselinesPass());
            ps.setObject(i++, run.getBaselinesFail());
            ps.setObject(i++, run.getBaselinesUnchecked());
            ps.setObject(i++, run.getCompliancePct());
            ps.setString(i++, toJson(run.getRiskSummary()));
            ps.setString(i++, run.getErrorMsg());
            ps.setObject(i++, run.getStartedAt());
            ps.setObject(i++, run.getFinishedAt());
            ps.setObject(i++, run.getDurationMs());
            ps.setString(i++, run.getExecutedBy());
            return ps;
        }, kh);

        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("巡检记录写入成功但未取得主键");
        }
        run.setId(key.longValue());

        for (InspectionRunQuery q : queries) {
            q.setRunId(run.getId());
            jdbc.update("""
                    INSERT INTO inspection_run_query
                        (run_id, chapter_number, chapter_title, query_key, query_sql, description_zh,
                         status, elapsed_ms, row_count, columns_json, rows_json, truncated, error_msg)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    q.getRunId(), q.getChapterNumber(), q.getChapterTitle(), q.getQueryKey(),
                    q.getQuerySql(), q.getDescriptionZh(), q.getStatus(), q.getElapsedMs(),
                    q.getRowCount(), toJson(q.getColumns()), toJson(q.getRows()),
                    q.getTruncated() == null ? 0 : q.getTruncated(), q.getErrorMsg());
        }

        for (InspectionRunBaseline bl : baselines) {
            bl.setRunId(run.getId());
            jdbc.update("""
                    INSERT INTO inspection_run_baseline
                        (run_id, param_name, operator, expected_value, actual_value,
                         sample_count, violation_count, risk_level, is_pass, is_checked,
                         message, description_zh, elapsed_ms, error_msg)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    bl.getRunId(), bl.getParamName(), bl.getOperator(), bl.getExpectedValue(),
                    bl.getActualValue(), bl.getSampleCount(), bl.getViolationCount(),
                    bl.getRiskLevel(), bl.getIsPass(), bl.getIsChecked(), bl.getMessage(),
                    bl.getDescriptionZh(), bl.getElapsedMs(), bl.getErrorMsg());
        }
    }

    // ==================================================================
    // 查询执行历史
    // ==================================================================

    /** 执行历史（不含明细），按开始时间倒序 */
    public List<InspectionRun> listRuns(Long dataSourceId, String dbType, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM inspection_run WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dataSourceId != null) {
            sql.append(" AND data_source_id=?");
            args.add(dataSourceId);
        }
        if (dbType != null && !dbType.isBlank()) {
            sql.append(" AND db_type=?");
            args.add(dbType);
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ?");
        args.add(limit <= 0 ? 50 : Math.min(limit, 500));
        return jdbc.query(sql.toString(), RUN_MAPPER, args.toArray());
    }

    /** 取一次执行的完整明细（表头 + 规则结果 + 基线判定） */
    public Optional<InspectionRun> findRun(Long id, boolean withDetail) {
        List<InspectionRun> rows = jdbc.query(
                "SELECT * FROM inspection_run WHERE id=?", RUN_MAPPER, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        InspectionRun run = rows.get(0);
        if (withDetail) {
            run.setQueries(jdbc.query(
                    "SELECT * FROM inspection_run_query WHERE run_id=? ORDER BY chapter_number, id",
                    RUNQ_MAPPER, id));
            run.setBaselines(jdbc.query(
                    "SELECT * FROM inspection_run_baseline WHERE run_id=? ORDER BY id",
                    RUNBL_MAPPER, id));
        }
        return Optional.of(run);
    }

    /** 删除一次执行记录（明细随外键级联删除） */
    public void deleteRun(Long id) {
        jdbc.update("DELETE FROM inspection_run WHERE id=?", id);
    }

    /** 某数据源最近一次执行（用于界面首屏） */
    public Optional<InspectionRun> latestRun(Long dataSourceId) {
        List<InspectionRun> r = jdbc.query(
                "SELECT * FROM inspection_run WHERE data_source_id=? "
                        + "ORDER BY started_at DESC, id DESC LIMIT 1",
                RUN_MAPPER, dataSourceId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    // ==================================================================
    // 行映射与工具
    // ==================================================================

    private static final RowMapper<InspectionRun> RUN_MAPPER = (rs, n) -> InspectionRun.builder()
            .id(rs.getLong("id"))
            .dataSourceId(nullableLong(rs, "data_source_id"))
            .dataSourceName(rs.getString("data_source_name"))
            .dbType(rs.getString("db_type"))
            .templateId(nullableLong(rs, "template_id"))
            .templateName(rs.getString("template_name"))
            .status(rs.getString("status"))
            .triggerSource(rs.getString("trigger_source"))
            .totalQueries(nullableInt(rs, "total_queries"))
            .okQueries(nullableInt(rs, "ok_queries"))
            .failedQueries(nullableInt(rs, "failed_queries"))
            .totalBaselines(nullableInt(rs, "total_baselines"))
            .baselinesPass(nullableInt(rs, "baselines_pass"))
            .baselinesFail(nullableInt(rs, "baselines_fail"))
            .baselinesUnchecked(nullableInt(rs, "baselines_unchecked"))
            .compliancePct(rs.getObject("compliance_pct", Double.class))
            .riskSummary(readIntMap(rs.getString("risk_summary")))
            .errorMsg(rs.getString("error_msg"))
            .startedAt(toLdt(rs, "started_at"))
            .finishedAt(toLdt(rs, "finished_at"))
            .durationMs(nullableLong(rs, "duration_ms"))
            .executedBy(rs.getString("executed_by"))
            .build();

    private static final RowMapper<InspectionRunQuery> RUNQ_MAPPER = (rs, n) ->
            InspectionRunQuery.builder()
                    .id(rs.getLong("id"))
                    .runId(rs.getLong("run_id"))
                    .chapterNumber(nullableInt(rs, "chapter_number"))
                    .chapterTitle(rs.getString("chapter_title"))
                    .queryKey(rs.getString("query_key"))
                    .querySql(rs.getString("query_sql"))
                    .descriptionZh(rs.getString("description_zh"))
                    .status(rs.getString("status"))
                    .elapsedMs(nullableLong(rs, "elapsed_ms"))
                    .rowCount(nullableInt(rs, "row_count"))
                    .columns(readJson(rs.getString("columns_json"),
                            new TypeReference<List<String>>() {}))
                    .rows(readJson(rs.getString("rows_json"),
                            new TypeReference<List<List<Object>>>() {}))
                    .truncated(nullableInt(rs, "truncated"))
                    .errorMsg(rs.getString("error_msg"))
                    .build();

    private static final RowMapper<InspectionRunBaseline> RUNBL_MAPPER = (rs, n) ->
            InspectionRunBaseline.builder()
                    .id(rs.getLong("id"))
                    .runId(rs.getLong("run_id"))
                    .paramName(rs.getString("param_name"))
                    .operator(rs.getString("operator"))
                    .expectedValue(rs.getString("expected_value"))
                    .actualValue(rs.getString("actual_value"))
                    .sampleCount(nullableInt(rs, "sample_count"))
                    .violationCount(nullableInt(rs, "violation_count"))
                    .riskLevel(rs.getString("risk_level"))
                    .isPass(nullableInt(rs, "is_pass"))
                    .isChecked(nullableInt(rs, "is_checked"))
                    .message(rs.getString("message"))
                    .descriptionZh(rs.getString("description_zh"))
                    .elapsedMs(nullableLong(rs, "elapsed_ms"))
                    .errorMsg(rs.getString("error_msg"))
                    .build();

    private static Long nullableLong(ResultSet rs, String col) {
        try {
            long v = rs.getLong(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer nullableInt(ResultSet rs, String col) {
        try {
            int v = rs.getInt(col);
            return rs.wasNull() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime toLdt(ResultSet rs, String col) {
        try {
            java.sql.Timestamp ts = rs.getTimestamp(col);
            return ts == null ? null : ts.toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 去掉结尾的分号。
     * JDBC 各驱动对结尾分号的容忍度不一致（Oracle 直接报 ORA-00911），
     * 而模板里的 SQL 往往是人工从客户端粘过来的、习惯带分号。
     */
    private static String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s.isEmpty() ? null : s;
    }

    private static String describeExpected(InspectionBaseline b) {
        if ("BETWEEN".equalsIgnoreCase(b.getOperator())) {
            return "[" + b.getExpectedValueMin() + ", " + b.getExpectedValueMax() + "]";
        }
        return b.getExpectedValue() == null ? "" : b.getExpectedValue();
    }

    /** 去重后拼接采集值，保持出现顺序 */
    private static String joinDistinct(List<String> values) {
        LinkedHashSet<String> uniq = new LinkedHashSet<>(values);
        String joined = String.join(", ", uniq);
        return joined.length() > 500 ? joined.substring(0, 500) + "…" : joined;
    }

    private static String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化失败，降级为 null: {}", o.getClass().getSimpleName(), e);
            return null;
        }
    }

    private static <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            log.warn("反序列化失败: {}", json, e);
            return null;
        }
    }

    /** 风险归集：保证返回非 null（前端直接渲染，省得判空） */
    private static Map<String, Integer> readIntMap(String json) {
        Map<String, Integer> m = readJson(json, new TypeReference<Map<String, Integer>>() {});
        return m == null ? new LinkedHashMap<>() : m;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (Exception e) {
            log.debug("关闭巡检连接失败", e);
        }
    }
}
