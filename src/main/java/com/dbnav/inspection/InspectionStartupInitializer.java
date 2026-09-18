package com.dbnav.inspection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时导入巡检配置种子。
 *
 * 启动编排：
 *   1. 建表（由 schema.sql 完成）
 *   2. 各表为空时分别导入规则库、模板与章节、配置基线
 *   3. 老库升级：模板还在但绑定为空 → 只重建「章节引用了哪些规则」，
 *      然后删掉旧的 inspection_query 表
 *
 * 幂等：每一类数据各自判空，重复启动不会重复导入。
 */
@Slf4j
@Component
@Order(20)   // 在驱动初始化（默认顺序）之后执行
@RequiredArgsConstructor
public class InspectionStartupInitializer {

    private final InspectionConfigService configService;
    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("=== 巡检配置初始化 ===");
        try {
            Map<String, Object> stat = configService.seedIfEmpty();
            log.info("巡检配置就绪：模板 {} 个 / 章节 {} 章 / 规则库 {} 条 / 章节引用 {} 条 / 基线 {} 条"
                            + "（本次导入 模板 {} / 规则 {} / 引用 {} / 基线 {}）",
                    stat.get("templatesTotal"), stat.get("chaptersTotal"),
                    stat.get("rulesTotal"), stat.get("bindingsTotal"),
                    stat.get("baselinesTotal"),
                    stat.get("templatesImported"), stat.get("rulesImported"),
                    stat.get("bindingsImported"), stat.get("baselinesImported"));
            dropLegacyQueryTable();
        } catch (Exception e) {
            log.error("巡检配置初始化失败", e);
        }
    }

    /**
     * 删除历史遗留的 inspection_query 表。
     *
     * 规则曾经内嵌在章节下（inspection_query），后来拆成「规则库 + 章节绑定」。
     * 此时规则已由 rules.json 导入 inspection_rule，章节引用也已按 templates.json
     * 的 rule_keys 重建，旧表留着只会让人误以为还有一份规则正文。
     *
     * 用「查一下能不能查」来判断表是否存在 —— H2 没有 DROP TABLE IF EXISTS 之外的
     * 元数据查询便利，而这个探测足够准且不依赖数据库方言。
     */
    private void dropLegacyQueryTable() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM inspection_query", Integer.class);
        } catch (Exception notExists) {
            return;   // 新库没有这张表，正常路径
        }
        try {
            jdbc.execute("DROP TABLE inspection_query");
            log.info("已删除旧表 inspection_query：规则正文现在只存放在 inspection_rule");
        } catch (Exception e) {
            log.warn("旧表 inspection_query 删除失败，不影响使用：{}", e.getMessage());
        }
    }
}
