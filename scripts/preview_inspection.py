#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
巡检配置与执行预览实现（对齐 Java 侧 InspectionConfigService / BaselineChecker / InspectionRunner）。

本模块被 devserver.py 调用，用 SQLite 复刻巡检模型：
    巡检配置管理  模板 → 章节（两级 ON DELETE CASCADE）
    规则引擎      规则库（规则的唯一存放处）+ 章节绑定（多对多，可跨模板复用）
    基线配置管理  配置基线（按库类型独立维护）
    修改留痕      独立表
    执行记录      执行记录 → 规则结果 / 基线判定（两级 ON DELETE CASCADE）

对外只暴露四个函数：
    init_schema(conn)                建表
    seed(conn, res_dir)              表为空时导入种子
    handle(conn, method, path, qs, body)   路由处理，非巡检路径返回 None

执行接口的能力边界（重要）：
    预览服务没有 JDBC 层，无法连接 Oracle / MySQL / H2 等真实数据库。
    因此 POST /api/inspection/run 会<b>如实</b>产出一条 status=FAILED 的执行记录——
    与 Java 侧「连不上库」是同一种结局，而不是伪造一份好看的报告。
    规则按模板顺序逐条列出但标记为 SKIPPED（未执行），基线全部标记为「未采集」，
    error_msg / message 里写明原因。这样报告界面的布局仍可在预览下开发与回归，
    真实结果请启动 Java 后端获取。

导出接口的能力边界（同样重要）：
    GET /api/inspection/runs/{id}/export 在预览服务下恒定返回 <b>501</b>。
    报告导出依赖 Java 侧的 POI / openhtmltopdf / 中文字体嵌入，本服务不具备，
    也<b>不伪造</b>一份内容对不上的 .docx/.pdf/.html —— 那种文件比明确的 501 更难排查。
    校验顺序（先 404 后 400 再 501）与 Java 侧保持一致，见 _export_stub。
"""

import json
import os
import re
import sqlite3
import time
from datetime import datetime
from urllib.parse import parse_qs

# ---------------------------------------------------------------------------
# 建表（对齐 Java schema.sql 的巡检部分）
# ---------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS inspection_template (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    db_type           TEXT NOT NULL,
    template_name_zh  TEXT NOT NULL,
    template_name_en  TEXT,
    version           TEXT DEFAULT 'v1',
    description       TEXT,
    is_default        INTEGER DEFAULT 0,
    is_preset         INTEGER DEFAULT 0,
    created_at        TEXT DEFAULT (datetime('now','localtime')),
    updated_at        TEXT DEFAULT (datetime('now','localtime')),
    UNIQUE (db_type, template_name_zh)
);

CREATE TABLE IF NOT EXISTS inspection_chapter (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id       INTEGER NOT NULL,
    chapter_number    INTEGER NOT NULL,
    chapter_title_zh  TEXT NOT NULL,
    chapter_title_en  TEXT,
    description       TEXT,
    enabled           INTEGER DEFAULT 1,
    sort_order        INTEGER DEFAULT 0,
    created_at        TEXT DEFAULT (datetime('now','localtime')),
    updated_at        TEXT DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (template_id) REFERENCES inspection_template(id) ON DELETE CASCADE,
    UNIQUE (template_id, chapter_number)
);

CREATE TABLE IF NOT EXISTS inspection_rule (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_key      TEXT NOT NULL,
    db_type       TEXT NOT NULL,
    rule_name_zh  TEXT NOT NULL,
    rule_name_en  TEXT,
    rule_sql      TEXT,
    category      TEXT,
    enabled       INTEGER DEFAULT 1,
    source        TEXT DEFAULT 'PRESET',
    created_at    TEXT DEFAULT (datetime('now','localtime')),
    updated_at    TEXT DEFAULT (datetime('now','localtime')),
    UNIQUE (rule_key)
);

CREATE TABLE IF NOT EXISTS inspection_chapter_rule (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    chapter_id  INTEGER NOT NULL,
    rule_id     INTEGER NOT NULL,
    sort_order  INTEGER DEFAULT 0,
    created_at  TEXT DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (chapter_id) REFERENCES inspection_chapter(id) ON DELETE CASCADE,
    FOREIGN KEY (rule_id)    REFERENCES inspection_rule(id)    ON DELETE CASCADE,
    UNIQUE (chapter_id, rule_id)
);

CREATE TABLE IF NOT EXISTS inspection_baseline (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    db_type             TEXT NOT NULL,
    param_name          TEXT NOT NULL,
    query_sql           TEXT,
    operator            TEXT,
    expected_value      TEXT,
    expected_value_min  REAL,
    expected_value_max  REAL,
    risk_level          TEXT,
    description_zh      TEXT,
    description_en      TEXT,
    enabled             INTEGER DEFAULT 1,
    created_at          TEXT DEFAULT (datetime('now','localtime')),
    updated_at          TEXT DEFAULT (datetime('now','localtime')),
    UNIQUE (db_type, param_name)
);

CREATE TABLE IF NOT EXISTS inspection_history (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name   TEXT NOT NULL,
    record_id    INTEGER NOT NULL,
    action       TEXT NOT NULL,
    old_value    TEXT,
    new_value    TEXT,
    modified_by  TEXT,
    modified_at  TEXT DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS inspection_run (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    data_source_id      INTEGER,
    data_source_name    TEXT,
    db_type             TEXT,
    template_id         INTEGER,
    template_name       TEXT,
    status              TEXT,
    trigger_source      TEXT,
    total_queries       INTEGER,
    ok_queries          INTEGER,
    failed_queries      INTEGER,
    total_baselines     INTEGER,
    baselines_pass      INTEGER,
    baselines_fail      INTEGER,
    baselines_unchecked INTEGER,
    compliance_pct      REAL,
    risk_summary        TEXT,
    error_msg           TEXT,
    started_at          TEXT,
    finished_at         TEXT,
    duration_ms         INTEGER,
    executed_by         TEXT
);

CREATE TABLE IF NOT EXISTS inspection_run_query (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          INTEGER NOT NULL,
    chapter_number  INTEGER,
    chapter_title   TEXT,
    query_key       TEXT,
    query_sql       TEXT,
    description_zh  TEXT,
    status          TEXT,
    elapsed_ms      INTEGER,
    row_count       INTEGER,
    columns_json    TEXT,
    rows_json       TEXT,
    truncated       INTEGER DEFAULT 0,
    error_msg       TEXT,
    FOREIGN KEY (run_id) REFERENCES inspection_run(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS inspection_run_baseline (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id          INTEGER NOT NULL,
    param_name      TEXT,
    operator        TEXT,
    expected_value  TEXT,
    actual_value    TEXT,
    sample_count    INTEGER DEFAULT 0,
    violation_count INTEGER DEFAULT 0,
    risk_level      TEXT,
    is_pass         INTEGER,
    is_checked      INTEGER,
    message         TEXT,
    description_zh  TEXT,
    elapsed_ms      INTEGER,
    error_msg       TEXT,
    FOREIGN KEY (run_id) REFERENCES inspection_run(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_insp_run_ds      ON inspection_run(data_source_id);
CREATE INDEX IF NOT EXISTS idx_insp_run_started ON inspection_run(started_at);
CREATE INDEX IF NOT EXISTS idx_insp_runq_run    ON inspection_run_query(run_id);
CREATE INDEX IF NOT EXISTS idx_insp_runbl_run   ON inspection_run_baseline(run_id);
"""

VALID_OPERATORS = ("=", ">", "<", ">=", "<=", "!=", "BETWEEN", "LIKE")
VALID_RISK = ("LOW", "MEDIUM", "HIGH", "CRITICAL")


def init_schema(conn):
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(SCHEMA)
    conn.commit()


# ---------------------------------------------------------------------------
# 种子导入
# ---------------------------------------------------------------------------

def seed(conn, res_dir):
    """各表为空时分别导入规则库、模板与章节、基线。返回统计字典。

    与 Java 侧 seedIfEmpty() 的编排保持一致：
      inspection_rule      空 → 从 rules.json 导入规则库
      inspection_template  空 → 从 templates.json 导入模板与章节，并按 rule_keys 建绑定
      两者都在、绑定为空    → 只重建「章节引用了哪些规则」，不动已有模板与章节
    """
    stat = {"templatesImported": 0, "rulesImported": 0,
            "bindingsImported": 0, "baselinesImported": 0}

    rules_empty = conn.execute("SELECT COUNT(*) FROM inspection_rule").fetchone()[0] == 0
    tpl_empty = conn.execute("SELECT COUNT(*) FROM inspection_template").fetchone()[0] == 0
    bind_empty = conn.execute("SELECT COUNT(*) FROM inspection_chapter_rule").fetchone()[0] == 0

    # 顺序不能反：先有规则库，模板的 rule_keys 才有东西可指
    if rules_empty:
        stat["rulesImported"] = _seed_rules(conn, res_dir)
    if tpl_empty:
        stat["templatesImported"] = _seed_templates(conn, res_dir)
    elif bind_empty:
        stat["bindingsImported"] = _rebuild_bindings(conn, res_dir)

    if conn.execute("SELECT COUNT(*) FROM inspection_baseline").fetchone()[0] == 0:
        stat["baselinesImported"] = _seed_baselines(conn, res_dir)

    _drop_legacy_query_table(conn)

    # Total 是「现在库里有多少」，与上面的 Imported 分开报
    stat["templatesTotal"] = conn.execute(
        "SELECT COUNT(*) FROM inspection_template").fetchone()[0]
    stat["chaptersTotal"] = conn.execute(
        "SELECT COUNT(*) FROM inspection_chapter").fetchone()[0]
    stat["rulesTotal"] = conn.execute(
        "SELECT COUNT(*) FROM inspection_rule").fetchone()[0]
    stat["bindingsTotal"] = conn.execute(
        "SELECT COUNT(*) FROM inspection_chapter_rule").fetchone()[0]
    stat["baselinesTotal"] = conn.execute(
        "SELECT COUNT(*) FROM inspection_baseline").fetchone()[0]
    return stat


