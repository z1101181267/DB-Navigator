#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DB Navigator · 双实现契约一致性校验

本项目有两套实现同一套 REST 契约的后端：
  · Java / Spring Boot  —— 生产实现
  · Python / devserver  —— 无需 JDK 的本地预览，同时充当「契约的可执行规格」

本脚本逐字段比对两者的响应体，确保预览服务不会与生产实现漂移。

注意：列表类接口（如 /api/datasources）比的是**实际数据**，因此两侧需要保持
同一份夹具。默认假设两侧都只有「平台自检库」这一个数据源；跑之前请自行对齐。
执行记录 /api/inspection/runs 不做逐字段比对（两侧各有独立数据库、各跑各的巡检），
其成功路径的字段结构由 api_test.py 校验，错误路径由本脚本的 ERROR_CASES 校验。

用法：
    python scripts/parity_check.py [java_base] [python_base]
    python scripts/parity_check.py http://127.0.0.1:8080 http://127.0.0.1:9090

退出码：0 = 完全一致，1 = 存在差异
"""

import difflib
import json
import sys
import urllib.error
import urllib.request

JAVA = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
PYV = (sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:9090").rstrip("/")

# 自增主键与时间戳天然不同，比对时剔除
IGNORE_KEYS = {
    "id", "templateId", "chapterId", "recordId", "runId",
    "createdAt", "updatedAt", "modifiedAt", "uploadedAt",
    # 以下三项由「连通性测试」这个运行时副作用写入，不是接口契约的一部分：
    # 同一份数据源配置，测过连接与没测过，值就不同。
    "lastConnected", "status",
    # 密码密文：两侧各自加密（Java AES-256-GCM / 预览自带实现），
    # 同一明文也必然得到不同密文，比对它没有意义。
    "passwordEnc",
}

CASES = [
    ("巡检总览",             "/api/inspection/summary"),
    ("巡检模板列表",          "/api/inspection/templates"),
    ("模板列表(按类型)",       "/api/inspection/templates?dbType=mysql"),
    ("模板树 oracle",        "/api/inspection/templates/default/oracle/tree"),
    ("模板树 mysql",         "/api/inspection/templates/default/mysql/tree"),
    ("模板树 postgresql",    "/api/inspection/templates/default/postgresql/tree"),
    ("模板树 sqlserver",     "/api/inspection/templates/default/sqlserver/tree"),
    ("模板树 kingbase",      "/api/inspection/templates/default/kingbase/tree"),
    ("模板树 h2(自检)",       "/api/inspection/templates/default/h2/tree"),
    ("模板树(仅启用)",         "/api/inspection/templates/default/mysql/tree?onlyEnabled=true"),
    ("基线 oracle",          "/api/inspection/baselines?dbType=oracle"),
    ("基线 mysql",           "/api/inspection/baselines?dbType=mysql"),
    ("基线 postgresql",      "/api/inspection/baselines?dbType=postgresql"),
    ("基线 sqlserver",       "/api/inspection/baselines?dbType=sqlserver"),
    ("基线 kingbase",        "/api/inspection/baselines?dbType=kingbase"),
    ("基线 h2(自检)",         "/api/inspection/baselines?dbType=h2"),
    ("驱动类型元数据",         "/api/drivers/types"),
    ("驱动登记列表",           "/api/drivers"),
    ("数据源列表",             "/api/datasources"),
]

# 注意：执行记录（/api/inspection/runs）的**成功**路径不做逐字段比对 ——
# 两套实现各有独立数据库、各跑各的巡检，数据本就不同。
# 契约一致性改由两层保证：这里比对错误路径的状态码，api_test.py 校验成功路径的字段结构。
ERROR_CASES = [
    ("404 模板不存在",        "GET", "/api/inspection/templates/999999"),
    ("404 默认模板不存在",     "GET", "/api/inspection/templates/default/nosuchdb/tree"),
    ("404 基线不存在",        "GET", "/api/inspection/baselines/999999"),
    ("404 执行记录不存在",     "GET", "/api/inspection/runs/999999"),
    ("404 最近执行不存在",     "GET", "/api/inspection/runs/latest?dataSourceId=999999"),
    ("400 非法比较符",        "POST", "/api/inspection/baselines",
     {"dbType": "mysql", "paramName": "__P__", "operator": "~~", "expectedValue": "1"}),
    ("400 缺必填字段",        "POST", "/api/inspection/templates", {"dbType": "mysql"}),
    ("400 BETWEEN 缺区间",   "POST", "/api/inspection/baselines",
     {"dbType": "mysql", "paramName": "__P__", "operator": "BETWEEN", "riskLevel": "LOW"}),
    ("400 缺 dataSourceId",  "POST", "/api/inspection/run", {}),
    ("400 数据源不存在",      "POST", "/api/inspection/run", {"dataSourceId": 999999}),
    ("404 删不存在的记录",     "DELETE", "/api/inspection/runs/999999"),
    ("404 导出记录不存在",     "GET", "/api/inspection/runs/999999/export?format=pdf"),
]


def request(base, method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
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


def request_status(base, method, path, body=None):
    """
    只要 HTTP 状态码，不碰响应体内容。

    报告导出返回的是二进制（.pdf/.docx），用 request() 会走到 json.loads 前先
    在 .decode("utf-8") 上炸掉 —— 于是拿到 status=0，看起来像「后端挂了」，
    实际是测试自己的问题。只读状态码就不会有这种歧义。
    """
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def normalize(o):
    if isinstance(o, dict):
        return {k: normalize(v) for k, v in sorted(o.items()) if k not in IGNORE_KEYS}
    if isinstance(o, list):
        return [normalize(x) for x in o]
    return o


def diff_lines(a, b, limit=20):
    sa = json.dumps(a, ensure_ascii=False, indent=1).splitlines()
    sb = json.dumps(b, ensure_ascii=False, indent=1).splitlines()
    return list(difflib.unified_diff(sa, sb, "java", "python", lineterm="", n=1))[:limit]


def export_divergence_section():
    """
    报告导出接口：唯一一处**故意不一致**的契约，因此要专门断言这个差异本身。

    Java 交出真实文件（200），预览服务如实返回 501 —— 两者在能力上就是不同的，
    强行比对响应体没有意义。但「不一致」必须是被声明并被检查的，而不是被忽略的：
    本节的断言写死了 java=200 / python=501，任何一侧改动（比如预览服务开始伪造文件、
    或 Java 侧挂了）都会在这里失败。

    400（非法格式）这条需要一个两侧都存在的执行记录 id，而两边的 id 天然不同，
    所以先各自取第一条记录再比对，不能写死。
    """
    print("\n" + "=" * 66)
    print("报告导出：能力差异断言（Java 产出文件 / 预览服务如实 501）")
    print("=" * 66)

    bad = 0

    def first_run_id(base):
        st, r = request(base, "GET", "/api/inspection/runs?limit=1")
        rows = (r or {}).get("data") or []
        return rows[0]["id"] if rows else None

    jid, pid = first_run_id(JAVA), first_run_id(PYV)
    if jid is None or pid is None:
        print(f"  \u2500 跳过：至少一侧没有执行记录（java={jid} python={pid}）")
        return 0

    # 1) 非法格式 → 两侧都必须是 400，且原因一致
    ja = request(JAVA, "GET", f"/api/inspection/runs/{jid}/export?format=xlsx")
    jb = request(PYV, "GET", f"/api/inspection/runs/{pid}/export?format=xlsx")
    ca, cb = (ja[1] or {}).get("code"), (jb[1] or {}).get("code")
    if ja[0] == 400 and jb[0] == 400 and ca == 400 and cb == 400:
        print(f"  \u2714 {'400 非法导出格式':<22} 两侧 HTTP 400 / code 400")
    else:
        bad += 1
        print(f"  \u2718 {'400 非法导出格式':<22} java=HTTP {ja[0]}/code {ca}  "
              f"python=HTTP {jb[0]}/code {cb}")

    # 2) 合法格式 → 必须 Java 200、Python 501（写死，改动即失败）
    sa = request_status(JAVA, "GET", f"/api/inspection/runs/{jid}/export?format=pdf")
    sb = request_status(PYV, "GET", f"/api/inspection/runs/{pid}/export?format=pdf")
    if sa == 200 and sb == 501:
        print(f"  \u2714 {'200 vs 501（已声明差异）':<22} java=200 python=501")
    else:
        bad += 1
        print(f"  \u2718 {'200 vs 501（已声明差异）':<22} java=HTTP {sa}  "
              f"python=HTTP {sb}   ← 预期 java=200 / python=501")

    return bad


def main():
    print("=" * 66)
    print("响应体逐字段对比（已剔除自增 id 与时间戳）")
    print(f"  Java   : {JAVA}")
    print(f"  Python : {PYV}")
    print("=" * 66)

    bad = 0
    for label, path in CASES:
        ja, jb = request(JAVA, "GET", path), request(PYV, "GET", path)
        if ja[0] != jb[0]:
            bad += 1
            print(f"  \u2718 {label:<22} HTTP 状态码不同：java={ja[0]} python={jb[0]}")
            continue
        a, b = normalize(ja[1]), normalize(jb[1])
        if a == b:
            print(f"  \u2714 {label:<22} 一致（{len(json.dumps(a, ensure_ascii=False))} 字节）")
        else:
            bad += 1
            print(f"  \u2718 {label:<22} 响应体不一致")
            for line in diff_lines(a, b):
                print("        " + line)

    print("\n" + "=" * 66)
    print("错误路径对比（HTTP 状态码 + 业务 code 均须一致）")
    print("=" * 66)
    for case in ERROR_CASES:
        label, method, path = case[0], case[1], case[2]
        body = case[3] if len(case) > 3 else None
        ja, jb = request(JAVA, method, path, body), request(PYV, method, path, body)
        ca, cb = (ja[1] or {}).get("code"), (jb[1] or {}).get("code")
        if ja[0] == jb[0] and ca == cb:
            print(f"  \u2714 {label:<22} HTTP {ja[0]} / code {ca}")
        else:
            bad += 1
            print(f"  \u2718 {label:<22} java=HTTP {ja[0]}/code {ca}  "
                  f"python=HTTP {jb[0]}/code {cb}")

    bad += export_divergence_section()

    print("\n" + "=" * 66)
    if bad:
        print(f"结论：发现 {bad} 处契约漂移")
    else:
        print("结论：两套实现契约完全一致")
    print("=" * 66)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
