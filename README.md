# DB Navigator

多数据库纳管平台：**JDBC 驱动管理 · 巡检配置与执行 · 报告留痕与多格式导出**。

面向**异构数据库 + 国产库混布**场景——同一套控制台里纳管 Oracle、MySQL、PostgreSQL、
SQL Server、KingbaseES，驱动可多版本共存、按需切换，巡检规则与阈值全部配置化落库。

纳管数据库：**Oracle · MySQL · PostgreSQL · SQL Server · KingbaseES（人大金仓）**
自检数据库：**H2（内置驱动，无需外部实例，用于端到端验证巡检链路）**

Web 控制台七个顶级菜单：数据源纳管 · 驱动管理 · 巡检配置 · **基线规则** · 巡检执行 · **巡检历史** · SQL 编辑器。
其中「巡检历史」支持报告**在线预览**与 **Word / PDF / HTML 下载**。

---

## 一、设计出发点

多库纳管最容易踩坑的不是 SQL，而是**驱动**与**配置**这两件事：驱动 JAR 版本冲突、
换机后绝对路径失效、升级驱动怕影响存量连接、库类型定义散落在 `if-else` 链里、
巡检规则写死在代码里……本项目把这些问题逐条拆开，用「元数据外置 + 表驱动」的方式解决：

| 真实痛点 | 设计应对 | 实现落点 |
|---|---|---|
| 不同库驱动 JAR 版本冲突、无法共存 | `drivers/<db_type>/<version>/<jar>` 目录分层 | `DriverPathResolver` 三层路径解析 |
| 打包/换机后绝对路径失效 | 按文件名重定位 | `resolveJarPath()` 三级回退扫描 |
| 新增库要改代码 | 驱动元数据存表 + 目录扫描自动登记 | `DriverRegistry` + `DriverDirectoryScanner` |
| 升级驱动不敢动，怕影响存量连接 | `is_active` 激活位，同类型仅一个生效 | `activateDriver()` 先清后置 |
| 驱动下载源不可控（信创内网） | 驱动随包分发 + 手工上传 | 上传接口 + 种子导入 |
| 库类型定义散落在 `elif` 链里 | 外置 JSON 元数据 | `db-types.json` + `DbTypeMeta` |
| 巡检规则散落在各库分支代码里 | 模板 → 章节 → 规则 三级配置化落库 | `inspection_*` 五表 + 两级级联 |
| 阈值判定逻辑按库各写一遍 | 统一算子语义 + 语义枚举序 | `BaselineChecker` 单点实现 |
| 巡检跑完只出一份 HTML，过后查不到 | 执行记录 + 明细表持久化 | `inspection_run` + 两张明细表，`/runs/latest` 支持趋势对比 |
| 报告格式固定、不可二次加工 | 同一份数据渲染多格式 | 同一份 HTML 渲染出 HTML/PDF，另用 POI 生成可编辑 .docx |
| 巡检链路无法在无库环境验证 | 内置自检库类型，免外部依赖 | 内置 `h2` 自检类型，`driver_bundled` 免 JAR |

---

## 二、驱动管理体系

### 2.1 目录布局

`drivers/<db_type>/<version>/<jar>` 目录约定：

```
drivers/
├── oracle/
│   ├── 8/          ojdbc8.jar
│   ├── 6/          ojdbc6.jar
│   └── 19.20.0.0/  ojdbc11.jar
├── mysql/
│   └── 8.0.33/     mysql-connector-j-8.0.33.jar
├── postgresql/
│   └── 42.7.13/    postgresql-42.7.13.jar
├── sqlserver/
│   └── 13.4.0.jre11/  mssql-jdbc-13.4.0.jre11.jar
└── kingbase/
    └── 9.0.0/      kingbase8-9.0.0.jar
```

**JAR 不随代码提交**，仅目录与 `README.md` 说明入库；部署时放入对应目录即可，无需改代码。

### 2.2 驱动注册表

元数据存于内嵌 H2，字段如下：

| 字段 | 说明 |
|---|---|
| `db_type` / `version` / `jar_filename` | 联合唯一键 `UNIQUE(db_type, version, jar_filename)` |
| `driver_class` | 全限定驱动类名，如 `com.kingbase8.Driver` |
| `jar_path` / `file_size` | 物理路径与大小（路径失效时按文件名重定位） |
| `is_active` | 激活位，**同一 `db_type` 仅一个为 1** |
| `uploaded_at` / `note` | 上传时间与备注 |

### 2.3 三条自愈链路

1. **种子导入** `DriverSeedLoader` — 首次启动且表为空时，从 `drivers-seed.json` 导入元数据（不含 `jar_path`，按文件名解析）。
2. **目录扫描** `DriverDirectoryScanner` — 扫描 `drivers/` 实况，自动登记未入库的 JAR（幂等，靠唯一键冲突跳过）。
3. **路径重定位** `DriverPathResolver` — 存储路径失效时，按 `类型/版本/文件名` → `类型/文件名` → `根/文件名` 三级回退查找。

### 2.4 版本共存与激活

- 同类型可登记**多版本**（如 Oracle `8` / `6` / `19.20.0.0` 并存）
- `activateDriver()` 先 `is_active=0` 清空同类型，再置目标为 1
- `deleteDriver()` 删除激活项时，自动激活同类型最新一条（`uploaded_at DESC`）
- `addDriver()` 若该类型**尚无激活驱动**，自动激活新上传的

---

## 三、纳管数据库与驱动清单

| 数据库 | `db_type` | 默认端口 | 驱动类 | 推荐 JAR |
|---|---|---|---|---|
| Oracle | `oracle` | 1521 | `oracle.jdbc.OracleDriver` | `ojdbc8.jar` |
| MySQL | `mysql` | 3306 | `com.mysql.cj.jdbc.Driver` | `mysql-connector-j-8.0.33.jar` |
| PostgreSQL | `postgresql` | 5432 | `org.postgresql.Driver` | `postgresql-42.7.13.jar` |
| SQL Server | `sqlserver` | 1433 | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | `mssql-jdbc-13.4.0.jre11.jar` |
| KingbaseES | `kingbase` | 54321 | `com.kingbase8.Driver` | `kingbase8-9.0.0.jar` |
| H2（自检） | `h2` | — | `org.h2.Driver` | **内置**（`driver_bundled`，无需 JAR） |

**连接 URL 模板**（定义于 `db-types.json`）：

```
Oracle      jdbc:oracle:thin:@//{host}:{port}/{service_name}     # 或 SID 模式 @{host}:{port}:{sid}
MySQL       jdbc:mysql://{host}:{port}/{database}?useSSL=false&serverTimezone=UTC&...
PostgreSQL  jdbc:postgresql://{host}:{port}/{database}
SQL Server  jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=false;...
KingbaseES  jdbc:kingbase8://{host}:{port}/{database}
H2（自检）  jdbc:h2:mem:dbnav_selfcheck;DB_CLOSE_DELAY=-1          # 库名固定，host/port 仅占位
```

