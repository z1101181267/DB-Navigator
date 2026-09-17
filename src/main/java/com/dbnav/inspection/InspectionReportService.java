package com.dbnav.inspection;

import com.dbnav.inspection.model.InspectionRun;
import com.dbnav.inspection.model.InspectionRunBaseline;
import com.dbnav.inspection.model.InspectionRunQuery;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检报告导出：把一次执行记录渲染成 HTML / Word(.docx) / PDF。
 *
 * <p>三种格式共用同一份数据（{@link InspectionRun} 及其明细），但渲染路径不同：
 * <ul>
 *   <li><b>HTML</b> —— 手写独立模板（内联 CSS，可直接打开、可打印）。这是主渲染器。</li>
 *   <li><b>PDF</b> —— 用 openhtmltopdf 渲染<b>同一份 HTML</b>，保证 HTML 与 PDF 内容永远一致。</li>
 *   <li><b>Word</b> —— 用 POI 生成<b>真正的 .docx</b>（不是把 HTML 改后缀成 .doc），可继续编辑。</li>
 * </ul>
 *
 * <p>PDF 的中文依赖嵌入字体：openhtmltopdf/PDFBox 只接受 <b>TTF</b>（TTC 字体集合会失败），
 * 因此按候选列表探测，并允许用 {@code dbnav.report.pdf-font} 显式指定。找不到时抛出
 * 可操作的错误，而不是生成一份中文变方框的 PDF。
 */
@Slf4j
@Service
public class InspectionReportService {

    /** 报告中每条规则的实测结果最多保留多少行（报告可读性与体积的折中）。 */
    private static final int REPORT_ROWS = 20;

    /** 报告中单元格文本的最大长度，超出截断。 */
    private static final int CELL_MAX = 120;