def _drop_legacy_query_table(conn):
    """删掉旧版内嵌在章节下的规则表 inspection_query。

    规则正文现在只存放在 inspection_rule，这张表已无人读写。它只存在于从旧
    schema 升级上来的库里（新库根本不会建它），所以先探测再删，正常路径直接返回。

    注意别删错：inspection_run_query 是**执行快照**表（每次巡检把当时跑的 SQL
    连同结果存一份，事后改规则库不该改写历史报告），与这张配置表不是一回事。
    """
    try:
        conn.execute("SELECT COUNT(*) FROM inspection_query").fetchone()
    except sqlite3.OperationalError:
        return          # 新库没有这张表，正常路径
    conn.execute("DROP TABLE inspection_query")


def _seed_rules(conn, res_dir):
    path = os.path.join(res_dir, "inspection", "rules.json")
    if not os.path.isfile(path):
        return 0
    with open(path, encoding="utf-8") as f:
        rules = json.load(f).get("rules", [])

    count = 0
    for r in rules:
        try:
            conn.execute(
                """INSERT INTO inspection_rule
                   (rule_key, db_type, rule_name_zh, rule_name_en, rule_sql,
                    category, enabled, source)
                   VALUES (?,?,?,?,?,?,?,?)""",
                (r["rule_key"], r["db_type"], r["rule_name_zh"], r.get("rule_name_en"),
                 r.get("rule_sql"), r.get("category"), r.get("enabled", 1),
                 r.get("source", "PRESET")))
            count += 1
        except sqlite3.IntegrityError:
            pass
    conn.commit()
    return count


def _bind_rule_keys(conn, chapter_id, rule_keys):
    """按 rule_key 建绑定；库里查不到的 key 跳过（与 Java 侧一致：不中断导入）。"""
    if not rule_keys:
        return 0
    row = conn.execute("SELECT COALESCE(MAX(sort_order), -1) FROM inspection_chapter_rule "
                       "WHERE chapter_id=?", (chapter_id,)).fetchone()
    order = (row[0] if row and row[0] is not None else -1) + 1
    n = 0
    for key in rule_keys:
        hit = conn.execute("SELECT id FROM inspection_rule WHERE rule_key=?", (key,)).fetchone()
        if not hit:
            continue
        try:
            conn.execute("INSERT INTO inspection_chapter_rule (chapter_id, rule_id, sort_order) "
                         "VALUES (?,?,?)", (chapter_id, hit[0], order))
            order += 1
            n += 1
        except sqlite3.IntegrityError:
            pass
    return n


def _seed_templates(conn, res_dir):
    path = os.path.join(res_dir, "inspection", "templates.json")
    if not os.path.isfile(path):
        return 0
    with open(path, encoding="utf-8") as f:
        templates = json.load(f).get("templates", [])

    count = 0
    for t in templates:
        conn.execute(
            """INSERT INTO inspection_template
               (db_type, template_name_zh, template_name_en, version,
                description, is_default, is_preset)
               VALUES (?,?,?,?,?,?,?)""",
            (t["db_type"], t["template_name_zh"], t.get("template_name_en"),
             t.get("version", "v1"), t.get("description"),
             t.get("is_default", 0), t.get("is_preset", 0)))
        tpl_id = conn.execute(
            "SELECT id FROM inspection_template WHERE db_type=? AND template_name_zh=?",
            (t["db_type"], t["template_name_zh"])).fetchone()[0]
        count += 1

        for c in t.get("chapters", []):
            conn.execute(
                """INSERT INTO inspection_chapter
                   (template_id, chapter_number, chapter_title_zh, chapter_title_en,
                    description, enabled, sort_order)
                   VALUES (?,?,?,?,?,?,?)""",
                (tpl_id, c["chapter_number"], c["chapter_title_zh"],
                 c.get("chapter_title_en"), c.get("description"),
                 c.get("enabled", 1), c.get("sort_order", c["chapter_number"])))
            ch_id = conn.execute(
                "SELECT id FROM inspection_chapter WHERE template_id=? AND chapter_number=?",
                (tpl_id, c["chapter_number"])).fetchone()[0]
            _bind_rule_keys(conn, ch_id, c.get("rule_keys"))

    conn.commit()
    return count


def _rebuild_bindings(conn, res_dir):
    """模板与章节都在、只是绑定为空时的补建路径（按 db_type + 模板名 + 章节号匹配）。"""
    path = os.path.join(res_dir, "inspection", "templates.json")
    if not os.path.isfile(path):
        return 0
    with open(path, encoding="utf-8") as f:
        templates = json.load(f).get("templates", [])

    count = 0
    for t in templates:
        hit = conn.execute(
            "SELECT id FROM inspection_template WHERE db_type=? AND template_name_zh=?",
            (t["db_type"], t["template_name_zh"])).fetchone()
        if not hit:
            continue
        tpl_id = hit[0]
        for c in t.get("chapters", []):
            ch = conn.execute(
                "SELECT id FROM inspection_chapter WHERE template_id=? AND chapter_number=?",
                (tpl_id, c["chapter_number"])).fetchone()
            if not ch:
                continue
            count += _bind_rule_keys(conn, ch[0], c.get("rule_keys"))
    conn.commit()
    return count


def _seed_baselines(conn, res_dir):
    path = os.path.join(res_dir, "inspection", "baselines.json")
    if not os.path.isfile(path):
        return 0
    with open(path, encoding="utf-8") as f:
        mapping = json.load(f).get("baselines", {})

    count = 0
    for db_type, items in mapping.items():
        for b in items:
            try:
                conn.execute(
                    """INSERT INTO inspection_baseline
                       (db_type, param_name, query_sql, operator, expected_value,
                        expected_value_min, expected_value_max, risk_level,
                        description_zh, description_en, enabled)
                       VALUES (?,?,?,?,?,?,?,?,?,?,1)""",
                    (db_type, b["param_name"], b.get("query_sql"),
                     b.get("operator", "="), b.get("expected_value"),
                     b.get("expected_value_min"), b.get("expected_value_max"),
                     b.get("risk_level", "MEDIUM"),
                     b.get("description_zh"), b.get("description_en")))
                count += 1
            except sqlite3.IntegrityError:
                pass
    conn.commit()
    return count


# ---------------------------------------------------------------------------
# 基线判定（镜像 Java 的 BaselineChecker）
# ---------------------------------------------------------------------------

_SIZE_RE = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGT]B)\s*$", re.I)
_UNIT = {"KB": 1024, "MB": 1024 ** 2, "GB": 1024 ** 3, "TB": 1024 ** 4}

_RANKS = {}


def _rank(literal, value):
    _RANKS.setdefault(literal.lower(), value)


for _l, _v in [
    ("off", 0), ("on", 1), ("false", 0), ("true", 1), ("no", 0), ("yes", 1),
    ("minimal", 0), ("replica", 1), ("logical", 2),
    ("md5", 0), ("scram-sha-256", 1),
    ("none", 0), ("archive", 1), ("hot_standby", 2),
    ("low", 0), ("medium", 1), ("strong", 2),
    ("simple", 0), ("bulk_logged", 1), ("full", 2),
    ("torn_page_detection", 1), ("checksum", 2),
    ("os", 1), ("db", 2), ("db_extended", 3),
]:
    _rank(_l, _v)


def _to_number(raw):
    if raw is None:
        return None
    s = str(raw).strip()
    if not s:
        return None
    m = _SIZE_RE.match(s)
    if m:
        return float(m.group(1)) * _UNIT[m.group(2).upper()]
    try:
        return float(s)
    except ValueError:
        return None


def _cmp(op, c):
    return {
        "=": c == 0, "!=": c != 0, ">": c > 0,
        ">=": c >= 0, "<": c < 0, "<=": c <= 0,
    }.get(op)