> KingbaseES 虽为 PostgreSQL 兼容协议，但使用**自有驱动类** `com.kingbase8.Driver` 与独立 URL 前缀，不能直接复用 PG 驱动。

> H2 是**自检类型**，不属于纳管目标：驱动随应用 classpath 提供，创建数据源时无需上传 JAR，
> 表单里的主机/端口不参与连接。它的用途是让巡检链路在没有任何外部数据库的机器上也能真实跑通。

### 驱动来源（`scripts/fetch_drivers.py` 的下载坐标）

| 库类型 | 版本 | 目标文件 | 上游构件 |
|---|---|---|---|
| Oracle | `8` | `ojdbc8.jar` | `com.oracle.database.jdbc:ojdbc8:19.3.0.0` |
| Oracle | `6` | `ojdbc6.jar` | `com.oracle.database.jdbc:ojdbc6:11.2.0.4` |
| Oracle | `19.20.0.0` | `ojdbc11.jar` | `com.oracle.database.jdbc:ojdbc11:21.23.0.0` |
| MySQL | `8.0.33` | `mysql-connector-j-8.0.33.jar` | `com.mysql:mysql-connector-j:8.0.33` |
| MySQL | `9.7.0` | `mysql-connector-j-9.7.0.jar` | `com.mysql:mysql-connector-j:9.7.0` |
| PostgreSQL | `42.7.13` | `postgresql-42.7.13.jar` | `org.postgresql:postgresql:42.7.13` |
| SQL Server | `13.4.0.jre11` | `mssql-jdbc-13.4.0.jre11.jar` | `com.microsoft.sqlserver:mssql-jdbc:13.4.0.jre11` |
| KingbaseES | `9.0.0` | `kingbase8-9.0.0.jar` | `cn.com.kingbase:kingbase8:9.0.0`（仅镜像） |

前 7 个目标文件与种子里声明的 `file_size` **逐字节吻合**。唯一的例外是 Oracle `19.20.0.0`：
`ojdbc11` 在 Maven Central 上**没有 19.x 版本**（起始就是 21.1.0.0），种子里的 4981936 字节
在任何公开构件上都找不到，因此取 **21.23.0.0**（向下兼容 19c）作为该槽位的驱动，
大小 5254720 字节。种子里的 `19.20.0.0` 描述的是目标数据库代次，不是驱动构建号。

---

## 四、驱动加载机制

用 **`URLClassLoader` 在运行时加载 JDBC 驱动**，无需预先配置 classpath，也无需重启进程：

```java
// DynamicDriverLoader
new URLClassLoader(new URL[]{jarUrl}, ClassLoader.getPlatformClassLoader());
//                                                    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
// 关键：父加载器必须是平台加载器。Java 9+ 中 java.sql.Driver 属于 java.sql
// 平台模块，若用 parent=null（bootstrap）将无法解析，导致 ClassNotFoundException。
```

- 每个 `db_type::version` 一个 ClassLoader，缓存在 `ConcurrentHashMap`
- 驱动实例同样缓存，避免重复 `Class.forName()`
- 删除/换激活版本时 `evict()` 释放 ClassLoader
- **`driver_bundled` 例外**：当驱动已随应用 classpath 提供（如 H2 自检类型），不走 `URLClassLoader`，
  而是用 `getClass().getClassLoader()` 直接取已加载的类。此时连接测试返回
  `driverVersion="bundled"`、`driverBundled=true`，前端据此提示「驱动随应用内置，无需上传 JAR」。
  ——注意**不能用** `getPlatformClassLoader()`：bundled 驱动在应用类加载器上，平台加载器看不到它。

---

## 五、API 一览

### 驱动管理 `/api/drivers`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/types` | 列出支持的所有数据库类型 |
| GET | `?dbType=oracle` | 列出驱动（可按类型过滤） |
| GET | `/active/{dbType}` | 获取当前激活驱动 |
| POST | `/upload` | 上传注册 JAR（`file` / `dbType` / `version` / `driverClass`） |
| PUT | `/{id}/activate` | 激活指定版本 |
| DELETE | `/{id}` | 删除驱动（自动接任激活） |
| POST | `/scan` | 手动触发目录扫描 |
| GET | `/path/{dbType}/{version}` | 查询期望的 JAR 存放目录 |

### 数据源纳管 `/api/datasources`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 列出全部数据源 |
| POST | `/` | 新建数据源（密码 AES-256-GCM 加密存储） |
| PUT | `/{id}` | 更新数据源 |
| DELETE | `/{id}` | 删除数据源（关闭连接池） |
| POST | `/{id}/test` | 测试连接 |
| POST | `/test` | 免保存内联测试 |
| GET | `/types` | 数据库类型元数据 |
| GET | `/defaults/{dbType}` | 默认端口/用户/URL 模板 |

### SQL 执行 `/api/query`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/{datasourceId}/execute` | 执行 SQL（SELECT 返回结果集，DML 返回影响行数） |
| GET | `/{datasourceId}/history` | 查询执行历史 |

### 巡检配置 `/api/inspection`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/summary` | 总览：模板/章节/规则数、基线数、风险分布 |
| GET | `/history?limit=200` | 变更历史（模板/章节/规则/基线的增删改留痕） |
| GET | `/templates?dbType=mysql` | 模板列表 |
| GET | `/templates/{id}/tree` | 模板树（含章节与规则，`onlyEnabled=true` 仅启用项） |
| GET | `/templates/default/{dbType}/tree` | 该类型默认模板的树 |
| POST | `/templates` | 新建模板 |
| PUT | `/templates/{id}` | 更新模板（预置模板名称/版本受保护） |
| POST | `/templates/{id}/default` | 设为该类型默认模板 |
| DELETE | `/templates/{id}?force=true` | 删除模板（级联章节与规则；预置需 `force`） |
| GET | `/templates/{id}/chapters` | 章节列表 |
| POST | `/chapters` | 新建章节（缺省时自动取 `MAX(chapter_number)+1`） |
| PUT | `/chapters/{id}` | 更新章节 |
| DELETE | `/chapters/{id}` | 删除章节（级联规则） |
| GET | `/chapters/{id}/queries` | 规则列表 |
| POST | `/queries` | 新建规则（`key` 在同章节内唯一） |
| PUT | `/queries/{id}` | 更新规则（`key` 不可变更） |
| DELETE | `/queries/{id}` | 删除规则 |
| GET | `/baselines?dbType=oracle` | 基线列表 |
| POST | `/baselines` | 新建基线（校验 operator / riskLevel / BETWEEN 区间） |
| PUT | `/baselines/{id}` | 更新基线 |
| DELETE | `/baselines/{id}` | 删除基线 |
| POST | `/baselines/enabled?dbType=&enabled=` | 按类型批量启用/停用 |
| POST | `/baselines/{id}/check` | 单条校验（body：`{actualValue}`） |
| POST | `/baselines/check` | 批量校验（body：`{dbType, values:{参数名: 实测值}}`） |

