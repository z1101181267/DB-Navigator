package com.dbnav.inspection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时导入巡检配置种子。
 *
 * 对齐 RaccoonX 的 init_db 编排：
 *   1. 建表（由 schema.sql 完成）
 *   2. 表为空时导入默认模板（21 章框架）与默认基线
 *
 * 幂等：仅当对应表为空时才导入。
 */
@Slf4j
@Component
@Order(20)   // 在驱动初始化（默认顺序）之后执行
@RequiredArgsConstructor
public class InspectionStartupInitializer {

    private final InspectionConfigService configService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("=== 巡检配置初始化 ===");
        try {
            Map<String, Object> stat = configService.seedIfEmpty();
            log.info("巡检配置就绪：模板 {} 个 / 章节 {} 章 / 规则 {} 条 / 基线 {} 条",
                    stat.get("templatesLoaded"), stat.get("chaptersLoaded"),
                    stat.get("queriesLoaded"), stat.get("baselinesLoaded"));
        } catch (Exception e) {
            log.error("巡检配置初始化失败", e);
        }
    }
}