def check_baseline(baseline, actual):
    """返回判定结果 dict，字段与 Java BaselineCheckResult 对齐。"""
    op = (baseline.get("operator") or "=").strip().upper()
    exp = baseline.get("expected_value")
    exp_min = baseline.get("expected_value_min")
    exp_max = baseline.get("expected_value_max")

    expected_desc = (f"[{exp_min}, {exp_max}]" if op == "BETWEEN"
                     else ("" if exp is None else str(exp)))

    out = {
        "dbType": baseline.get("db_type"),
        "paramName": baseline.get("param_name"),
        "operator": op,
        "expectedValue": expected_desc,
        "actualValue": actual,
        "riskLevel": baseline.get("risk_level"),
        "descriptionZh": baseline.get("description_zh"),
        "pass": False,
        "checked": False,
        "message": "",
    }

    if actual is None:
        out["message"] = "未采集到参数值，无法判定"
        return out

    a = str(actual)

    if op == "LIKE":
        ok = (exp or "").lower() in a.lower()
    elif op == "BETWEEN":
        n = _to_number(a)
        if n is None or exp_min is None or exp_max is None:
            out["message"] = "无法比较：" + a + " BETWEEN " + expected_desc
            return out
        ok = exp_min <= n <= exp_max
    else:
        na, ne = _to_number(a), _to_number(exp)
        if na is not None and ne is not None:
            ok = _cmp(op, (na > ne) - (na < ne))
        else:
            ak, ek = a.strip().lower(), (exp or "").strip().lower()
            if ak in _RANKS and ek in _RANKS:
                ra, re_ = _RANKS[ak], _RANKS[ek]
                ok = _cmp(op, (ra > re_) - (ra < re_))
            else:
                cmp = (ak > ek) - (ak < ek)
                ok = _cmp(op, cmp)

        if ok is None:
            out["message"] = "无法比较：" + a + " " + op + " " + expected_desc
            return out

    out["checked"] = True
    out["pass"] = bool(ok)
    out["message"] = (f"合规：{a} {op} {expected_desc}" if ok
                      else f"不合规：实际 {a} 不满足 {op} {expected_desc}")
    return out


# ---------------------------------------------------------------------------
# 序列化
# ---------------------------------------------------------------------------

def _tpl(row, with_children=False, conn=None, only_enabled=False):
    d = {
        "id": row["id"], "dbType": row["db_type"],
        "templateNameZh": row["template_name_zh"],
        "templateNameEn": row["template_name_en"],
        "version": row["version"], "description": row["description"],
        "isDefault": row["is_default"], "isPreset": row["is_preset"],
        "createdAt": row["created_at"], "updatedAt": row["updated_at"],
    }
    if with_children:
        d["chapters"] = _chapters_of(conn, row["id"], only_enabled)
    return d


def _chapters_of(conn, tpl_id, only_enabled=False):
    sql = "SELECT * FROM inspection_chapter WHERE template_id=?"
    if only_enabled:
        sql += " AND enabled=1"
    sql += " ORDER BY chapter_number, sort_order"
    rows = conn.execute(sql, (tpl_id,)).fetchall()
    out = []
    for c in rows:
        out.append({
            "id": c["id"], "templateId": c["template_id"],
            "chapterNumber": c["chapter_number"],
            "chapterTitleZh": c["chapter_title_zh"],
            "chapterTitleEn": c["chapter_title_en"],
            "description": c["description"], "enabled": c["enabled"],
            "sortOrder": c["sort_order"],
            "rules": _chapter_rules(conn, c["id"], only_enabled),
        })
    return out


def _chapter_rules(conn, chapter_id, only_enabled=False):
    """某章引用的规则。规则正文来自规则库，排序来自绑定表。"""
    sql = ("SELECT r.*, cr.sort_order AS bind_order, "
           "(SELECT COUNT(*) FROM inspection_chapter_rule x WHERE x.rule_id = r.id) AS ref_count "
           "FROM inspection_chapter_rule cr JOIN inspection_rule r ON r.id = cr.rule_id "
           "WHERE cr.chapter_id=?")
    if only_enabled:
        sql += " AND r.enabled=1"
    sql += " ORDER BY cr.sort_order, cr.id"
    rows = conn.execute(sql, (chapter_id,)).fetchall()
    out = []
    for r in rows:
        item = _rule(r)
        item["sortOrder"] = r["bind_order"]
        item["usedBy"] = _used_by(conn, r["id"])
        out.append(item)
    return out


def _rule(r, with_used_by=False, conn=None):
    item = {
        "id": r["id"], "ruleKey": r["rule_key"], "dbType": r["db_type"],
        "ruleNameZh": r["rule_name_zh"], "ruleNameEn": r["rule_name_en"],
        "ruleSql": r["rule_sql"], "category": r["category"],
        "enabled": r["enabled"], "source": r["source"],
        "createdAt": r["created_at"], "updatedAt": r["updated_at"],
        "sortOrder": None,
        "refCount": r["ref_count"] if "ref_count" in r.keys() else None,
        "usedBy": [],
    }
    if with_used_by and conn is not None:
        item["usedBy"] = _used_by(conn, r["id"])
    return item


def _used_by(conn, rule_id):
    rows = conn.execute(
        "SELECT t.template_name_zh FROM inspection_chapter_rule cr "
        "JOIN inspection_chapter c ON c.id = cr.chapter_id "
        "JOIN inspection_template t ON t.id = c.template_id "
        "WHERE cr.rule_id=? ORDER BY t.template_name_zh", (rule_id,)).fetchall()
    out = []
    for r in rows:
        if r[0] not in out:
            out.append(r[0])
    return out


def _baseline(r):
    return {
        "id": r["id"], "dbType": r["db_type"], "paramName": r["param_name"],
        "querySql": r["query_sql"], "operator": r["operator"],
        "expectedValue": r["expected_value"],
        "expectedValueMin": r["expected_value_min"],
        "expectedValueMax": r["expected_value_max"],
        "riskLevel": r["risk_level"],
        "descriptionZh": r["description_zh"],
        "descriptionEn": r["description_en"], "enabled": r["enabled"],
    }


def _record(conn, table, record_id, action, old=None, new=None):
    conn.execute(
        """INSERT INTO inspection_history
           (table_name, record_id, action, old_value, new_value, modified_by)
           VALUES (?,?,?,?,?,?)""",
        (table, record_id, action,
         json.dumps(old, ensure_ascii=False) if old else None,
         json.dumps(new, ensure_ascii=False) if new else None, "console"))
    conn.commit()


# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------