### 巡检执行 `/api/inspection`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/run` | 对数据源发起一次巡检（body：`{dataSourceId, templateId?, onlyEnabled?, collectBaselines?}`），同步返回完整报告 |
| GET | `/runs?dataSourceId=&dbType=&limit=50` | 执行记录列表（不含明细，按开始时间倒序） |
| GET | `/runs/latest?dataSourceId=` | 该数据源最近一次执行（含明细），无记录返回 `404` |
| GET | `/runs/{id}` | 单次执行详情（含规则结果与基线判定明细） |
| GET | `/runs/{id}/export?format=html\|word\|pdf&download=` | **导出报告**（见 §7.6） |
| DELETE | `/runs/{id}` | 删除执行记录（级联明细），不存在返回 `404` |

**错误分层**——请求级错误与执行级错误分开表达：

| 情况 | 表现 |
|---|---|
| `dataSourceId` 为空 / 数据源不存在 / 模板不存在 | `HTTP 400`，**不落执行记录** |
| 连不上库、驱动缺失、模板无可执行规则 | `HTTP 200` + `status=FAILED` 记录（带 `errorMsg`） |
| 某条规则 SQL 不兼容 | 该条记 `FAILED`，其余照跑，整次置 `PARTIAL` |

「连不上库」属于巡检这一操作的正常业务结果（与「SQL 执行失败仍是 200」同一逻辑），
因此返回 200 并留痕，让报告能说清失败原因，而不是把运维人员挡在一个 HTTP 错误码前。

---

## 六、巡检配置体系

巡检配置落库为 H2 关系表：模板 → 章节 → 规则三级配置化，阈值判定收敛到单点实现。

### 6.1 八张表

```
inspection_template ──┬── inspection_chapter ──┬── inspection_query
   (模板, 按库类型)     │      (章节, 1..N)       │      (规则, 1..N)
                       │                        │
                       └── 两级 ON DELETE CASCADE ┘

inspection_baseline    基线阈值（按库类型，独立于模板）
inspection_history     变更留痕（模板/章节/规则/基线的增删改）

inspection_run ──┬── inspection_run_query     (逐条规则结果)
   (执行记录)     └── inspection_run_baseline  (逐条基线判定)
      └── 两级 ON DELETE CASCADE ┘
```

| 表 | 关键字段 | 约束 |
|---|---|---|
| `inspection_template` | `db_type` / `template_name_zh` / `version` / `is_default` / `is_preset` | `UNIQUE(db_type, template_name_zh)`；同类型仅一个 `is_default=1` |
| `inspection_chapter` | `template_id` / `chapter_number` / `chapter_title_zh` / `enabled` / `sort_order` | `UNIQUE(template_id, chapter_number)`；`ON DELETE CASCADE` |
| `inspection_query` | `chapter_id` / `query_key` / `query_sql` / `query_description_zh` / `enabled` | `UNIQUE(chapter_id, query_key)`；`ON DELETE CASCADE` |
| `inspection_baseline` | `db_type` / `param_name` / `operator` / `expected_value` / `expected_value_min|max` / `risk_level` | `UNIQUE(db_type, param_name)` |
| `inspection_history` | `table_name` / `record_id` / `action` / `old_value` / `new_value` | 仅追加 |
| `inspection_run` | `data_source_id` / `template_id` / `status` / `started_at` / `duration_ms` / `total_queries` / `ok_queries` / `failed_queries` / `total_baselines` / `pass_baselines` / `fail_baselines` / `unchecked_baselines` / `compliance` / `risk_summary` / `operator` / `error_msg` | `status ∈ {SUCCESS, PARTIAL, FAILED}` |
| `inspection_run_query` | `run_id` / `chapter_number` / `query_key` / `status` / `row_count` / `truncated` / `duration_ms` / `error_msg` / `columns_json` / `rows_json` | `status ∈ {OK, FAILED, SKIPPED}`；`ON DELETE CASCADE` |
| `inspection_run_baseline` | `run_id` / `param_name` / `operator` / `expected_value` / `actual_value` / `is_pass` / `is_checked` / `sample_count` / `violation_count` / `risk_level` / `message` | `ON DELETE CASCADE` |

> `query_key` 与 `chapter_number` **创建后不可变更**——它们是巡检结果归因的稳定标识。前端编辑态会将这两个字段置为只读。

### 6.2 基线算子语义

支持 `= > < >= <= != BETWEEN LIKE` 七种算子，风险等级 `LOW / MEDIUM / HIGH / CRITICAL`。

判定顺序（`BaselineChecker`）：

1. **`LIKE`** → 期望值作为子串匹配（大小写不敏感）
2. **`BETWEEN`** → 实测值需落在 `[min, max]` 闭区间内
3. **数值比较** → 两侧都能解析为数字（支持 `128MB` / `4GB` / `1.5G` 等单位后缀归一化）时按数值比较
4. **语义枚举序** → 双方都是已知语义等级时按**等级序**而非字典序比较。例如
   `wal_level`：`minimal < replica < logical`；`recovery model`：`simple < bulk_logged < full`
5. **字符串比较** → 兜底

实测值缺失（`null`）时判定为「未采集」，不计入合规率分母。注意**空字符串是合法采集值**，只有 `null` 才算未采集。

### 6.3 预置内容

| 库类型 | 章节 | 规则 | 基线 |
|---|---:|---:|---:|
| Oracle | 21 | 26 | 12 |
| MySQL | 21 | 30 | 13 |
| PostgreSQL | 21 | 30 | 13 |
| SQL Server | 21 | 25 | 12 |
| KingbaseES | 21 | 23 | 13 |
| **H2（内置自检）** | **8** | **26** | **25** |
| **合计** | **113** | **160** | **88** |

- 五个外部库类型的章节覆盖 21 个巡检维度（连接、配置、存储、复制、锁、慢查询、安全、备份等）
- 基线阈值为内置默认值，风险分布：`CRITICAL 1 / HIGH 17 / MEDIUM 26 / LOW 19`
  （唯一的 `CRITICAL` 是 SQL Server 的 `HAS_DBACCESS`）
- **H2 一组是内置自检数据**：它让「驱动加载 → 规则执行 → 结果集预览 → 基线采集判定 → 落库与报告」
  整条链路在没有外部数据库的机器上也能**真实跑通**，而不是靠 mock。详见 §7.4。

