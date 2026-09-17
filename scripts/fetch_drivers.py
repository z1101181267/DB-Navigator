#!/usr/bin/env python3
"""
下载 JDBC 驱动 JAR 到 drivers/ 目录，使平台能连接真实数据库。

驱动不入库（.gitignore 排除 drivers/**/*.jar），因此新环境需要先跑一次本脚本。
清单来自 src/main/resources/drivers-seed.json —— 脚本按 (db_type, version) 找到上游
Maven 坐标，下载后**重命名**为种子声明的 jar_filename（注册表是按文件名匹配的）。

用法:
    python scripts/fetch_drivers.py                  # 补齐缺失的，已存在则跳过
    python scripts/fetch_drivers.py --force          # 全部重新下载
    python scripts/fetch_drivers.py --only mysql,oracle
    python scripts/fetch_drivers.py --check          # 只校验磁盘上现有的，不下载

环境变量:
    DBNAV_MAVEN_REPO   仓库地址，默认阿里云公共镜像
                       （KingbaseES 驱动只在镜像上有，Maven Central 没有）
"""

import argparse
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED = ROOT / "src" / "main" / "resources" / "drivers-seed.json"
DRIVERS = ROOT / "drivers"
REPO = os.environ.get("DBNAV_MAVEN_REPO", "https://maven.aliyun.com/repository/public").rstrip("/")

# (db_type, version) -> (仓库内相对路径, 上游文件名)
# 注意 oracle 三项：上游构件名与种子里的目标文件名不同，必须重命名。
# 种子版本号 "19.20.0.0" 描述的是目标数据库代次；ojdbc11 在 Maven Central 上没有 19.x，
# 故取 21.x 最新稳定版（向下兼容 19c）。
SOURCES = {
    ("oracle", "8"):          ("com/oracle/database/jdbc/ojdbc8/19.3.0.0/ojdbc8-19.3.0.0.jar", "ojdbc8-19.3.0.0.jar"),
    ("oracle", "6"):          ("com/oracle/database/jdbc/ojdbc6/11.2.0.4/ojdbc6-11.2.0.4.jar", "ojdbc6-11.2.0.4.jar"),
    ("oracle", "19.20.0.0"):  ("com/oracle/database/jdbc/ojdbc11/21.23.0.0/ojdbc11-21.23.0.0.jar", "ojdbc11-21.23.0.0.jar"),
    ("mysql", "8.0.33"):      ("com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar", "mysql-connector-j-8.0.33.jar"),
    ("mysql", "9.7.0"):       ("com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar", "mysql-connector-j-9.7.0.jar"),
    ("postgresql", "42.7.13"): ("org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar", "postgresql-42.7.13.jar"),
    ("sqlserver", "13.4.0.jre11"): ("com/microsoft/sqlserver/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.jar", "mssql-jdbc-13.4.0.jre11.jar"),
    ("kingbase", "9.0.0"):    ("cn/com/kingbase/kingbase8/9.0.0/kingbase8-9.0.0.jar", "kingbase8-9.0.0.jar"),
}

G, Y, R, DIM, OFF = "\033[32m", "\033[33m", "\033[31m", "\033[2m", "\033[0m"
if not sys.stdout.isatty() or os.name == "nt" and not os.environ.get("WT_SESSION"):
    G = Y = R = DIM = OFF = ""


def human(n):
    return f"{n/1048576:.1f} MB" if n >= 1048576 else f"{n/1024:.0f} KB"


def download(url, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "dbnav-fetch-drivers"})
    with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
    tmp.replace(dest)


def verify(path, driver_class):
    """JAR 必须是合法 zip 且包含声明的驱动类——防止把 HTML 错误页当成 JAR。"""
    cls = driver_class.replace(".", "/") + ".class"
    try:
        with zipfile.ZipFile(path) as z:
            return cls in z.namelist()
    except (zipfile.BadZipFile, OSError):
        return False


def main():
    ap = argparse.ArgumentParser(description="下载 JDBC 驱动 JAR 到 drivers/")
    ap.add_argument("--force", action="store_true", help="已存在也重新下载")
    ap.add_argument("--check", action="store_true", help="只校验现有文件，不下载")
    ap.add_argument("--only", default="", help="逗号分隔的库类型，如 mysql,oracle")
    args = ap.parse_args()

    seed = json.loads(SEED.read_text(encoding="utf-8"))["drivers"]
    only = {s.strip() for s in args.only.split(",") if s.strip()}

    print(f"仓库 : {REPO}")
    print(f"清单 : {SEED.relative_to(ROOT)}  ({len(seed)} 条)")
    print()
    print(f"{'库类型':<11}{'版本':<13}{'状态':<8}{'大小':>9}  {'目标文件'}")
    print("-" * 78)

    ok = skipped = failed = 0
    for s in seed:
        key = (s["db_type"], s["version"])
        if only and s["db_type"] not in only:
            continue

        target = DRIVERS / s["db_type"] / s["version"] / s["jar_filename"]
        src = SOURCES.get(key)

        # 已存在且通过校验 → 跳过（除非 --force）
        if target.exists() and not args.force:
            if verify(target, s["driver_class"]):
                print(f"{s['db_type']:<11}{s['version']:<13}{DIM}{'已存在':<8}{OFF}"
                      f"{human(target.stat().st_size):>9}  {s['jar_filename']}")
                skipped += 1
                continue
            print(f"{s['db_type']:<11}{s['version']:<13}{Y}{'损坏重下':<8}{OFF}"
                  f"{'':>9}  {s['jar_filename']}")
        elif target.exists() and args.force:
            pass
        elif args.check:
            print(f"{s['db_type']:<11}{s['version']:<13}{R}{'缺失':<8}{OFF}"
                  f"{'':>9}  {s['jar_filename']}")
            failed += 1
            continue

        if not src:
            print(f"{s['db_type']:<11}{s['version']:<13}{R}{'无源':<8}{OFF}"
                  f"{'':>9}  {s['jar_filename']}")
            print(f"{'':<11}{DIM}  未在 SOURCES 中登记上游坐标{OFF}")
            failed += 1
            continue

        rel, upstream = src
        url = f"{REPO}/{rel}"
        try:
            download(url, target)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as e:
            print(f"{s['db_type']:<11}{s['version']:<13}{R}{'下载失败':<8}{OFF}"
                  f"{'':>9}  {s['jar_filename']}")
            print(f"{'':<11}{DIM}  {type(e).__name__}: {str(e)[:80]}{OFF}")
            failed += 1
            continue

        size = target.stat().st_size
        if not verify(target, s["driver_class"]):
            target.unlink(missing_ok=True)
            print(f"{s['db_type']:<11}{s['version']:<13}{R}{'校验失败':<8}{OFF}"
                  f"{size:>9}  {s['jar_filename']}")
            print(f"{'':<11}{DIM}  不包含 {s['driver_class']}，已删除{OFF}")
            failed += 1
            continue

        mark = G + "完成" + OFF
        print(f"{s['db_type']:<11}{s['version']:<13}{mark:<17}{human(size):>9}  {s['jar_filename']}")
        ok += 1

    print("-" * 78)
    print(f"下载 {ok} · 跳过 {skipped} · 失败 {failed}")
    if failed:
        print()
        print("失败项可能原因：上游版本已下架、仓库不可达、或 SOURCES 里的坐标需要更新。")
    if skipped and not args.force:
        print(f"提示：加 {DIM}--force{OFF} 可强制重新下载。")
    print()
    print("下一步：重启后端（启动时会自动扫描 drivers/ 并登记），或调用 POST /api/drivers/scan")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
