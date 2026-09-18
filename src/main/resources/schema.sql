-- ================================================================
-- DB Navigator - JDBC Driver Registry Schema
-- Created on startup; the surrounding orchestration lives in the Java initializers
-- ================================================================

-- Driver metadata registry
CREATE TABLE IF NOT EXISTS jdbc_driver_registry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_type         VARCHAR(64)  NOT NULL,
    version         VARCHAR(64)  NOT NULL,
    driver_class    VARCHAR(256),
    jar_filename    VARCHAR(256) NOT NULL,
    jar_path        VARCHAR(1024) NOT NULL,
    file_size       BIGINT       DEFAULT 0,
    is_active       INT          NOT NULL DEFAULT 0,
    uploaded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note            VARCHAR(512),
    CONSTRAINT uq_driver UNIQUE (db_type, version, jar_filename)
);

CREATE INDEX IF NOT EXISTS idx_dr_dbtype ON jdbc_driver_registry(db_type);
CREATE INDEX IF NOT EXISTS idx_dr_active ON jdbc_driver_registry(db_type, is_active);

-- User-hidden built-in database types
CREATE TABLE IF NOT EXISTS driver_type_hidden (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_type VARCHAR(64) NOT NULL UNIQUE,
    hidden_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- User-custom database types
CREATE TABLE IF NOT EXISTS driver_type_custom (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_type       VARCHAR(64) NOT NULL UNIQUE,
    label         VARCHAR(128),
    port          INT,
    default_user  VARCHAR(128),
    driver_class  VARCHAR(256),
    protocol      VARCHAR(32) DEFAULT 'other',
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Managed data source instances (database connections)
CREATE TABLE IF NOT EXISTS data_source (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    db_type         VARCHAR(64)  NOT NULL,
    host            VARCHAR(256) NOT NULL,
    port            INT          NOT NULL,
    username        VARCHAR(128) NOT NULL,
    password_enc    VARCHAR(512),          -- AES-encrypted password
    database_name   VARCHAR(128),
    sid             VARCHAR(128),           -- Oracle SID (optional)
    service_name    VARCHAR(128),           -- Oracle service name (optional)
    extra_params    VARCHAR(1024),          -- additional JDBC URL params
    driver_version  VARCHAR(64),            -- explicitly selected driver version
    pool_size       INT          DEFAULT 10,
    status          VARCHAR(16)  DEFAULT 'OFFLINE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_connected  TIMESTAMP,
    note            VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_ds_dbtype ON data_source(db_type);
CREATE INDEX IF NOT EXISTS idx_ds_status ON data_source(status);

-- SQL query execution history
CREATE TABLE IF NOT EXISTS query_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    datasource_id   BIGINT NOT NULL,
    sql_text        CLOB NOT NULL,
    status          VARCHAR(16) NOT NULL,
    row_count       INT DEFAULT 0,
    duration_ms     BIGINT DEFAULT 0,
    error_msg       CLOB,
    executed_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_qh_ds FOREIGN KEY (datasource_id) REFERENCES data_source(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_qh_ds ON query_history(datasource_id);
CREATE INDEX IF NOT EXISTS idx_qh_time ON query_history(executed_at);


-- ================================================================
-- 巡检配置
--
-- 分三个模块：
--   巡检配置管理  模板 → 章节（报告骨架），两级 ON DELETE CASCADE
--   规则引擎      规则库（规则的唯一存放处）+ 章节绑定（多对多，可跨模板复用）
--   基线配置管理  参数推荐值与阈值，按库类型独立维护
-- 另有修改留痕表，记录以上三者的每次增删改。
-- ================================================================

-- 1) 巡检模板：每种数据库类型可有多个模板，其中至多一个 is_default=1
CREATE TABLE IF NOT EXISTS inspection_template (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_type           VARCHAR(64)  NOT NULL,
    template_name_zh  VARCHAR(200) NOT NULL,
    template_name_en  VARCHAR(200),
    version           VARCHAR(50)  DEFAULT 'v1',
    description       CLOB,
    is_default        INT          DEFAULT 0,   -- 0=否 1=是（同 db_type 内互斥）
    is_preset         INT          DEFAULT 0,   -- 1=预置模板，禁改名/禁删
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_insp_tpl UNIQUE (db_type, template_name_zh)
);

-- 2) 巡检章节：报告的一章，可独立启停与排序
CREATE TABLE IF NOT EXISTS inspection_chapter (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id       BIGINT       NOT NULL,
    chapter_number    INT          NOT NULL,
    chapter_title_zh  VARCHAR(200) NOT NULL,
    chapter_title_en  VARCHAR(200),
    description       CLOB,
    enabled           INT          DEFAULT 1,   -- 0=停用 1=启用
    sort_order        INT          DEFAULT 0,
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ch_tpl FOREIGN KEY (template_id)
        REFERENCES inspection_template(id) ON DELETE CASCADE,
    CONSTRAINT uq_insp_ch UNIQUE (template_id, chapter_number)
);

-- 3) 巡检规则库：规则正文的唯一存放处，跨模板共享
--    一条规则 = 一段采集 SQL + 它归属的库类型。章节通过下一张绑定表引用规则，
--    因此同一条规则可以被多个模板的章节复用；改一次，所有引用处同时生效。
CREATE TABLE IF NOT EXISTS inspection_rule (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_key      VARCHAR(100) NOT NULL,
    db_type       VARCHAR(64)  NOT NULL,
    rule_name_zh  VARCHAR(300) NOT NULL,
    rule_name_en  VARCHAR(300),
    rule_sql      CLOB,
    category      VARCHAR(200),                -- 建议归类章节（仅供规则库筛选，不是硬绑定）
    enabled       INT          DEFAULT 1,
    source        VARCHAR(20)  DEFAULT 'PRESET',  -- PRESET=种子导入 / CUSTOM=界面新建
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_insp_rule UNIQUE (rule_key)
);

-- 3b) 章节 ↔ 规则 绑定：报告骨架与规则库的接合点
--     解绑只删这一行，规则本身留在库里；删规则则级联清掉所有引用。
--     这里刻意不放 enabled：一条规则只有一个启停开关（在规则库里），
--     某个模板不想跑某条规则，直接解绑即可 —— 避免出现「规则启用了但绑定停用了」这种双重否定。
CREATE TABLE IF NOT EXISTS inspection_chapter_rule (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    chapter_id  BIGINT NOT NULL,
    rule_id     BIGINT NOT NULL,
    sort_order  INT    DEFAULT 0,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cr_ch   FOREIGN KEY (chapter_id)
        REFERENCES inspection_chapter(id) ON DELETE CASCADE,
    CONSTRAINT fk_cr_rule FOREIGN KEY (rule_id)
        REFERENCES inspection_rule(id) ON DELETE CASCADE,
    CONSTRAINT uq_insp_cr UNIQUE (chapter_id, rule_id)
);