种子文件位于 `src/main/resources/inspection/`：

- `templates.json` — 6 个模板 × 113 章节 × 160 规则
- `baselines.json` — 88 条基线

导入策略与驱动一致：**表为空时才导入**，幂等，不覆盖用户改动。预置模板 `is_preset=1`，默认拒绝删除，名称与版本受保护。

### 6.4 关键实现落点

| 环节 | 落点 |
|---|---|
| 种子导入入口 | `InspectionConfigService.seedIfEmpty()`（两个表各自判空，幂等） |
| 模板种子 | `inspection/templates.json` → `loadTemplateSeed()` |
| 基线种子 | `inspection/baselines.json` → `loadBaselineSeed()` |
| 基线判定 | `BaselineChecker.check()`，内部按算子分派到 `evaluate()` |
| 两级级联删除 | H2 `ON DELETE CASCADE`（模板 → 章节 → 规则） |
| 变更留痕 | `inspection_history` + `recordHistory()` |

---

## 七、巡检执行体系

巡检结果落库为「执行记录 + 明细」的持久化模型，而不是执行完就丢掉。这样做的收益是能回答
「这次和上次比，哪几条基线从不合规变成合规了」——而这正是巡检的价值所在。

### 7.1 执行状态语义

| 状态 | 含义 |
|---|---|
| `SUCCESS` | 全部规则执行成功（停用规则不算失败） |
| `PARTIAL` | 部分规则失败，但整体仍产出可用结果 |
| `FAILED` | 连不上库 / 驱动缺失 / 模板里没有可执行规则，报告不可用 |

### 7.2 执行流程

`InspectionRunner.run()` 的主线：

```
校验 dataSourceId
  → 查数据源（不存在 → IllegalArgumentException → HTTP 400，不落记录）
  → 解析模板：显式 templateId > 该 db_type 的默认模板
  → 开一条连接（失败 → 落一条 status=FAILED 记录并写 errorMsg）
  → executeQueries   按「章节号 → 规则 sort_order」顺序逐条执行
  → executeBaselines 逐条采集基线实测值并判定
  → aggregate        汇总统计、判定整体 status、算合规率
  → persist          写 inspection_run 表头取自增 id，再写两张子表
  → 回填 queries/baselines 返回完整报告
```

几处刻意的设计：

- **`onlyEnabled=true` 时，停用的章节/规则仍记一条 `SKIPPED`**。让报告能说清「160 条里有 N 条是停用的」，
  而不是静默消失让人误以为漏跑。
- **结果集预览保留前 200 行**（`PREVIEW_ROWS`），单条规则最多扫描 5000 行（`MAX_SCAN_ROWS`）。
  超出时置 `truncated=1`，前端提示「已截断」。
- **SQL 去尾分号后再执行**——Oracle 对结尾 `;` 会报 `ORA-00911`，而运维写的巡检语句常常带分号。
- **预览用的 JSON 不走 `ConfigJsonMapper`**（那个是 snake_case，给配置文件用的），
  另用一个普通 `ObjectMapper`，保持前端拿到的字段名是 camelCase。

### 7.3 多行基线语义

采集 SQL 常按库/按实例返回多行，例如 SQL Server 的：

```sql
SELECT name, is_auto_close_on FROM sys.databases
```

因此基线判定是**逐行判定、整体归并**：

- **只要有一行不合规，整条基线即判为不合规**
- `sampleCount` 记录采集到多少行，`violationCount` 记录其中多少行不合规
- 判定说明在单行时复用判定器的原始措辞；多行时给出
  「共采集 N 行，其中 M 行不满足…（首个不合规值：x）」

**取值列约定：取结果集最后一列。** 一条规则同时覆盖三种常见写法：

| 写法 | 结果集 | 取值列 |
|---|---|---|
| `SHOW wal_level` | `Variable_name, Value` | `Value` |
| `SELECT name, value FROM v$parameter` | `name, value` | `value` |
| `SELECT @@max_connections` | 单列 | 该列 |

「未采集」的两种来源：采集返回 0 行（`samples == 0`），或所有行的值列都是 `NULL`（`checkedRows == 0`）。
两种情况都置 `isChecked=0`，**不计入合规率分母**。

**合规率 = `pass / (pass + fail) × 100`**，保留一位小数。未采集既不算通过也不算不通过。

### 7.4 H2 自检类型（`h2`，🧪）

这是为了让巡检链路**可端到端验证**而内置的库类型，不需要任何外部数据库实例。

- 驱动在应用 classpath 上（H2），因此 `driver_bundled = true`：**无需上传 JAR、无需注册表记录**
- `DynamicDriverLoader` 在这种情形下用 `getClass().getClassLoader()` 而非 `getPlatformClassLoader()`，
  否则类加载器隔离会导致驱动找不到
- 连接 URL 固定库名：`jdbc:h2:mem:dbnav_selfcheck;DB_CLOSE_DELAY=-1`
  （`db-types.json` 里的主机/端口仅为表单占位，不参与连接）
- 模板 8 章 26 条规则**全部只读** `INFORMATION_SCHEMA` 与内置函数，不写库、不建表
- 基线刻意包含会「不合规」与「未采集」的条目，用来覆盖三种判定结果：
  `h2_compress`、`h2_recompile_always`（H2 默认值与期望值不符 → 不合规），
  `h2_default_lock_timeout`（默认实例不暴露该设置 → 未采集）

### 7.5 执行结果的可取用形态

| 形态 | 落点 / 接口 |
|---|---|
| 持久化 | `inspection_run` + `inspection_run_query` + `inspection_run_baseline` 三张表 |
| 执行记录列表 | `GET /api/inspection/runs` |
| 单次执行明细 | `GET /api/inspection/runs/{id}` |
| 最近一次结果（用于趋势对比） | `GET /api/inspection/runs/latest` |
| 报告导出 | `GET /api/inspection/runs/{id}/export?format=html\|word\|pdf` |
| 免 JAR 运行 | `driver_bundled` 内置驱动（H2 自检类型） |

### 7.6 报告导出（HTML / Word / PDF）

三种格式共用同一份数据，但渲染路径不同——这是刻意的设计：

| 格式 | 实现 | 说明 |
|---|---|---|
| **HTML** | 手写独立模板（内联 CSS） | **主渲染器**。自带样式、不依赖主界面 CSS，可直接打开/打印，也用于「在线预览」 |
| **PDF** | openhtmltopdf 渲染**同一份 HTML** | 复用 HTML 渲染器 ⇒ HTML 与 PDF 的内容永远一致，不会各写一套而漂移 |
| **Word** | Apache POI 生成**真 .docx** | 不是把 HTML 改后缀成 `.doc`，下载后能继续编辑 |