def handle(conn, method, path, qs, body):
    """处理 /api/inspection/* 。非本模块路径返回 None。"""
    if not path.startswith("/api/inspection"):
        return None

    q1 = lambda k, d=None: (qs.get(k) or [d])[0]
    ok = lambda data: (200, {"code": 200, "message": "success", "data": data})
    bad = lambda c, m: (c, {"code": c, "message": m, "data": None})

    # ---------------- 总览 ----------------
    if method == "GET" and path == "/api/inspection/summary":
        return ok(_summary(conn))
    if method == "GET" and path == "/api/inspection/history":
        limit = int(q1("limit", "100"))
        rows = conn.execute(
            "SELECT * FROM inspection_history ORDER BY modified_at DESC, id DESC LIMIT ?",
            (limit,)).fetchall()
        return ok([{
            "id": r["id"], "tableName": r["table_name"], "recordId": r["record_id"],
            "action": r["action"], "oldValue": r["old_value"],
            "newValue": r["new_value"], "modifiedBy": r["modified_by"],
            "modifiedAt": r["modified_at"],
        } for r in rows])

    # ---------------- 模板 ----------------
    if method == "GET" and path == "/api/inspection/templates":
        db_type = q1("dbType")
        if db_type:
            rows = conn.execute(
                "SELECT * FROM inspection_template WHERE db_type=? "
                "ORDER BY is_default DESC, id", (db_type,)).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM inspection_template "
                "ORDER BY db_type, is_default DESC, id").fetchall()
        return ok([_tpl(r) for r in rows])

    m = re.match(r"^/api/inspection/templates/(\d+)/tree$", path)
    if method == "GET" and m:
        row = conn.execute("SELECT * FROM inspection_template WHERE id=?",
                           (m.group(1),)).fetchone()
        if not row:
            return bad(404, "模板不存在")
        return ok(_tpl(row, True, conn, q1("onlyEnabled", "false").lower() == "true"))

    m = re.match(r"^/api/inspection/templates/default/([\w-]+)/tree$", path)
    if method == "GET" and m:
        row = conn.execute(
            "SELECT * FROM inspection_template WHERE db_type=? AND is_default=1 LIMIT 1",
            (m.group(1),)).fetchone()
        if not row:
            return bad(404, "该类型暂无默认模板")
        return ok(_tpl(row, True, conn, q1("onlyEnabled", "true").lower() == "true"))

    m = re.match(r"^/api/inspection/templates/(\d+)$", path)
    if m and method == "GET":
        row = conn.execute("SELECT * FROM inspection_template WHERE id=?",
                           (m.group(1),)).fetchone()
        return ok(_tpl(row)) if row else bad(404, "模板不存在")
    if m and method == "PUT":
        return _update_template(conn, int(m.group(1)), body, ok, bad)
    if m and method == "DELETE":
        return _delete_template(conn, int(m.group(1)),
                                q1("force", "false").lower() == "true", ok, bad)

    if method == "POST" and path == "/api/inspection/templates":
        return _create_template(conn, body, ok, bad)

    m = re.match(r"^/api/inspection/templates/(\d+)/default$", path)
    if method == "POST" and m:
        return _set_default(conn, int(m.group(1)), ok, bad)

    # ---------------- 章节 ----------------
    m = re.match(r"^/api/inspection/templates/(\d+)/chapters$", path)
    if method == "GET" and m:
        return ok(_chapters_of(conn, int(m.group(1))))
    if method == "POST" and path == "/api/inspection/chapters":
        return _create_chapter(conn, body, ok, bad)
    m = re.match(r"^/api/inspection/chapters/(\d+)$", path)
    if m and method == "PUT":
        return _update_chapter(conn, int(m.group(1)), body, ok, bad)
    if m and method == "DELETE":
        row = conn.execute("SELECT * FROM inspection_chapter WHERE id=?",
                           (m.group(1),)).fetchone()
        if not row:
            return bad(404, "章节不存在")
        conn.execute("DELETE FROM inspection_chapter WHERE id=?", (m.group(1),))
        conn.commit()
        _record(conn, "inspection_chapter", int(m.group(1)), "DELETE",
                {"title": row["chapter_title_zh"]})
        return ok(None)

    # ---------------- 规则引擎：规则库 ----------------
    if method == "GET" and path == "/api/inspection/rules":
        return ok(_list_rules(conn, q1("dbType"), q1("category"),
                              q1("enabled"), q1("keyword")))

    if method == "GET" and path == "/api/inspection/rules/stats":
        return ok(_rule_stats(conn))

    if method == "GET" and path == "/api/inspection/rules/categories":
        db_type = q1("dbType")
        if db_type:
            rows = conn.execute(
                "SELECT DISTINCT category FROM inspection_rule "
                "WHERE category IS NOT NULL AND db_type=? ORDER BY category",
                (db_type,)).fetchall()
        else:
            rows = conn.execute(
                "SELECT DISTINCT category FROM inspection_rule "
                "WHERE category IS NOT NULL ORDER BY category").fetchall()
        return ok([r[0] for r in rows])

    if method == "POST" and path == "/api/inspection/rules":
        return _create_rule(conn, body, ok, bad)

    if method == "POST" and path == "/api/inspection/rules/enabled":
        db_type = q1("dbType")
        enabled = q1("enabled", "true").lower() == "true"
        cur = conn.execute(
            "UPDATE inspection_rule SET enabled=?, updated_at=datetime('now','localtime') "
            "WHERE db_type=?", (1 if enabled else 0, db_type))
        conn.commit()
        _record(conn, "inspection_rule", 0, "UPDATE",
                None, {"dbType": db_type, "bulkEnabled": enabled, "affected": cur.rowcount})
        return ok({"dbType": db_type, "enabled": enabled, "affected": cur.rowcount})

    m = re.match(r"^/api/inspection/rules/(\d+)/enabled$", path)
    if m and method == "POST":
        enabled = q1("enabled", "true").lower() == "true"
        row = conn.execute("SELECT * FROM inspection_rule WHERE id=?",
                           (m.group(1),)).fetchone()
        if not row:
            return bad(404, "规则不存在")
        conn.execute("UPDATE inspection_rule SET enabled=?, "
                     "updated_at=datetime('now','localtime') WHERE id=?",
                     (1 if enabled else 0, m.group(1)))
        conn.commit()
        _record(conn, "inspection_rule", int(m.group(1)), "UPDATE",
                {"key": row["rule_key"], "enabled": row["enabled"]},
                {"key": row["rule_key"], "enabled": 1 if enabled else 0})
        return ok(_rule(_fetch_rule(conn, int(m.group(1))), True, conn))

    m = re.match(r"^/api/inspection/rules/(\d+)/test$", path)
    if m and method == "POST":
        return _test_rule_stub(conn, int(m.group(1)), body, ok, bad)

    m = re.match(r"^/api/inspection/rules/(\d+)$", path)
    if m and method == "GET":
        row = _fetch_rule(conn, int(m.group(1)))
        return ok(_rule(row, True, conn)) if row else bad(404, "规则不存在")
    if m and method == "PUT":
        return _update_rule(conn, int(m.group(1)), body, ok, bad)
    if m and method == "DELETE":
        return _delete_rule(conn, int(m.group(1)), q1("force", "false"), ok, bad)

    # ---------------- 规则引擎：章节 ↔ 规则 绑定 ----------------
    m = re.match(r"^/api/inspection/chapters/(\d+)/rules$", path)
    if method == "GET" and m:
        return ok(_chapter_rules(conn, int(m.group(1))))

    m = re.match(r"^/api/inspection/chapters/(\d+)/rules/order$", path)
    if m and method == "PUT":
        return _reorder_chapter_rules(conn, int(m.group(1)), body, ok, bad)

    m = re.match(r"^/api/inspection/chapters/(\d+)/rules/(\d+)$", path)
    if m and method == "DELETE":
        return _unbind_rule(conn, int(m.group(1)), int(m.group(2)), ok, bad)

    m = re.match(r"^/api/inspection/chapters/(\d+)/rules$", path)
    if m and method == "POST":
        return _bind_rule(conn, int(m.group(1)), body, ok, bad)

    # ---------------- 基线 ----------------
    if method == "GET" and path == "/api/inspection/baselines":
        db_type = q1("dbType")
        if db_type:
            rows = conn.execute(
                "SELECT * FROM inspection_baseline WHERE db_type=? ORDER BY param_name",
                (db_type,)).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM inspection_baseline ORDER BY db_type, param_name").fetchall()
        return ok([_baseline(r) for r in rows])

    if method == "POST" and path == "/api/inspection/baselines":
        return _create_baseline(conn, body, ok, bad)

    if method == "POST" and path == "/api/inspection/baselines/enabled":
        db_type = q1("dbType")
        enabled = q1("enabled", "true").lower() == "true"
        cur = conn.execute(
            "UPDATE inspection_baseline SET enabled=?, updated_at=datetime('now','localtime') "
            "WHERE db_type=?", (1 if enabled else 0, db_type))
        conn.commit()
        _record(conn, "inspection_baseline", 0, "UPDATE",
                None, {"dbType": db_type, "bulkEnabled": enabled, "affected": cur.rowcount})
        return ok({"dbType": db_type, "enabled": enabled, "affected": cur.rowcount})

    if method == "POST" and path == "/api/inspection/baselines/check":
        return _check_many(conn, body, ok, bad)

    m = re.match(r"^/api/inspection/baselines/(\d+)$", path)
    if m and method == "GET":
        row = conn.execute("SELECT * FROM inspection_baseline WHERE id=?",
                           (m.group(1),)).fetchone()
        return ok(_baseline(row)) if row else bad(404, "基线不存在")
    if m and method == "PUT":
        return _update_baseline(conn, int(m.group(1)), body, ok, bad)
    if m and method == "DELETE":
        row = conn.execute("SELECT * FROM inspection_baseline WHERE id=?",
                           (m.group(1),)).fetchone()
        if not row:
            return bad(404, "基线不存在")
        conn.execute("DELETE FROM inspection_baseline WHERE id=?", (m.group(1),))
        conn.commit()
        _record(conn, "inspection_baseline", int(m.group(1)), "DELETE",
                {"dbType": row["db_type"], "param": row["param_name"]})
        return ok(None)

    m = re.match(r"^/api/inspection/baselines/(\d+)/check$", path)
    if method == "POST" and m:
        row = conn.execute("SELECT * FROM inspection_baseline WHERE id=?",
                           (m.group(1),)).fetchone()
        if not row:
            return bad(404, "基线不存在")
        return ok(check_baseline(dict(row), (body or {}).get("actualValue")))

    # ---------------- 执行 ----------------
    if method == "POST" and path == "/api/inspection/run":
        return _run_inspection(conn, body, ok, bad)

    if method == "GET" and path == "/api/inspection/runs":
        ds_id = q1("dataSourceId")
        db_type = q1("dbType")
        try:
            limit = int(q1("limit", "50"))
        except (TypeError, ValueError):
            limit = 50
        limit = 50 if limit <= 0 else min(limit, 500)

        sql = "SELECT * FROM inspection_run WHERE 1=1"
        args = []
        if ds_id:
            sql += " AND data_source_id=?"
            args.append(int(ds_id))
        if db_type:
            sql += " AND db_type=?"
            args.append(db_type)
        sql += " ORDER BY started_at DESC, id DESC LIMIT ?"
        args.append(limit)
        return ok([_run_json(r) for r in conn.execute(sql, args).fetchall()])

    if method == "GET" and path == "/api/inspection/runs/latest":
        ds_id = q1("dataSourceId")
        if not ds_id:
            return bad(400, "dataSourceId 不能为空")
        row = conn.execute(
            "SELECT * FROM inspection_run WHERE data_source_id=? "
            "ORDER BY started_at DESC, id DESC LIMIT 1", (int(ds_id),)).fetchone()
        return ok(_run_json(row)) if row else bad(404, "该数据源暂无巡检记录")

    m = re.match(r"^/api/inspection/runs/(\d+)/export$", path)
    if m and method == "GET":
        return _export_stub(conn, int(m.group(1)), q1("format", "html"), ok, bad)

    m = re.match(r"^/api/inspection/runs/(\d+)$", path)
    if m and method == "GET":
        return _run_detail(conn, int(m.group(1)), ok, bad)
    if m and method == "DELETE":
        row = conn.execute("SELECT id FROM inspection_run WHERE id=?",
                           (m.group(1),)).fetchone()
        if not row:
            return bad(404, "巡检记录不存在: " + m.group(1))
        conn.execute("DELETE FROM inspection_run WHERE id=?", (m.group(1),))
        conn.commit()
        return ok(None)

    return bad(404, "未知巡检接口: " + path)


# ---------------------------------------------------------------------------
# 各端点实现
# ---------------------------------------------------------------------------

