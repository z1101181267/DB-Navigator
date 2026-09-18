#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DB Navigator · 本地预览服务（无需 JDK）

用途
----
Spring Boot 后端需要 JDK 17 + Maven 才能启动。本脚本用 Python 标准库
复刻同一套 REST 契约，让前端控制台可以立刻跑起来，用于：
  1. 预览 / 联调 Web 界面
  2. 作为 API 契约的可执行规格（与 Java 端字段一一对应）

它与 Java 后端的差异（有意为之，避免产生"另一个后端"的错觉）：
  · 驱动元数据存 SQLite（Java 端是 H2），表结构与 Java schema.sql 对齐
  · 「测试连接」执行真实的 TCP 可达性探测，但不建立 JDBC 连接
  · 「执行 SQL」不真正连库，返回明确的演示响应

真正的数据库连接与 SQL 执行由 Java 后端负责。

启动：
    python devserver.py [--port 8080]
"""

import argparse
import base64
import json
import os
import re
import socket
import sqlite3
import sys
import time
import uuid
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "src", "main", "resources", "static")
RES_DIR = os.path.join(BASE_DIR, "src", "main", "resources")
DRIVERS_DIR = os.path.join(BASE_DIR, "drivers")
DATA_DIR = os.path.join(BASE_DIR, "data")
DB_PATH = os.path.join(DATA_DIR, "preview.db")

sys.path.insert(0, os.path.join(BASE_DIR, "scripts"))

import preview_inspection  # noqa: E402  (巡检配置预览实现，对齐 Java 侧)

# ----------------------------------------------------------------------------
# 配置装载
# ----------------------------------------------------------------------------

def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_db_types():
    """读取 db-types.json（snake_case），转成前端使用的 camelCase。"""
    raw = load_json(os.path.join(RES_DIR, "db-types.json"))
    out = []
    for t in raw.get("types", []):
        out.append({
            "dbType": t["db_type"],
            "label": t.get("label", t["db_type"]),
            "port": t.get("port"),
            "user": t.get("user"),
            "defaultDatabase": t.get("default_database", ""),
            "icon": t.get("icon", ""),
            "emoji": t.get("emoji", ""),
            "description": t.get("description", ""),
            "protocol": t.get("protocol", "other"),
            "sqlEditor": t.get("sql_editor", True),
            "showDatabaseField": t.get("show_database_field", True),
            "driverClassHint": t.get("driver_class_hint"),
            "driverBundled": t.get("driver_bundled"),
            "urlTemplate": t.get("url_template"),
            "urlTemplateSid": t.get("url_template_sid"),
            "extraParamsExample": t.get("extra_params_example", ""),
        })
    return out


DB_TYPES = load_db_types()
DB_TYPE_MAP = {t["dbType"]: t for t in DB_TYPES}


def driver_class_hint(db_type):
    t = DB_TYPE_MAP.get(db_type)
    return t.get("driverClassHint") if t else None


# ----------------------------------------------------------------------------
# SQLite 存储（表结构对齐 Java schema.sql）
# ----------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS jdbc_driver_registry (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    db_type       TEXT NOT NULL,
    version       TEXT NOT NULL,
    driver_class  TEXT,
    jar_filename  TEXT NOT NULL,
    jar_path      TEXT NOT NULL,
    file_size     INTEGER DEFAULT 0,
    is_active     INTEGER NOT NULL DEFAULT 0,
    uploaded_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    note          TEXT,
    UNIQUE (db_type, version, jar_filename)
);

CREATE TABLE IF NOT EXISTS data_source (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL UNIQUE,
    db_type         TEXT NOT NULL,
    host            TEXT NOT NULL,
    port            INTEGER NOT NULL,
    username        TEXT NOT NULL,
    password_enc    TEXT,
    database_name   TEXT,
    sid             TEXT,
    service_name    TEXT,
    extra_params    TEXT,
    driver_version  TEXT,
    pool_size       INTEGER,
    status          TEXT DEFAULT 'OFFLINE',
    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    last_connected  TEXT,
    note            TEXT
);

CREATE TABLE IF NOT EXISTS query_history (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    datasource_id  INTEGER NOT NULL,
    sql_text       TEXT NOT NULL,
    status         TEXT NOT NULL,
    row_count      INTEGER DEFAULT 0,
    duration_ms    INTEGER DEFAULT 0,
    error_msg      TEXT,
    executed_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
"""


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")   # 巡检章节/规则依赖级联删除
    return conn