导出接口是本项目**唯一不返回 `Result` 包装**的端点（它要吐字节流），
但出错时仍返回 `Result` 形状的 JSON，前端一套错误处理就能覆盖下载失败：

| 情况 | 表现 |
|---|---|
| 记录不存在 | `HTTP 404` + JSON |
| `format` 不是 html/word/pdf | `HTTP 400` + JSON（列出可选值） |
| 找不到可嵌入的中文字体 | `HTTP 500` + JSON，**原样带出原因** |

最后一条值得展开：PDF 的中文依赖**嵌入 TTF 字体**（PDFBox 的 `PDType0Font`
**不能**加载 `.ttc` 字体集合）。找不到字体时宁可报错，也不回退成一份「中文全是方框」的
PDF——那种文件比一条明确的错误难排查得多。候选字体按序探测，可用
`dbnav.report.pdf-font` 显式指定。

文件名通过 `Content-Disposition` 同时给出 ASCII 兜底与 RFC 5987 的 `filename*=UTF-8''…`，
保证中文报告名在各浏览器下都不乱码。`format=html` 默认 `inline`（供 iframe 在线预览），
加 `&download=1` 变为 `attachment`。

**报告 CSS 的三个约束**（都是被 openhtmltopdf 的实际行为逼出来的）：

1. 必须写成**严格 XHTML**——openhtmltopdf 用 TRaX 的 XML 解析器，不是宽容的 HTML 解析器。
   一个没自闭合的 `<meta>` 就直接抛 `SAXParseException`。
2. **不能用 flex 做主要布局**。实测 `.metric` 上的 `flex: 1 1 120px` 不被识别，
   四个指标卡会退化成块级元素竖向堆叠——HTML 里正常、PDF 里难看，同一份 CSS 两边表现不一致。
   改用 `display: table / table-cell`，两边都稳定。
3. `esc()` 除了五个实体字符，还要**滤掉非法控制字符**。XML 1.0 只允许 `\t \n \r`，
   JDBC 的 `errorMsg` 里带 `U+0000..U+001F` 是常事（驱动把二进制响应直接拼进异常消息），
   碰上一个就整份报告渲染失败。

### 7.7 预览服务对导出接口的态度：如实 501

`devserver.py` 只有 Python 标准库，没有 POI / openhtmltopdf，也做不了字体嵌入。
因此导出接口在预览服务下**恒定返回 `501`**，且响应体里**不含**任何像文档的东西。

这是刻意的：一份内容对不上的 `.docx` 比一个明确的「不支持」难排查得多。
校验顺序（先 404 → 再 400 → 最后 501）与 Java 侧完全一致，否则同一个请求
在两套实现下会得到不同状态码，`parity_check.py` 会把这种差异当成契约漂移报出来——
而且它确实就是漂移。

`parity_check.py` 专门有一段断言这个**已声明差异**（java=200 / python=501），
把「不一致」显式写进检查项，而不是绕过它。

---

## 八、快速开始

### 方式 A · 本地预览（无需 JDK，立即可用）

`devserver.py` 用 Python 标准库复刻了同一套 REST 契约，可直接预览 Web 控制台：

```bash
python devserver.py --port 8080
# 打开 http://127.0.0.1:8080
```

仅需 Python 3.8+，无第三方依赖。它复用**同一份前端**与**同一套 API 契约**（驱动、数据源、巡检配置与巡检执行全部可用，
巡检实现见 `scripts/preview_inspection.py`），适合界面预览与联调；
但**不建立真实 JDBC 连接**——「测试连接」做的是端口可达性探测，「执行 SQL」返回演示响应。

巡检执行在预览服务下会**如实**产出一条 `status=FAILED` 的执行记录（写明「预览服务无 JDBC 层」），
而不是伪造一份看起来正常的报告——**假报告会让契约比对失去意义**。
想验证完整的巡检链路，请用方式 B 并建一个 `h2` 自检数据源。

### 方式 B · 完整后端（需 JDK 17 + Maven）

```bash
# 1. 下载 JDBC 驱动 JAR（8 个，约 20 MB，从阿里云 Maven 镜像）
python scripts/fetch_drivers.py

# 2. 启动
mvn spring-boot:run

# 3. 打开 http://localhost:8080
```

驱动 JAR **不入版本库**（`.gitignore` 排除 `drivers/**/*.jar`），所以新环境必须先跑一次
`fetch_drivers.py`。它会按 `drivers-seed.json` 的清单下载并**重命名为种子声明的文件名**
（注册表是按文件名匹配的），逐个校验 JAR 是合法 zip 且**真的包含声明的驱动类**——
防止把 HTML 错误页当成 JAR 存下来。

```bash
python scripts/fetch_drivers.py --check          # 只体检，不下载（CI 可用，缺失则退出码 1）
python scripts/fetch_drivers.py --only mysql     # 只取某几类
python scripts/fetch_drivers.py --force          # 全部重下
```

> **KingbaseES 驱动只在阿里云镜像上有**，Maven Central 没有，所以脚本默认走
> `https://maven.aliyun.com/repository/public`（可用 `DBNAV_MAVEN_REPO` 覆盖）。

> **没有外部数据库也能验证巡检？** 新建数据源时选 **H2（内置自检）** 类型——
> 驱动随应用内置（无需放 JAR），点「测试连接」应返回 `driverVersion="bundled"`，
> 然后到「巡检执行」选它发起一次巡检，即可看到一份真实的报告（26 条规则全跑、基线三态齐全）。

前端静态资源位于 `src/main/resources/static/`，Spring Boot 启动后由同一端口提供，
前端自动识别后端并切换到真实模式。

启动时会依次执行：建表 → 驱动种子导入 → 目录扫描 → 激活校验 → 巡检模板/基线种子导入，全部幂等。

### 驱动的「声明式」与「观测式」字段

`jdbc_driver_registry` 里的字段分两类，**更新时机不同**——踩过一次坑，写在这里：

| 类别 | 字段 | 何时更新 |
|---|---|---|
| **声明式**（种子拥有） | `note`、`driver_class` | **仅当注册表为空时**由种子导入写入。平台没有编辑驱动的接口，所以这些字段改种子文件对**已有环境无效** |
| **观测式**（磁盘拥有） | `jar_path`、`file_size` | **每次启动扫描**都按磁盘实际值校正 |

因此 `drivers-seed.json` 的 `_comment` 里明确写了：改声明式字段只对全新库生效，
要刷新已有环境需清空 `jdbc_driver_registry` 后重启。

观测式字段的自动校正不是可有可无的：种子导入时若 JAR 还没放上去，记录里存的是
**种子声明的** `file_size`；JAR 后来才落地（或被换成别的构建）时，若扫描不刷新，
控制台就会**永远显示一个和磁盘不符的大小**。扫描只对齐能观测到的事实，
绝不覆盖声明式字段。

