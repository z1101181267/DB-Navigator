#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DB Navigator · 巡检配置 API 契约测试

对任意实现了同一套 REST 契约的后端执行全量往返测试：
  · 模板 / 章节 / 规则 / 基线 的 CRUD
  · 唯一约束与参数校验的拒绝行为
  · 两级级联删除
  · 预置模板保护
  · 单条与批量基线校验（含未采集语义）

用法：
    python scripts/api_test.py                        # 默认 http://127.0.0.1:8080
    python scripts/api_test.py http://127.0.0.1:9090  # 指定后端

退出码：0 = 全部通过，1 = 有失败项
"""

import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")

passed, failed = [], []


def call(method, path, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw}
    except Exception as e:
        return 0, {"error": f"{type(e).__name__}: {e}"}


def check(name, cond, extra=None):
    (passed if cond else failed).append(name)
    mark = "\u2714" if cond else "\u2718"
    line = f"  {mark} {name}"
    if not cond and extra is not None:
        line += f"   {str(extra)[:240]}"
    print(line)


def call_raw(method, path):
    """
    同 call()，但不解析 JSON —— 报告导出返回的是二进制（.docx / .pdf），
    硬走 json.loads 只会把整个文件读进内存再抛 ValueError。
    返回 (status, headers, body_bytes)。
    """
    req = urllib.request.Request(BASE + path, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers or {}), e.read()
    except Exception as e:
        return 0, {}, f"{type(e).__name__}: {e}".encode("utf-8")


def main():
    print(f"目标后端：{BASE}\n")

    # ---------------- 总览 ----------------
    print("\u2500\u2500 总览 \u2500\u2500")
    st, r = call("GET", "/api/inspection/summary")
    d = r.get("data") or {}
    check("GET /summary 可用", st == 200, r)
    check("模板数 = 6", d.get("templateCount") == 6, d.get("templateCount"))
    check("章节数 = 113", d.get("totalChapters") == 113, d.get("totalChapters"))
    check("规则数 = 160", d.get("totalQueries") == 160, d.get("totalQueries"))
    check("基线数 = 88", sum((d.get("baselineCountByType") or {}).values()) == 88,
          d.get("baselineCountByType"))
    check("基线风险分布 CRITICAL=1", (d.get("baselineRiskDistribution") or {}).get("CRITICAL") == 1,
          d.get("baselineRiskDistribution"))

    # ---------------- 模板 CRUD ----------------
    print("\n\u2500\u2500 模板 CRUD \u2500\u2500")
    st, r = call("POST", "/api/inspection/templates", {
        "dbType": "mysql", "templateNameZh": "__契约测试模板__",
        "templateNameEn": "Contract Test", "version": "v9", "description": "自动化测试用"})
    check("创建模板", st == 200 and (r.get("data") or {}).get("id"), r)
    tid = (r.get("data") or {}).get("id")
    if not tid:
        return finish()

    st, r = call("GET", f"/api/inspection/templates/{tid}/tree")
    check("新模板章节为空", st == 200 and (r.get("data") or {}).get("chapters") == [], r)

    st, r = call("PUT", f"/api/inspection/templates/{tid}", {
        "templateNameZh": "__契约测试模板_改__", "version": "v10", "description": "已修改"})
    check("更新模板", st == 200 and (r.get("data") or {}).get("version") == "v10", r)

    st, r = call("GET", f"/api/inspection/templates?dbType=mysql")
    check("按类型过滤模板", st == 200 and len(r.get("data") or []) >= 2, r)

    # ---------------- 章节 CRUD ----------------
    print("\n\u2500\u2500 章节 CRUD \u2500\u2500")
    st, r = call("POST", "/api/inspection/chapters", {
        "templateId": tid, "chapterTitleZh": "测试章节", "chapterTitleEn": "Test Chapter",
        "description": "章节说明", "sortOrder": 0, "enabled": 1})
    check("创建章节（自动编号为 1）", st == 200 and (r.get("data") or {}).get("chapterNumber") == 1, r)
    cid = (r.get("data") or {}).get("id")

    st, r = call("POST", "/api/inspection/chapters", {
        "templateId": tid, "chapterNumber": 1, "chapterTitleZh": "重复编号"})
    check("重复章节号被拒绝(400)", st == 400, r)

    st, r = call("PUT", f"/api/inspection/chapters/{cid}",
                 {"chapterTitleZh": "测试章节_改", "enabled": 0})
    check("更新章节", st == 200 and (r.get("data") or {}).get("chapterTitleZh") == "测试章节_改"
          and (r.get("data") or {}).get("enabled") == 0, r)

    # ---------------- 规则 CRUD ----------------
    print("\n\u2500\u2500 规则 CRUD \u2500\u2500")
    st, r = call("POST", "/api/inspection/queries", {
        "chapterId": cid, "key": "contract_q1",
        "sql": "SHOW GLOBAL STATUS LIKE 'Threads_connected';",
        "desc_zh": "当前连接数", "desc_en": "Threads connected", "enabled": 1, "sortOrder": 0})
    check("创建规则", st == 200 and (r.get("data") or {}).get("key") == "contract_q1", r)
    qid = (r.get("data") or {}).get("id")

    st, r = call("POST", "/api/inspection/queries", {
        "chapterId": cid, "key": "contract_q1", "sql": "SELECT 1"})
    check("同章节重复 key 被拒绝(400)", st == 400, r)

    st, r = call("PUT", f"/api/inspection/queries/{qid}", {
        "sql": "SELECT 1;", "desc_zh": "改后描述", "desc_en": "", "enabled": 0, "sortOrder": 3})
    check("更新规则", st == 200 and (r.get("data") or {}).get("sql") == "SELECT 1;"
          and (r.get("data") or {}).get("enabled") == 0, r)

    st, r = call("GET", f"/api/inspection/templates/{tid}/tree?onlyEnabled=true")
    check("onlyEnabled=true 过滤掉停用项", st == 200 and (r.get("data") or {}).get("chapters") == [], r)

    # ---------------- 基线 CRUD ----------------
    print("\n\u2500\u2500 基线 CRUD \u2500\u2500")
    st, r = call("POST", "/api/inspection/baselines", {
        "dbType": "mysql", "paramName": "__CONTRACT_PARAM__", "operator": "<=",
        "expectedValue": "5", "riskLevel": "HIGH",
        "querySql": "SELECT 1", "descriptionZh": "契约测试基线", "enabled": 1})
    check("创建基线", st == 200, r)
    bid = (r.get("data") or {}).get("id")

    st, r = call("POST", "/api/inspection/baselines", {
        "dbType": "mysql", "paramName": "__BAD_OP__", "operator": "~~", "expectedValue": "1"})
    check("非法比较符被拒绝(400)", st == 400, r)

    st, r = call("POST", "/api/inspection/baselines", {
        "dbType": "mysql", "paramName": "__BAD_BETWEEN__", "operator": "BETWEEN",
        "riskLevel": "LOW"})
    check("BETWEEN 缺区间被拒绝(400)", st == 400, r)

    st, r = call("POST", "/api/inspection/baselines", {
        "dbType": "mysql", "paramName": "__BAD_RISK__", "operator": "=",
        "expectedValue": "1", "riskLevel": "URGENT"})
    check("非法风险等级被拒绝(400)", st == 400, r)

    # ---------------- 基线校验 ----------------
    print("\n\u2500\u2500 基线校验 \u2500\u2500")
    st, r = call("POST", f"/api/inspection/baselines/{bid}/check", {"actualValue": "3"})
    check("单条：3 <= 5 → 合规", st == 200 and (r.get("data") or {}).get("pass") is True
          and (r.get("data") or {}).get("checked") is True, r)

    st, r = call("POST", f"/api/inspection/baselines/{bid}/check", {"actualValue": "9"})
    check("单条：9 <= 5 → 不合规", st == 200 and (r.get("data") or {}).get("pass") is False, r)

    st, r = call("POST", f"/api/inspection/baselines/{bid}/check", {})
    check("单条：未传值 → 未采集(checked=false)",
          st == 200 and (r.get("data") or {}).get("checked") is False, r)

    st, r = call("POST", f"/api/inspection/baselines/{bid}/check", {"actualValue": ""})
    check("单条：空串是合法采集值(checked=true)",
          st == 200 and (r.get("data") or {}).get("checked") is True, r)

    st, r = call("POST", "/api/inspection/baselines/check", {
        "dbType": "mysql",
        "values": {
            "__CONTRACT_PARAM__": "3",   # <= 5  → 合规
            "max_connections": "10",     # >= 500 → 不合规
            "local_infile": "ON",        # = OFF  → 不合规
        }})
    d = r.get("data") or {}
    check("批量校验返回汇总字段", st == 200 and "compliancePct" in d and d.get("total", 0) > 0, r)
    check("批量校验识别未采集项", d.get("unchecked", 0) > 0, d.get("unchecked"))
    check("批量校验识别不合规项", d.get("fail", 0) == 2, d.get("fail"))
    check("批量校验识别合规项", d.get("pass", 0) == 1, d.get("pass"))
    check("不合规项按风险等级归集", (d.get("failByRisk") or {}).get("HIGH", 0) >= 1, d.get("failByRisk"))
    check("批量校验合规率在 0-100 之间",
          isinstance(d.get("compliancePct"), (int, float)) and 0 <= d["compliancePct"] <= 100,
          d.get("compliancePct"))

    # ---------------- 基线算子语义 ----------------
    print("\n\u2500\u2500 基线算子语义（含语义枚举序） \u2500\u2500")
    for param, op, exp, actual, want, label in [
        ("__SEM_LIKE__", "LIKE", "ENABLED", "ENABLED", True, "LIKE 子串命中"),
        ("__SEM_LIKE2__", "LIKE", "ENABLED", "DISABLED", False, "LIKE 子串未命中"),
        ("__SEM_NE__", "!=", "OFF", "ON", True, "!= 不相等 → 合规"),
        ("__SEM_NE2__", "!=", "OFF", "OFF", False, "!= 相等 → 不合规"),
        ("__SEM_SIZE__", "<=", "128MB", "64MB", True, "容量串 64MB <= 128MB"),
        ("__SEM_SIZE2__", "<=", "128MB", "256MB", False, "容量串 256MB <= 128MB"),
        ("__SEM_RANK__", ">=", "replica", "logical", True, "语义序 logical >= replica"),
        ("__SEM_RANK2__", ">=", "replica", "minimal", False, "语义序 minimal >= replica"),
        ("__SEM_RM__", "=", "full", "FULL", True, "枚举忽略大小写 full = FULL"),
    ]:
        st, r = call("POST", "/api/inspection/baselines", {
            "dbType": "mysql", "paramName": param, "operator": op,
            "expectedValue": exp, "riskLevel": "LOW", "enabled": 1})
        tmp = (r.get("data") or {}).get("id")
        st, r = call("POST", f"/api/inspection/baselines/{tmp}/check", {"actualValue": actual})
        check(label, st == 200 and (r.get("data") or {}).get("pass") is want,
              (r.get("data") or {}).get("message"))
        call("DELETE", f"/api/inspection/baselines/{tmp}")

    st, r = call("POST", "/api/inspection/baselines", {
        "dbType": "mysql", "paramName": "__SEM_BETWEEN__", "operator": "BETWEEN",
        "expectedValueMin": 10, "expectedValueMax": 20, "riskLevel": "LOW", "enabled": 1})
    tmp = (r.get("data") or {}).get("id")
    for actual, want, label in [("15", True, "BETWEEN 区间内 → 合规"),
                                ("25", False, "BETWEEN 区间外 → 不合规")]:
        st, r = call("POST", f"/api/inspection/baselines/{tmp}/check", {"actualValue": actual})
        check(label, st == 200 and (r.get("data") or {}).get("pass") is want,
              (r.get("data") or {}).get("message"))
    call("DELETE", f"/api/inspection/baselines/{tmp}")

    # ---------------- 基线更新与批量启停 ----------------
    print("\n\u2500\u2500 基线更新与批量启停 \u2500\u2500")
    st, r = call("PUT", f"/api/inspection/baselines/{bid}",
                 {"expectedValue": "10", "enabled": 0})
    check("更新基线", st == 200 and (r.get("data") or {}).get("expectedValue") == "10"
          and (r.get("data") or {}).get("enabled") == 0, r)

    st, r = call("POST", "/api/inspection/baselines/enabled?dbType=mysql&enabled=true")
    check("批量启用基线", st == 200 and (r.get("data") or {}).get("affected", 0) > 0, r)

    st, r = call("POST", "/api/inspection/baselines/enabled?dbType=mysql&enabled=false")
    check("批量停用基线", st == 200 and (r.get("data") or {}).get("affected", 0) > 0, r)
    call("POST", "/api/inspection/baselines/enabled?dbType=mysql&enabled=true")

    # ---------------- 历史 ----------------
    print("\n\u2500\u2500 变更历史 \u2500\u2500")
    st, r = call("GET", "/api/inspection/history?limit=20")
    check("读取变更历史", st == 200 and len(r.get("data") or []) > 0, r)

    # ---------------- 默认模板 ----------------
    print("\n\u2500\u2500 默认模板与预置保护 \u2500\u2500")
    st, r = call("GET", "/api/inspection/templates/default/mysql/tree")
    check("读取默认模板树", st == 200 and len((r.get("data") or {}).get("chapters") or []) == 21, r)

    st, r = call("GET", "/api/inspection/templates?dbType=oracle")
    oid = (r.get("data") or [{}])[0].get("id")
    st, r = call("DELETE", f"/api/inspection/templates/{oid}")
    check("预置模板默认拒绝删除(400)", st == 400, r)

    # ---------------- 级联删除 ----------------
    print("\n\u2500\u2500 级联删除 \u2500\u2500")
    st, r = call("DELETE", f"/api/inspection/templates/{tid}?force=true")
    check("删除模板", st == 200, r)
    st, r = call("GET", f"/api/inspection/templates/{tid}")
    check("模板已不存在(404)", st == 404, r)
    st, r = call("GET", f"/api/inspection/chapters/{cid}/queries")
    check("章节级联删除后规则不可见", st == 200 and (r.get("data") or []) == [], r)
    st, r = call("DELETE", f"/api/inspection/baselines/{bid}")
    check("删除测试基线", st == 200, r)

    # ---------------- 巡检执行 ----------------
    run_section(call, check)

    return finish()


def run_section(call, check):
    """
    巡检执行接口的契约校验。

    刻意只断言「结构 + 内部一致性 + 状态码」，不断言具体数值 ——
    因为两套后端的能力边界不同：
      · Java  能真实连库，会产出 SUCCESS / PARTIAL 与真实结果集；
      · 预览服务没有 JDBC 层，如实产出 FAILED（不伪造报告）。
    两者都必须满足同一套字段契约与统计恒等式，这正是本节的检查目标。
    """
    print("\n\u2500\u2500 巡检执行 \u2500\u2500")

    # 找一个 h2 自检数据源；没有就临时建一个（跑完删掉，不留夹具）
    st, r = call("GET", "/api/datasources")
    ds = next((x for x in (r.get("data") or []) if x.get("dbType") == "h2"), None)
    tmp_ds = None
    if ds is None:
        st, r = call("POST", "/api/datasources", {
            "name": "__CONTRACT_RUN__", "dbType": "h2", "host": "localhost",
            "port": 0, "username": "sa", "password": "", "databaseName": "dbnav_selfcheck",
        })
        ds = r.get("data")
        tmp_ds = (ds or {}).get("id")
    check("存在 h2 自检数据源（否则临时创建）", bool(ds), r)
    if not ds:
        return
    ds_id = ds["id"]

    st, r = call("POST", "/api/inspection/run",
                 {"dataSourceId": ds_id, "executedBy": "api_test"})
    run = r.get("data") or {}
    check("POST /run 返回 200", st == 200, r)

    run_keys = {
        "id", "dataSourceId", "dataSourceName", "dbType", "templateId", "templateName",
        "status", "triggerSource", "totalQueries", "okQueries", "failedQueries",
        "totalBaselines", "baselinesPass", "baselinesFail", "baselinesUnchecked",
        "compliancePct", "riskSummary", "errorMsg", "startedAt", "finishedAt",
        "durationMs", "executedBy",
    }
    missing = sorted(run_keys - set(run.keys()))
    check("执行记录字段完整", not missing, missing)

    check("status 取值合法",
          run.get("status") in ("SUCCESS", "PARTIAL", "FAILED"), run.get("status"))
    check("triggerSource = MANUAL", run.get("triggerSource") == "MANUAL", run.get("triggerSource"))
    check("executedBy 回显", run.get("executedBy") == "api_test", run.get("executedBy"))
    check("dataSourceId 回显", run.get("dataSourceId") == ds_id, run.get("dataSourceId"))

    # 统计恒等式
    check("totalQueries = ok + failed",
          run.get("totalQueries") == (run.get("okQueries") or 0) + (run.get("failedQueries") or 0),
          run)
    check("totalBaselines = pass + fail + unchecked",
          run.get("totalBaselines") == (run.get("baselinesPass") or 0)
          + (run.get("baselinesFail") or 0) + (run.get("baselinesUnchecked") or 0), run)
    check("riskSummary 为对象", isinstance(run.get("riskSummary"), dict), run.get("riskSummary"))
    check("riskSummary 合计 = baselinesFail",
          sum((run.get("riskSummary") or {}).values()) == (run.get("baselinesFail") or 0),
          run.get("riskSummary"))

    denom = (run.get("baselinesPass") or 0) + (run.get("baselinesFail") or 0)
    expect = round((run.get("baselinesPass") or 0) * 100.0 / denom, 1) if denom else 0.0
    check("合规率 = pass/(pass+fail)，未采集不计入分母",
          abs((run.get("compliancePct") or 0) - expect) < 0.05,
          (run.get("compliancePct"), expect))

    check("明细随执行结果一并返回",
          isinstance(run.get("queries"), list) and isinstance(run.get("baselines"), list), None)
    check("规则明细条数 >= 实际执行数",
          len(run.get("queries") or []) >= (run.get("totalQueries") or 0),
          len(run.get("queries") or []))
    check("基线明细条数 = totalBaselines",
          len(run.get("baselines") or []) == (run.get("totalBaselines") or 0),
          len(run.get("baselines") or []))

    qs = run.get("queries") or []
    if qs:
        q_keys = {"id", "runId", "chapterNumber", "chapterTitle", "queryKey", "querySql",
                  "descriptionZh", "status", "elapsedMs", "rowCount", "columns", "rows",
                  "truncated", "errorMsg"}
        q_missing = sorted(q_keys - set(qs[0].keys()))
        check("规则结果字段完整", not q_missing, q_missing)
        check("规则 status 取值合法",
              all(x.get("status") in ("OK", "FAILED", "SKIPPED") for x in qs),
              [x.get("status") for x in qs if x.get("status") not in ("OK", "FAILED", "SKIPPED")])
        check("规则按章节号升序返回",
              [x.get("chapterNumber") for x in qs] == sorted(x.get("chapterNumber") for x in qs), None)
    else:
        check("无规则明细时 totalQueries 应为 0", (run.get("totalQueries") or 0) == 0,
              run.get("totalQueries"))

    bs = run.get("baselines") or []
    if bs:
        b_keys = {"id", "runId", "paramName", "operator", "expectedValue", "actualValue",
                  "sampleCount", "violationCount", "riskLevel", "isPass", "isChecked",
                  "message", "descriptionZh", "elapsedMs", "errorMsg"}
        b_missing = sorted(b_keys - set(bs[0].keys()))
        check("基线判定字段完整", not b_missing, b_missing)
        check("isPass / isChecked 为 0 或 1",
              all(x.get("isPass") in (0, 1) and x.get("isChecked") in (0, 1) for x in bs), None)
        check("未采集的基线不应被判为合规",
              all(x.get("isChecked") == 1 for x in bs if x.get("isPass") == 1), None)
        check("不合规的基线必须已采集",
              all(x.get("isChecked") == 1 for x in bs if x.get("isPass") == 0 and x.get("violationCount")),
              None)
    else:
        check("无基线明细时 totalBaselines 应为 0", (run.get("totalBaselines") or 0) == 0,
              run.get("totalBaselines"))

    # 后端能力差异：FAILED 必须有原因；真跑过的则必须真的执行了规则
    if run.get("status") == "FAILED":
        check("失败执行留有原因 errorMsg", bool(run.get("errorMsg")), run.get("errorMsg"))
    else:
        check("成功/部分成功的执行必须跑出规则", (run.get("totalQueries") or 0) > 0,
              run.get("totalQueries"))

    # ---- 历史与明细 ----
    st, r = call("GET", f"/api/inspection/runs?dataSourceId={ds_id}&limit=20")
    runs = r.get("data") or []
    check("执行历史可读", st == 200, st)
    check("历史包含本次执行", any(x.get("id") == run.get("id") for x in runs), None)
    check("历史列表不含明细（queries/baselines 键不出现）",
          all("queries" not in x and "baselines" not in x for x in runs), None)

    st, r = call("GET", f"/api/inspection/runs/latest?dataSourceId={ds_id}")
    check("最近一次执行可读", st == 200 and (r.get("data") or {}).get("id") == run.get("id"), st)

    st, r = call("GET", f"/api/inspection/runs/{run.get('id')}")
    detail = r.get("data") or {}
    check("执行明细可读", st == 200, st)
    check("明细的规则数与发起时一致",
          len(detail.get("queries") or []) == len(qs), None)
    check("明细的基线与发起时一致",
          len(detail.get("baselines") or []) == len(bs), None)

    # ---- 错误路径 ----
    st, r = call("POST", "/api/inspection/run", {})
    check("缺 dataSourceId 被拒(400)", st == 400, r)
    st, r = call("POST", "/api/inspection/run", {"dataSourceId": 999999})
    check("数据源不存在被拒(400)", st == 400, r)
    st, r = call("POST", "/api/inspection/run", {"dataSourceId": ds_id, "templateId": 999999})
    check("模板不存在被拒(400)", st == 400, r)
    st, r = call("GET", "/api/inspection/runs/999999")
    check("执行记录不存在(404)", st == 404, r)
    st, r = call("GET", "/api/inspection/runs/latest?dataSourceId=999999")
    check("无记录的数据源查最近一次(404)", st == 404, r)
    st, r = call("DELETE", "/api/inspection/runs/999999")
    check("删除不存在的记录(404)", st == 404, r)

    # ---- 部分失败路径：只有真正执行 SQL 的后端才验证得了 ----
    if (run.get("totalQueries") or 0) > 0:
        st, r = call("GET", "/api/inspection/templates/default/h2/tree")
        chapters = (r.get("data") or {}).get("chapters") or []
        ch_id = chapters[0].get("id") if chapters else None
        st, r = call("POST", "/api/inspection/queries", {
            "chapterId": ch_id, "key": "__CONTRACT_BROKEN__",
            "sql": "SELECT * FROM __NO_SUCH_TABLE__", "desc_zh": "契约测试用坏规则",
        })
        qid = (r.get("data") or {}).get("id")
        check("注入坏规则成功", st == 200 and qid is not None, r)

        st, r = call("POST", "/api/inspection/run", {"dataSourceId": ds_id})
        run2 = r.get("data") or {}
        check("坏规则导致 PARTIAL", st == 200 and run2.get("status") == "PARTIAL", run2.get("status"))
        check("失败规则记有 errorMsg",
              any(x.get("status") == "FAILED" and x.get("errorMsg")
                  for x in (run2.get("queries") or [])), None)
        check("失败规则不影响其它规则",
              (run2.get("okQueries") or 0) > 0, run2.get("okQueries"))

        call("DELETE", f"/api/inspection/queries/{qid}")
        call("DELETE", f"/api/inspection/runs/{run2.get('id')}")
    else:
        print("  \u2500 跳过「部分失败路径」（当前后端不执行真实 SQL，"
              "该路径由 Java 后端覆盖）")

    # ---- 报告导出 ----
    check_report_export(run.get("id"))

    # ---- 清理 ----
    st, r = call("DELETE", f"/api/inspection/runs/{run.get('id')}")
    check("删除执行记录", st == 200, r)
    st, r = call("GET", f"/api/inspection/runs/{run.get('id')}")
    check("删除后记录不可见(404)", st == 404, r)

    if tmp_ds:
        st, r = call("DELETE", f"/api/datasources/{tmp_ds}")
        check("清理临时数据源", st == 200, r)


#: 各格式的「这个文件真的是这种东西吗」判据：(Content-Type 片段, 文件头字节, 扩展名)
EXPORT_FORMATS = {
    "html": ("text/html", b"<!DOCTYPE", ".html"),
    "word": ("wordprocessingml.document", b"PK\x03\x04", ".docx"),
    "pdf":  ("application/pdf", b"%PDF", ".pdf"),
}


def check_report_export(run_id):
    """
    报告导出接口的契约校验。

    两套后端的能力边界不同，所以断言分两类：
      · <b>无条件断言</b>：路由与参数校验（404 / 400）。这是纯契约，两边必须一模一样。
      · <b>条件断言</b>：成功路径。Java 必须交出真实文件；预览服务必须交出 501，
        且响应体<b>不能</b>长得像一份文档 —— 这条是「反伪造守卫」：
        如果哪天有人在预览服务里塞了个假 .docx，这里会失败。
        只断言「501 也可以」而不检查「501 时没有伪造文件」，守卫就是假的。
    """
    print("\n\u2500\u2500 报告导出 \u2500\u2500")
    if not run_id:
        check("导出测试需要一条执行记录", False, "run_id 为空")
        return

    # ---- 无条件：参数校验 ----
    st, _, _ = call_raw("GET", "/api/inspection/runs/999999/export?format=pdf")
    check("导出 · 记录不存在 → 404", st == 404, st)

    st, _, body = call_raw("GET", f"/api/inspection/runs/{run_id}/export?format=xlsx")
    check("导出 · 非法格式 → 400", st == 400, st)
    check("导出 · 400 带可读原因",
          "html" in body.decode("utf-8", "replace") and "word" in body.decode("utf-8", "replace"),
          body[:160])

    # ---- 逐格式 ----
    preview_mode = False
    for fmt, (ctype_part, magic, ext) in EXPORT_FORMATS.items():
        st, hdrs, body = call_raw("GET", f"/api/inspection/runs/{run_id}/export?format={fmt}")
        ct = hdrs.get("Content-Type", "")

        if st == 501:
            preview_mode = True
            # 反伪造守卫：501 的响应体里不能出现任何「这是一份文档」的特征
            looks_like_doc = body[:4] == b"%PDF" or body[:4] == b"PK\x03\x04" \
                or b"<!DOCTYPE" in body[:64]
            check(f"导出 {fmt} · 501 时不伪造文件", not looks_like_doc, body[:80])
            check(f"导出 {fmt} · 501 说明不可用原因",
                  "不生成" in body.decode("utf-8", "replace"), body[:160])
            continue

        check(f"导出 {fmt} · HTTP 200", st == 200, st)
        check(f"导出 {fmt} · Content-Type 正确", ctype_part in ct, ct)
        check(f"导出 {fmt} · 文件头正确", body[:len(magic)] == magic, body[:16])
        check(f"导出 {fmt} · 内容非空", len(body) > 512, len(body))

        cd = hdrs.get("Content-Disposition", "")
        check(f"导出 {fmt} · 文件名带 {ext}", ext in cd, cd[:160])
        check(f"导出 {fmt} · 文件名有 UTF-8 形式",
              "filename*=UTF-8''" in cd, cd[:160])

        # html 默认 inline（供 iframe 在线预览），word/pdf 恒为 attachment
        want = "inline" if fmt == "html" else "attachment"
        check(f"导出 {fmt} · disposition={want}", cd.startswith(want), cd[:40])

    if preview_mode:
        print("  \u2500 预览服务模式：导出接口如实返回 501（能力边界，非缺陷）")
    else:
        # html 加 download=1 必须转成 attachment，否则「下载 HTML」会变成开新标签
        st, hdrs, _ = call_raw(
            "GET", f"/api/inspection/runs/{run_id}/export?format=html&download=1")
        cd = hdrs.get("Content-Disposition", "")
        check("导出 html · download=1 → attachment", cd.startswith("attachment"), cd[:40])


def finish():
    print("\n" + "=" * 58)
    print(f"通过 {len(passed)} 项，失败 {len(failed)} 项")
    if failed:
        print("失败项：")
        for f in failed:
            print(f"  \u2718 {f}")
    print("=" * 58)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
