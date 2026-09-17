package com.dbnav.controller;

import com.dbnav.common.Result;
import com.dbnav.inspection.BaselineChecker;
import com.dbnav.inspection.InspectionConfigService;
import com.dbnav.inspection.InspectionReportService;
import com.dbnav.inspection.InspectionRunner;
import com.dbnav.inspection.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 巡检 REST API。
 *
 * 分五组：
 *   模板   /api/inspection/templates
 *   章节   /api/inspection/chapters
 *   规则   /api/inspection/queries
 *   基线   /api/inspection/baselines
 *   执行   /api/inspection/run(s)
 *
 * 另有 /api/inspection/summary 总览与 /api/inspection/history 修改留痕。
 *
 * <h3>错误码约定</h3>
 * 路径变量里的 id 找不到 → 404；请求体里引用的 id 找不到 → 400。
 * 前者是「这个资源不存在」，后者是「你给的参数有问题」。
 */
@Slf4j
@RestController
@RequestMapping("/api/inspection")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionConfigService service;
    private final BaselineChecker baselineChecker;
    private final InspectionRunner runner;
    private final InspectionReportService reportService;

    // ==================== 总览 ====================

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        List<InspectionTemplate> all = service.listTemplates(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateCount", all.size());
        out.put("baselineCountByType", service.countBaselinesByType());

        // 按 db_type 汇总模板 / 章节 / 规则
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        int totalChapters = 0, totalQueries = 0;
        for (InspectionTemplate t : all) {
            Map<String, Object> agg = byType.computeIfAbsent(t.getDbType(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dbType", k);
                m.put("templates", 0);
                m.put("chapters", 0);
                m.put("queries", 0);
                return m;
            });
            agg.put("templates", (int) agg.get("templates") + 1);

            List<InspectionChapter> chapters = service.listChapters(t.getId());
            agg.put("chapters", (int) agg.get("chapters") + chapters.size());
            totalChapters += chapters.size();

            int q = 0;
            for (InspectionChapter c : chapters) {
                q += service.listQueries(c.getId()).size();
            }
            agg.put("queries", (int) agg.get("queries") + q);
            totalQueries += q;
        }
        out.put("byDbType", new ArrayList<>(byType.values()));
        out.put("totalChapters", totalChapters);
        out.put("totalQueries", totalQueries);

        // 风险等级分布
        Map<String, Integer> risk = new LinkedHashMap<>();
        for (InspectionBaseline b : service.listBaselines(null)) {
            risk.merge(b.getRiskLevel() == null ? "UNKNOWN" : b.getRiskLevel(), 1, Integer::sum);
        }
        out.put("baselineRiskDistribution", risk);

        return Result.ok(out);
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Result.ok(service.listHistory(limit));
    }

    // ==================== 模板 ====================

    @GetMapping("/templates")
    public Result<List<InspectionTemplate>> listTemplates(
            @RequestParam(value = "dbType", required = false) String dbType) {
        return Result.ok(service.listTemplates(dbType));
    }

    @GetMapping("/templates/{id}")
    public Result<InspectionTemplate> getTemplate(@PathVariable Long id) {
        return service.findTemplate(id).map(Result::ok)
                .orElseGet(() -> Result.fail(404, "模板不存在: " + id));
    }

    @GetMapping("/templates/{id}/tree")
    public Result<InspectionTemplate> getTemplateTree(
            @PathVariable Long id,
            @RequestParam(value = "onlyEnabled", defaultValue = "false") boolean onlyEnabled) {
        return service.getTemplateTree(id, onlyEnabled).map(Result::ok)
                .orElseGet(() -> Result.fail(404, "模板不存在: " + id));
    }

    @GetMapping("/templates/default/{dbType}/tree")
    public Result<InspectionTemplate> getDefaultTemplateTree(
            @PathVariable String dbType,
            @RequestParam(value = "onlyEnabled", defaultValue = "true") boolean onlyEnabled) {
        return service.getDefaultTemplateTree(dbType, onlyEnabled).map(Result::ok)
                .orElseGet(() -> Result.fail(404, "该类型暂无默认模板: " + dbType));
    }

    @PostMapping("/templates")
    public Result<InspectionTemplate> createTemplate(@RequestBody InspectionTemplate t) {
        try {
            return Result.ok(service.createTemplate(t));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @PutMapping("/templates/{id}")
    public Result<InspectionTemplate> updateTemplate(@PathVariable Long id,
                                                     @RequestBody InspectionTemplate t) {
        try {
            return Result.ok(service.updateTemplate(id, t));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @PostMapping("/templates/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        try {
            service.setDefaultTemplate(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/templates/{id}")
    public Result<Void> deleteTemplate(
            @PathVariable Long id,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        try {
            service.deleteTemplate(id, force);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    // ==================== 章节 ====================

    @GetMapping("/templates/{templateId}/chapters")
    public Result<List<InspectionChapter>> listChapters(@PathVariable Long templateId) {
        return Result.ok(service.listChapters(templateId));
    }

    @PostMapping("/chapters")
    public Result<InspectionChapter> createChapter(@RequestBody InspectionChapter c) {
        try {
            return Result.ok(service.createChapter(c));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @PutMapping("/chapters/{id}")
    public Result<InspectionChapter> updateChapter(@PathVariable Long id,
                                                   @RequestBody InspectionChapter c) {
        try {
            return Result.ok(service.updateChapter(id, c));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/chapters/{id}")
    public Result<Void> deleteChapter(@PathVariable Long id) {
        try {
            service.deleteChapter(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    // ==================== 规则 ====================

    @GetMapping("/chapters/{chapterId}/queries")
    public Result<List<InspectionQuery>> listQueries(@PathVariable Long chapterId) {
        return Result.ok(service.listQueries(chapterId));
    }

    @PostMapping("/queries")
    public Result<InspectionQuery> createQuery(@RequestBody InspectionQuery q) {
        try {
            return Result.ok(service.createQuery(q));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @PutMapping("/queries/{id}")
    public Result<InspectionQuery> updateQuery(@PathVariable Long id,
                                               @RequestBody InspectionQuery q) {
        try {
            return Result.ok(service.updateQuery(id, q));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/queries/{id}")
    public Result<Void> deleteQuery(@PathVariable Long id) {
        try {
            service.deleteQuery(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    // ==================== 配置基线 ====================

    @GetMapping("/baselines")
    public Result<List<InspectionBaseline>> listBaselines(
            @RequestParam(value = "dbType", required = false) String dbType) {
        return Result.ok(service.listBaselines(dbType));
    }

    @GetMapping("/baselines/{id}")
    public Result<InspectionBaseline> getBaseline(@PathVariable Long id) {
        return service.findBaseline(id).map(Result::ok)
                .orElseGet(() -> Result.fail(404, "基线不存在: " + id));
    }

    @PostMapping("/baselines")
    public Result<InspectionBaseline> createBaseline(@RequestBody InspectionBaseline b) {
        try {
            return Result.ok(service.createBaseline(b));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @PutMapping("/baselines/{id}")
    public Result<InspectionBaseline> updateBaseline(@PathVariable Long id,
                                                     @RequestBody InspectionBaseline b) {
        try {
            return Result.ok(service.updateBaseline(id, b));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/baselines/{id}")
    public Result<Void> deleteBaseline(@PathVariable Long id) {
        try {
            service.deleteBaseline(id);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    /** 批量启停某类型的全部基线 */
    @PostMapping("/baselines/enabled")
    public Result<Map<String, Object>> setBaselinesEnabled(
            @RequestParam String dbType,
            @RequestParam boolean enabled) {
        try {
            int n = service.setBaselinesEnabled(dbType, enabled);
            return Result.ok(Map.of("dbType", dbType, "enabled", enabled, "affected", n));
        } catch (Exception e) {
            return Result.fail(400, e.getMessage());
        }
    }

    // ==================== 基线判定（预览/试算） ====================

    /**
     * 用给定实际值试算一条基线是否合规。
     * 便于在界面里验证 operator 语义，无需真实连库。
     */
    @PostMapping("/baselines/{id}/check")
    public Result<BaselineCheckResult> checkBaseline(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        InspectionBaseline b = service.findBaseline(id).orElse(null);
        if (b == null) {
            return Result.fail(404, "基线不存在: " + id);
        }
        return Result.ok(baselineChecker.check(b, body.get("actualValue")));
    }

    /**
     * 批量试算：传入 {dbType, values:{paramName: actualValue}}，返回逐条判定与汇总。
     */
    @PostMapping("/baselines/check")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> checkBaselines(@RequestBody Map<String, Object> body) {
        String dbType = (String) body.get("dbType");

        // values 的值可能是字符串/数字/布尔，统一转成字符串再比较
        Map<String, String> values = new LinkedHashMap<>();
        Object rawValues = body.get("values");
        if (rawValues instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                values.put(String.valueOf(e.getKey()),
                        e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }

        List<InspectionBaseline> baselines = service.listBaselines(dbType);
        if (baselines.isEmpty()) {
            return Result.fail(404, "该类型暂无基线配置: " + dbType);
        }

        List<BaselineCheckResult> results = new ArrayList<>();
        int pass = 0, fail = 0, unchecked = 0;
        Map<String, Integer> failByRisk = new LinkedHashMap<>();

        for (InspectionBaseline b : baselines) {
            if (b.getEnabled() != null && b.getEnabled() == 0) {
                continue;
            }
            BaselineCheckResult r = baselineChecker.check(b, values.get(b.getParamName()));
            results.add(r);
            if (!r.isChecked()) {
                unchecked++;
            } else if (r.isPass()) {
                pass++;
            } else {
                fail++;
                failByRisk.merge(r.getRiskLevel() == null ? "UNKNOWN" : r.getRiskLevel(),
                        1, Integer::sum);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dbType", dbType);
        out.put("total", results.size());
        out.put("pass", pass);
        out.put("fail", fail);
        out.put("unchecked", unchecked);
        out.put("compliancePct", results.isEmpty() ? 0
                : Math.round(pass * 1000.0 / Math.max(pass + fail, 1)) / 10.0);
        out.put("failByRisk", failByRisk);
        out.put("results", results);
        return Result.ok(out);
    }

    // ==================== 执行 ====================

    /**
     * 执行一次巡检。
     *
     * 请求体：
     * <pre>
     * {
     *   "dataSourceId": 1,          // 必填
     *   "templateId": 2,            // 可选，缺省用该库类型的默认模板
     *   "onlyEnabled": true,        // 可选，默认 true
     *   "includeBaselines": true,   // 可选，默认 true
     *   "chapterNumbers": [1,2],    // 可选，只跑指定章节
     *   "executedBy": "ops"         // 可选
     * }
     * </pre>
     *
     * 注意：「连不上库」「模板里没有可执行规则」这类执行级失败 <b>不是</b> HTTP 错误——
     * 它会返回 200 并带一条 status=FAILED 的执行记录，因为「跑了但失败」本身就是要留痕的结果。
     * 只有请求本身不合法（dataSourceId 缺失、引用了不存在的数据源/模板）才返回 4xx。
     */
    @PostMapping("/run")
    public Result<InspectionRun> run(@RequestBody(required = false) InspectionRunRequest req) {
        try {
            return Result.ok(runner.run(req));
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("巡检执行异常", e);
            return Result.fail(500, e.getMessage());
        }
    }

    /**
     * 执行历史（不含明细）。
     *
     * @param dataSourceId 可选，只看某个数据源
     * @param dbType       可选，只看某个库类型
     * @param limit        默认 50，上限 500
     */
    @GetMapping("/runs")
    public Result<List<InspectionRun>> listRuns(
            @RequestParam(value = "dataSourceId", required = false) Long dataSourceId,
            @RequestParam(value = "dbType", required = false) String dbType,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.ok(runner.listRuns(dataSourceId, dbType, limit));
    }

    /** 某数据源最近一次执行（界面首屏用） */
    @GetMapping("/runs/latest")
    public Result<InspectionRun> latestRun(@RequestParam("dataSourceId") Long dataSourceId) {
        return runner.latestRun(dataSourceId)
                .map(Result::ok)
                .orElseGet(() -> Result.fail(404, "该数据源暂无巡检记录"));
    }

    /** 一次执行的完整报告：表头 + 规则结果 + 基线判定 */
    @GetMapping("/runs/{id}")
    public Result<InspectionRun> getRun(@PathVariable Long id) {
        return runner.findRun(id, true)
                .map(Result::ok)
                .orElseGet(() -> Result.fail(404, "巡检记录不存在: " + id));
    }

    @DeleteMapping("/runs/{id}")
    public Result<Void> deleteRun(@PathVariable Long id) {
        if (runner.findRun(id, false).isEmpty()) {
            return Result.fail(404, "巡检记录不存在: " + id);
        }
        runner.deleteRun(id);
        return Result.ok();
    }

    // ==================== 报告导出 ====================

    /**
     * 导出一次巡检结果为 HTML / Word / PDF。
     *
     * <p>这是本项目唯一<b>不</b>返回 {@link Result} 包装的端点——它要吐原始字节流。
     * 出错时仍返回 {@code Result} 形状的 JSON（HTTP 状态码同时置为对应值），
     * 这样前端一套错误处理就能覆盖下载失败（如 PDF 字体缺失），不用区分「二进制响应里其实是一段错误文本」。
     *
     * <p>{@code disposition}：
     * <ul>
     *   <li>{@code format=html} 默认 <b>inline</b>——「在线预览」直接把它塞进 iframe 即可，
     *       报告是自带内联样式的独立页面，不依赖主界面 CSS；</li>
     *   <li>加 {@code download=true} 变成 attachment，用于「下载 HTML」；</li>
     *   <li>word / pdf 恒为 attachment。</li>
     * </ul>
     *
     * @param format html | word | pdf（大小写不敏感）
     */
    @GetMapping("/runs/{id}/export")
    public ResponseEntity<Object> exportRun(
            @PathVariable Long id,
            @RequestParam(value = "format", defaultValue = "html") String format,
            @RequestParam(value = "download", defaultValue = "false") boolean download) {

        InspectionRun run = runner.findRun(id, true).orElse(null);
        if (run == null) {
            return ResponseEntity.status(404).body(Result.fail(404, "巡检记录不存在: " + id));
        }

        String fmt = format == null ? "html" : format.trim().toLowerCase(Locale.ROOT);
        byte[] body;
        String ext;
        MediaType type;
        boolean inline = false;

        try {
            switch (fmt) {
                case "html", "htm" -> {
                    body = reportService.toHtml(run).getBytes(StandardCharsets.UTF_8);
                    ext = ".html";
                    type = new MediaType("text", "html", StandardCharsets.UTF_8);
                    inline = !download;
                }
                case "word", "docx" -> {
                    body = reportService.toWord(run);
                    ext = ".docx";
                    type = MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                }
                case "pdf" -> {
                    body = reportService.toPdf(run);
                    ext = ".pdf";
                    type = MediaType.APPLICATION_PDF;
                }
                default -> {
                    return ResponseEntity.status(400).body(Result.fail(400,
                            "不支持的导出格式: " + format + "（可选 html / word / pdf）"));
                }
            }
        } catch (IOException e) {
            // 典型场景：找不到可嵌入 PDF 的中文字体。这里必须把原因原样吐出来，
            // 而不是回退成一份「中文全是方框」的 PDF——那种文件比报错更难排查。
            log.error("巡检报告导出失败 id={} format={}", id, fmt, e);
            return ResponseEntity.status(500).body(Result.fail(500, e.getMessage()));
        }

        String fileName = reportService.baseFileName(run) + ext;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                (inline ? "inline" : "attachment") + "; " + rfc5987(fileName));
        // 报告是执行结果的快照，一旦生成就不再变化；但也不要让浏览器把「刚跑完的那次」缓存住。
        headers.setCacheControl(CacheControl.noCache().getHeaderValue());
        headers.setContentLength(body.length);

        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    /**
     * 构造兼容中文文件名的 Content-Disposition 参数。
     *
     * <p>只写 {@code filename="巡检报告_x.docx"} 会被多数浏览器按 Latin-1 解出乱码，
     * 所以同时给两个：ASCII 兜底给老客户端，RFC 5987 的 {@code filename*} 给现代浏览器
     * （后者优先级更高，且明确声明 UTF-8 百分号编码）。
     */
    private static String rfc5987(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        return "filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