> **内网/国内环境**：仓库自带 `.mvn/settings.xml`，指向阿里云公共镜像，避免从 Maven Central 拉包过慢。
> 显式使用：`mvn -s .mvn/settings.xml spring-boot:run`。该文件不影响全局 `~/.m2/settings.xml`。

### 生产环境务必设置密钥

数据源密码使用 AES-256-GCM 加密落库，密钥由环境变量经 SHA-256 派生：

```bash
export DBNAV_SECRET_KEY="your-strong-passphrase"
```

> 未设置时使用内置开发口令，**上线前必须覆盖**。

### 响应约定

所有接口统一返回 `{"code": 200, "message": "success", "data": ...}`。

**HTTP 状态码与响应体 `code` 保持一致**（由 `ApiStatusAdvice` 统一后置映射）：
业务错误返回 `HTTP 400/404` 而非 `HTTP 200 + code=400`，便于 `curl -f`、网关重试与监控告警直接判断。

唯一例外：SQL 执行失败是通过 `Result.ok(...)` 内嵌 `success=false` 表达的，外层仍是 `HTTP 200`——
因为「语句报错」是查询这一操作的正常业务结果，不是接口调用失败。

---

## 九、构建与验证

### 四个验证脚本

项目自带四个可重复执行的验证脚本，覆盖「接口契约 → 双实现一致性 → 前端逻辑 → 真实浏览器渲染」四层：

| 脚本 | 作用 | 用法 |
|---|---|---|
| `scripts/api_test.py` | 巡检配置 + 巡检执行 + **报告导出** API 全量往返测试（CRUD、约束拒绝、级联删除、算子语义、执行状态机、三格式导出的文件头与文件名） | `python scripts/api_test.py [base_url]` |
| `scripts/parity_check.py` | 比对 Java 与 Python 两套实现的响应体与错误码，防止契约漂移；并显式断言导出接口的**已声明差异** | `python scripts/parity_check.py [java_url] [python_url]` |
| `scripts/dom_smoke.js` | 在 jsdom 中真实执行 `app.js`，验证渲染、页签、弹窗、驱动两栏布局、巡检执行与巡检历史交互 | `NODE_PATH=<node_modules> node scripts/dom_smoke.js` |
| `scripts/ui_guard.js` | **真实浏览器**（headless Edge/Chrome + CDP）验证元素显隐、条件字段联动、两栏真实几何、iframe 预览加载、按钮配色 | `node scripts/ui_guard.js` |

`dom_smoke.js` 需要 `jsdom`；`ui_guard.js` **零依赖**（用 Node 22 内置 `WebSocket` 直连 CDP，不需要 playwright/puppeteer）。

另有两个**工具脚本**（不参与契约验证）：

| 脚本 | 作用 | 用法 |
|---|---|---|
| `scripts/fetch_drivers.py` | 下载 JDBC 驱动 JAR 到 `drivers/`，逐个校验含声明的驱动类 | `python scripts/fetch_drivers.py [--check\|--force\|--only X]` |
| `scripts/PdfInspect.java` | PDF 报告体检：提取文本 / 列出嵌入字体 / 栅格化成 PNG | 见下方 |

```bash
npm install jsdom
NODE_PATH=./node_modules node scripts/dom_smoke.js
```

### 为什么必须有真实浏览器这一层

`jsdom` 不跑 CSS，因此**抓不到「元素该藏起来却显示」这类缺陷**。实测 jsdom 30 的
`getComputedStyle` 对带 `hidden` 属性的元素**一律**返回 `display: none`，
即使样式表里明确写着 `.modal-mask { display: flex }`——它没有忠实实现 CSS 级联。
换句话说，**用 jsdom 验证这类问题是无效的**：脚本会永远通过。

`ui_guard.js` 补上这一层，做的是 jsdom 做不到的事：

- 真实 CSS 级联（谁覆盖谁）
- 真实布局（`getBoundingClientRect` 给出实际尺寸，而非仅看声明）
- 真实渲染结果（iframe 里的报告**真的加载进来了吗**——读 `contentDocument` 确认，
  而不是只断言 `src` 属性写对了）

它守的第一类缺陷是**类选择器覆盖 `hidden` 属性**：

> UA 样式表的 `[hidden] { display: none }` 优先级低于作者样式表的类选择器。
> 所以只要有人写出 `.modal-mask { display: flex }`，`hidden` 属性就彻底失效。

本项目已因此修复三处真实缺陷（`.modal-mask` / `.badge` / `.field` 三类），
并在 `app.css` 顶部加了兜底规则 `[hidden] { display: none !important; }`。
**新增任何用 `hidden` 属性控制显隐的元素，都要保证它有对应的布局断言。**

第二类是**布局本身**：驱动管理两栏是否真的是 `280px + 1fr`（不是「看起来像」）、
两栏是否等高、表头是否真的 `position: sticky`。这些只有真实浏览器能回答——
jsdom 的 `getComputedStyle` 对布局属性给不出可信结果。

```bash
node scripts/ui_guard.js                                    # 38 项（默认打 8080）
DBNAV_BASE=http://127.0.0.1:9090 node scripts/ui_guard.js   # 38 项
```

守卫本身经过两次反向验证，都是「先让它失败」：

1. 把 `[hidden] { display: none !important; }` 兜底规则去掉 → **13 项失败、退出码 1**
2. 把 `.drivers-split` 的 `grid-template-columns: 280px 1fr` 改成 `1fr` → **4 项失败、退出码 1**
   （诊断信息里给出实际值 `1156px` 与两栏的实际 x 坐标）

**一个不会失败的守卫比没有守卫更糟**，因为它提供虚假的安全感。

### 验证矩阵

四套测试都同时跑在 **Java 后端**与 **Python 预览**上，确保预览服务不会与生产实现漂移：

```bash
# 终端 1：Java 后端
mvn -s .mvn/settings.xml spring-boot:run

# 终端 2：Python 预览（另起端口）
python devserver.py --port 9090

# 终端 3：四层验证
python scripts/api_test.py   http://127.0.0.1:8080   # 119 项
python scripts/api_test.py   http://127.0.0.1:9090   # 99 项（执行真实 SQL 的用例跳过，导出走 501 分支）
python scripts/parity_check.py http://127.0.0.1:8080 http://127.0.0.1:9090
node scripts/dom_smoke.js                            # 82 项（默认打 8080）
DBNAV_BASE=http://127.0.0.1:9090 node scripts/dom_smoke.js   # 82 项
node scripts/ui_guard.js                             # 38 项（默认打 8080）
DBNAV_BASE=http://127.0.0.1:9090 DBNAV_CDP_PORT=9334 node scripts/ui_guard.js   # 38 项
```