-- 4) 配置基线：参数推荐值与风险等级，偏离即告警
CREATE TABLE IF NOT EXISTS inspection_baseline (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    db_type             VARCHAR(64)  NOT NULL,
    param_name          VARCHAR(100) NOT NULL,
    query_sql           CLOB,                  -- 采集该参数值的 SQL
    operator            VARCHAR(20),           -- = > < >= <= != BETWEEN LIKE
    expected_value      CLOB,                  -- 期望值（标量或 LIKE 模式）
    expected_value_min  DOUBLE,                -- BETWEEN 下界
    expected_value_max  DOUBLE,                -- BETWEEN 上界
    risk_level          VARCHAR(20),           -- LOW / MEDIUM / HIGH / CRITICAL
    description_zh      CLOB,
    description_en      CLOB,
    enabled             INT          DEFAULT 1,
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_insp_bl UNIQUE (db_type, param_name)
);

-- 5) 配置修改留痕：模板/章节/规则/基线的每次增删改都落一条
CREATE TABLE IF NOT EXISTS inspection_history (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    table_name   VARCHAR(64) NOT NULL,
    record_id    BIGINT      NOT NULL,
    action       VARCHAR(20) NOT NULL,          -- INSERT / UPDATE / DELETE
    old_value    CLOB,                          -- JSON
    new_value    CLOB,                          -- JSON
    modified_by  VARCHAR(100),
    modified_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_insp_tpl_dbtype ON inspection_template(db_type);
CREATE INDEX IF NOT EXISTS idx_insp_ch_tpl     ON inspection_chapter(template_id);
CREATE INDEX IF NOT EXISTS idx_insp_rule_db    ON inspection_rule(db_type);
CREATE INDEX IF NOT EXISTS idx_insp_rule_cat   ON inspection_rule(category);
CREATE INDEX IF NOT EXISTS idx_insp_cr_ch      ON inspection_chapter_rule(chapter_id);
CREATE INDEX IF NOT EXISTS idx_insp_cr_rule    ON inspection_chapter_rule(rule_id);
CREATE INDEX IF NOT EXISTS idx_insp_bl_dbtype  ON inspection_baseline(db_type);
CREATE INDEX IF NOT EXISTS idx_insp_hist_rec   ON inspection_history(table_name, record_id);
CREATE INDEX IF NOT EXISTS idx_insp_hist_time  ON inspection_history(modified_at);

-- ---------------------------------------------------------------------------
-- 巡检执行：一次巡检 = 一行 run，下挂逐条规则结果与逐条基线判定
-- ---------------------------------------------------------------------------

-- 6) 巡检执行记录（表头）
CREATE TABLE IF NOT EXISTS inspection_run (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_source_id      BIGINT,
    data_source_name    VARCHAR(200),
    db_type             VARCHAR(64),
    template_id         BIGINT,
    template_name       VARCHAR(300),
    status              VARCHAR(20),           -- SUCCESS / PARTIAL / FAILED
    trigger_source      VARCHAR(20),           -- MANUAL / SCHEDULED
    total_queries       INT DEFAULT 0,
    ok_queries          INT DEFAULT 0,
    failed_queries      INT DEFAULT 0,
    total_baselines     INT DEFAULT 0,
    baselines_pass      INT DEFAULT 0,
    baselines_fail      INT DEFAULT 0,
    baselines_unchecked INT DEFAULT 0,
    compliance_pct      DOUBLE,
    risk_summary        CLOB,                  -- JSON: {"HIGH":2,"MEDIUM":1}
    error_msg           CLOB,                  -- 整体失败原因（连不上库等）
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    duration_ms         BIGINT,
    executed_by         VARCHAR(100)
);