def init_db():
    os.makedirs(DATA_DIR, exist_ok=True)
    conn = db()
    conn.executescript(SCHEMA)
    preview_inspection.init_schema(conn)
    conn.commit()
    conn.close()


def enc_pwd(p):
    # 空密码不加密：Java 侧 PasswordEncryptor.encrypt("") 也返回 ""，
    # 两边保持一致，免得同一个「没设密码」在两侧表现为 "" 与 "enc:"。
    if not p:
        return ""
    return "enc:" + base64.b64encode(p.encode("utf-8")).decode("ascii")


def dec_pwd(e):
    if not e or not e.startswith("enc:"):
        return e or ""
    return base64.b64decode(e[4:]).decode("utf-8")


# ----------------------------------------------------------------------------
# 驱动登记：种子导入 + 目录扫描（对齐 Java 的 DriverSeedLoader / DriverDirectoryScanner）
# ----------------------------------------------------------------------------

def resolve_jar(db_type, version, jar_name):
    """三级回退查找，对齐 Java 的 DriverPathResolver。"""
    candidates = [
        os.path.join(DRIVERS_DIR, db_type, version, jar_name),
        os.path.join(DRIVERS_DIR, db_type, jar_name),
        os.path.join(DRIVERS_DIR, jar_name),
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def seed_drivers():
    conn = db()
    empty = conn.execute("SELECT COUNT(*) FROM jdbc_driver_registry").fetchone()[0] == 0
    if not empty:
        conn.close()
        return 0

    seed_path = os.path.join(RES_DIR, "drivers-seed.json")
    if not os.path.isfile(seed_path):
        conn.close()
        return 0

    added = 0
    for s in load_json(seed_path).get("drivers", []):
        db_type = s["db_type"]
        version = s["version"]
        jar_name = s["jar_filename"]
        path = resolve_jar(db_type, version, jar_name)
        size = os.path.getsize(path) if path else (s.get("file_size") or 0)
        jar_path = path or os.path.join(DRIVERS_DIR, db_type, version, jar_name)
        try:
            conn.execute(
                """INSERT INTO jdbc_driver_registry
                   (db_type, version, driver_class, jar_filename, jar_path, file_size, is_active, note)
                   VALUES (?,?,?,?,?,?,?,?)""",
                (db_type, version, s.get("driver_class") or driver_class_hint(db_type),
                 jar_name, jar_path, size, s.get("is_active", 0), s.get("note", "")),
            )
            added += 1
        except sqlite3.IntegrityError:
            pass
    conn.commit()
    conn.close()
    return added


def scan_drivers():
    """扫描 drivers/<type>/<version>/<jar>，登记未入库的 JAR（幂等）。"""
    conn = db()
    added = 0
    if os.path.isdir(DRIVERS_DIR):
        for db_type in sorted(os.listdir(DRIVERS_DIR)):
            type_dir = os.path.join(DRIVERS_DIR, db_type)
            if not os.path.isdir(type_dir):
                continue
            for entry in sorted(os.listdir(type_dir)):
                entry_path = os.path.join(type_dir, entry)
                # 布局 1：类型/版本/xxx.jar
                if os.path.isdir(entry_path):
                    for jar in sorted(os.listdir(entry_path)):
                        if jar.lower().endswith(".jar"):
                            added += _register(conn, db_type, entry,
                                               jar, os.path.join(entry_path, jar))
                # 布局 2：类型/xxx.jar（平铺）
                elif entry.lower().endswith(".jar"):
                    m = re.search(r"(\d+(?:\.\d+)*)", entry[:-4])
                    if m:
                        added += _register(conn, db_type, m.group(1), entry, entry_path)
    conn.commit()
    ensure_activation(conn)
    conn.close()
    return added


def _register(conn, db_type, version, jar_name, jar_path):
    try:
        conn.execute(
            """INSERT INTO jdbc_driver_registry
               (db_type, version, driver_class, jar_filename, jar_path, file_size, is_active, note)
               VALUES (?,?,?,?,?,?,0,?)""",
            (db_type, version, driver_class_hint(db_type), jar_name,
             jar_path, os.path.getsize(jar_path) if os.path.isfile(jar_path) else 0,
             "Auto-registered by directory scan"),
        )
        return 1
    except sqlite3.IntegrityError:
        return 0


def ensure_activation(conn):
    """每个 db_type 至少有一个激活驱动（对齐 Java 的 ensureActivation）。"""
    types = [r[0] for r in conn.execute(
        "SELECT DISTINCT db_type FROM jdbc_driver_registry").fetchall()]
    for t in types:
        n = conn.execute(
            "SELECT COUNT(*) FROM jdbc_driver_registry WHERE db_type=? AND is_active=1",
            (t,)).fetchone()[0]
        if n == 0:
            row = conn.execute(
                "SELECT id FROM jdbc_driver_registry WHERE db_type=? "
                "ORDER BY uploaded_at DESC, id DESC LIMIT 1", (t,)).fetchone()
            if row:
                conn.execute("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?", (row[0],))
    conn.commit()


def driver_rows(db_type=None):
    conn = db()
    if db_type:
        rows = conn.execute(
            "SELECT * FROM jdbc_driver_registry WHERE db_type=? "
            "ORDER BY is_active DESC, uploaded_at DESC", (db_type,)).fetchall()
    else:
        rows = conn.execute(
            "SELECT * FROM jdbc_driver_registry "
            "ORDER BY db_type, is_active DESC, uploaded_at DESC").fetchall()
    conn.close()
    return [{
        "id": r["id"],
        "dbType": r["db_type"],
        "version": r["version"],
        "driverClass": r["driver_class"],
        "jarFilename": r["jar_filename"],
        "jarPath": r["jar_path"],
        "fileSize": r["file_size"],
        "active": bool(r["is_active"]),
        "uploadedAt": r["uploaded_at"],
        "note": r["note"],
        "jarPresent": bool(r["jar_path"] and os.path.isfile(r["jar_path"])),
    } for r in rows]


# ----------------------------------------------------------------------------
# 连接测试（真实 TCP 可达性探测）
# ----------------------------------------------------------------------------

def tcp_probe(host, port, timeout=5.0):
    start = time.time()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True, int((time.time() - start) * 1000), None
    except socket.timeout:
        return False, int((time.time() - start) * 1000), f"连接超时（{timeout}s）"
    except OSError as e:
        return False, int((time.time() - start) * 1000), str(e)


def build_url(ds):
    t = DB_TYPE_MAP.get(ds["dbType"], {})
    host, port = ds["host"], ds["port"]
    if ds["dbType"] == "oracle":
        if ds.get("sid"):
            return (t.get("urlTemplateSid") or "jdbc:oracle:thin:@{host}:{port}:{sid}") \
                .replace("{host}", host).replace("{port}", str(port)).replace("{sid}", ds["sid"])
        return (t.get("urlTemplate") or "jdbc:oracle:thin:@//{host}:{port}/{service_name}") \
            .replace("{host}", host).replace("{port}", str(port)) \
            .replace("{service_name}", ds.get("serviceName") or "ORCL")
    url = (t.get("urlTemplate") or "") \
        .replace("{host}", host).replace("{port}", str(port)) \
        .replace("{database}", ds.get("databaseName") or "")
    extra = (ds.get("extraParams") or "").strip()
    if extra:
        sep = "&" if "?" in url else (";" if ";" in url else "?")
        url += sep + extra
    return url


def test_connection(ds):
    result = {
        "dbType": ds["dbType"], "host": ds["host"], "port": ds["port"],
        "jdbcUrl": build_url(ds), "mode": "preview",
    }

    # 是否有可用驱动
    conn = db()
    drv = conn.execute(
        "SELECT * FROM jdbc_driver_registry WHERE db_type=? AND is_active=1 LIMIT 1",
        (ds["dbType"],)).fetchone()
    conn.close()

    if drv is None:
        result.update(success=False,
                      error=f"未登记 {ds['dbType']} 的驱动，请先上传对应 JAR")
        return result

    jar_path = resolve_jar(drv["db_type"], drv["version"], drv["jar_filename"])
    if not jar_path:
        result.update(
            success=False,
            driverVersion=drv["version"],
            error=f"磁盘上未找到驱动 JAR：{drv['jar_filename']}",
            expectedPath=f"drivers/{drv['db_type']}/{drv['version']}/{drv['jar_filename']}")
        return result

    # 真实 TCP 可达性探测
    ok, ms, err = tcp_probe(ds["host"], ds["port"])
    result.update(
        success=ok,
        elapsedMs=ms,
        driverVersion=drv["version"],
        driverClass=drv["driver_class"],
        databaseProductName="（预览模式未建立 JDBC 连接）",
        note="预览服务仅验证端口可达性；完整连接与元数据需启动 Java 后端",
    )
    if not ok:
        result["error"] = err
    return result


# ----------------------------------------------------------------------------
# HTTP 处理
# ----------------------------------------------------------------------------

MIME = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
}