`api_test.py` 对巡检执行的断言**只校验结构、内部一致性与状态码，不校验具体数值**——
因为两套后端的能力边界不同（预览服务没有 JDBC 层）。内部一致性包含：
`totalQueries = ok + failed`、`totalBaselines = pass + fail + unchecked`、
`riskSummary` 合计 = `baselinesFail`、合规率符合公式。

导出接口的测试分两类断言：**路由与参数校验**（404 / 400）无条件比对，两边必须一致；
**成功路径**则分支断言——Java 必须交出真实文件（校验 Content-Type、文件头魔数、体积、
`Content-Disposition` 里的中文文件名），预览服务必须交出 501 且响应体**不能**长得像文档。
最后那条是**反伪造守卫**：只断言「501 也可以」而不检查「501 时没有假文件」，守卫就是假的。
实测把它反向验证过一次——让预览服务返回一个 15 字节的假 PDF，测试报出 **17 项失败、退出码 1**。

`parity_check.py` 覆盖 **19 个 GET 用例 + 12 条错误路径**，逐字段比对响应体；
执行记录本身不做逐字段比对（两侧数据不同源），只比状态码与结构。
另有一节专门断言导出接口的已声明差异（`java=200 / python=501`），
把「不一致」写进检查项而不是绕过它——任何一侧改动都会在这里失败。

### 真实端到端巡检

用内置的 H2 自检数据源可以完整验证巡检执行链路，无需任何外部数据库：

```bash
# 建一个 h2 数据源 → 测连接（应返回 driverVersion="bundled"、driverBundled=true）
curl -s -X POST http://127.0.0.1:8080/api/datasources/1/test

# 跑一次巡检
curl -s -X POST http://127.0.0.1:8080/api/inspection/run \
     -H 'Content-Type: application/json' \
     -d '{"dataSourceId":1,"onlyEnabled":true,"collectBaselines":true}'
```

预期：`status=SUCCESS`、26/26 规则全 `OK`、基线 22 通过 / 2 不合规 / 1 未采集、合规率 `91.7%`、耗时约 30 ms。

再把这条记录导出成三种格式，验证报告链路（尤其 PDF 的中文）：

```bash
ID=1   # 换成上面 run 返回的 id
curl -sD - -o /tmp/r.html "http://127.0.0.1:8080/api/inspection/runs/$ID/export?format=html" | head -4
curl -sD - -o /tmp/r.docx "http://127.0.0.1:8080/api/inspection/runs/$ID/export?format=word" | head -4
curl -sD - -o /tmp/r.pdf  "http://127.0.0.1:8080/api/inspection/runs/$ID/export?format=pdf"  | head -4

file /tmp/r.pdf    # → PDF document, 应约 9 页
```

PDF 的中文是否真的嵌入了，光看字节数不够——用 PDFBox 拆开确认：

```bash
# 先导出 classpath（PDFBox 是 openhtmltopdf 的传递依赖，已在里面）
mvn -s .mvn/settings.xml -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

# 提取文本（应能读出正确汉字，且替换字符 U+FFFD 为 0）
# 列出嵌入字体（应看到 AOTLCF+SimHei [嵌入=是]）
# 把第 1 页栅格化成 PNG（必须看图确认，文本提取正确但字形缺失是可能的）
java -cp "target/classes;$(cat target/cp.txt)" scripts/PdfInspect.java /tmp/r.pdf /tmp/p1.png 2 0
```

一份正常的输出形如：

```
页数: 9
提取字符数: 1485 | 中日韩汉字: 129 | 替换字符(U+FFFD): 0
---- 嵌入字体 ----
  AOTLCF+SimHei  [嵌入=是]
  Courier  [嵌入=否]
已渲染第 1 页 -> /tmp/p1.png (909x1286)
```

`Courier  [嵌入=否]` 是正常的——它是 PDF 的 14 个标准字体之一，用于 SQL 代码块的等宽部分，
不需要嵌入。关键是 CJK 字体必须是 `[嵌入=是]`，且 `U+FFFD` 计数为 0。

---

## 十、Web 控制台

导航为七个顶级菜单：

| 模块 | 能力 |
|---|---|
| **数据源纳管** | 新建 / 编辑 / 删除数据源；按库类型动态切换表单字段（Oracle 显示 SID / Service Name）；JDBC URL 实时预览；连接测试 |
| **驱动管理** | **两栏布局**（左 280px 库类型列表 / 右该类型的驱动版本表）。左栏列出**全部**受支持类型（含尚未放 JAR 的），这样「哪些类型还没配驱动」一眼可见——只列已有驱动的类型会把「缺失」这个最重要的信息藏起来。右栏表头：驱动版本 / 驱动类 / 驱动 JAR 包 / 操作，激活行淡绿底 + 激活徽标；支持激活切换、上传 JAR、扫描驱动目录；JAR 缺失时高亮期望路径 |
| **巡检配置** | 两个页签：**巡检模板**（模板列表 → 章节手风琴 → 规则，支持增删改与启停）、**变更历史**（增删改留痕） |
| **基线规则** | 按库类型管理 `参数 → 比较符 → 期望值` 阈值规则；顶部统计条（该类型基线数 / 启用中 / 全部类型合计 / 风险分布）；批量启停；填实测值跑试算，输出合规率与逐项结论 |
| **巡检执行** | 只负责「发起 + 看本次结果」：选数据源 + 模板发起巡检，报告区展示执行状态、合规率、规则执行成功数、基线合规数；章节手风琴展开看每条规则的 SQL、耗时、行数与结果预览（NULL 以斜体区分），基线判定表 7 列（参数 / 比较符 / 期望值 / 实测值 / 结论 / 风险 / 说明） |
| **巡检历史** | 留痕与导出。左侧执行记录列表（可按数据源 / 库类型筛选）；右侧**在线预览**，可在「报告原文」（iframe 直接加载导出的 HTML）与「结构化明细」之间切换；顶部三个下载按钮（Word 深蓝 / PDF 红 / HTML 青绿） |
| **SQL 编辑器** | 选择数据源执行 SQL；结果表格化（含列类型）；执行耗时；`Ctrl/Cmd + Enter` 快捷执行 |

### 为什么「在线预览」用 iframe 直接加载导出接口

预览区 `iframe.src` 指向 `GET /runs/{id}/export?format=html`——**看到的就是下载到的那份文件**。
如果预览另写一套渲染逻辑，就会出现「预览正常、导出不对」这类只有用户才会发现的问题。
代价是预览区无法自定义（比如不能加行内按钮），但这个取舍很划算。

### 为什么下载走 fetch + blob 而不是 `window.open`