    /** CJK 字体候选：优先纯 TTF（TTC 字体集合 PDFBox 无法直接加载）。 */
    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/SimsunExtG.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttf",
            "/System/Library/Fonts/Supplemental/Songti.ttc",
    };

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Word 报告正文字体。用系统自带的微软雅黑，避免在别人机器上打开时缺字体。 */
    private static final String WORD_FONT = "微软雅黑";

    private final org.springframework.core.env.Environment env;

    public InspectionReportService(org.springframework.core.env.Environment env) {
        this.env = env;
    }

    /* ==================================================================
       格式分发
       ================================================================== */

    public String toHtml(InspectionRun run) {
        return renderHtml(run);
    }

    public byte[] toPdf(InspectionRun run) throws IOException {
        File font = resolveCjkFont();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(renderHtml(run), null);
        builder.useFont(font, "dbnav-cjk");
        builder.toStream(out);
        try {
            builder.run();
        } catch (Exception e) {
            throw new IOException("生成 PDF 失败：" + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    public byte[] toWord(InspectionRun run) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            renderWord(run, doc);
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** 报告文件名（不含扩展名）。 */
    public String baseFileName(InspectionRun run) {
        String ds = run.getDataSourceName() == null ? "datasource" : run.getDataSourceName();
        ds = ds.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String ts = run.getStartedAt() == null ? "" : run.getStartedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "巡检报告_" + ds + "_" + ts;
    }

    /* ==================================================================
       CJK 字体解析
       ================================================================== */

    /**
     * 解析可嵌入 PDF 的中文字体。只接受 TTF —— PDFBox 的 PDType0Font 不支持 TTC 集合。
     */
    private File resolveCjkFont() throws IOException {
        String configured = env.getProperty("dbnav.report.pdf-font", "");
        List<String> candidates = new ArrayList<>();
        if (!configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.addAll(List.of(FONT_CANDIDATES));

        for (String path : candidates) {
            File f = new File(path);
            // 明确排除 .ttc：PDFBox 无法直接加载字体集合，加了只会得到一个含糊的失败
            if (f.isFile() && !path.toLowerCase().endsWith(".ttc")) {
                log.debug("报告 PDF 使用中文字体: {}", f.getAbsolutePath());
                return f;
            }
        }
        throw new IOException("""
                生成 PDF 失败：找不到可嵌入的中文字体（TTF）。
                PDFBox 不支持 .ttc 字体集合，请安装一个 TTF 中文字体（如 simhei.ttf / Noto Sans CJK），
                或通过配置项 dbnav.report.pdf-font 显式指定字体文件的绝对路径。
                已尝试：""" + String.join(", ", candidates));
    }

    /* ==================================================================
       HTML 渲染（同时作为 PDF 的输入）
       ================================================================== */

    /**
     * 渲染 HTML。这份 HTML 同时是 PDF 的输入，所以必须写成 <b>严格 XHTML</b>：
     * openhtmltopdf 用的是 TRaX 的 XML 解析器，不是宽容的 HTML 解析器——
     * 一个没自闭合的 {@code <meta>} 就会直接抛 SAXParseException。
     * 因此空元素一律写自闭合形式（{@code <meta/>}），且 CSS 里不能出现 {@code <} 或 {@code &}。
     */
    private String renderHtml(InspectionRun run) {
        StringBuilder b = new StringBuilder(16384);
        b.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\"/>\n")
                .append("<title>").append(esc(baseFileName(run))).append("</title>\n")
                .append("<style>\n").append(css()).append("\n</style>\n</head>\n<body>\n");

        // ---- 标题 ----
        b.append("<header class=\"hd\">\n")
                .append("<h1>数据库巡检报告</h1>\n")
                .append("<div class=\"sub\">").append(esc(run.getDataSourceName()))
                .append(" · ").append(esc(String.valueOf(run.getDbType()).toUpperCase()))
                .append("</div>\n")
                .append("</header>\n");

        // ---- 基本信息 ----
        b.append("<table class=\"kv\">\n");
        kv(b, "执行状态", statusLabel(run.getStatus()), statusClass(run.getStatus()));
        kv(b, "数据源", run.getDataSourceName() + "（" + run.getDbType() + "）");
        kv(b, "巡检模板", run.getTemplateName());
        kv(b, "执行人", run.getExecutedBy());
        kv(b, "开始时间", run.getStartedAt() == null ? "—" : run.getStartedAt().format(TS));
        kv(b, "结束时间", run.getFinishedAt() == null ? "—" : run.getFinishedAt().format(TS));
        kv(b, "执行耗时", run.getDurationMs() == null ? "—" : run.getDurationMs() + " ms");
        kv(b, "触发方式", "SCHEDULED".equals(run.getTriggerSource()) ? "定时" : "手动");
        b.append("</table>\n");

        // ---- 概览指标 ----
        b.append("<div class=\"metrics\">\n");
        metric(b, "合规率", run.getCompliancePct() == null ? "—" : trimNum(run.getCompliancePct()) + "%",
                run.getCompliancePct() == null ? "" : (run.getCompliancePct() >= 90 ? "good" : run.getCompliancePct() >= 70 ? "mid" : "bad"));
        metric(b, "规则执行", nz(run.getOkQueries()) + "/" + nz(run.getTotalQueries()), "");
        metric(b, "基线合规", nz(run.getBaselinesPass()) + "/" + nz(run.getTotalBaselines()), "");
        metric(b, "未采集", String.valueOf(nz(run.getBaselinesUnchecked())), "");
        b.append("</div>\n");

        // ---- 风险归集 ----
        Map<String, Integer> risk = run.getRiskSummary();
        if (risk != null && !risk.isEmpty()) {
            b.append("<h2>风险归集</h2>\n<div class=\"chips\">\n");
            for (Map.Entry<String, Integer> e : risk.entrySet()) {
                b.append("<span class=\"chip risk-").append(esc(e.getKey())).append("\">")
                        .append(esc(e.getKey())).append(" × ").append(e.getValue()).append("</span>\n");
            }
            b.append("</div>\n");
        }

        // ---- 执行级错误 ----
        if (run.getErrorMsg() != null && !run.getErrorMsg().isBlank()) {
            b.append("<div class=\"banner bad\"><b>执行失败</b><pre>").append(esc(run.getErrorMsg())).append("</pre></div>\n");
        }

        // ---- 规则执行结果 ----
        List<InspectionRunQuery> queries = run.getQueries() == null ? List.of() : run.getQueries();
        b.append("<h2>规则执行结果 <span class=\"dim\">共 ").append(queries.size()).append(" 条</span></h2>\n");
        if (queries.isEmpty()) {
            b.append("<p class=\"dim\">本次执行没有规则明细。</p>\n");
        } else {
            for (Map.Entry<Integer, List<InspectionRunQuery>> ch : groupByChapter(queries).entrySet()) {
                List<InspectionRunQuery> list = ch.getValue();
                String title = list.get(0).getChapterTitle();
                long ok = list.stream().filter(q -> "OK".equals(q.getStatus())).count();
                b.append("<section class=\"chapter\">\n<h3>")
                        .append(ch.getKey()).append(". ").append(esc(title))
                        .append(" <span class=\"dim\">").append(ok).append("/").append(list.size()).append(" 成功</span>")
                        .append("</h3>\n");
                for (InspectionRunQuery q : list) {
                    renderQueryHtml(b, q);
                }
                b.append("</section>\n");
            }
        }

        // ---- 基线判定 ----
        List<InspectionRunBaseline> baselines = run.getBaselines() == null ? List.of() : run.getBaselines();
        b.append("<h2>基线判定 <span class=\"dim\">共 ").append(baselines.size()).append(" 条</span></h2>\n");
        if (baselines.isEmpty()) {
            b.append("<p class=\"dim\">本次执行未采集基线。</p>\n");
        } else {
            b.append("<table class=\"grid\">\n<thead><tr>")
                    .append("<th>参数</th><th>比较符</th><th>期望值</th><th>实测值</th>")
                    .append("<th>结论</th><th>风险</th><th>说明</th></tr></thead>\n<tbody>\n");
            for (InspectionRunBaseline bl : baselines) {
                boolean checked = bl.getIsChecked() != null && bl.getIsChecked() == 1;
                boolean pass = bl.getIsPass() != null && bl.getIsPass() == 1;
                String verdict = !checked ? "未采集" : (pass ? "合规" : "不合规");
                String vcls = !checked ? "v-none" : (pass ? "v-pass" : "v-fail");
                b.append("<tr").append(checked && !pass ? " class=\"row-fail\"" : "").append(">")
                        .append("<td class=\"mono\">").append(esc(bl.getParamName())).append("</td>")
                        .append("<td class=\"c\">").append(esc(bl.getOperator())).append("</td>")
                        .append("<td class=\"mono\">").append(esc(nvl(bl.getExpectedValue()))).append("</td>")
                        .append("<td class=\"mono\">").append(esc(nvl(bl.getActualValue()))).append("</td>")
                        .append("<td class=\"c ").append(vcls).append("\">").append(verdict).append("</td>")
                        .append("<td class=\"c\">").append(esc(nvl(bl.getRiskLevel()))).append("</td>")
                        .append("<td>").append(esc(nvl(bl.getMessage()))).append("</td>")
                        .append("</tr>\n");
            }
            b.append("</tbody>\n</table>\n");
        }

        b.append("<footer class=\"ft\">本报告由 DB Navigator 自动生成 · 执行记录 #")
                .append(run.getId()).append("</footer>\n");
        b.append("</body>\n</html>\n");
        return b.toString();
    }

    private void renderQueryHtml(StringBuilder b, InspectionRunQuery q) {
        String st = q.getStatus() == null ? "" : q.getStatus();
        b.append("<div class=\"rule\">\n")
                .append("<div class=\"rule-hd\">")
                .append("<span class=\"rk\">").append(esc(q.getQueryKey())).append("</span>")
                .append("<span class=\"badge ").append(statusClass(st)).append("\">").append(esc(statusLabel(st))).append("</span>")
                .append("<span class=\"dim\">")
                .append(q.getRowCount() == null ? "" : q.getRowCount() + " 行 · ")
                .append(q.getElapsedMs() == null ? "" : q.getElapsedMs() + " ms")
                .append(q.getTruncated() != null && q.getTruncated() == 1 ? " · 已截断" : "")
                .append("</span></div>\n");
        if (q.getDescriptionZh() != null && !q.getDescriptionZh().isBlank()) {
            b.append("<div class=\"rule-desc\">").append(esc(q.getDescriptionZh())).append("</div>\n");
        }
        if (q.getQuerySql() != null && !q.getQuerySql().isBlank()) {
            b.append("<pre class=\"sql\">").append(esc(q.getQuerySql())).append("</pre>\n");
        }
        if (q.getErrorMsg() != null && !q.getErrorMsg().isBlank()) {
            b.append("<div class=\"banner bad\"><pre>").append(esc(q.getErrorMsg())).append("</pre></div>\n");
        }
        List<String> cols = q.getColumns();
        List<List<Object>> rows = q.getRows();
        if (cols != null && !cols.isEmpty() && rows != null && !rows.isEmpty()) {
            int shown = Math.min(rows.size(), REPORT_ROWS);
            b.append("<table class=\"grid small\">\n<thead><tr>");
            for (String c : cols) {
                b.append("<th>").append(esc(c)).append("</th>");
            }
            b.append("</tr></thead>\n<tbody>\n");
            for (int i = 0; i < shown; i++) {
                b.append("<tr>");
                List<Object> row = rows.get(i);
                for (int c = 0; c < cols.size(); c++) {
                    Object v = c < row.size() ? row.get(c) : null;
                    b.append("<td").append(v == null ? " class=\"null\"" : "").append(">")
                            .append(esc(truncate(v == null ? "NULL" : String.valueOf(v), CELL_MAX))).append("</td>");
                }
                b.append("</tr>\n");
            }
            b.append("</tbody>\n</table>\n");
            if (rows.size() > shown) {
                b.append("<div class=\"dim\">… 另有 ").append(rows.size() - shown).append(" 行未在报告中展开</div>\n");
            }
        }
        b.append("</div>\n");
    }

    private void kv(StringBuilder b, String k, String v) {
        b.append("<tr><th>").append(esc(k)).append("</th><td>").append(esc(nvl(v))).append("</td></tr>\n");
    }

    private void kv(StringBuilder b, String k, String v, String cls) {
        b.append("<tr><th>").append(esc(k)).append("</th><td><span class=\"badge ").append(cls).append("\">")
                .append(esc(nvl(v))).append("</span></td></tr>\n");
    }

    private void metric(StringBuilder b, String label, String value, String cls) {
        b.append("<div class=\"metric\"><div class=\"mv ").append(cls).append("\">").append(esc(value))
                .append("</div><div class=\"ml\">").append(esc(label)).append("</div></div>\n");
    }

    private String css() {
        return """
                :root { --line:#d8dee9; --muted:#6b7785; --text:#1f2733; --ok:#15803d; --bad:#c0392b; --warn:#b45309; }
                * { box-sizing:border-box; }
                body { font-family:"dbnav-cjk","Microsoft YaHei","PingFang SC","Hiragino Sans GB","Noto Sans CJK SC",sans-serif;
                       color:var(--text); margin:0; padding:0 0 24px; font-size:12.5px; line-height:1.6; }
                h1 { font-size:22px; margin:0 0 4px; }
                h2 { font-size:15px; margin:22px 0 10px; padding-bottom:6px; border-bottom:2px solid var(--line); }
                h3 { font-size:13.5px; margin:16px 0 8px; }
                .hd { border-bottom:3px solid #2563eb; padding:0 0 12px; margin-bottom:16px; }
                .sub { color:var(--muted); font-size:13px; }
                .dim { color:var(--muted); font-weight:400; font-size:11.5px; }
                table { width:100%; border-collapse:collapse; }
                table.kv { margin-bottom:14px; }
                table.kv th { width:110px; text-align:left; font-weight:600; color:var(--muted);
                              background:#f7f9fc; border:1px solid var(--line); padding:5px 9px; font-size:12px; }
                table.kv td { border:1px solid var(--line); padding:5px 9px; }
                /* 指标卡：这里刻意不用 flex。openhtmltopdf 对 flex 的支持是不完整的——
                   实测 .metric 上的 flex:1 1 120px 不被识别，四个卡片会退化成块级元素竖向堆叠
                   （HTML 里正常、PDF 里难看，且同一份 CSS 两边表现不一致）。
                   display:table/table-cell 在浏览器和 openhtmltopdf 里都稳定，用它换取一致性。 */
                .metrics { display:table; width:100%; table-layout:fixed; border-spacing:9px 0;
                           margin:12px 0 4px; }
                .metric { display:table-cell; border:1px solid var(--line); border-radius:8px;
                          padding:10px 12px; background:#fbfcfe; }
                .mv { font-size:20px; font-weight:700; }
                .mv.good { color:var(--ok); } .mv.mid { color:var(--warn); } .mv.bad { color:var(--bad); }
                .ml { color:var(--muted); font-size:11.5px; margin-top:2px; }
                .chips { margin:2px 0; }
                .chip { display:inline-block; margin:0 7px 6px 0; border-radius:20px; padding:2px 11px;
                        font-size:11.5px; font-weight:600; background:#eef2f7; color:#334155; }
                .chip.risk-CRITICAL { background:#fde8e6; color:#b3261e; }
                .chip.risk-HIGH { background:#fdeceb; color:#c0392b; }
                .chip.risk-MEDIUM { background:#fdf3e3; color:#b45309; }
                .chip.risk-LOW { background:#eef3ff; color:#2563eb; }
                .badge { display:inline-block; border-radius:20px; padding:1px 9px; font-size:11px; font-weight:600;
                         background:#eef2f7; color:#334155; }
                .badge.st-SUCCESS, .badge.st-OK { background:#e9f7ee; color:var(--ok); }
                .badge.st-PARTIAL { background:#fdf3e3; color:var(--warn); }
                .badge.st-FAILED, .badge.st-FAILED- { background:#fdeceb; color:var(--bad); }
                .badge.st-SKIPPED { background:#eef2f7; color:var(--muted); }
                .banner { border-radius:8px; padding:9px 12px; margin:8px 0; border:1px solid; }
                .banner.bad { background:#fdeceb; border-color:#f5cdc9; color:#8a1f14; }
                .banner pre { margin:6px 0 0; white-space:pre-wrap; word-break:break-all; font-size:11.5px; }
                .chapter { page-break-inside:auto; }
                .rule { border:1px solid var(--line); border-radius:8px; padding:9px 11px; margin:8px 0;
                        page-break-inside:avoid; }
                /* 同理不用 flex：规则标题行是「序号 + 名称 + 状态徽标 + 元信息」的流式文本，
                   用自然行内流更稳——flex-wrap 在 openhtmltopdf 里不生效时，长规则名会把
                   徽标挤出页面右边缘。 */
                .rule-hd { margin-bottom:2px; }
                .rule-hd .badge { margin-left:6px; vertical-align:middle; }
                .rule-hd .dim { margin-left:6px; }
                .rk { font-weight:700; font-size:12.5px; }
                .rule-desc { color:#475569; font-size:12px; margin-top:3px; }
                pre.sql { background:#f6f8fb; border:1px solid var(--line); border-radius:6px; padding:7px 9px;
                          margin:6px 0 0; font-size:11px; white-space:pre-wrap; word-break:break-all;
                          font-family:Consolas,"Courier New",monospace; color:#334155; }
                table.grid { font-size:11.5px; margin-top:7px; }
                table.grid th { background:#f7f9fc; border:1px solid var(--line); padding:5px 8px;
                                text-align:left; font-weight:600; color:var(--muted); }
                table.grid td { border:1px solid var(--line); padding:5px 8px; vertical-align:top; }
                table.grid td.c { text-align:center; white-space:nowrap; }
                table.grid td.mono, .mono { font-family:Consolas,"Courier New",monospace; word-break:break-all; }
                table.grid tr.row-fail td { background:#fdf6f5; }
                td.null { color:#9aa5b1; font-style:italic; }
                .v-pass { color:var(--ok); font-weight:600; }
                .v-fail { color:var(--bad); font-weight:600; }
                .v-none { color:var(--muted); }
                .ft { margin-top:26px; padding-top:10px; border-top:1px solid var(--line);
                      color:var(--muted); font-size:11px; text-align:center; }
                @page { size:A4; margin:14mm 12mm; }
                @media print { body { font-size:11.5px; } .rule { page-break-inside:avoid; } }
                """;
    }

    /* ==================================================================
       Word 渲染
       ================================================================== */

    private void renderWord(InspectionRun run, XWPFDocument doc) {
        wordParagraph(doc, "数据库巡检报告", 20, true, ParagraphAlignment.CENTER);
        wordParagraph(doc, nvl(run.getDataSourceName()) + " · " + String.valueOf(run.getDbType()).toUpperCase()
                + " · " + statusLabel(run.getStatus()), 11, false, ParagraphAlignment.CENTER);

        // 基本信息
        Map<String, String> info = new LinkedHashMap<>();
        info.put("数据源", run.getDataSourceName() + "（" + run.getDbType() + "）");
        info.put("巡检模板", nvl(run.getTemplateName()));
        info.put("执行人", nvl(run.getExecutedBy()));
        info.put("开始时间", run.getStartedAt() == null ? "—" : run.getStartedAt().format(TS));
        info.put("执行耗时", run.getDurationMs() == null ? "—" : run.getDurationMs() + " ms");
        info.put("合规率", run.getCompliancePct() == null ? "—" : trimNum(run.getCompliancePct()) + "%");
        info.put("规则执行", nz(run.getOkQueries()) + " / " + nz(run.getTotalQueries()));
        info.put("基线合规", nz(run.getBaselinesPass()) + " / " + nz(run.getTotalBaselines())
                + "（未采集 " + nz(run.getBaselinesUnchecked()) + "）");

        XWPFTable kvTable = doc.createTable(info.size(), 2);
        kvTable.setWidth("100%");
        int r = 0;
        for (Map.Entry<String, String> e : info.entrySet()) {
            wordCell(kvTable.getRow(r).getCell(0), e.getKey(), true);
            wordCell(kvTable.getRow(r).getCell(1), e.getValue(), false);
            r++;
        }

        if (run.getErrorMsg() != null && !run.getErrorMsg().isBlank()) {
            wordParagraph(doc, "执行失败：" + run.getErrorMsg(), 11, false, ParagraphAlignment.LEFT);
        }

        Map<String, Integer> risk = run.getRiskSummary();
        if (risk != null && !risk.isEmpty()) {
            wordParagraph(doc, "风险归集", 14, true, ParagraphAlignment.LEFT);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : risk.entrySet()) {
                sb.append(e.getKey()).append(" × ").append(e.getValue()).append("    ");
            }
            wordParagraph(doc, sb.toString().trim(), 11, false, ParagraphAlignment.LEFT);
        }

        // 规则执行结果
        List<InspectionRunQuery> queries = run.getQueries() == null ? List.of() : run.getQueries();
        wordParagraph(doc, "规则执行结果（共 " + queries.size() + " 条）", 14, true, ParagraphAlignment.LEFT);
        if (queries.isEmpty()) {
            wordParagraph(doc, "本次执行没有规则明细。", 11, false, ParagraphAlignment.LEFT);
        }
        for (Map.Entry<Integer, List<InspectionRunQuery>> ch : groupByChapter(queries).entrySet()) {
            List<InspectionRunQuery> list = ch.getValue();
            wordParagraph(doc, ch.getKey() + ". " + nvl(list.get(0).getChapterTitle()), 12, true, ParagraphAlignment.LEFT);
            XWPFTable t = doc.createTable(list.size() + 1, 5);
            t.setWidth("100%");
            String[] head = {"规则", "状态", "行数", "耗时(ms)", "说明 / 错误"};
            for (int i = 0; i < head.length; i++) {
                wordCell(t.getRow(0).getCell(i), head[i], true);
            }
            int ri = 1;
            for (InspectionRunQuery q : list) {
                XWPFTableRow row = t.getRow(ri++);
                wordCell(row.getCell(0), nvl(q.getQueryKey()), false);
                wordCell(row.getCell(1), statusLabel(q.getStatus()), false);
                wordCell(row.getCell(2), q.getRowCount() == null ? "—" : String.valueOf(q.getRowCount()), false);
                wordCell(row.getCell(3), q.getElapsedMs() == null ? "—" : String.valueOf(q.getElapsedMs()), false);
                String note = q.getErrorMsg() != null && !q.getErrorMsg().isBlank()
                        ? q.getErrorMsg()
                        : nvl(q.getDescriptionZh());
                wordCell(row.getCell(4), truncate(note, 300), false);
            }
        }

        // 基线判定
        List<InspectionRunBaseline> baselines = run.getBaselines() == null ? List.of() : run.getBaselines();
        wordParagraph(doc, "基线判定（共 " + baselines.size() + " 条）", 14, true, ParagraphAlignment.LEFT);
        if (baselines.isEmpty()) {
            wordParagraph(doc, "本次执行未采集基线。", 11, false, ParagraphAlignment.LEFT);
        } else {
            XWPFTable t = doc.createTable(baselines.size() + 1, 6);
            t.setWidth("100%");
            String[] head = {"参数", "比较符", "期望值", "实测值", "结论", "风险"};
            for (int i = 0; i < head.length; i++) {
                wordCell(t.getRow(0).getCell(i), head[i], true);
            }
            int ri = 1;
            for (InspectionRunBaseline bl : baselines) {
                XWPFTableRow row = t.getRow(ri++);
                boolean checked = bl.getIsChecked() != null && bl.getIsChecked() == 1;
                boolean pass = bl.getIsPass() != null && bl.getIsPass() == 1;
                wordCell(row.getCell(0), nvl(bl.getParamName()), false);
                wordCell(row.getCell(1), nvl(bl.getOperator()), false);
                wordCell(row.getCell(2), nvl(bl.getExpectedValue()), false);
                wordCell(row.getCell(3), nvl(bl.getActualValue()), false);
                wordCell(row.getCell(4), !checked ? "未采集" : (pass ? "合规" : "不合规"), false);
                wordCell(row.getCell(5), nvl(bl.getRiskLevel()), false);
            }
        }

        wordParagraph(doc, "本报告由 DB Navigator 自动生成 · 执行记录 #" + run.getId(), 9, false, ParagraphAlignment.CENTER);
    }

    /**
     * 给 run 指定正文字体。
     *
     * <p>POI 5.2.5 的 {@code setFontFamily} 只有 (String) 与 (String, FontCharRange) 两种重载
     * ——没有可变参数版本，所以三个字符范围要逐个设。这不是啰嗦，而是必须：
     * 只调 {@code setFontFamily(name)} 只会写 w:rFonts 的 ascii/hAnsi，中文字符走的是
     * {@code eastAsia}，漏掉它 Word 就会回退到宋体，并且看起来「字号也不对」。
     */
    private static void applyCjkFont(XWPFRun run) {
        for (XWPFRun.FontCharRange range : new XWPFRun.FontCharRange[]{
                XWPFRun.FontCharRange.ascii,
                XWPFRun.FontCharRange.hAnsi,
                XWPFRun.FontCharRange.eastAsia}) {
            run.setFontFamily(WORD_FONT, range);
        }
    }

    /** 写一个段落。 */
    private void wordParagraph(XWPFDocument doc, String text, int sizePt, boolean bold, ParagraphAlignment align) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(align);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontSize(sizePt);
        applyCjkFont(run);
    }

    private void wordCell(XWPFTableCell cell, String text, boolean bold) {
        // createTable 会自带一个空段落，复用它避免每格多出一行空白
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        XWPFRun run = p.createRun();
        run.setText(text == null ? "" : text);
        run.setBold(bold);
        run.setFontSize(9);
        applyCjkFont(run);
    }

    /* ==================================================================
       公共小工具
       ================================================================== */

    private Map<Integer, List<InspectionRunQuery>> groupByChapter(List<InspectionRunQuery> queries) {
        Map<Integer, List<InspectionRunQuery>> map = new LinkedHashMap<>();
        for (InspectionRunQuery q : queries) {
            int n = q.getChapterNumber() == null ? 0 : q.getChapterNumber();
            map.computeIfAbsent(n, k -> new ArrayList<>()).add(q);
        }
        return map;
    }

    static String statusLabel(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "SUCCESS" -> "成功";
            case "PARTIAL" -> "部分成功";
            case "FAILED" -> "失败";
            case "OK" -> "成功";
            case "SKIPPED" -> "已停用";
            default -> status;
        };
    }

    static String statusClass(String status) {
        if (status == null) return "st-NONE";
        return "st-" + status;
    }

    private static String nvl(String s) {
        return s == null ? "—" : s;
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static String trimNum(Double d) {
        if (d == null) return "—";
        return d == Math.floor(d) ? String.valueOf(d.intValue()) : String.valueOf(d);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * HTML/XML 转义。
     *
     * <p>除了五个实体字符，还要滤掉非法控制字符：XML 1.0 只允许 {@code \t \n \r} 三个
     * 控制字符，其余 {@code U+0000..U+001F} 一律非法。JDBC 的 {@code errorMsg} 里带这种
     * 字符是常事（驱动把二进制响应直接拼进异常消息），而 PDF 走的是严格 XML 解析器，
     * 碰上一个就整份报告渲染失败——所以在这里一次挡掉，而不是等 PDF 报错再回头查。
     */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                case '\t', '\n', '\r' -> b.append(c);
                default -> {
                    if (c < 0x20 || c == 0xFFFE || c == 0xFFFF) {
                        b.append(' ');      // 非法控制字符：替换为空格，保留可读性
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