def _summary(conn):
    # 规则数与引用数是两个不同的口径：一条规则被 3 个章节引用算 3 条引用、1 条规则。
    # 两个都报，免得把「跨模板共享」误读成「规则重复」。
    rules_by_type = {r["db_type"]: r["c"] for r in conn.execute(
        "SELECT db_type, COUNT(*) c FROM inspection_rule GROUP BY db_type ORDER BY db_type")}
    binds_by_type = {r["db_type"]: r["c"] for r in conn.execute(
        "SELECT t.db_type, COUNT(*) c FROM inspection_chapter_rule cr "
        "JOIN inspection_chapter c ON c.id = cr.chapter_id "
        "JOIN inspection_template t ON t.id = c.template_id "
        "GROUP BY t.db_type ORDER BY t.db_type")}

    by_type = {}
    total_ch = 0
    for t in conn.execute("SELECT * FROM inspection_template ORDER BY db_type").fetchall():
        agg = by_type.setdefault(t["db_type"], {
            "dbType": t["db_type"], "templates": 0, "chapters": 0,
            "rules": 0, "bindings": 0})
        agg["templates"] += 1
        chs = conn.execute(
            "SELECT id FROM inspection_chapter WHERE template_id=?", (t["id"],)).fetchall()
        agg["chapters"] += len(chs)
        total_ch += len(chs)

    total_rules, total_bindings = 0, 0
    for db_type, agg in by_type.items():
        agg["rules"] = rules_by_type.get(db_type, 0)
        agg["bindings"] = binds_by_type.get(db_type, 0)
        total_rules += agg["rules"]
        total_bindings += agg["bindings"]

    risk = {}
    for r in conn.execute(
            "SELECT risk_level, COUNT(*) c FROM inspection_baseline GROUP BY risk_level"):
        risk[r["risk_level"] or "UNKNOWN"] = r["c"]

    counts = {r["db_type"]: r["c"] for r in conn.execute(
        "SELECT db_type, COUNT(*) c FROM inspection_baseline GROUP BY db_type ORDER BY db_type")}

    return {
        "templateCount": conn.execute(
            "SELECT COUNT(*) FROM inspection_template").fetchone()[0],
        "byDbType": list(by_type.values()),
        "totalChapters": total_ch,
        "totalRules": total_rules,
        "totalBindings": total_bindings,
        "ruleCountByType": rules_by_type,
        "baselineCountByType": counts,
        "baselineRiskDistribution": risk,
    }


def _create_template(conn, body, ok, bad):
    if not body.get("templateNameZh") or not body.get("dbType"):
        return bad(400, "模板名称与 db_type 为必填项")
    try:
        conn.execute(
            """INSERT INTO inspection_template
               (db_type, template_name_zh, template_name_en, version, description,
                is_default, is_preset)
               VALUES (?,?,?,?,?,?,0)""",
            (body["dbType"], body["templateNameZh"], body.get("templateNameEn"),
             body.get("version") or "v1", body.get("description"),
             body.get("isDefault") or 0))
        conn.commit()
    except sqlite3.IntegrityError:
        return bad(400, "同类型下模板名已存在")
    tid = conn.execute(
        "SELECT id FROM inspection_template WHERE db_type=? AND template_name_zh=?",
        (body["dbType"], body["templateNameZh"])).fetchone()[0]
    if (body.get("isDefault") or 0) == 1:
        _set_default(conn, tid, ok, bad)
    _record(conn, "inspection_template", tid, "INSERT",
            None, {"dbType": body["dbType"], "name": body["templateNameZh"]})
    return ok(_tpl(conn.execute("SELECT * FROM inspection_template WHERE id=?",
                                (tid,)).fetchone()))


def _update_template(conn, tid, body, ok, bad):
    row = conn.execute("SELECT * FROM inspection_template WHERE id=?", (tid,)).fetchone()
    if not row:
        return bad(404, "模板不存在")
    preset = row["is_preset"] == 1
    name = row["template_name_zh"] if preset else (body.get("templateNameZh") or row["template_name_zh"])
    version = row["version"] if preset else (body.get("version") or row["version"])
    conn.execute(
        """UPDATE inspection_template SET template_name_zh=?, template_name_en=?,
           version=?, description=?, updated_at=datetime('now','localtime') WHERE id=?""",
        (name, body.get("templateNameEn") or row["template_name_en"], version,
         body.get("description") or row["description"], tid))
    conn.commit()
    if body.get("isDefault") == 1:
        _set_default(conn, tid, ok, bad)
    _record(conn, "inspection_template", tid, "UPDATE",
            {"name": row["template_name_zh"]}, {"name": name})
    return ok(_tpl(conn.execute("SELECT * FROM inspection_template WHERE id=?",
                                (tid,)).fetchone()))


def _delete_template(conn, tid, force, ok, bad):
    row = conn.execute("SELECT * FROM inspection_template WHERE id=?", (tid,)).fetchone()
    if not row:
        return bad(404, "模板不存在")
    if row["is_preset"] == 1 and not force:
        return bad(400, "预置模板不允许删除（如需强制删除请传 force=true）")
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("DELETE FROM inspection_template WHERE id=?", (tid,))
    conn.commit()
    _record(conn, "inspection_template", tid, "DELETE",
            {"dbType": row["db_type"], "name": row["template_name_zh"]})
    return ok(None)


def _set_default(conn, tid, ok, bad):
    row = conn.execute("SELECT * FROM inspection_template WHERE id=?", (tid,)).fetchone()
    if not row:
        return bad(404, "模板不存在")
    conn.execute("UPDATE inspection_template SET is_default=0 WHERE db_type=?", (row["db_type"],))
    conn.execute("UPDATE inspection_template SET is_default=1 WHERE id=?", (tid,))
    conn.commit()
    _record(conn, "inspection_template", tid, "UPDATE", None, {"isDefault": 1})
    return ok(None)


def _create_chapter(conn, body, ok, bad):
    tid = body.get("templateId")
    if not tid:
        return bad(400, "templateId 不能为空")
    if not body.get("chapterTitleZh"):
        return bad(400, "章节标题不能为空")
    number = body.get("chapterNumber")
    if not number:
        number = conn.execute(
            "SELECT COALESCE(MAX(chapter_number),0)+1 FROM inspection_chapter WHERE template_id=?",
            (tid,)).fetchone()[0]
    try:
        conn.execute(
            """INSERT INTO inspection_chapter
               (template_id, chapter_number, chapter_title_zh, chapter_title_en,
                description, enabled, sort_order)
               VALUES (?,?,?,?,?,?,?)""",
            (tid, number, body["chapterTitleZh"], body.get("chapterTitleEn"),
             body.get("description"), body.get("enabled", 1),
             body.get("sortOrder", 0)))
        conn.commit()
    except sqlite3.IntegrityError:
        return bad(400, f"该模板下章节号已存在：{number}")
    row = conn.execute(
        "SELECT * FROM inspection_chapter WHERE template_id=? AND chapter_number=?",
        (tid, number)).fetchone()
    _record(conn, "inspection_chapter", row["id"], "INSERT",
            None, {"title": row["chapter_title_zh"], "number": number})
    return ok({"id": row["id"], "templateId": tid, "chapterNumber": number,
               "chapterTitleZh": row["chapter_title_zh"],
               "chapterTitleEn": row["chapter_title_en"],
               "description": row["description"], "enabled": row["enabled"],
               "sortOrder": row["sort_order"],
               # 新建章节时还没有绑定任何规则；rules 由树接口回填，
               # 这里与 Java 侧一致地给 null（不是空数组），避免两侧形状不同。
               # 不带 ruleKeys：那是种子文件专用的字段，Java 侧标了 WRITE_ONLY，
               # 接口响应里不会出现。
               "rules": None})


def _update_chapter(conn, cid, body, ok, bad):
    row = conn.execute("SELECT * FROM inspection_chapter WHERE id=?", (cid,)).fetchone()
    if not row:
        return bad(404, "章节不存在")
    conn.execute(
        """UPDATE inspection_chapter SET chapter_title_zh=?, chapter_title_en=?,
           description=?, enabled=?, sort_order=?, updated_at=datetime('now','localtime')
           WHERE id=?""",
        (body.get("chapterTitleZh") or row["chapter_title_zh"],
         body.get("chapterTitleEn") or row["chapter_title_en"],
         body.get("description") or row["description"],
         body.get("enabled") if body.get("enabled") is not None else row["enabled"],
         body.get("sortOrder") if body.get("sortOrder") is not None else row["sort_order"],
         cid))
    conn.commit()
    _record(conn, "inspection_chapter", cid, "UPDATE",
            {"title": row["chapter_title_zh"]},
            {"title": body.get("chapterTitleZh") or row["chapter_title_zh"]})
    r = conn.execute("SELECT * FROM inspection_chapter WHERE id=?", (cid,)).fetchone()
    return ok({"id": r["id"], "templateId": r["template_id"],
               "chapterNumber": r["chapter_number"],
               "chapterTitleZh": r["chapter_title_zh"],
               "chapterTitleEn": r["chapter_title_en"],
               "description": r["description"], "enabled": r["enabled"],
               "sortOrder": r["sort_order"]})


def _fetch_rule(conn, rule_id):
    return conn.execute(
        "SELECT r.*, (SELECT COUNT(*) FROM inspection_chapter_rule cr "
        "WHERE cr.rule_id = r.id) AS ref_count FROM inspection_rule r WHERE r.id=?",
        (rule_id,)).fetchone()


def _fetch_rule_by_key(conn, key):
    return conn.execute(
        "SELECT r.*, (SELECT COUNT(*) FROM inspection_chapter_rule cr "
        "WHERE cr.rule_id = r.id) AS ref_count FROM inspection_rule r WHERE r.rule_key=?",
        (key,)).fetchone()