导出接口出错时返回的是 JSON（例如 PDF 找不到可嵌入的中文字体）。直接开新窗口会把这段
JSON 当成文件存下来，用户拿到一个 0 字节的 `.pdf` 却不知道原因。先读响应、判断状态码，
再决定是保存还是弹出错误提示——错误信息就能真正到达用户。

---

## 十一、项目结构

```
db-navigator/
├── pom.xml
├── .mvn/settings.xml                     # 阿里云镜像（内网/国内构建用，不污染全局配置）
├── devserver.py                          # 本地预览服务（无需 JDK）
├── scripts/
│   ├── fetch_drivers.py                  # 下载 JDBC 驱动 JAR 到 drivers/（含内容校验）
│   ├── preview_inspection.py             # 巡检 API 的 Python 镜像实现（预览服务用）
│   ├── api_test.py                       # 巡检配置 + 执行 + 导出 API 契约测试（Java 119 项 / 预览 99 项）
│   ├── parity_check.py                   # 双实现响应体/错误码一致性校验（19 GET + 12 错误路径 + 导出差异断言）
│   ├── dom_smoke.js                      # jsdom 前端行为冒烟测试（82 项，不跑 CSS）
│   ├── ui_guard.js                       # 真实浏览器 UI 守卫（38 项，headless Edge + CDP，零依赖）
│   └── PdfInspect.java                   # PDF 报告体检：提取文本 / 嵌入字体 / 栅格化出图
├── drivers/                              # JDBC 驱动 JAR 存放（按 类型/版本 分层）
├── src/main/resources/
│   ├── application.yml                   # 端口、H2、连接池、自定义配置
│   ├── schema.sql                        # 驱动 / 数据源 / 查询历史 / 巡检配置五表 / 巡检执行三表 建表
│   ├── db-types.json                     # 库类型元数据（含 h2 自检类型）
│   ├── drivers-seed.json                 # 驱动种子（仅元数据，导入时按文件名解析路径）
│   ├── inspection/
│   │   ├── templates.json                # 巡检模板种子（6 模板 / 113 章节 / 160 规则）
│   │   └── baselines.json                # 基线种子（88 条，含 h2 自检组）
│   └── static/                           # Web 控制台（index.html / css / js）
└── src/main/java/com/dbnav/
    ├── DbNavigatorApplication.java
    ├── common/            Result、PasswordEncryptor、ApiStatusAdvice
    ├── config/            DbNavProperties、ConfigJsonMapper
    ├── driver/
    │   ├── DriverRegistry.java           # 注册表 CRUD + 激活（同类型仅一个 is_active=1）
    │   ├── DriverPathResolver.java       # 三级路径重定位
    │   ├── DriverDirectoryScanner.java   # 目录扫描自动登记
    │   ├── DriverSeedLoader.java         # 种子导入
    │   ├── DynamicDriverLoader.java      # URLClassLoader 运行时加载（含 bundled 回退）
    │   ├── DbTypeRegistry.java           # 库类型元数据注册表
    │   ├── DriverStartupInitializer.java # 启动编排
    │   └── model/                        # DriverInfo / DriverSeedEntry / DbTypeMeta
    ├── datasource/
    │   ├── DataSourceManager.java        # 数据源 CRUD + 连接池 + 连接测试
    │   ├── ConnectionUrlBuilder.java     # JDBC URL 模板渲染
    │   └── model/                        # DataSourceInfo / ConnectionRequest
    ├── inspection/
    │   ├── InspectionConfigService.java  # 模板/章节/规则/基线 CRUD + 种子 + 留痕
    │   ├── BaselineChecker.java          # 基线算子判定（含语义枚举序）
    │   ├── InspectionRunner.java         # 巡检执行器：采集 → 判定 → 聚合 → 落库
    │   ├── InspectionReportService.java  # 报告导出：HTML（主渲染器）/ PDF / Word
    │   ├── InspectionStartupInitializer.java
    │   └── model/                        # Template / Chapter / Query / Baseline / CheckResult
    │                                     # + InspectionRun / InspectionRunQuery / InspectionRunBaseline
    └── controller/                       # Driver / DataSource / Query / Inspection 四组 REST API
```

---

## 十二、技术选型与取舍

| 维度 | 选择 | 原因 |
|---|---|---|
| 语言/运行时 | Java 17 / Spring Boot 3 | 原生 JDBC，不需要跨语言桥接 JVM |
| 驱动加载 | `URLClassLoader` 运行时加载 | 每个 `类型::版本` 一个 ClassLoader，隔离干净；上传即生效，不用重启 |
| 元数据库 | H2 文件库 | 零安装、随应用启动，与 Spring 生态契合 |
| 密码加密 | AES-256-GCM（SHA-256 派生密钥） | 无额外依赖；每条记录独立随机 IV，同一明文密文不同 |
| 巡检执行 | 手动发起（`POST /api/inspection/run`），结果落库为「执行记录 + 明细」 | 保留历史才能做趋势对比；定时调度留待后续接入 |
| 巡检报告 | 前端按需渲染 + `/runs/latest` 支持「与上次对比」 | 报告与数据解耦，便于换皮与二次加工 |
| 报告导出 | `GET /runs/{id}/export?format=html\|word\|pdf`，三种格式共用一份数据 | 接口收敛为一个（`format` 参数区分）；HTML/PDF 复用同一渲染器，不会出现「预览对、导出错」 |
| 导航结构 | 驱动管理 / 基线规则 / 巡检历史各为**独立顶级菜单** | 基线配置与「发起巡检」是两件事，塞进同一个页签会让日常操作变绕 |
| 自检能力 | 内置 `h2` 自检类型（`driver_bundled`，无需外部库） | 让整条链路可端到端验证，不靠 mock |

几处刻意的取舍：

- **多三张表换历史**：`inspection_run` / `inspection_run_query` / `inspection_run_baseline`
  让每次执行都可追溯，代价是数据量随执行次数增长（当前不做自动清理）。
- **导出接口不走统一的 `Result` 包装**：它要吐原始字节流。出错时仍返回 `Result` 形状的 JSON，
  并同时置对应的 HTTP 状态码——这样前端一套错误处理就能覆盖下载失败（如 PDF 字体缺失）。
- **驱动 JAR 不入库**：体积大且有分发许可问题，只提交目录结构与 `drivers/<type>/README.md`。

### 一处刻意的不一致：预览服务的导出接口

`devserver.py` 是「契约的可执行规格」，理应与 Java 实现完全一致——但导出接口是唯一的例外：
预览服务**恒定返回 501**，因为它只有 Python 标准库，做不出真正的 `.docx` / `.pdf`。

处理方式不是绕过它，而是把它**显式声明成检查项**：`parity_check.py` 里有一段断言
`java=200 / python=501`，任何一侧改动都会让检查失败。这样「已知差异」和「契约漂移」
在报告里是两种不同的东西，不会被混为一谈。