-- 7) 逐条巡检规则的执行结果
CREATE TABLE IF NOT EXISTS inspection_run_query (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT NOT NULL,
    chapter_number  INT,
    chapter_title   VARCHAR(300),
    query_key       VARCHAR(200),
    query_sql       CLOB,
    description_zh  CLOB,
    status          VARCHAR(20),               -- OK / FAILED / SKIPPED
    elapsed_ms      BIGINT,
    row_count       INT,
    columns_json    CLOB,                      -- JSON: 列名数组
    rows_json       CLOB,                      -- JSON: 结果预览（截断）
    truncated       INT DEFAULT 0,             -- 1=结果被截断
    error_msg       CLOB,
    CONSTRAINT fk_insp_run_q FOREIGN KEY (run_id)
        REFERENCES inspection_run(id) ON DELETE CASCADE
);

-- 8) 逐条基线的判定结果
CREATE TABLE IF NOT EXISTS inspection_run_baseline (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT NOT NULL,
    param_name      VARCHAR(100),
    operator        VARCHAR(20),
    expected_value  CLOB,
    actual_value    CLOB,                      -- 采集到的值（多行时逗号拼接，截断展示）
    sample_count    INT DEFAULT 0,             -- 采集到的行数；0 = 未采集
    violation_count INT DEFAULT 0,             -- 多行结果中不合规的行数
    risk_level      VARCHAR(20),
    is_pass         INT,                       -- 1/0
    is_checked      INT,                       -- 1/0（0 = 未采集，不计入合规率分母）
    message         CLOB,
    description_zh  CLOB,
    elapsed_ms      BIGINT,
    error_msg       CLOB,
    CONSTRAINT fk_insp_run_bl FOREIGN KEY (run_id)
        REFERENCES inspection_run(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_insp_run_ds      ON inspection_run(data_source_id);
CREATE INDEX IF NOT EXISTS idx_insp_run_started ON inspection_run(started_at);
CREATE INDEX IF NOT EXISTS idx_insp_runq_run    ON inspection_run_query(run_id);
CREATE INDEX IF NOT EXISTS idx_insp_runbl_run   ON inspection_run_baseline(run_id);