def _list_rules(conn, db_type, category, enabled, keyword):
    sql = ("SELECT r.*, (SELECT COUNT(*) FROM inspection_chapter_rule cr "
           "WHERE cr.rule_id = r.id) AS ref_count FROM inspection_rule r WHERE 1=1")
    args = []
    if db_type:
        sql += " AND r.db_type=?"
        args.append(db_type)
    if category:
        sql += " AND r.category=?"
        args.append(category)
    if enabled not in (None, ""):
        sql += " AND r.enabled=?"
        args.append(1 if str(enabled).lower() == "true" else 0)
    if keyword:
        kw = "%" + keyword.lower() + "%"
        sql += (" AND (LOWER(r.rule_key) LIKE ? OR LOWER(r.rule_name_zh) LIKE ? "
                "OR LOWER(r.rule_sql) LIKE ?)")
        args += [kw, kw, kw]
    sql += " ORDER BY r.db_type, r.category, r.rule_key"
    return [_rule(r, True, conn) for r in conn.execute(sql, args).fetchall()]


def _rule_stats(conn):
    def one(sql, *a):
        return conn.execute(sql, a).fetchone()[0]

    by_type = {}
    for row in conn.execute("SELECT db_type, COUNT(*) FROM inspection_rule "
                            "GROUP BY db_type ORDER BY db_type"):
        by_type[row[0]] = row[1]

    by_cat = {}
    for row in conn.execute("SELECT COALESCE(category,'(未归类)'), COUNT(*) "
                            "FROM inspection_rule GROUP BY 1 ORDER BY 2 DESC, 1"):
        by_cat[row[0]] = row[1]

    return {
        "total": one("SELECT COUNT(*) FROM inspection_rule"),
        "enabled": one("SELECT COUNT(*) FROM inspection_rule WHERE enabled=1"),
        "disabled": one("SELECT COUNT(*) FROM inspection_rule WHERE enabled=0"),
        "unbound": one("SELECT COUNT(*) FROM inspection_rule r WHERE NOT EXISTS "
                       "(SELECT 1 FROM inspection_chapter_rule cr WHERE cr.rule_id = r.id)"),
        "byDbType": by_type,
        "byCategory": by_cat,
    }


def _validate_rule(body):
    if not body.get("ruleKey"):
        return "规则 key 不能为空"
    if not body.get("dbType"):
        return "db_type 不能为空"
    if not body.get("ruleNameZh"):
        return "规则名称不能为空"
    if not body.get("ruleSql"):
        return "规则 SQL 不能为空"
    return None


def _create_rule(conn, body, ok, bad):
    err = _validate_rule(body)
    if err:
        return bad(400, err)
    key = body.get("ruleKey")
    try:
        conn.execute(
            """INSERT INTO inspection_rule
               (rule_key, db_type, rule_name_zh, rule_name_en, rule_sql,
                category, enabled, source)
               VALUES (?,?,?,?,?,?,?,?)""",
            (key, body.get("dbType"), body.get("ruleNameZh"), body.get("ruleNameEn"),
             body.get("ruleSql"), body.get("category"),
             body.get("enabled", 1), body.get("source") or "CUSTOM"))
        conn.commit()
    except sqlite3.IntegrityError:
        return bad(400, f"规则 key 已存在：{key}")
    row = _fetch_rule_by_key(conn, key)
    _record(conn, "inspection_rule", row["id"], "INSERT", None,
            {"key": key, "dbType": row["db_type"]})
    return ok(_rule(row, True, conn))


def _update_rule(conn, rid, body, ok, bad):
    row = _fetch_rule(conn, rid)
    if not row:
        return bad(404, "规则不存在")

    # 预置规则的 key 与库类型不允许改：改了等于换了一条规则，引用它的章节会莫名其妙
    preset = (row["source"] or "").upper() == "PRESET"
    key = row["rule_key"] if (preset or not body.get("ruleKey")) else body["ruleKey"]
    db_type = row["db_type"] if (preset or not body.get("dbType")) else body["dbType"]
    name = body.get("ruleNameZh") or row["rule_name_zh"]
    sql = body.get("ruleSql") or row["rule_sql"]

    if not sql:
        return bad(400, "规则 SQL 不能为空")
    if not name:
        return bad(400, "规则名称不能为空")

    try:
        conn.execute(
            """UPDATE inspection_rule SET rule_key=?, db_type=?, rule_name_zh=?,
               rule_name_en=?, rule_sql=?, category=?, enabled=?,
               updated_at=datetime('now','localtime') WHERE id=?""",
            (key, db_type, name,
             body.get("ruleNameEn") if body.get("ruleNameEn") is not None else row["rule_name_en"],
             sql,
             body.get("category") if body.get("category") is not None else row["category"],
             body.get("enabled") if body.get("enabled") is not None else row["enabled"],
             rid))
        conn.commit()
    except sqlite3.IntegrityError:
        return bad(400, f"规则 key 已存在：{key}")

    refs = conn.execute("SELECT COUNT(*) FROM inspection_chapter_rule WHERE rule_id=?",
                        (rid,)).fetchone()[0]
    _record(conn, "inspection_rule", rid, "UPDATE",
            {"key": row["rule_key"], "enabled": row["enabled"]},
            {"key": key,
             "enabled": body.get("enabled") if body.get("enabled") is not None else row["enabled"],
             "refs": refs})
    return ok(_rule(_fetch_rule(conn, rid), True, conn))


def _delete_rule(conn, rid, force, ok, bad):
    row = _fetch_rule(conn, rid)
    if not row:
        return bad(404, "规则不存在")

    used = _used_by(conn, rid)
    if used and str(force).lower() != "true":
        return bad(400, f"该规则正被 {len(used)} 个章节引用（{'、'.join(used)}），"
                        f"删除会影响这些模板的报告。如确认删除请传 force=true")

    conn.execute("DELETE FROM inspection_rule WHERE id=?", (rid,))
    conn.commit()
    _record(conn, "inspection_rule", rid, "DELETE",
            {"key": row["rule_key"], "usedBy": ",".join(used)})
    return ok(None)


# ---------------- 章节 ↔ 规则 绑定 ----------------

def _bind_rule(conn, chapter_id, body, ok, bad):
    rid = body.get("ruleId")
    if not rid:
        return bad(400, "ruleId 不能为空")
    ch = conn.execute("SELECT id FROM inspection_chapter WHERE id=?", (chapter_id,)).fetchone()
    if not ch:
        return bad(400, f"章节不存在: {chapter_id}")
    rule = conn.execute("SELECT * FROM inspection_rule WHERE id=?", (rid,)).fetchone()
    if not rule:
        return bad(400, f"规则不存在: {rid}")

    if body.get("sortOrder") is not None:
        order = body["sortOrder"]
    else:
        row = conn.execute("SELECT COALESCE(MAX(sort_order), -1) FROM inspection_chapter_rule "
                           "WHERE chapter_id=?", (chapter_id,)).fetchone()
        order = (row[0] if row and row[0] is not None else -1) + 1
    try:
        conn.execute("INSERT INTO inspection_chapter_rule (chapter_id, rule_id, sort_order) "
                     "VALUES (?,?,?)", (chapter_id, rid, order))
        conn.commit()
    except sqlite3.IntegrityError:
        return bad(400, f"该章节已引用规则：{rule['rule_key']}")
    _record(conn, "inspection_chapter_rule", chapter_id, "INSERT", None,
            {"chapterId": chapter_id, "rule": rule["rule_key"], "sortOrder": order})
    return ok(None)


def _unbind_rule(conn, chapter_id, rule_id, ok, bad):
    rule = conn.execute("SELECT * FROM inspection_rule WHERE id=?", (rule_id,)).fetchone()
    cur = conn.execute("DELETE FROM inspection_chapter_rule WHERE chapter_id=? AND rule_id=?",
                       (chapter_id, rule_id))
    if cur.rowcount == 0:
        return bad(400, "该章节未引用此规则")
    conn.commit()
    _record(conn, "inspection_chapter_rule", chapter_id, "DELETE",
            {"chapterId": chapter_id,
             "rule": rule["rule_key"] if rule else str(rule_id)})
    return ok(None)


def _reorder_chapter_rules(conn, chapter_id, body, ok, bad):
    ids = body.get("ruleIds")
    if not isinstance(ids, list):
        return bad(400, "ruleIds 必须是数组")
    n = 0
    for order, rid in enumerate(ids):
        cur = conn.execute("UPDATE inspection_chapter_rule SET sort_order=? "
                           "WHERE chapter_id=? AND rule_id=?", (order, chapter_id, rid))
        n += cur.rowcount
    conn.commit()
    _record(conn, "inspection_chapter_rule", chapter_id, "UPDATE", None,
            {"chapterId": chapter_id, "reordered": n})
    return ok({"chapterId": chapter_id, "reordered": n})


_TEST_UNAVAILABLE = (
    "预览服务不具备数据库连接能力，无法试跑规则。"
    "请启动 Java 后端（JDK 17 + Maven）以获得真实的规则试跑结果。"
)