def ok(data):
    return 200, {"code": 200, "message": "success", "data": data}


def fail(code, msg):
    return code, {"code": code, "message": msg, "data": None}


class Handler(BaseHTTPRequestHandler):
    server_version = "DBNavPreview/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("  %s\n" % (fmt % args))

    # ---------- helpers ----------

    def _send(self, status, payload, ctype="application/json; charset=utf-8"):
        body = payload if isinstance(payload, bytes) else json.dumps(
            payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _raw_body(self):
        """
        取原始请求体，**一个请求只读一次**。

        http.server 的 rfile 是一次性流：read(n) 之后流已耗尽，再 read(n) 会一直
        阻塞等新数据，而客户端正等着响应 —— 于是双方僵死，客户端超时（curl 报
        HTTP 000 / Connection reset）。

        这个坑真实存在过：api_post/api_put/api_delete 开头会统一解析一次 body 交给
        巡检路由，业务分支（如 _create_ds、_multipart）又解析一次，导致
        POST /api/datasources 与驱动上传在预览服务上必然挂住。
        在这里缓存原始字节，所有解析入口共用，从根上避免「读两次」。
        """
        if getattr(self, "_raw_cache", None) is None:
            n = int(self.headers.get("Content-Length") or 0)
            self._raw_cache = self.rfile.read(n) if n else b""
        return self._raw_cache

    def _json_body(self):
        try:
            return json.loads(self._raw_body().decode("utf-8"))
        except Exception:
            return {}

    def _multipart(self):
        """极简 multipart/form-data 解析（Python 3.13 已移除 cgi 模块）。"""
        ctype = self.headers.get("Content-Type", "")
        m = re.search(r"boundary=(.+)$", ctype)
        if not m:
            return {}, {}
        boundary = m.group(1).strip().strip('"').encode("ascii")
        raw = self._raw_body()

        fields, files = {}, {}
        for part in raw.split(b"--" + boundary):
            part = part.strip(b"\r\n")
            if not part or part == b"--":
                continue
            head, _, content = part.partition(b"\r\n\r\n")
            content = content.rstrip(b"\r\n")
            head_txt = head.decode("utf-8", "replace")
            nm = re.search(r'name="([^"]*)"', head_txt)
            if not nm:
                continue
            name = nm.group(1)
            fn = re.search(r'filename="([^"]*)"', head_txt)
            if fn and fn.group(1):
                files[name] = (fn.group(1), content)
            else:
                fields[name] = content.decode("utf-8", "replace")
        return fields, files

    # ---------- routing ----------

    def do_GET(self):
        u = urlparse(self.path)
        path, qs = u.path, parse_qs(u.query)
        try:
            if path.startswith("/api/"):
                return self.api_get(path, qs)
            return self.static(path)
        except Exception as e:
            self._send(*fail(500, f"{type(e).__name__}: {e}"))

    def do_POST(self):
        u = urlparse(self.path)
        try:
            return self.api_post(u.path, parse_qs(u.query))
        except Exception as e:
            self._send(*fail(500, f"{type(e).__name__}: {e}"))

    def do_PUT(self):
        u = urlparse(self.path)
        try:
            return self.api_put(u.path, parse_qs(u.query))
        except Exception as e:
            self._send(*fail(500, f"{type(e).__name__}: {e}"))

    def do_DELETE(self):
        u = urlparse(self.path)
        try:
            return self.api_delete(u.path, parse_qs(u.query))
        except Exception as e:
            self._send(*fail(500, f"{type(e).__name__}: {e}"))

    # ---------- static ----------

    def static(self, path):
        if path in ("/", ""):
            path = "/index.html"
        rel = unquote(path).lstrip("/")
        full = os.path.normpath(os.path.join(STATIC_DIR, rel))
        if not full.startswith(STATIC_DIR) or not os.path.isfile(full):
            return self._send(404, "Not Found", "text/plain; charset=utf-8")
        ext = os.path.splitext(full)[1].lower()
        with open(full, "rb") as f:
            self._send(200, f.read(), MIME.get(ext, "application/octet-stream"))

    # ---------- API: GET ----------

    def _inspection(self, method, path, qs, body=None):
        """把 /api/inspection/* 交给 preview_inspection 处理。返回 (status, payload) 或 None。"""
        if not path.startswith("/api/inspection"):
            return None
        conn = db()
        try:
            return preview_inspection.handle(conn, method, path, qs, body)
        finally:
            conn.close()

    def api_get(self, path, qs):
        r = self._inspection("GET", path, qs)
        if r is not None:
            return self._send(*r)
        # 驱动
        if path == "/api/drivers/types":
            return self._send(*ok(DB_TYPES))
        if path == "/api/drivers":
            db_type = (qs.get("dbType") or [None])[0]
            return self._send(*ok(driver_rows(db_type)))
        m = re.match(r"^/api/drivers/active/([\w-]+)$", path)
        if m:
            rows = [d for d in driver_rows(m.group(1)) if d["active"]]
            return self._send(*(ok(rows[0]) if rows else fail(404, "无激活驱动")))
        m = re.match(r"^/api/drivers/path/([\w-]+)/(.+)$", path)
        if m:
            d, v = m.group(1), m.group(2)
            return self._send(*ok({
                "directory": os.path.join(DRIVERS_DIR, d, v),
                "dbType": d, "version": v,
            }))
        # 数据源
        if path == "/api/datasources":
            conn = db()
            rows = conn.execute("SELECT * FROM data_source ORDER BY name").fetchall()
            conn.close()
            return self._send(*ok([self._ds_json(r) for r in rows]))
        if path == "/api/datasources/types":
            return self._send(*ok(DB_TYPES))
        m = re.match(r"^/api/datasources/defaults/([\w-]+)$", path)
        if m:
            t = DB_TYPE_MAP.get(m.group(1))
            return self._send(*(ok(t) if t else fail(404, "未知数据库类型")))
        m = re.match(r"^/api/datasources/(\d+)$", path)
        if m:
            conn = db()
            r = conn.execute("SELECT * FROM data_source WHERE id=?", (m.group(1),)).fetchone()
            conn.close()
            return self._send(*(ok(self._ds_json(r)) if r else fail(404, "数据源不存在")))
        # 查询历史
        m = re.match(r"^/api/query/(\d+)/history$", path)
        if m:
            conn = db()
            rows = conn.execute(
                "SELECT * FROM query_history WHERE datasource_id=? "
                "ORDER BY executed_at DESC LIMIT 50", (m.group(1),)).fetchall()
            conn.close()
            return self._send(*ok([dict(r) for r in rows]))
        return self._send(*fail(404, "未知接口: " + path))

    @staticmethod
    def _ds_json(r):
        return {
            "id": r["id"], "name": r["name"], "dbType": r["db_type"],
            "host": r["host"], "port": r["port"], "username": r["username"],
            "databaseName": r["database_name"], "sid": r["sid"],
            "serviceName": r["service_name"], "extraParams": r["extra_params"],
            "driverVersion": r["driver_version"], "poolSize": r["pool_size"],
            "status": r["status"], "createdAt": r["created_at"],
            "updatedAt": r["updated_at"], "lastConnected": r["last_connected"],
            "note": r["note"],
        }

    # ---------- API: POST ----------

    def api_post(self, path, qs=None):
        r = self._inspection("POST", path, qs or {}, self._json_body())
        if r is not None:
            return self._send(*r)
        # 扫描驱动目录
        if path == "/api/drivers/scan":
            n = scan_drivers()
            return self._send(*ok({"newDrivers": n, "totalDrivers": len(driver_rows())}))

        # 上传驱动
        if path == "/api/drivers/upload":
            fields, files = self._multipart()
            if "file" not in files:
                return self._send(*fail(400, "缺少 file 字段"))
            filename, content = files["file"]
            db_type = (fields.get("dbType") or "").strip()
            version = (fields.get("version") or "").strip()
            driver_class = (fields.get("driverClass") or "").strip() or driver_class_hint(db_type)

            if not db_type or db_type not in DB_TYPE_MAP:
                return self._send(*fail(400, f"未知数据库类型: {db_type}"))
            if not version:
                return self._send(*fail(400, "缺少 version"))
            if not re.match(r"^[A-Za-z0-9][A-Za-z0-9._+\-]{0,63}$", version):
                return self._send(*fail(400, f"版本号非法: {version}"))
            # 文件名安全校验（对齐 Java addDriver，先校验后使用）
            if ("/" in filename or "\\" in filename or ".." in filename
                    or not filename.lower().endswith(".jar")):
                return self._send(*fail(400, "文件名非法（不得含路径分隔符或 ..，且必须以 .jar 结尾）"))

            target_dir = os.path.join(DRIVERS_DIR, db_type, version)
            os.makedirs(target_dir, exist_ok=True)
            target = os.path.join(target_dir, filename)
            with open(target, "wb") as f:
                f.write(content)

            conn = db()
            try:
                conn.execute(
                    """INSERT INTO jdbc_driver_registry
                       (db_type, version, driver_class, jar_filename, jar_path, file_size, is_active, note)
                       VALUES (?,?,?,?,?,?,0,?)""",
                    (db_type, version, driver_class, filename, target, len(content), "Uploaded via console"))
                conn.commit()
            except sqlite3.IntegrityError:
                conn.close()
                return self._send(*fail(400, f"驱动已存在：{db_type} v{version} {filename}"))
            # 无激活驱动则自动激活
            n = conn.execute(
                "SELECT COUNT(*) FROM jdbc_driver_registry WHERE db_type=? AND is_active=1",
                (db_type,)).fetchone()[0]
            if n == 0:
                conn.execute("UPDATE jdbc_driver_registry SET is_active=1 WHERE db_type=?",
                             (db_type,))
                conn.commit()
            row = conn.execute(
                "SELECT * FROM jdbc_driver_registry WHERE db_type=? AND version=? AND jar_filename=?",
                (db_type, version, filename)).fetchone()
            conn.close()
            return self._send(*ok({
                "id": row["id"], "dbType": row["db_type"], "version": row["version"],
                "driverClass": row["driver_class"], "jarFilename": row["jar_filename"],
                "jarPath": row["jar_path"], "fileSize": row["file_size"],
                "active": bool(row["is_active"]), "note": row["note"],
            }))

        # 新建数据源
        if path == "/api/datasources":
            return self._create_ds(self._json_body())

        # 内联测试连接
        if path == "/api/datasources/test":
            return self._send(*ok(test_connection(self._normalize_ds(self._json_body()))))

        # 测试已保存数据源
        m = re.match(r"^/api/datasources/(\d+)/test$", path)
        if m:
            conn = db()
            r = conn.execute("SELECT * FROM data_source WHERE id=?", (m.group(1),)).fetchone()
            if not r:
                conn.close()
                return self._send(*fail(404, "数据源不存在"))
            ds = self._ds_json(r)
            ds["password"] = dec_pwd(r["password_enc"])
            conn.close()
            result = test_connection(ds)
            conn = db()
            conn.execute("UPDATE data_source SET status=?, last_connected=datetime('now','localtime') "
                         "WHERE id=?",
                         ("ONLINE" if result.get("success") else "ERROR", m.group(1)))
            conn.commit()
            conn.close()
            return self._send(*ok(result))

        # 执行 SQL
        m = re.match(r"^/api/query/(\d+)/execute$", path)
        if m:
            body = self._json_body()
            sql = (body.get("sql") or "").strip()
            if not sql:
                return self._send(*fail(400, "SQL 为空"))
            start = time.time()
            conn = db()
            conn.execute(
                """INSERT INTO query_history
                   (datasource_id, sql_text, status, row_count, duration_ms, error_msg)
                   VALUES (?,?,?,?,?,?)""",
                (m.group(1), sql, "PREVIEW", 0, 0, None))
            conn.commit()
            conn.close()
            elapsed = int((time.time() - start) * 1000)
            return self._send(*ok({
                "datasourceId": int(m.group(1)),
                "sql": sql,
                "success": True,
                "elapsedMs": elapsed,
                "type": "QUERY",
                "rowCount": 0,
                "columns": [],
                "rows": [],
                "preview": True,
                "notice": "预览服务不执行真实 SQL。请启动 Java 后端（需 JDK 17 + Maven）以连接数据库并执行语句。",
            }))

        return self._send(*fail(404, "未知接口: " + path))

    # ---------- API: PUT ----------

    def api_put(self, path, qs=None):
        r = self._inspection("PUT", path, qs or {}, self._json_body())
        if r is not None:
            return self._send(*r)
        m = re.match(r"^/api/drivers/(\d+)/activate$", path)
        if m:
            conn = db()
            row = conn.execute("SELECT * FROM jdbc_driver_registry WHERE id=?",
                               (m.group(1),)).fetchone()
            if not row:
                conn.close()
                return self._send(*fail(404, "驱动不存在"))
            conn.execute("UPDATE jdbc_driver_registry SET is_active=0 WHERE db_type=?",
                         (row["db_type"],))
            conn.execute("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?", (m.group(1),))
            conn.commit()
            conn.close()
            return self._send(*ok(None))

        m = re.match(r"^/api/datasources/(\d+)$", path)
        if m:
            return self._update_ds(int(m.group(1)), self._json_body())

        return self._send(*fail(404, "未知接口: " + path))

    # ---------- API: DELETE ----------

    def api_delete(self, path, qs=None):
        r = self._inspection("DELETE", path, qs or {})
        if r is not None:
            return self._send(*r)
        m = re.match(r"^/api/drivers/(\d+)$", path)
        if m:
            conn = db()
            row = conn.execute("SELECT * FROM jdbc_driver_registry WHERE id=?",
                               (m.group(1),)).fetchone()
            if not row:
                conn.close()
                return self._send(*fail(404, "驱动不存在"))
            was_active, db_type = row["is_active"], row["db_type"]
            jar_path = row["jar_path"]
            conn.execute("DELETE FROM jdbc_driver_registry WHERE id=?", (m.group(1),))
            conn.commit()
            if was_active:
                nxt = conn.execute(
                    "SELECT id FROM jdbc_driver_registry WHERE db_type=? "
                    "ORDER BY uploaded_at DESC, id DESC LIMIT 1", (db_type,)).fetchone()
                if nxt:
                    conn.execute("UPDATE jdbc_driver_registry SET is_active=1 WHERE id=?",
                                 (nxt[0],))
                    conn.commit()
            conn.close()
            try:
                if jar_path and os.path.isfile(jar_path):
                    os.remove(jar_path)
            except OSError:
                pass
            return self._send(*ok(None))

        m = re.match(r"^/api/datasources/(\d+)$", path)
        if m:
            conn = db()
            conn.execute("DELETE FROM query_history WHERE datasource_id=?", (m.group(1),))
            conn.execute("DELETE FROM data_source WHERE id=?", (m.group(1),))
            conn.commit()
            conn.close()
            return self._send(*ok(None))

        return self._send(*fail(404, "未知接口: " + path))

    # ---------- data source helpers ----------

    def _normalize_ds(self, p):
        db_type = p.get("dbType", "")
        port = p.get("port") or 0
        if not port:
            port = (DB_TYPE_MAP.get(db_type) or {}).get("port") or 0
        # 可选字段刻意不默认成 ""：Java 侧没传就是 null，传了空串就是 ""，
        # 两者语义不同（前端有 `ds.sid === null` 这类判断），这里保持同一套语义。
        return {
            "name": p.get("name", ""), "dbType": db_type,
            "host": p.get("host", ""), "port": int(port),
            "username": p.get("username", ""), "password": p.get("password", ""),
            "databaseName": p.get("databaseName"), "sid": p.get("sid"),
            "serviceName": p.get("serviceName"), "extraParams": p.get("extraParams"),
            "driverVersion": p.get("driverVersion"), "note": p.get("note"),
        }

    def _create_ds(self, p):
        ds = self._normalize_ds(p)
        if not ds["name"] or not ds["dbType"] or not ds["host"] or not ds["username"]:
            return self._send(*fail(400, "名称 / 类型 / 主机 / 用户名为必填项"))
        conn = db()
        try:
            conn.execute(
                """INSERT INTO data_source
                   (name, db_type, host, port, username, password_enc, database_name,
                    sid, service_name, extra_params, driver_version, status, note)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,'OFFLINE',?)""",
                (ds["name"], ds["dbType"], ds["host"], ds["port"], ds["username"],
                 enc_pwd(ds["password"]), ds["databaseName"], ds["sid"], ds["serviceName"],
                 ds["extraParams"], ds["driverVersion"], ds["note"]))
            conn.commit()
        except sqlite3.IntegrityError:
            conn.close()
            return self._send(*fail(400, f"数据源名称已存在：{ds['name']}"))
        row = conn.execute("SELECT * FROM data_source WHERE name=?", (ds["name"],)).fetchone()
        conn.close()
        return self._send(*ok(self._ds_json(row)))

    def _update_ds(self, ds_id, p):
        ds = self._normalize_ds(p)
        conn = db()
        row = conn.execute("SELECT * FROM data_source WHERE id=?", (ds_id,)).fetchone()
        if not row:
            conn.close()
            return self._send(*fail(404, "数据源不存在"))
        # 密码留空表示不修改
        pwd = enc_pwd(ds["password"]) if ds["password"] else row["password_enc"]
        conn.execute(
            """UPDATE data_source SET name=?, db_type=?, host=?, port=?, username=?,
               password_enc=?, database_name=?, sid=?, service_name=?, extra_params=?,
               driver_version=?, note=?, updated_at=datetime('now','localtime')
               WHERE id=?""",
            (ds["name"], ds["dbType"], ds["host"], ds["port"], ds["username"], pwd,
             ds["databaseName"], ds["sid"], ds["serviceName"], ds["extraParams"],
             ds["driverVersion"], ds["note"], ds_id))
        conn.commit()
        row = conn.execute("SELECT * FROM data_source WHERE id=?", (ds_id,)).fetchone()
        conn.close()
        return self._send(*ok(self._ds_json(row)))


# ----------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="DB Navigator 本地预览服务")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()

    init_db()
    n_seed = seed_drivers()
    n_scan = scan_drivers()

    conn = db()
    try:
        insp = preview_inspection.seed(conn, RES_DIR)
        conn.commit()
    finally:
        conn.close()

    # 先绑定端口再打印启动横幅。
    # 反过来（先打印再 bind）会让横幅骗人：种子导入要好几秒，期间端口尚未 listen，
    # 看到横幅就立刻请求的客户端会直接被拒（curl 报 HTTP 000），
    # 表现为「服务明明说已就绪，第一个请求却失败」的偶发故障。
    # 构造 ThreadingHTTPServer 时就完成了 bind + listen，之后到达的连接会进入
    # 内核 backlog 排队，等 serve_forever() 一开就跑，客户端不会白等一次失败。
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)

    print("=" * 62)
    print("  DB Navigator · 本地预览服务（无需 JDK）")
    print("=" * 62)
    print(f"  数据库类型 : {len(DB_TYPES)} 种  ->  " +
          ", ".join(t["label"] for t in DB_TYPES))
    print(f"  驱动登记   : 种子导入 {n_seed} 条，目录扫描新增 {n_scan} 条，"
          f"合计 {len(driver_rows())} 条")
    print(f"  巡检配置   : 模板 {insp['templatesTotal']} 个 / 章节 "
          f"{insp['chaptersTotal']} 章 / 规则库 {insp['rulesTotal']} 条 / "
          f"章节引用 {insp['bindingsTotal']} 条 / 基线 {insp['baselinesTotal']} 条")
    print(f"  驱动目录   : {DRIVERS_DIR}")
    print(f"  元数据     : {DB_PATH}")
    print("-" * 62)
    print(f"  打开浏览器访问  http://{args.host}:{args.port}")
    print("=" * 62)
    print("  提示：本服务不建立真实 JDBC 连接。完整功能请启动 Java 后端。")
    print("=" * 62)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
        httpd.server_close()


if __name__ == "__main__":
    main()