def _test_rule_stub(conn, rid, body, ok, bad):
    """
    规则试跑在预览服务下恒定返回 501。

    与报告导出同一条原则：试跑的意义就是拿真实数据库撞一次，这里没有 JDBC 层，
    伪造一份「看起来跑通了」的列名与结果比明确的 501 危险得多。
    校验顺序与 Java 侧一致：先 404（规则不存在），再 400（缺 dataSourceId），最后 501。
    """
    if not _fetch_rule(conn, rid):
        return bad(404, "规则不存在")
    if not body or body.get("dataSourceId") is None:
        return bad(400, "dataSourceId 不能为空")
    return (501, {"code": 501, "message": _TEST_UNAVAILABLE, "data": None})


def _create_baseline(conn, body, ok, bad):
    err = _validate_baseline(body)
    if err:
        return bad(400, err)
    try:
        conn.execute(
            """INSERT INTO inspection_baseline
               (db_type, param_name, query_sql, operator, expected_value,
                expected_value_min, expected_value_max, risk_level,
                description_zh, description_en, enabled)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            (body["dbType"], body["paramName"], body.get("querySql"),
             body["operator"], body.get("expectedValue"),
             body.get("expectedValueMin"), body.get("expectedValueMax"),
             body["riskLevel"], body.get("descriptionZh"),
             body.get("descriptionEn"), body.get("enabled", 1)))
        conn.commit()
    except sqlite3.IntegrityError:
        return bad(400, f"该类型下参数基线已存在：{body['dbType']} / {body['paramName']}")
    r = conn.execute(
        "SELECT * FROM inspection_baseline WHERE db_type=? AND param_name=?",
        (body["dbType"], body["paramName"])).fetchone()
    _record(conn, "inspection_baseline", r["id"], "INSERT",
            None, {"dbType": r["db_type"], "param": r["param_name"]})
    return ok(_baseline(r))


def _update_baseline(conn, bid, body, ok, bad):
    row = conn.execute("SELECT * FROM inspection_baseline WHERE id=?", (bid,)).fetchone()
    if not row:
        return bad(404, "基线不存在")
    merged = dict(row)
    for k_src, k_dst in [("operator", "operator"), ("expectedValue", "expected_value"),
                         ("expectedValueMin", "expected_value_min"),
                         ("expectedValueMax", "expected_value_max"),
                         ("riskLevel", "risk_level"), ("querySql", "query_sql"),
                         ("descriptionZh", "description_zh"),
                         ("descriptionEn", "description_en"), ("enabled", "enabled")]:
        if body.get(k_src) is not None:
            merged[k_dst] = body[k_src]
    err = _validate_baseline({
        "dbType": merged["db_type"], "paramName": merged["param_name"],
        "operator": merged["operator"], "riskLevel": merged["risk_level"],
        "expectedValueMin": merged["expected_value_min"],
        "expectedValueMax": merged["expected_value_max"]})
    if err:
        return bad(400, err)
    conn.execute(
        """UPDATE inspection_baseline SET query_sql=?, operator=?, expected_value=?,
           expected_value_min=?, expected_value_max=?, risk_level=?, description_zh=?,
           description_en=?, enabled=?, updated_at=datetime('now','localtime') WHERE id=?""",
        (merged["query_sql"], merged["operator"], merged["expected_value"],
         merged["expected_value_min"], merged["expected_value_max"],
         merged["risk_level"], merged["description_zh"], merged["description_en"],
         merged["enabled"], bid))
    conn.commit()
    _record(conn, "inspection_baseline", bid, "UPDATE",
            {"param": row["param_name"], "expected": row["expected_value"]},
            {"param": row["param_name"], "expected": merged["expected_value"]})
    return ok(_baseline(conn.execute("SELECT * FROM inspection_baseline WHERE id=?",
                                     (bid,)).fetchone()))


def _validate_baseline(b):
    if not b.get("dbType") or not b.get("paramName"):
        return "db_type 与 param_name 为必填项"
    op = (b.get("operator") or "=").upper()
    if op not in VALID_OPERATORS:
        return f"operator 非法：{op}，允许值 {VALID_OPERATORS}"
    if op == "BETWEEN" and (b.get("expectedValueMin") is None
                            or b.get("expectedValueMax") is None):
        return "operator=BETWEEN 时必须提供 expectedValueMin 与 expectedValueMax"
    lv = (b.get("riskLevel") or "MEDIUM").upper()
    if lv not in VALID_RISK:
        return f"risk_level 非法：{lv}，允许值 {VALID_RISK}"
    return None


def _check_many(conn, body, ok, bad):
    db_type = (body or {}).get("dbType")
    values = (body or {}).get("values") or {}
    rows = conn.execute(
        "SELECT * FROM inspection_baseline WHERE db_type=? ORDER BY param_name",
        (db_type,)).fetchall()
    if not rows:
        return bad(404, f"该类型暂无基线配置：{db_type}")

    results, n_pass, n_fail, n_unchecked = [], 0, 0, 0
    fail_by_risk = {}
    for r in rows:
        if r["enabled"] == 0:
            continue
        d = dict(r)
        res = check_baseline(d, values.get(d["param_name"]))
        results.append(res)
        if not res["checked"]:
            n_unchecked += 1
        elif res["pass"]:
            n_pass += 1
        else:
            n_fail += 1
            fail_by_risk[res["riskLevel"] or "UNKNOWN"] = \
                fail_by_risk.get(res["riskLevel"] or "UNKNOWN", 0) + 1

    return ok({
        "dbType": db_type, "total": len(results), "pass": n_pass,
        "fail": n_fail, "unchecked": n_unchecked,
        "compliancePct": round(n_pass * 100.0 / max(n_pass + n_fail, 1), 1),
        "failByRisk": fail_by_risk, "results": results,
    })


# ---------------------------------------------------------------------------
# 巡检执行（预览实现）
# ---------------------------------------------------------------------------

PREVIEW_NO_DB = ("预览服务不具备数据库连接能力，未执行任何规则。"
                 "请启动 Java 后端（JDK 17 + Maven）以获得真实巡检结果。")

PREVIEW_NO_COLLECT = "预览服务不具备数据库连接能力，未采集参数值。"


def _run_json(r):
    return {
        "id": r["id"],
        "dataSourceId": r["data_source_id"],
        "dataSourceName": r["data_source_name"],
        "dbType": r["db_type"],
        "templateId": r["template_id"],
        "templateName": r["template_name"],
        "status": r["status"],
        "triggerSource": r["trigger_source"],
        "totalQueries": r["total_queries"],
        "okQueries": r["ok_queries"],
        "failedQueries": r["failed_queries"],
        "totalBaselines": r["total_baselines"],
        "baselinesPass": r["baselines_pass"],
        "baselinesFail": r["baselines_fail"],
        "baselinesUnchecked": r["baselines_unchecked"],
        "compliancePct": r["compliance_pct"],
        "riskSummary": json.loads(r["risk_summary"]) if r["risk_summary"] else {},
        "errorMsg": r["error_msg"],
        "startedAt": r["started_at"],
        "finishedAt": r["finished_at"],
        "durationMs": r["duration_ms"],
        "executedBy": r["executed_by"],
    }


def _runq_json(r):
    return {
        "id": r["id"], "runId": r["run_id"],
        "chapterNumber": r["chapter_number"], "chapterTitle": r["chapter_title"],
        "queryKey": r["query_key"], "querySql": r["query_sql"],
        "descriptionZh": r["description_zh"], "status": r["status"],
        "elapsedMs": r["elapsed_ms"], "rowCount": r["row_count"],
        "columns": json.loads(r["columns_json"]) if r["columns_json"] else None,
        "rows": json.loads(r["rows_json"]) if r["rows_json"] else None,
        "truncated": r["truncated"], "errorMsg": r["error_msg"],
    }


def _runbl_json(r):
    return {
        "id": r["id"], "runId": r["run_id"],
        "paramName": r["param_name"], "operator": r["operator"],
        "expectedValue": r["expected_value"], "actualValue": r["actual_value"],
        "sampleCount": r["sample_count"], "violationCount": r["violation_count"],
        "riskLevel": r["risk_level"], "isPass": r["is_pass"], "isChecked": r["is_checked"],
        "message": r["message"], "descriptionZh": r["description_zh"],
        "elapsedMs": r["elapsed_ms"], "errorMsg": r["error_msg"],
    }


def _run_detail(conn, run_id, ok, bad):
    row = conn.execute("SELECT * FROM inspection_run WHERE id=?", (run_id,)).fetchone()
    if not row:
        return bad(404, "巡检记录不存在: %d" % run_id)
    d = _run_json(row)
    d["queries"] = [_runq_json(x) for x in conn.execute(
        "SELECT * FROM inspection_run_query WHERE run_id=? ORDER BY chapter_number, id",
        (run_id,)).fetchall()]
    d["baselines"] = [_runbl_json(x) for x in conn.execute(
        "SELECT * FROM inspection_run_baseline WHERE run_id=? ORDER BY id",
        (run_id,)).fetchall()]
    return ok(d)


# ---------------------------------------------------------------------------
# 报告导出（GET /api/inspection/runs/{id}/export）
# ---------------------------------------------------------------------------

#: 预览服务对本接口的答复。刻意不生成任何报告文件。
_EXPORT_UNAVAILABLE = (
    "预览服务不生成报告文件：Word/PDF/HTML 导出需要 Java 后端的 POI（真 .docx）、"
    "openhtmltopdf（HTML→PDF）与中文字体嵌入，本服务只有 Python 标准库，不具备该能力。"
    "请启动 Java 后端（默认 127.0.0.1:8080）后使用本接口。"
    "这里刻意返回 501 而不是伪造一个 .docx/.pdf —— 一份内容对不上的报告文件"
    "比一个明确的「不支持」难排查得多。"
)


def _export_stub(conn, run_id, fmt, ok, bad):
    """
    导出接口的预览实现：**如实报告不可用**，不伪造文件。

    校验顺序与 Java 侧 InspectionController#exportRun 完全一致：
    先查记录是否存在（404），再校验格式（400），最后才是能力缺失（501）。
    顺序必须一致，否则同一个请求在两套实现下会得到不同的状态码，
    parity_check 会把这种差异当成契约漂移报出来 —— 而且它确实就是漂移。
    """
    row = conn.execute("SELECT id FROM inspection_run WHERE id=?", (run_id,)).fetchone()
    if not row:
        return bad(404, "巡检记录不存在: %d" % run_id)

    fmt = (fmt or "html").strip().lower()
    if fmt not in ("html", "htm", "word", "docx", "pdf"):
        return bad(400, "不支持的导出格式: %s（可选 html / word / pdf）" % fmt)

    # 第三个元素是 Content-Type —— devserver._send 支持三元组，用来覆盖默认的 application/json。
    # 这里仍用 JSON，前端读 message 就能给出可读的失败提示。
    return (501, {"code": 501, "message": _EXPORT_UNAVAILABLE, "data": None})


def _run_inspection(conn, body, ok, bad):
    """
    执行一次巡检。

    真做：数据源与模板解析、章节/规则按序遍历、统计聚合、三张表落库、
          参数校验与错误码。
    不做：连接目标数据库、执行规则 SQL、采集基线实际值 —— 见模块开头的说明。

    与 Java 侧的差异只在「数据」不在「契约」：
      · Java 连不上库时不落任何子表行；这里为便于界面开发，仍把规则与基线
        逐条列出并统一标记为「未执行 / 未采集」。
      · Java 的 status 为 FAILED、errorMsg 写明原因，这里完全一致。
    """
    body = body or {}
    ds_id = body.get("dataSourceId")
    if ds_id is None:
        return bad(400, "dataSourceId 不能为空")
    try:
        ds_id = int(ds_id)
    except (TypeError, ValueError):
        return bad(400, "dataSourceId 必须是数字: %r" % (body.get("dataSourceId"),))

    try:
        ds = conn.execute("SELECT * FROM data_source WHERE id=?", (ds_id,)).fetchone()
    except sqlite3.OperationalError:
        ds = None
    if not ds:
        return bad(400, "数据源不存在: %d" % ds_id)

    only_enabled = body.get("onlyEnabled")
    only_enabled = True if only_enabled is None else bool(only_enabled)
    include_baselines = body.get("includeBaselines")
    include_baselines = True if include_baselines is None else bool(include_baselines)
    chapter_filter = set(body.get("chapterNumbers") or [])
    executed_by = body.get("executedBy") or "system"

    tpl_id = body.get("templateId")
    if tpl_id is not None:
        try:
            tpl_id = int(tpl_id)
        except (TypeError, ValueError):
            return bad(400, "templateId 必须是数字: %r" % (body.get("templateId"),))
        tpl = conn.execute("SELECT * FROM inspection_template WHERE id=?", (tpl_id,)).fetchone()
        if not tpl:
            return bad(400, "巡检模板不存在: %d" % tpl_id)
    else:
        tpl = conn.execute(
            "SELECT * FROM inspection_template WHERE db_type=? AND is_default=1 LIMIT 1",
            (ds["db_type"],)).fetchone()
        if not tpl:
            return bad(400, "库类型 %s 没有默认巡检模板，请显式指定 templateId" % ds["db_type"])

    started = datetime.now().isoformat(timespec="seconds")
    t0 = time.time()

    # ---- 规则：按章节号 → 绑定 sort_order 顺序逐条列出 ----
    # 规则正文来自规则库（inspection_rule），章节只是通过绑定表引用它
    rule_rows = []
    for ch in conn.execute(
            "SELECT * FROM inspection_chapter WHERE template_id=? "
            "ORDER BY chapter_number, sort_order", (tpl["id"],)).fetchall():
        if chapter_filter and ch["chapter_number"] not in chapter_filter:
            continue
        chapter_enabled = ch["enabled"] != 0
        for q in conn.execute(
                "SELECT r.* FROM inspection_chapter_rule cr "
                "JOIN inspection_rule r ON r.id = cr.rule_id "
                "WHERE cr.chapter_id=? ORDER BY cr.sort_order, cr.id",
                (ch["id"],)).fetchall():
            rule_enabled = q["enabled"] != 0
            reason = PREVIEW_NO_DB
            if only_enabled and not (chapter_enabled and rule_enabled):
                reason = "所属章节已停用" if not chapter_enabled else "规则已停用"
            rule_rows.append({
                "chapterNumber": ch["chapter_number"],
                "chapterTitle": ch["chapter_title_zh"],
                "queryKey": q["rule_key"],
                "querySql": q["rule_sql"],
                "descriptionZh": q["rule_name_zh"],
                "status": "SKIPPED",
                "elapsedMs": 0,
                "rowCount": 0,
                "columns": None,
                "rows": None,
                "truncated": 0,
                "errorMsg": reason,
            })

    # ---- 基线：全部「未采集」，不计入合规率分母 ----
    baseline_rows = []
    if include_baselines:
        for bl in conn.execute(
                "SELECT * FROM inspection_baseline WHERE db_type=? AND enabled=1 "
                "ORDER BY param_name", (ds["db_type"],)).fetchall():
            expected = ("[%s, %s]" % (bl["expected_value_min"], bl["expected_value_max"])
                        if (bl["operator"] or "").upper() == "BETWEEN"
                        else (bl["expected_value"] or ""))
            baseline_rows.append({
                "paramName": bl["param_name"],
                "operator": bl["operator"],
                "expectedValue": expected,
                "actualValue": None,
                "sampleCount": 0,
                "violationCount": 0,
                "riskLevel": bl["risk_level"],
                "isPass": 0,
                "isChecked": 0,
                "message": PREVIEW_NO_COLLECT,
                "descriptionZh": bl["description_zh"],
                "elapsedMs": 0,
                "errorMsg": None,
            })

    duration_ms = int((time.time() - t0) * 1000)
    run = {
        "dataSourceId": ds["id"],
        "dataSourceName": ds["name"],
        "dbType": ds["db_type"],
        "templateId": tpl["id"],
        "templateName": tpl["template_name_zh"],
        "status": "FAILED",
        "triggerSource": "MANUAL",
        "totalQueries": 0,
        "okQueries": 0,
        "failedQueries": 0,
        "totalBaselines": len(baseline_rows),
        "baselinesPass": 0,
        "baselinesFail": 0,
        "baselinesUnchecked": len(baseline_rows),
        "compliancePct": 0.0,
        "riskSummary": {},
        "errorMsg": PREVIEW_NO_DB,
        "startedAt": started,
        "finishedAt": datetime.now().isoformat(timespec="seconds"),
        "durationMs": duration_ms,
        "executedBy": executed_by,
    }

    cur = conn.execute(
        """INSERT INTO inspection_run
           (data_source_id, data_source_name, db_type, template_id, template_name,
            status, trigger_source, total_queries, ok_queries, failed_queries,
            total_baselines, baselines_pass, baselines_fail, baselines_unchecked,
            compliance_pct, risk_summary, error_msg,
            started_at, finished_at, duration_ms, executed_by)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (run["dataSourceId"], run["dataSourceName"], run["dbType"],
         run["templateId"], run["templateName"], run["status"], run["triggerSource"],
         run["totalQueries"], run["okQueries"], run["failedQueries"],
         run["totalBaselines"], run["baselinesPass"], run["baselinesFail"],
         run["baselinesUnchecked"], run["compliancePct"],
         json.dumps(run["riskSummary"], ensure_ascii=False), run["errorMsg"],
         run["startedAt"], run["finishedAt"], run["durationMs"], run["executedBy"]))
    run_id = cur.lastrowid

    for r in rule_rows:
        conn.execute(
            """INSERT INTO inspection_run_query
               (run_id, chapter_number, chapter_title, query_key, query_sql, description_zh,
                status, elapsed_ms, row_count, columns_json, rows_json, truncated, error_msg)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (run_id, r["chapterNumber"], r["chapterTitle"], r["queryKey"], r["querySql"],
             r["descriptionZh"], r["status"], r["elapsedMs"], r["rowCount"],
             None, None, r["truncated"], r["errorMsg"]))

    for bl in baseline_rows:
        conn.execute(
            """INSERT INTO inspection_run_baseline
               (run_id, param_name, operator, expected_value, actual_value,
                sample_count, violation_count, risk_level, is_pass, is_checked,
                message, description_zh, elapsed_ms, error_msg)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (run_id, bl["paramName"], bl["operator"], bl["expectedValue"], bl["actualValue"],
             bl["sampleCount"], bl["violationCount"], bl["riskLevel"], bl["isPass"],
             bl["isChecked"], bl["message"], bl["descriptionZh"], bl["elapsedMs"],
             bl["errorMsg"]))
    conn.commit()

    # 回读一次，保证返回的就是库里存着的那份（含自增 id 与子表行）
    return _run_detail(conn, run_id, ok, bad)
