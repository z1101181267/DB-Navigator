/* ============================================================
   DB Navigator · Admin Console
   Vanilla JS, no dependencies — works against the Spring Boot REST API
   ============================================================ */

const state = {
    dbTypes: [],       // DbTypeMeta[]
    drivers: [],       // DriverInfo[]
    datasources: [],   // DataSourceInfo[]
    mode: 'unknown',   // 'live' | 'demo' | 'unknown'
    driver: {
        type: null     // 驱动管理左栏选中的库类型（null = 尚未选择，渲染时取第一个有驱动的类型）
    },
    inspection: {
        loaded: false,
        summary: null,
        templates: [],
        activeTplId: null,
        tree: null,        // 当前模板（含 chapters[].rules[]，rules 是引用来的规则，不是正文）
        baselines: [],
        baseType: null,
        values: {},        // paramName -> 实测值
        history: null,
        openChapters: {}   // chapterId -> true
    },
    rules: {
        loaded: false,
        list: [],          // InspectionRule[]（规则库）
        stats: null,
        categories: [],
        dbType: null,
        category: '',
        enabled: '',       // '' = 全部 | '1' = 已启用 | '0' = 已停用
        keyword: '',
        testResult: null,
        testRuleId: null,
        // 章节引用规则弹窗
        bindChapterId: null,
        bindList: []
    },
    run: {
        loaded: false,
        list: [],          // InspectionRun[]（不含明细）
        activeId: null,
        detail: null,      // 当前选中的执行记录（含 queries[] / baselines[]）
        running: false,
        openChapters: {},  // chapterNumber -> true
        // 巡检历史视图
        histLoaded: false,
        histActiveId: null,
        histDetail: null,  // 历史视图选中的记录（含明细）
        filterDs: '',      // '' = 全部数据源
        filterType: '',    // '' = 全部库类型
        previewMode: 'report',   // 'report' = 报告原文(iframe) | 'detail' = 结构化明细
        lastRunId: null    // 巡检执行页当前展示的那条
    }
};

/* ---------------- Utilities ---------------- */

const $ = (id) => document.getElementById(id);

function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function fmtSize(bytes) {
    if (!bytes) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
}

/**
 * 时间戳压成 MM-DD HH:mm。
 * Java 侧输出 ISO（2026-09-17T20:35:14），预览服务输出 SQLite 风格
 * （2026-09-17 20:35:14），这里统一处理；解析不了就原样返回，不猜。
 */
function shortTime(s) {
    if (!s) return '—';
    const m = String(s).replace('T', ' ').match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})/);
    return m ? `${m[2]}-${m[3]} ${m[4]}:${m[5]}` : String(s);
}

function toast(msg, kind) {
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = msg;
    $('toastWrap').appendChild(el);
    setTimeout(() => el.remove(), 4200);
}

/* ---------------- API client ---------------- */

async function api(path, options = {}) {
    const res = await fetch(path, options);
    const text = await res.text();
    let json;
    try {
        json = text ? JSON.parse(text) : {};
    } catch (e) {
        throw new Error('响应不是合法 JSON：' + text.slice(0, 180));
    }
    if (json.code && json.code !== 200) {
        throw new Error(json.message || ('请求失败 (' + json.code + ')'));
    }
    return json.data;
}

/* ---------------- Bootstrap ---------------- */

async function boot() {
    try {
        state.dbTypes = await api('/api/drivers/types') || [];
        setMode('live', '后端已连接');
    } catch (e) {
        // Backend unreachable — fall back to a local type table so the UI stays explorable
        state.dbTypes = FALLBACK_TYPES;
        setMode('demo', '演示模式（后端未连接）');
        toast('未检测到后端服务，界面以演示模式运行', 'bad');
    }

    renderDbChips();
    fillTypeSelects();
    await Promise.all([loadDatasources(), loadDrivers()]);
    bindEvents();
}

function setMode(mode, label) {
    state.mode = mode;
    $('modeBadge').textContent = label;
    const dot = $('statusDot');
    dot.className = 'dot-status ' + (mode === 'live' ? 'ok' : 'bad');
    $('statusText').textContent = mode === 'live' ? '在线' : '离线';
}

const FALLBACK_TYPES = [
    { dbType: 'oracle', label: 'Oracle', port: 1521, user: 'system', emoji: '🔴',
      driverClassHint: 'oracle.jdbc.OracleDriver',
      urlTemplate: 'jdbc:oracle:thin:@//{host}:{port}/{service_name}',
      urlTemplateSid: 'jdbc:oracle:thin:@{host}:{port}:{sid}' },
    { dbType: 'mysql', label: 'MySQL', port: 3306, user: 'root', emoji: '🐬',
      driverClassHint: 'com.mysql.cj.jdbc.Driver',
      urlTemplate: 'jdbc:mysql://{host}:{port}/{database}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8' },
    { dbType: 'postgresql', label: 'PostgreSQL', port: 5432, user: 'postgres', emoji: '🐘',
      driverClassHint: 'org.postgresql.Driver',
      urlTemplate: 'jdbc:postgresql://{host}:{port}/{database}' },
    { dbType: 'sqlserver', label: 'SQL Server', port: 1433, user: 'sa', emoji: '🟠',
      driverClassHint: 'com.microsoft.sqlserver.jdbc.SQLServerDriver',
      urlTemplate: 'jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=false;trustServerCertificate=true' },
    { dbType: 'kingbase', label: 'KingbaseES', port: 54321, user: 'system', emoji: '🔵',
      driverClassHint: 'com.kingbase8.Driver',
      urlTemplate: 'jdbc:kingbase8://{host}:{port}/{database}' },
    { dbType: 'h2', label: 'H2（内置自检）', port: 0, user: 'sa', emoji: '🧪',
      driverClassHint: 'org.h2.Driver', driverBundled: true,
      urlTemplate: 'jdbc:h2:mem:dbnav_selfcheck;DB_CLOSE_DELAY=-1' }
];

function typeMeta(dbType) {
    return state.dbTypes.find(t => t.dbType === dbType) || { dbType, label: dbType };
}

/* ---------------- Rendering: sidebar chips ---------------- */

function renderDbChips() {
    $('dbChips').innerHTML = state.dbTypes
        .map(t => `<span class="chip">${esc(t.emoji || '')} ${esc(t.label || t.dbType)}</span>`)
        .join('');
}

function fillTypeSelects() {
    const opts = state.dbTypes
        .map(t => `<option value="${esc(t.dbType)}">${esc(t.emoji || '')} ${esc(t.label || t.dbType)}</option>`)
        .join('');
    $('dsType').innerHTML = opts;
    $('upType').innerHTML = opts;
}

/* ---------------- View switching ---------------- */

function bindEvents() {
    document.querySelectorAll('.nav-item').forEach(btn => {
        btn.addEventListener('click', () => switchView(btn.dataset.view));
    });

    document.querySelectorAll('[data-close]').forEach(btn => {
        btn.addEventListener('click', () => closeModal(btn.dataset.close));
    });

    // Data source
    $('btnNewDs').addEventListener('click', () => openDsModal(null));
    $('dsType').addEventListener('change', onTypeChange);
    ['dsHost', 'dsPort', 'dsDatabase', 'dsService', 'dsSid', 'dsExtra'].forEach(id => {
        $(id).addEventListener('input', updateUrlPreview);
    });
    $('btnTestDs').addEventListener('click', testCurrentForm);
    $('btnSaveDs').addEventListener('click', saveDs);

    // Drivers
    $('btnScan').addEventListener('click', scanDrivers);
    $('btnUpload').addEventListener('click', () => openModal('upModal'));
    $('upType').addEventListener('change', updateUpPathHint);
    $('btnDoUpload').addEventListener('click', doUpload);

    // Query
    $('btnRun').addEventListener('click', runQuery);
    $('sqlInput').addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); runQuery(); }
    });

    // Inspection
    bindInspectionEvents();

    // 基线配置管理（独立菜单）
    bindBaselineEvents();

    // 规则引擎（独立菜单）
    bindRuleEvents();

    // 巡检执行
    bindRunEvents();

    // 巡检历史
    bindRunHistoryEvents();

    // Close modal on mask click
    document.querySelectorAll('.modal-mask').forEach(mask => {
        mask.addEventListener('click', (e) => { if (e.target === mask) mask.hidden = true; });
    });
}

function switchView(name) {
    document.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.view === name));
    document.querySelectorAll('.view').forEach(v => v.classList.toggle('active', v.id === 'view-' + name));
    if (name === 'query') renderQueryDsSelect();
    if (name === 'inspection') ensureInspection();
    if (name === 'baselines') ensureBaselines();
    if (name === 'rules') ensureRules();
    if (name === 'inspectionRun') ensureRunExec();
    if (name === 'runs') ensureRunsHistory();
}

function openModal(id) { $(id).hidden = false; }
function closeModal(id) { $(id).hidden = true; }

/* ============================================================
   数据源纳管
   ============================================================ */

async function loadDatasources() {
    try {
        state.datasources = await api('/api/datasources') || [];
    } catch (e) {
        state.datasources = [];
    }
    renderDatasources();
    renderQueryDsSelect();
}

function renderDatasources() {
    const tbody = $('dsTable').querySelector('tbody');
    $('dsEmpty').hidden = state.datasources.length > 0;

    tbody.innerHTML = state.datasources.map(ds => {
        const meta = typeMeta(ds.dbType);
        const statusBadge =
            ds.status === 'ONLINE' ? '<span class="badge badge-ok">在线</span>' :
            ds.status === 'ERROR' ? '<span class="badge badge-bad">异常</span>' :
            '<span class="badge badge-soft">未连接</span>';
        const target = ds.dbType === 'oracle'
            ? (ds.serviceName || ds.sid || '—')
            : (ds.databaseName || '—');

        return `<tr>
            <td><strong>${esc(ds.name)}</strong></td>
            <td>${esc(meta.emoji || '')} ${esc(meta.label || ds.dbType)}</td>
            <td class="mono">${esc(ds.host)}:${esc(ds.port)}</td>
            <td class="mono">${esc(target)}</td>
            <td class="mono">${esc(ds.driverVersion || '激活版本')}</td>
            <td>${statusBadge}</td>
            <td class="right">
                <button class="btn-link" data-test="${ds.id}">测试</button>
                <button class="btn-link" data-edit="${ds.id}">编辑</button>
                <button class="btn-link danger" data-del="${ds.id}">删除</button>
            </td>
        </tr>`;
    }).join('');

    tbody.querySelectorAll('[data-test]').forEach(b => b.addEventListener('click', () => testSavedDs(+b.dataset.test)));
    tbody.querySelectorAll('[data-edit]').forEach(b => b.addEventListener('click', () => openDsModal(+b.dataset.edit)));
    tbody.querySelectorAll('[data-del]').forEach(b => b.addEventListener('click', () => deleteDs(+b.dataset.del)));
}

function openDsModal(id) {
    const isEdit = id !== null;
    $('dsModalTitle').textContent = isEdit ? '编辑数据源' : '新建数据源';
    $('dsTestResult').hidden = true;

    if (isEdit) {
        const ds = state.datasources.find(d => d.id === id);
        if (!ds) return;
        $('dsId').value = ds.id;
        $('dsName').value = ds.name || '';
        $('dsType').value = ds.dbType || '';
        $('dsHost').value = ds.host || '';
        $('dsPort').value = ds.port || '';
        $('dsUser').value = ds.username || '';
        $('dsPassword').value = '';
        $('dsDatabase').value = ds.databaseName || '';
        $('dsService').value = ds.serviceName || '';
        $('dsSid').value = ds.sid || '';
        $('dsDriverVersion').value = ds.driverVersion || '';
        $('dsExtra').value = ds.extraParams || '';
        $('dsNote').value = ds.note || '';
    } else {
        $('dsId').value = '';
        ['dsName', 'dsHost', 'dsUser', 'dsPassword', 'dsDatabase', 'dsService', 'dsSid', 'dsDriverVersion', 'dsExtra', 'dsNote']
            .forEach(f => $(f).value = '');
        $('dsType').selectedIndex = 0;
    }

    onTypeChange();
    openModal('dsModal');
}

/** Toggle Oracle-specific fields and apply type defaults */
function onTypeChange() {
    const t = typeMeta($('dsType').value);
    const isOracle = t.dbType === 'oracle';

    $('fService').hidden = !isOracle;
    $('fSid').hidden = !isOracle;
    $('fDatabase').hidden = isOracle;

    // Apply defaults only for a fresh form
    if (!$('dsId').value) {
        if (!$('dsPort').value) $('dsPort').value = t.port || '';
        if (!$('dsUser').value) $('dsUser').value = t.user || '';
        if (!$('dsDatabase').value) $('dsDatabase').value = t.defaultDatabase || '';
        if (!$('dsService').value) $('dsService').value = isOracle ? 'ORCL' : '';
    }
    updateUrlPreview();
}

/** Render a client-side JDBC URL preview from the type template */
function updateUrlPreview() {
    const t = typeMeta($('dsType').value);
    const host = $('dsHost').value || '{host}';
    const port = $('dsPort').value || t.port || '{port}';
    let url;

    if (t.dbType === 'oracle') {
        if ($('dsSid').value) {
            url = (t.urlTemplateSid || 'jdbc:oracle:thin:@{host}:{port}:{sid}')
                .replace('{host}', host).replace('{port}', port).replace('{sid}', $('dsSid').value);
        } else {
            url = (t.urlTemplate || 'jdbc:oracle:thin:@//{host}:{port}/{service_name}')
                .replace('{host}', host).replace('{port}', port)
                .replace('{service_name}', $('dsService').value || '{service_name}');
        }
    } else {
        url = (t.urlTemplate || '')
            .replace('{host}', host).replace('{port}', port)
            .replace('{database}', $('dsDatabase').value || '{database}');
    }

    const extra = $('dsExtra').value.trim();
    if (extra) {
        url += url.includes('?') ? '&' + extra : (url.includes(';') ? ';' + extra : '?' + extra);
    }
    $('dsUrlPreview').textContent = url;
}

function collectDsForm() {
    return {
        name: $('dsName').value.trim(),
        dbType: $('dsType').value,
        host: $('dsHost').value.trim(),
        port: parseInt($('dsPort').value, 10) || 0,
        username: $('dsUser').value.trim(),
        password: $('dsPassword').value,
        databaseName: $('dsDatabase').value.trim(),
        serviceName: $('dsService').value.trim(),
        sid: $('dsSid').value.trim(),
        extraParams: $('dsExtra').value.trim(),
        driverVersion: $('dsDriverVersion').value.trim(),
        note: $('dsNote').value.trim()
    };
}

function validateDsForm(p) {
    if (!p.name) return '请填写名称';
    if (!p.dbType) return '请选择数据库类型';
    if (!p.host) return '请填写主机';
    if (!p.username) return '请填写用户名';
    if (!p.password) return '请填写密码';
    return null;
}

function showTestResult(result) {
    const box = $('dsTestResult');
    box.hidden = false;

    if (result.success) {
        box.className = 'test-result ok';
        box.innerHTML =
            `✔ 连接成功（${esc(result.elapsedMs)} ms）<br>` +
            `驱动版本：<code>${esc(result.driverVersion || '—')}</code><br>` +
            `产品：<code>${esc(result.databaseProductName || '—')} ${esc(result.databaseProductVersion || '')}</code><br>` +
            `URL：<code>${esc(result.jdbcUrl || '')}</code>`;
    } else {
        box.className = 'test-result bad';
        let html = `✘ 连接失败<br>${esc(result.error || '未知错误')}`;
        if (result.expectedPath) {
            html += `<br>期望的驱动位置：<code>${esc(result.expectedPath)}</code>`;
        }
        box.innerHTML = html;
    }
}

async function testCurrentForm() {
    const p = collectDsForm();
    const err = validateDsForm(p);
    if (err) { toast(err, 'bad'); return; }

    const box = $('dsTestResult');
    box.hidden = false;
    box.className = 'test-result';
    box.textContent = '测试中…';

    try {
        const r = await api('/api/datasources/test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(p)
        });
        showTestResult(r);
    } catch (e) {
        showTestResult({ success: false, error: e.message });
    }
}

async function testSavedDs(id) {
    toast('正在测试连接…');
    try {
        const r = await api(`/api/datasources/${id}/test`, { method: 'POST' });
        if (r.success) toast(`连接成功（${r.elapsedMs} ms）`, 'ok');
        else toast('连接失败：' + (r.error || ''), 'bad');
        await loadDatasources();
    } catch (e) {
        toast('测试失败：' + e.message, 'bad');
    }
}

async function saveDs() {
    const p = collectDsForm();
    const err = validateDsForm(p);
    if (err) { toast(err, 'bad'); return; }

    const id = $('dsId').value;
    try {
        if (id) {
            await api(`/api/datasources/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(p)
            });
            toast('数据源已更新', 'ok');
        } else {
            await api('/api/datasources', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(p)
            });
            toast('数据源已创建', 'ok');
        }
        closeModal('dsModal');
        await loadDatasources();
    } catch (e) {
        toast('保存失败：' + e.message, 'bad');
    }
}

async function deleteDs(id) {
    const ds = state.datasources.find(d => d.id === id);
    if (!confirm(`确定删除数据源「${ds ? ds.name : id}」？该操作不可撤销。`)) return;
    try {
        await api(`/api/datasources/${id}`, { method: 'DELETE' });
        toast('已删除', 'ok');
        await loadDatasources();
    } catch (e) {
        toast('删除失败：' + e.message, 'bad');
    }
}

/* ============================================================
   驱动管理
   ============================================================ */

async function loadDrivers() {
    try {
        state.drivers = await api('/api/drivers') || [];
    } catch (e) {
        state.drivers = [];
    }
    renderDrivers();
}

/* ---------------- 驱动管理：左栏库类型 + 右栏版本表 ----------------
   布局：
   左 280px 是「数据库/产品类型」列表，右栏是当前类型的驱动版本表。
   左栏列出**全部**受支持类型（含尚未放 JAR 的），这样「哪些类型还没配驱动」一眼可见；
   只列已有驱动的类型会把「缺失」这个最重要的信息藏起来。 */

/** 当前类型下的驱动，按版本排序（激活的排最前，其余按版本号自然序） */
function driversOfType(dbType) {
    return (state.drivers || [])
        .filter(d => d.dbType === dbType)
        .sort((a, b) => {
            if (!!a.active !== !!b.active) return a.active ? -1 : 1;
            return String(a.version).localeCompare(String(b.version), undefined, { numeric: true });
        });
}

function renderDrivers() {
    const types = state.dbTypes || [];
    const listEl = $('drvTypeList');

    if (!types.length) {
        listEl.innerHTML = '<div class="drivers-empty">未能加载库类型定义（db-types.json）</div>';
        $('drvTbody').innerHTML = '<tr><td colspan="4" class="drivers-empty">—</td></tr>';
        return;
    }

    // 默认选中：保持原选择 → 第一个有驱动的类型 → 第一个类型
    const valid = types.some(t => t.dbType === state.driver.type);
    if (!valid) {
        const withDrivers = types.find(t => driversOfType(t.dbType).length > 0);
        state.driver.type = (withDrivers || types[0]).dbType;
    }
    const cur = state.driver.type;

    const configured = types.filter(t => driversOfType(t.dbType).length > 0).length;
    $('drvTypeCount').textContent = `${configured}/${types.length} 已配置`;

    listEl.innerHTML = types.map((t, i) => {
        const list = driversOfType(t.dbType);
        const activeN = list.filter(d => d.active).length;
        const badge = activeN
            ? '<span class="drivers-type-badge">激活</span>'
            : (list.length ? '<span class="drivers-type-tag warn">无激活</span>'
                : (t.driverBundled ? '<span class="drivers-type-tag native">内置</span>'
                    : '<span class="drivers-type-tag">未配置</span>'));
        return `<div class="drivers-type-item${t.dbType === cur ? ' active' : ''}"
                     data-drv-type="${esc(t.dbType)}">
            <span class="order">${i + 1}</span>
            <span class="name">${esc(t.emoji || '')} ${esc(t.label || t.dbType)}</span>
            <span class="cnt">${list.length ? list.length + ' 版本' : ''}</span>
            ${badge}
        </div>`;
    }).join('');

    listEl.querySelectorAll('[data-drv-type]').forEach(el => {
        el.addEventListener('click', () => {
            state.driver.type = el.dataset.drvType;
            renderDrivers();
        });
    });

    renderDriverTable(cur);
}

function renderDriverTable(dbType) {
    const meta = typeMeta(dbType);
    const list = driversOfType(dbType);
    const tbody = $('drvTbody');

    $('drvCurrentType').innerHTML =
        `${esc(meta.emoji || '')} ${esc(meta.label || dbType)}`;
    $('drvCurrentNote').innerHTML = meta.driverClassHint
        ? `默认驱动类 <code>${esc(meta.driverClassHint)}</code>` : '';

    if (!list.length) {
        tbody.innerHTML = `<tr><td colspan="4" class="drivers-empty">
            ${meta.driverBundled
                ? '该类型驱动随应用内置（已在 classpath 上），无需往 drivers/ 目录放 JAR。'
                : `尚未登记驱动。把 JAR 放到 <code>drivers/${esc(dbType)}/&lt;version&gt;/</code> 后点「扫描驱动目录」，或用「上传 JAR」。`}
        </td></tr>`;
        return;
    }

    tbody.innerHTML = list.map(d => {
        const present = d.jarPresent !== false;
        const cls = d.active ? ' class="is-active"' : '';
        const actBtn = d.active
            ? '<span class="muted">当前激活</span>'
            : `<button class="btn btn-sm" data-activate="${d.id}">激活</button>`;
        const note = d.note ? `<div class="drv-note">${esc(d.note)}</div>` : '';
        const missing = present ? '' :
            `<div class="drv-missing">⚠ 磁盘上未找到该 JAR，请放入
             <code>drivers/${esc(dbType)}/${esc(d.version)}/${esc(d.jarFilename)}</code></div>`;

        return `<tr${cls}>
            <td>
                <span class="drv-ver">${esc(d.version)}</span>
                ${d.active ? '<span class="drv-active-tag">激活</span>' : ''}
                ${present ? '' : '<span class="drv-missing-tag">JAR 缺失</span>'}
                ${note}
            </td>
            <td><code>${esc(d.driverClass || '—')}</code></td>
            <td>
                <div class="drv-jar">${esc(d.jarFilename)}</div>
                <div class="drv-jar-meta">${fmtSize(d.fileSize)} · ${esc(shortTime(d.uploadedAt))}</div>
                ${missing}
            </td>
            <td>
                <div class="drivers-actions">
                    ${actBtn}
                    <button class="btn-link danger" data-del-driver="${d.id}">删除</button>
                </div>
            </td>
        </tr>`;
    }).join('');

    tbody.querySelectorAll('[data-activate]').forEach(b =>
        b.addEventListener('click', () => activateDriver(+b.dataset.activate)));
    tbody.querySelectorAll('[data-del-driver]').forEach(b =>
        b.addEventListener('click', () => deleteDriver(+b.dataset.delDriver)));
}

async function activateDriver(id) {
    try {
        await api(`/api/drivers/${id}/activate`, { method: 'PUT' });
        toast('已切换激活版本', 'ok');
        await loadDrivers();
    } catch (e) {
        toast('激活失败：' + e.message, 'bad');
    }
}

async function deleteDriver(id) {
    if (!confirm('确定删除该驱动记录及 JAR 文件？删除后同类型将自动接任激活。')) return;
    try {
        await api(`/api/drivers/${id}`, { method: 'DELETE' });
        toast('已删除', 'ok');
        await loadDrivers();
    } catch (e) {
        toast('删除失败：' + e.message, 'bad');
    }
}

async function scanDrivers() {
    toast('正在扫描 drivers/ 目录…');
    try {
        const r = await api('/api/drivers/scan', { method: 'POST' });
        toast(`扫描完成：新增 ${r.newDrivers} 个，共 ${r.totalDrivers} 个`, 'ok');
        await loadDrivers();
    } catch (e) {
        toast('扫描失败：' + e.message, 'bad');
    }
}

function updateUpPathHint() {
    const t = typeMeta($('upType').value);
    const ver = $('upVersion').value.trim() || '<version>';
    $('upDriverClass').placeholder = t.driverClassHint || '留空则用类型默认值';
    $('upPathHint').innerHTML =
        `JAR 将存入 <code>drivers/${esc(t.dbType)}/${esc(ver)}/</code>；` +
        `驱动类：<code>${esc(t.driverClassHint || '—')}</code>`;
}

async function doUpload() {
    const dbType = $('upType').value;
    const version = $('upVersion').value.trim();
    const driverClass = $('upDriverClass').value.trim();
    const file = $('upFile').files[0];

    if (!version) { toast('请填写驱动版本', 'bad'); return; }
    if (!file) { toast('请选择 JAR 文件', 'bad'); return; }

    const fd = new FormData();
    fd.append('file', file);
    fd.append('dbType', dbType);
    fd.append('version', version);
    if (driverClass) fd.append('driverClass', driverClass);

    try {
        await api('/api/drivers/upload', { method: 'POST', body: fd });
        toast('驱动上传成功', 'ok');
        closeModal('upModal');
        $('upVersion').value = '';
        $('upFile').value = '';
        await loadDrivers();
    } catch (e) {
        toast('上传失败：' + e.message, 'bad');
    }
}

/* ============================================================
   SQL 编辑器
   ============================================================ */

function renderQueryDsSelect() {
    const sel = $('queryDsSelect');
    const prev = sel.value;
    sel.innerHTML = state.datasources.length
        ? state.datasources.map(ds => {
            const meta = typeMeta(ds.dbType);
            return `<option value="${ds.id}">${esc(meta.label || ds.dbType)} · ${esc(ds.name)}</option>`;
        }).join('')
        : '<option value="">（暂无数据源）</option>';
    if (prev && [...sel.options].some(o => o.value === prev)) sel.value = prev;
}

async function runQuery() {
    const dsId = $('queryDsSelect').value;
    const sql = $('sqlInput').value.trim();

    if (!dsId) { toast('请先创建数据源', 'bad'); return; }
    if (!sql) { toast('请输入 SQL', 'bad'); return; }

    $('queryMeta').textContent = '执行中…';
    $('queryTiming').hidden = true;
    $('queryResult').innerHTML = '<div class="empty">执行中…</div>';

    try {
        const r = await api(`/api/query/${dsId}/execute`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sql })
        });
        renderQueryResult(r);
    } catch (e) {
        $('queryMeta').textContent = '执行失败';
        $('queryResult').innerHTML = `<div class="empty" style="color:var(--danger)">${esc(e.message)}</div>`;
    }
}

function renderQueryResult(r) {
    const timing = $('queryTiming');
    timing.hidden = false;
    timing.textContent = `${r.elapsedMs} ms`;

    if (!r.success) {
        $('queryMeta').textContent = '执行失败';
        $('queryResult').innerHTML =
            `<div class="empty" style="color:var(--danger)">
                ${esc(r.errorType || 'Error')}：${esc(r.error || '')}
             </div>`;
        return;
    }

    if (r.type === 'UPDATE') {
        $('queryMeta').textContent = `影响行数：${r.affectedRows}`;
        $('queryResult').innerHTML = `<div class="empty">语句执行成功，影响 ${r.affectedRows} 行</div>`;
        return;
    }

    const cols = r.columns || [];
    const rows = r.rows || [];
    $('queryMeta').textContent = `返回 ${r.rowCount} 行`;

    if (!rows.length) {
        $('queryResult').innerHTML = '<div class="empty">查询成功，无数据返回</div>';
        return;
    }

    const head = cols.map(c =>
        `<th>${esc(c.name)}<span class="coltype">${esc(c.type || '')}</span></th>`).join('');
    const body = rows.map(row =>
        '<tr>' + row.map(v =>
            v === null || v === undefined
                ? '<td class="null-val">NULL</td>'
                : `<td>${esc(v)}</td>`
        ).join('') + '</tr>'
    ).join('');

    $('queryResult').innerHTML =
        `<table class="grid"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

/* ============================================================
   巡检配置（模板 → 章节 → 规则 + 基线校验）
   ============================================================ */

const INSP_RISKS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const INSP_OPS = ['=', '>', '<', '>=', '<=', '!=', 'BETWEEN', 'LIKE'];

function bindInspectionEvents() {
    $('inspRefresh').addEventListener('click', () => loadInspection(true));
    $('btnNewTemplate').addEventListener('click', () => openTplModal(null));

    // Tabs（基线已拆为独立菜单，此处只剩模板 / 变更历史）
    document.querySelectorAll('#view-inspection .tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('#view-inspection .tab')
                .forEach(t => t.classList.toggle('active', t === tab));
            document.querySelectorAll('#view-inspection .tab-pane')
                .forEach(p => p.classList.toggle('active', p.id === 'pane-' + tab.dataset.tab));
            if (tab.dataset.tab === 'hist') loadHistory();
        });
    });

    // Template modal
    $('btnSaveTpl').addEventListener('click', saveTpl);

    // Chapter modal
    $('btnSaveCh').addEventListener('click', saveCh);

    // 章节引用规则弹窗（规则正文在规则引擎页维护，这里只做绑定）
    $('btnBindRule').addEventListener('click', bindRuleToChapter);

    $('btnSaveBase').addEventListener('click', saveBase);

    // 比较符切换 → BETWEEN 用区间输入
    $('baseOp').addEventListener('change', syncBaseOpFields);
}

/* ---------------- 基线规则（独立顶级菜单） ---------------- */

function bindBaselineEvents() {
    $('baseRefresh').addEventListener('click', () => loadBaselines());
    $('baseTypeSel').addEventListener('change', () => {
        state.inspection.baseType = $('baseTypeSel').value;
        state.inspection.values = {};
        loadBaselines();
    });
    $('btnNewBaseline').addEventListener('click', () => openBaseModal(null));
    $('btnBaseEnableAll').addEventListener('click', () => bulkBase(true));
    $('btnBaseDisableAll').addEventListener('click', () => bulkBase(false));
    $('btnRunCheck').addEventListener('click', runCheck);
    $('btnFillDemo').addEventListener('click', fillDemoValues);
    $('btnClearValues').addEventListener('click', () => {
        state.inspection.values = {};
        renderBaselines();
        $('checkResultWrap').innerHTML = '<div class="empty">已清空实测值</div>';
    });
}

/** 进入基线规则页：先确保模板/类型元数据已加载，再拉基线 */
async function ensureBaselines() {
    if (!state.dbTypes.length || !state.inspection.templates.length) {
        await loadInspection(false);
    }
    await loadBaselines();
}

/* ---------------- 数据加载 ---------------- */

async function ensureInspection() {
    if (state.inspection.loaded) return;
    await loadInspection(false);
}

async function loadInspection(force) {
    const insp = state.inspection;
    try {
        const [summary, templates] = await Promise.all([
            api('/api/inspection/summary'),
            api('/api/inspection/templates')
        ]);
        insp.summary = summary;
        insp.templates = templates || [];
        insp.loaded = true;
    } catch (e) {
        insp.loaded = false;
        $('inspStats').innerHTML =
            `<div class="stat-card"><div class="stat-label">巡检配置加载失败：${esc(e.message)}</div></div>`;
        $('tplList').innerHTML = '<div class="empty">无法加载模板</div>';
        return;
    }

    renderInspStats();
    renderTplList();

    // 默认选中：优先保持原选择，其次该类型默认模板
    if (!insp.activeTplId || !insp.templates.some(t => t.id === insp.activeTplId)) {
        const first = insp.templates.find(t => t.isDefault) || insp.templates[0];
        insp.activeTplId = first ? first.id : null;
    }
    if (insp.activeTplId) {
        if (force || !insp.tree) await selectTemplate(insp.activeTplId);
        else renderTplDetail();
    } else {
        $('tplDetail').innerHTML =
            '<div class="card"><div class="empty">尚无巡检模板，点击右上角「新建模板」创建</div></div>';
    }

    if (!$('baseTypeSel').options.length) fillBaseTypeSelect();
}

function fillBaseTypeSelect() {
    const opts = state.dbTypes
        .map(t => `<option value="${esc(t.dbType)}">${esc(t.emoji || '')} ${esc(t.label || t.dbType)}</option>`)
        .join('');
    $('baseTypeSel').innerHTML = opts;
    $('baseDbType').innerHTML = opts;
    if (!state.inspection.baseType) {
        state.inspection.baseType = state.dbTypes.length ? state.dbTypes[0].dbType : null;
    }
    if (state.inspection.baseType) $('baseTypeSel').value = state.inspection.baseType;
}

/* ---------------- 统计条 ---------------- */

function renderInspStats() {
    const s = state.inspection.summary || {};
    const risk = s.baselineRiskDistribution || {};
    const riskChips = INSP_RISKS
        .filter(r => risk[r])
        .map(r => `<span class="risk risk-${r}">${r} ${risk[r]}</span>`)
        .join(' ') || '<span class="count">—</span>';

    const baseTotal = Object.values(s.baselineCountByType || {})
        .reduce((a, b) => a + b, 0);

    $('inspStats').innerHTML = `
        <div class="stat-card">
            <div class="stat-num">${s.templateCount || 0}</div>
            <div class="stat-label">巡检模板</div>
            <div class="stat-sub">覆盖 ${(s.byDbType || []).length} 种数据库</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${s.totalChapters || 0}</div>
            <div class="stat-label">巡检章节</div>
            <div class="stat-sub">报告骨架：模板 → 章节</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${s.totalRules || 0}</div>
            <div class="stat-label">规则库</div>
            <div class="stat-sub">章节引用 ${s.totalBindings || 0} 条</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${baseTotal}</div>
            <div class="stat-label">基线阈值</div>
            <div class="stat-sub">${riskChips}</div>
        </div>`;
}

/* ---------------- 模板列表 ---------------- */

function renderTplList() {
    const insp = state.inspection;
    $('tplCount').textContent = insp.templates.length + ' 个';

    if (!insp.templates.length) {
        $('tplList').innerHTML = '<div class="empty">暂无模板</div>';
        return;
    }

    // 按数据库类型分组
    const groups = new Map();
    insp.templates.forEach(t => {
        if (!groups.has(t.dbType)) groups.set(t.dbType, []);
        groups.get(t.dbType).push(t);
    });

    const order = state.dbTypes.map(t => t.dbType);
    const keys = [...groups.keys()].sort((a, b) => {
        const ia = order.indexOf(a), ib = order.indexOf(b);
        return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib);
    });

    $('tplList').innerHTML = keys.map(dbType => {
        const meta = typeMeta(dbType);
        const items = groups.get(dbType).map(t => `
            <div class="tpl-item ${t.id === insp.activeTplId ? 'active' : ''}" data-tpl="${t.id}">
                <span class="tpl-name">${esc(t.templateNameZh)}</span>
                <span class="tpl-meta">
                    <span class="mono">${esc(t.version || 'v1')}</span>
                    ${t.isDefault ? '<span class="badge badge-active">默认</span>' : ''}
                    ${t.isPreset ? '<span class="badge badge-soft">预置</span>' : ''}
                </span>
            </div>`).join('');
        return `<div class="tpl-group-label">${esc(meta.emoji || '')} ${esc(meta.label || dbType)}</div>${items}`;
    }).join('');

    $('tplList').querySelectorAll('[data-tpl]').forEach(el =>
        el.addEventListener('click', () => selectTemplate(+el.dataset.tpl)));
}

async function selectTemplate(id) {
    state.inspection.activeTplId = id;
    renderTplList();
    try {
        state.inspection.tree = await api(`/api/inspection/templates/${id}/tree`);
    } catch (e) {
        toast('加载模板失败：' + e.message, 'bad');
        state.inspection.tree = null;
    }
    renderTplDetail();
}

/* ---------------- 模板详情 ---------------- */

function renderTplDetail() {
    const t = state.inspection.tree;
    const wrap = $('tplDetail');

    if (!t) {
        wrap.innerHTML = '<div class="card"><div class="empty">从左侧选择一个模板</div></div>';
        return;
    }

    const chapters = t.chapters || [];
    const rTotal = chapters.reduce((n, c) => n + (c.rules || []).length, 0);

    const chHtml = chapters.length ? chapters.map(c => {
        const open = !!state.inspection.openChapters[c.id];
        const rs = c.rules || [];
        const rHtml = rs.length ? rs.map(r => `
            <div class="q-row ${r.enabled ? '' : 'row-off'}">
                <div class="q-row-top">
                    <span class="q-key">${esc(r.ruleKey)}</span>
                    <span class="q-name">${esc(r.ruleNameZh || '')}</span>
                    ${r.enabled ? '' : '<span class="badge badge-soft">规则已停用</span>'}
                    ${r.refCount > 1 ? `<span class="badge badge-soft">另被 ${r.refCount - 1} 处引用</span>` : ''}
                    <span class="q-acts">
                        <button class="btn-link" data-edit-rule="${r.id}">编辑</button>
                        <button class="btn-link danger" data-unbind-rule="${r.id}" data-ch="${c.id}">解绑</button>
                    </span>
                </div>
                ${r.ruleNameEn ? `<div class="q-desc">${esc(r.ruleNameEn)}</div>` : ''}
                <div class="q-sql">${esc(r.ruleSql)}</div>
            </div>`).join('')
            : '<div class="empty" style="padding:14px">该章节尚未引用规则，点击下方按钮从规则库中挑选</div>';

        return `<div class="chap ${open ? 'open' : ''}" data-chap="${c.id}">
            <div class="chap-head" data-chap-toggle="${c.id}">
                <span class="chap-caret">▶</span>
                <span class="chap-num">${c.chapterNumber}</span>
                <span class="chap-title">
                    ${esc(c.chapterTitleZh)}
                    ${c.chapterTitleEn ? `<span class="en">${esc(c.chapterTitleEn)}</span>` : ''}
                </span>
                <span class="chap-acts">
                    <span class="badge ${c.enabled ? 'badge-soft' : 'badge-warn'}">${c.enabled ? '启用' : '停用'}</span>
                    <span class="count">${rs.length} 条规则</span>
                    <button class="btn-link" data-bind-rule="${c.id}">引用规则</button>
                    <button class="btn-link" data-edit-ch="${c.id}">编辑</button>
                    <button class="btn-link danger" data-del-ch="${c.id}">删除</button>
                </span>
            </div>
            <div class="chap-body">
                ${rHtml}
                <button class="btn btn-sm" data-bind-rule="${c.id}">+ 引用规则</button>
            </div>
        </div>`;
    }).join('')
        : '<div class="empty" style="padding:22px">该模板尚无章节，点击右上角「新增章节」开始配置</div>';

    wrap.innerHTML = `<div class="card">
        <div class="detail-head">
            <div>
                <h3>${esc(t.templateNameZh)}
                    ${t.isDefault ? '<span class="badge badge-active">默认</span>' : ''}
                    ${t.isPreset ? '<span class="badge badge-soft">预置</span>' : ''}
                </h3>
                <div class="desc">
                    ${esc(t.templateNameEn || '')} ·
                    <code>${esc(t.dbType)}</code> ·
                    <code>${esc(t.version || 'v1')}</code><br>
                    ${esc(t.description || '无说明')}
                </div>
            </div>
            <div class="detail-actions">
                <span class="count">${chapters.length} 章 / ${rTotal} 条规则引用</span>
                <button class="btn btn-sm" data-act="new-ch">+ 新增章节</button>
                <button class="btn btn-sm" data-act="edit-tpl">编辑模板</button>
                ${t.isDefault ? '' : '<button class="btn btn-sm" data-act="default-tpl">设为默认</button>'}
                <button class="btn btn-sm" data-act="del-tpl">删除模板</button>
            </div>
        </div>
        <div>${chHtml}</div>
    </div>`;

    // 章节折叠
    wrap.querySelectorAll('[data-chap-toggle]').forEach(el => {
        el.addEventListener('click', (e) => {
            if (e.target.closest('button')) return;   // 点操作按钮不折叠
            const id = el.dataset.chapToggle;
            state.inspection.openChapters[id] = !state.inspection.openChapters[id];
            el.parentElement.classList.toggle('open', !!state.inspection.openChapters[id]);
        });
    });

    // 模板级操作
    wrap.querySelector('[data-act="new-ch"]').addEventListener('click', () => openChModal(t.id, null));
    wrap.querySelector('[data-act="edit-tpl"]').addEventListener('click', () => openTplModal(t.id));
    wrap.querySelector('[data-act="del-tpl"]').addEventListener('click', () => deleteTpl(t));
    const defBtn = wrap.querySelector('[data-act="default-tpl"]');
    if (defBtn) defBtn.addEventListener('click', () => setDefaultTpl(t.id));

    // 章节操作
    wrap.querySelectorAll('[data-edit-ch]').forEach(b =>
        b.addEventListener('click', () => openChModal(t.id, +b.dataset.editCh)));
    wrap.querySelectorAll('[data-del-ch]').forEach(b =>
        b.addEventListener('click', () => deleteCh(+b.dataset.delCh)));

    // 规则引用操作（规则正文在「规则引擎」页维护，这里只做引用关系）
    wrap.querySelectorAll('[data-bind-rule]').forEach(b =>
        b.addEventListener('click', () => openBindModal(+b.dataset.bindRule)));
    wrap.querySelectorAll('[data-unbind-rule]').forEach(b =>
        b.addEventListener('click', () => unbindRule(+b.dataset.ch, +b.dataset.unbindRule)));
    wrap.querySelectorAll('[data-edit-rule]').forEach(b =>
        b.addEventListener('click', () => openRuleModal(+b.dataset.editRule)));
}

/* ---------------- 模板 CRUD ---------------- */

function openTplModal(id) {
    const isEdit = id !== null;
    $('tplModalTitle').textContent = isEdit ? '编辑巡检模板' : '新建巡检模板';
    $('tplId').value = isEdit ? id : '';

    if (isEdit) {
        const t = state.inspection.tree && state.inspection.tree.id === id
            ? state.inspection.tree
            : state.inspection.templates.find(x => x.id === id);
        if (!t) return;
        $('tplDbType').value = t.dbType;
        $('tplDbType').disabled = true;              // 类型归属创建后不可变
        $('tplNameZh').value = t.templateNameZh || '';
        $('tplNameZh').disabled = !!t.isPreset;      // 预置模板名称受保护
        $('tplNameEn').value = t.templateNameEn || '';
        $('tplVersion').value = t.version || '';
        $('tplVersion').disabled = !!t.isPreset;
        $('tplDesc').value = t.description || '';
    } else {
        $('tplDbType').disabled = false;
        $('tplNameZh').disabled = false;
        $('tplVersion').disabled = false;
        $('tplDbType').selectedIndex = 0;
        $('tplNameZh').value = '';
        $('tplNameEn').value = '';
        $('tplVersion').value = 'v1';
        $('tplDesc').value = '';
    }
    openModal('tplModal');
}

async function saveTpl() {
    const id = $('tplId').value;
    const body = {
        dbType: $('tplDbType').value,
        templateNameZh: $('tplNameZh').value.trim(),
        templateNameEn: $('tplNameEn').value.trim(),
        version: $('tplVersion').value.trim(),
        description: $('tplDesc').value.trim()
    };
    if (!body.dbType) { toast('请选择数据库类型', 'bad'); return; }
    if (!body.templateNameZh) { toast('请填写模板名称', 'bad'); return; }

    try {
        if (id) {
            await api(`/api/inspection/templates/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('模板已更新', 'ok');
        } else {
            const created = await api('/api/inspection/templates', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            state.inspection.activeTplId = created.id;
            toast('模板已创建', 'ok');
        }
        closeModal('tplModal');
        await loadInspection(true);
    } catch (e) {
        toast('保存失败：' + e.message, 'bad');
    }
}

async function setDefaultTpl(id) {
    try {
        await api(`/api/inspection/templates/${id}/default`, { method: 'POST' });
        toast('已设为该类型默认模板', 'ok');
        await loadInspection(true);
    } catch (e) {
        toast('设置失败：' + e.message, 'bad');
    }
}

async function deleteTpl(t) {
    let force = false;
    if (t.isPreset) {
        if (!confirm(`「${t.templateNameZh}」是预置模板。强制删除将连同其章节与规则一并移除，且不可恢复。\n\n确定继续？`)) return;
        force = true;
    } else if (!confirm(`确定删除模板「${t.templateNameZh}」？其下所有章节与规则将级联删除，不可恢复。`)) {
        return;
    }
    try {
        await api(`/api/inspection/templates/${t.id}?force=${force}`, { method: 'DELETE' });
        toast('模板已删除', 'ok');
        state.inspection.tree = null;
        state.inspection.activeTplId = null;
        await loadInspection(true);
    } catch (e) {
        toast('删除失败：' + e.message, 'bad');
    }
}

/* ---------------- 章节 CRUD ---------------- */

function openChModal(templateId, chId) {
    const isEdit = chId !== null;
    $('chModalTitle').textContent = isEdit ? '编辑章节' : '新增章节';
    $('chId').value = isEdit ? chId : '';
    $('chTemplateId').value = templateId;

    const chapters = (state.inspection.tree && state.inspection.tree.chapters) || [];

    if (isEdit) {
        const c = chapters.find(x => x.id === chId);
        if (!c) return;
        $('chNumber').value = c.chapterNumber;
        $('chNumber').disabled = true;               // 章节号创建后不可变
        $('chTitleZh').value = c.chapterTitleZh || '';
        $('chTitleEn').value = c.chapterTitleEn || '';
        $('chDesc').value = c.description || '';
        $('chSort').value = c.sortOrder || 0;
        $('chEnabled').value = c.enabled ? '1' : '0';
    } else {
        $('chNumber').disabled = false;
        const maxNum = chapters.reduce((m, c) => Math.max(m, c.chapterNumber || 0), 0);
        $('chNumber').value = maxNum + 1;
        $('chTitleZh').value = '';
        $('chTitleEn').value = '';
        $('chDesc').value = '';
        $('chSort').value = 0;
        $('chEnabled').value = '1';
    }
    openModal('chModal');
}

async function saveCh() {
    const id = $('chId').value;
    const body = {
        templateId: +$('chTemplateId').value,
        chapterNumber: parseInt($('chNumber').value, 10) || null,
        chapterTitleZh: $('chTitleZh').value.trim(),
        chapterTitleEn: $('chTitleEn').value.trim(),
        description: $('chDesc').value.trim(),
        sortOrder: parseInt($('chSort').value, 10) || 0,
        enabled: $('chEnabled').value === '1' ? 1 : 0
    };
    if (!body.chapterTitleZh) { toast('请填写章节标题', 'bad'); return; }

    try {
        if (id) {
            await api(`/api/inspection/chapters/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('章节已更新', 'ok');
        } else {
            await api('/api/inspection/chapters', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('章节已创建', 'ok');
        }
        closeModal('chModal');
        await reloadTree();
    } catch (e) {
        toast('保存失败：' + e.message, 'bad');
    }
}

async function deleteCh(chId) {
    const chapters = (state.inspection.tree && state.inspection.tree.chapters) || [];
    const c = chapters.find(x => x.id === chId);
    const n = c ? (c.rules || []).length : 0;
    if (!confirm(`确定删除章节「${c ? c.chapterTitleZh : chId}」？\n` +
        `该章引用的 ${n} 条规则会随之解绑，但规则本身仍保留在规则库中，其他模板不受影响。`)) return;
    try {
        await api(`/api/inspection/chapters/${chId}`, { method: 'DELETE' });
        toast('章节已删除', 'ok');
        await reloadTree();
    } catch (e) {
        toast('删除失败：' + e.message, 'bad');
    }
}

/* ============================================================
   规则引擎（规则库）
   ============================================================ */

function bindRuleEvents() {
    $('ruleRefresh').addEventListener('click', () => loadRules(true));
    $('btnNewRule').addEventListener('click', () => openRuleModal(null));
    $('btnSaveRule').addEventListener('click', saveRule);

    $('ruleTypeSel').addEventListener('change', () => {
        state.rules.dbType = $('ruleTypeSel').value;
        state.rules.category = '';        // 换了库类型，原分类多半不存在了
        loadRules(true);
    });
    $('ruleCatSel').addEventListener('change', () => {
        state.rules.category = $('ruleCatSel').value;
        loadRules(true);
    });
    $('ruleStateSel').addEventListener('change', () => {
        state.rules.enabled = $('ruleStateSel').value;
        loadRules(true);
    });

    // 搜索防抖：边打字边请求既浪费也会让结果乱序闪烁
    let kwTimer = null;
    $('ruleKeyword').addEventListener('input', () => {
        clearTimeout(kwTimer);
        kwTimer = setTimeout(() => {
            state.rules.keyword = $('ruleKeyword').value.trim();
            loadRules(true);
        }, 250);
    });

    $('btnRuleEnableAll').addEventListener('click', () => bulkRules(true));
    $('btnRuleDisableAll').addEventListener('click', () => bulkRules(false));
    $('btnRuleTest').addEventListener('click', runRuleTest);
    $('ruleTestSel').addEventListener('change', () => {
        const v = $('ruleTestSel').value;
        state.rules.testRuleId = v ? +v : null;
        state.rules.testResult = null;
        renderRuleTest();
    });
}

async function ensureRules() {
    if (state.rules.loaded) return;
    await loadRules(false);
}

function fillRuleTypeSelect() {
    const opts = state.dbTypes
        .map(t => `<option value="${esc(t.dbType)}">${esc(t.emoji || '')} ${esc(t.label || t.dbType)}</option>`)
        .join('');
    $('ruleTypeSel').innerHTML = opts;
    $('ruleDbType').innerHTML = opts;
    if (!state.rules.dbType) {
        state.rules.dbType = state.dbTypes.length ? state.dbTypes[0].dbType : null;
    }
    if (state.rules.dbType) $('ruleTypeSel').value = state.rules.dbType;
}

async function loadRules(force) {
    const r = state.rules;
    if (r.loaded && !force) return;
    if (!$('ruleTypeSel').options.length) fillRuleTypeSelect();

    const qs = new URLSearchParams();
    if (r.dbType) qs.set('dbType', r.dbType);
    if (r.category) qs.set('category', r.category);
    if (r.enabled !== '') qs.set('enabled', r.enabled);
    if (r.keyword) qs.set('keyword', r.keyword);

    try {
        const [list, stats, cats] = await Promise.all([
            api('/api/inspection/rules?' + qs.toString()),
            api('/api/inspection/rules/stats'),
            api('/api/inspection/rules/categories'
                + (r.dbType ? '?dbType=' + encodeURIComponent(r.dbType) : ''))
        ]);
        r.list = list || [];
        r.stats = stats || null;
        r.categories = cats || [];
        r.loaded = true;
    } catch (e) {
        r.list = [];
        toast('加载规则库失败：' + e.message, 'bad');
    }
    fillRuleCatSelect();
    renderRuleStats();
    renderRuleTable();
    fillRuleTestSelect();
}

function fillRuleCatSelect() {
    const cur = state.rules.category || '';
    $('ruleCatSel').innerHTML = ['<option value="">全部章节</option>']
        .concat((state.rules.categories || [])
            .map(c => `<option value="${esc(c)}">${esc(c)}</option>`))
        .join('');
    // 分类列表随库类型变化，旧选中项可能已不存在，这里以实际生效值为准
    $('ruleCatSel').value = cur;
    state.rules.category = $('ruleCatSel').value || '';
}

/** 规则引擎页顶部统计条 */
function renderRuleStats() {
    const st = state.rules.stats;
    if (!st) { $('ruleStats').innerHTML = ''; return; }

    const byType = st.byDbType || {};
    const chips = Object.entries(byType)
        .map(([k, v]) => `<span class="badge badge-soft">${esc(typeMeta(k).label || k)} ${esc(v)}</span>`)
        .join(' ') || '<span class="count">—</span>';

    $('ruleStats').innerHTML = `
        <div class="stat-card">
            <div class="stat-num">${st.total || 0}</div>
            <div class="stat-label">规则总数</div>
            <div class="stat-sub">启用 ${st.enabled || 0} · 停用 ${st.disabled || 0}</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${st.unbound || 0}</div>
            <div class="stat-label">未被任何章节引用</div>
            <div class="stat-sub">${st.unbound ? '这些规则不会参与巡检' : '全部规则都已挂到章节上'}</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${Object.keys(byType).length}</div>
            <div class="stat-label">覆盖库类型</div>
            <div class="stat-sub">${chips}</div>
        </div>`;
}

function renderRuleTable() {
    const list = state.rules.list || [];
    const enabled = list.filter(r => r.enabled).length;
    $('ruleCount').textContent = `${list.length} 条（启用 ${enabled}）`;

    const wrap = $('ruleTableWrap');
    if (!list.length) {
        wrap.innerHTML = '<div class="empty" style="padding:24px">' +
            '没有符合条件的规则，调整筛选条件或点击「新建规则」</div>';
        return;
    }

    const rows = list.map(r => {
        const usedBy = r.usedBy || [];
        const usedText = usedBy.length
            ? usedBy.map(u => `<span class="badge badge-soft">${esc(u)}</span>`).join(' ')
            : '<span class="count">未被引用</span>';
        return `<tr class="${r.enabled ? '' : 'row-off'}">
            <td class="mono shrink">${esc(r.ruleKey)}</td>
            <td>
                ${esc(r.ruleNameZh || '')}
                ${r.ruleNameEn ? `<div class="q-desc">${esc(r.ruleNameEn)}</div>` : ''}
            </td>
            <td class="shrink">${esc(typeMeta(r.dbType).label || r.dbType)}</td>
            <td class="shrink">${esc(r.category || '—')}</td>
            <td class="shrink">
                <span class="badge ${r.enabled ? 'badge-soft' : 'badge-warn'}">${r.enabled ? '启用' : '停用'}</span>
            </td>
            <td class="shrink">${r.refCount || 0}</td>
            <td class="shrink">${usedText}</td>
            <td class="right shrink">
                <button class="btn-link" data-test-rule="${r.id}">试跑</button>
                <button class="btn-link" data-edit-rule="${r.id}">编辑</button>
                <button class="btn-link" data-toggle-rule="${r.id}" data-on="${r.enabled ? 0 : 1}">
                    ${r.enabled ? '停用' : '启用'}</button>
                <button class="btn-link danger" data-del-rule="${r.id}">删除</button>
            </td>
        </tr>`;
    }).join('');

    wrap.innerHTML = `<table class="table">
        <thead><tr>
            <th>规则 Key</th><th>名称</th><th>库类型</th><th>建议章节</th>
            <th>状态</th><th>引用</th><th>被谁引用</th><th class="right">操作</th>
        </tr></thead>
        <tbody>${rows}</tbody>
    </table>`;

    wrap.querySelectorAll('[data-edit-rule]').forEach(b =>
        b.addEventListener('click', () => openRuleModal(+b.dataset.editRule)));
    wrap.querySelectorAll('[data-toggle-rule]').forEach(b =>
        b.addEventListener('click', () => toggleRule(+b.dataset.toggleRule, b.dataset.on === '1')));
    wrap.querySelectorAll('[data-del-rule]').forEach(b =>
        b.addEventListener('click', () => deleteRule(+b.dataset.delRule)));
    wrap.querySelectorAll('[data-test-rule]').forEach(b =>
        b.addEventListener('click', () => {
            const id = b.dataset.testRule;
            state.rules.testRuleId = +id;
            state.rules.testResult = null;
            $('ruleTestSel').value = id;
            runRuleTest();
        }));
}

/* ---------------- 规则 CRUD ---------------- */

async function openRuleModal(id) {
    const isEdit = id !== null;
    $('ruleModalTitle').textContent = isEdit ? '编辑规则' : '新建规则';
    $('ruleId').value = isEdit ? id : '';
    $('ruleModalHint').hidden = true;

    let r = null;
    if (isEdit) {
        r = (state.rules.list || []).find(x => x.id === id) || null;
        // 从模板页点进来时，该规则可能被当前筛选条件挡在外面，回源取一次
        if (!r) {
            try {
                r = await api(`/api/inspection/rules/${id}`);
            } catch (e) {
                toast('加载规则失败：' + e.message, 'bad');
                return;
            }
        }
    }

    if (!$('ruleDbType').options.length) fillRuleTypeSelect();

    if (r) {
        const preset = r.source === 'PRESET';
        $('ruleKey').value = r.ruleKey || '';
        $('ruleKey').disabled = preset;      // 预置规则的 key 与库类型不可改：改了等于换一条规则
        $('ruleDbType').value = r.dbType || '';
        $('ruleDbType').disabled = preset;
        $('ruleNameZh').value = r.ruleNameZh || '';
        $('ruleNameEn').value = r.ruleNameEn || '';
        $('ruleCategory').value = r.category || '';
        $('ruleSql').value = r.ruleSql || '';
        $('ruleEnabled').value = r.enabled ? '1' : '0';

        const refs = r.refCount || 0;
        $('ruleModalHint').hidden = false;
        $('ruleModalHint').textContent = refs
            ? `该规则被 ${refs} 处引用（${(r.usedBy || []).join('、')}），修改后所有引用它的模板同时生效`
            : '该规则目前没有被任何章节引用，修改只影响规则库本身';
    } else {
        $('ruleKey').disabled = false;
        $('ruleDbType').disabled = false;
        $('ruleKey').value = '';
        $('ruleDbType').value = state.rules.dbType || '';
        $('ruleNameZh').value = '';
        $('ruleNameEn').value = '';
        $('ruleCategory').value = state.rules.category || '';
        $('ruleSql').value = '';
        $('ruleEnabled').value = '1';
    }
    openModal('ruleModal');
}

async function saveRule() {
    const id = $('ruleId').value;
    const body = {
        ruleKey: $('ruleKey').value.trim(),
        dbType: $('ruleDbType').value,
        ruleNameZh: $('ruleNameZh').value.trim(),
        ruleNameEn: $('ruleNameEn').value.trim(),
        category: $('ruleCategory').value.trim(),
        ruleSql: $('ruleSql').value.trim(),
        enabled: $('ruleEnabled').value === '1' ? 1 : 0
    };
    if (!body.ruleKey) { toast('请填写规则 Key', 'bad'); return; }
    if (!body.dbType) { toast('请选择归属库类型', 'bad'); return; }
    if (!body.ruleNameZh) { toast('请填写规则名称', 'bad'); return; }
    if (!body.ruleSql) { toast('请填写 SQL 语句', 'bad'); return; }

    try {
        if (id) {
            await api(`/api/inspection/rules/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('规则已更新', 'ok');
        } else {
            await api('/api/inspection/rules', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('规则已创建', 'ok');
        }
        closeModal('ruleModal');
        await afterRuleChange();
    } catch (e) {
        toast('保存失败：' + e.message, 'bad');
    }
}

async function toggleRule(id, enabled) {
    try {
        await api(`/api/inspection/rules/${id}/enabled?enabled=${enabled}`, { method: 'POST' });
        toast(enabled ? '规则已启用' : '规则已停用', 'ok');
        await afterRuleChange();
    } catch (e) {
        toast('操作失败：' + e.message, 'bad');
    }
}

async function deleteRule(id) {
    const r = (state.rules.list || []).find(x => x.id === id);
    const refs = r ? (r.refCount || 0) : 0;
    const name = r ? r.ruleKey : id;

    // 被引用的规则默认拒绝删除（后端也拦一道），先讲清后果再带上 force
    if (refs) {
        if (!confirm(`规则「${name}」正被 ${refs} 处章节引用。\n` +
            `删除会一并解除这些引用，相关模板的巡检报告将不再包含这条规则。\n\n确定删除？`)) return;
        try {
            await api(`/api/inspection/rules/${id}?force=true`, { method: 'DELETE' });
            toast('规则及其引用已删除', 'ok');
            await afterRuleChange();
        } catch (e) {
            toast('删除失败：' + e.message, 'bad');
        }
        return;
    }

    if (!confirm(`确定删除规则「${name}」？`)) return;
    try {
        await api(`/api/inspection/rules/${id}`, { method: 'DELETE' });
        toast('规则已删除', 'ok');
        await afterRuleChange();
    } catch (e) {
        toast('删除失败：' + e.message, 'bad');
    }
}

async function bulkRules(enabled) {
    const dbType = state.rules.dbType;
    if (!dbType) return;
    if (!confirm(`确定将 ${typeMeta(dbType).label || dbType} 的全部规则${enabled ? '启用' : '停用'}？\n` +
        `启停是规则级的，会同时影响所有引用这些规则的模板。`)) return;
    try {
        const r = await api(
            `/api/inspection/rules/enabled?dbType=${encodeURIComponent(dbType)}&enabled=${enabled}`,
            { method: 'POST' });
        toast(`已${enabled ? '启用' : '停用'} ${r.affected} 条规则`, 'ok');
        await afterRuleChange();
    } catch (e) {
        toast('批量操作失败：' + e.message, 'bad');
    }
}

/** 规则改动后：规则库、当前模板树、总览统计都要跟着刷新 */
async function afterRuleChange() {
    await loadRules(true);
    if (state.inspection.activeTplId) {
        try {
            state.inspection.tree =
                await api(`/api/inspection/templates/${state.inspection.activeTplId}/tree`);
            renderTplDetail();
        } catch (e) { /* 模板页没打开时失败无所谓 */ }
    }
    await refreshSummary();
}

/* ---------------- 规则试跑 ---------------- */

function fillRuleTestSelect() {
    const list = state.rules.list || [];
    const cur = state.rules.testRuleId;
    $('ruleTestSel').innerHTML = ['<option value="">— 选择规则 —</option>']
        .concat(list.map(r =>
            `<option value="${r.id}">${esc(r.ruleKey)} · ${esc(r.ruleNameZh || '')}</option>`))
        .join('');
    if (cur && list.some(r => r.id === cur)) $('ruleTestSel').value = cur;

    const ds = state.datasources || [];
    let curDs = $('ruleTestDs').value;
    // 默认选中第一个数据源：否则行内「试跑」点下去只会弹「请先选择数据源」，
    // 而面板里那个下拉本身就摆在眼前，选中项随时可改。
    if (!curDs && ds.length) curDs = String(ds[0].id);
    $('ruleTestDs').innerHTML = ['<option value="">— 选择数据源 —</option>']
        .concat(ds.map(d =>
            `<option value="${d.id}">${esc(d.name)}（${esc(typeMeta(d.dbType).label || d.dbType)}）</option>`))
        .join('');
    if (curDs && ds.some(d => String(d.id) === curDs)) $('ruleTestDs').value = curDs;
}

async function runRuleTest() {
    const ruleId = $('ruleTestSel').value ? +$('ruleTestSel').value : null;
    const dsId = $('ruleTestDs').value ? +$('ruleTestDs').value : null;
    if (!ruleId) { toast('请先选择一条规则', 'bad'); return; }
    if (!dsId) { toast('请先选择数据源', 'bad'); return; }

    $('ruleTestWrap').innerHTML = '<div class="empty">执行中…</div>';
    try {
        state.rules.testResult = await api(`/api/inspection/rules/${ruleId}/test`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dataSourceId: dsId })
        });
    } catch (e) {
        // 预览服务没有 JDBC 能力，会明确回 501；如实展示，不伪造结果
        state.rules.testResult = { status: 'FAILED', errorMsg: e.message };
    }
    renderRuleTest();
}

function renderRuleTest() {
    const wrap = $('ruleTestWrap');
    const res = state.rules.testResult;
    if (!res) {
        wrap.innerHTML = '<div class="empty">选择规则与数据源后点击「试跑」</div>';
        return;
    }

    const ok = res.status === 'OK';
    const cols = res.columns || [];
    const rows = res.rows || [];
    const head = cols.length ? `<tr>${cols.map(c => `<th>${esc(c)}</th>`).join('')}</tr>` : '';
    const body = rows.length
        ? rows.map(r => {
            const cells = Array.isArray(r) ? r : cols.map(c => r[c]);
            return `<tr>${cells.map(v =>
                `<td class="mono">${esc(v === null || v === undefined ? '—' : v)}</td>`).join('')}</tr>`;
        }).join('')
        : `<tr><td colspan="${Math.max(cols.length, 1)}" class="count">无数据行</td></tr>`;

    wrap.innerHTML = `
        <div class="test-meta">
            <span class="badge ${ok ? 'badge-ok' : 'badge-bad'}">${esc(res.status || '—')}</span>
            <span class="count">耗时 ${esc(res.elapsedMs ?? '—')} ms</span>
            <span class="count">返回 ${esc(res.rowCount ?? 0)} 行</span>
            ${res.truncated ? '<span class="badge badge-warn">结果已截断</span>' : ''}
            ${res.dbTypeMismatch ? '<span class="badge badge-warn">规则库类型与数据源不一致</span>' : ''}
        </div>
        ${res.errorMsg ? `<div class="empty" style="color:var(--danger);padding:14px">${esc(res.errorMsg)}</div>` : ''}
        ${cols.length ? `<div class="table-scroll"><table class="table">${head}<tbody>${body}</tbody></table></div>` : ''}`;
}

/* ---------------- 章节 ↔ 规则 绑定 ---------------- */

async function openBindModal(chapterId) {
    state.rules.bindChapterId = chapterId;
    const chapters = (state.inspection.tree && state.inspection.tree.chapters) || [];
    const ch = chapters.find(c => c.id === chapterId);
    $('bindChapterInfo').textContent = ch
        ? `第 ${ch.chapterNumber} 章 · ${ch.chapterTitleZh}` +
          (ch.chapterTitleEn ? ` · ${ch.chapterTitleEn}` : '')
        : `章节 #${chapterId}`;

    $('bindListWrap').innerHTML = '<div class="empty">加载中…</div>';
    openModal('bindModal');
    await refreshBindList();
    await fillBindRuleSelect();
}

async function refreshBindList() {
    const chapterId = state.rules.bindChapterId;
    if (!chapterId) return;
    try {
        state.rules.bindList = await api(`/api/inspection/chapters/${chapterId}/rules`) || [];
    } catch (e) {
        state.rules.bindList = [];
        toast('加载章节引用失败：' + e.message, 'bad');
    }
    renderBindList();
}

function renderBindList() {
    const list = state.rules.bindList || [];
    const wrap = $('bindListWrap');
    if (!list.length) {
        wrap.innerHTML = '<div class="empty" style="padding:18px">该章节尚未引用任何规则</div>';
        return;
    }
    wrap.innerHTML = `<table class="table">
        <thead><tr>
            <th>规则 Key</th><th>名称</th><th>状态</th><th class="right">操作</th>
        </tr></thead>
        <tbody>${list.map(r => `<tr class="${r.enabled ? '' : 'row-off'}">
            <td class="mono shrink">${esc(r.ruleKey)}</td>
            <td>${esc(r.ruleNameZh || '')}</td>
            <td class="shrink">
                <span class="badge ${r.enabled ? 'badge-soft' : 'badge-warn'}">${r.enabled ? '启用' : '停用'}</span>
            </td>
            <td class="right shrink">
                <button class="btn-link danger" data-unbind="${r.id}">解绑</button>
            </td>
        </tr>`).join('')}</tbody></table>`;

    wrap.querySelectorAll('[data-unbind]').forEach(b =>
        b.addEventListener('click', () => unbindRule(state.rules.bindChapterId, +b.dataset.unbind)));
}

async function fillBindRuleSelect() {
    const chapterId = state.rules.bindChapterId;
    const chapters = (state.inspection.tree && state.inspection.tree.chapters) || [];
    const dbType = (state.inspection.tree && state.inspection.tree.dbType) || null;
    const bound = new Set((state.rules.bindList || []).map(r => r.id));

    // 只列同库类型的规则：别的库的 SQL 在这台上跑不通，列出来只会误导
    let pool = state.rules.list || [];
    if (dbType) {
        pool = pool.filter(r => r.dbType === dbType);
        if (!pool.length) {
            try {
                pool = await api(`/api/inspection/rules?dbType=${encodeURIComponent(dbType)}`) || [];
            } catch (e) { pool = []; }
        }
    }
    const avail = pool.filter(r => !bound.has(r.id));

    $('bindRuleSel').innerHTML = avail.length
        ? avail.map(r => `<option value="${r.id}">${esc(r.ruleKey)} · ${esc(r.ruleNameZh || '')}</option>`).join('')
        : '<option value="">（同库类型下已无可绑定的规则）</option>';
}

async function bindRuleToChapter() {
    const chapterId = state.rules.bindChapterId;
    const v = $('bindRuleSel').value;
    if (!chapterId || !v) { toast('没有可绑定的规则', 'bad'); return; }
    try {
        await api(`/api/inspection/chapters/${chapterId}/rules`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ ruleId: +v })
        });
        toast('已绑定', 'ok');
        await refreshBindList();
        await fillBindRuleSelect();
        await reloadTree();
    } catch (e) {
        toast('绑定失败：' + e.message, 'bad');
    }
}

async function unbindRule(chapterId, ruleId) {
    const r = (state.rules.bindList || []).find(x => x.id === ruleId)
        || (state.rules.list || []).find(x => x.id === ruleId);
    const name = r ? r.ruleKey : ruleId;
    if (!confirm(`确定解除对该规则的引用？\n规则「${name}」本身仍保留在规则库中。`)) return;
    try {
        await api(`/api/inspection/chapters/${chapterId}/rules/${ruleId}`, { method: 'DELETE' });
        toast('已解绑', 'ok');
        if (state.rules.bindChapterId === chapterId) {
            await refreshBindList();
            await fillBindRuleSelect();
        }
        await reloadTree();
    } catch (e) {
        toast('解绑失败：' + e.message, 'bad');
    }
}

/** 重新拉取当前模板树并保持章节展开状态 */
async function reloadTree() {
    const id = state.inspection.activeTplId;
    if (!id) return;
    try {
        state.inspection.tree = await api(`/api/inspection/templates/${id}/tree`);
    } catch (e) {
        toast('刷新模板失败：' + e.message, 'bad');
    }
    renderTplDetail();
    try {
        state.inspection.summary = await api('/api/inspection/summary');
        renderInspStats();
    } catch (e) { /* 统计失败不阻断主流程 */ }
}

/* ---------------- 基线管理 ---------------- */

async function loadBaselines() {
    if (!state.inspection.baseType) fillBaseTypeSelect();
    const dbType = state.inspection.baseType;
    if (!dbType) return;

    try {
        state.inspection.baselines = await api(`/api/inspection/baselines?dbType=${encodeURIComponent(dbType)}`) || [];
    } catch (e) {
        state.inspection.baselines = [];
        toast('加载基线失败：' + e.message, 'bad');
    }
    renderBaselines();
    renderBaseStats();
}

/** 基线规则页顶部统计条：当前类型 + 全局分布 */
function renderBaseStats() {
    const list = state.inspection.baselines || [];
    const enabled = list.filter(b => b.enabled).length;
    const risk = {};
    list.forEach(b => { risk[b.riskLevel || 'UNKNOWN'] = (risk[b.riskLevel || 'UNKNOWN'] || 0) + 1; });

    const sum = state.inspection.summary || {};
    const byType = sum.baselineCountByType || {};
    const globalTotal = Object.values(byType).reduce((a, b) => a + b, 0);

    const meta = typeMeta(state.inspection.baseType);
    const riskChips = Object.entries(risk)
        .sort((a, b) => INSP_RISKS.indexOf(a[0]) - INSP_RISKS.indexOf(b[0]))
        .map(([k, v]) => `<span class="risk risk-${esc(k)}">${esc(k)} × ${esc(v)}</span>`)
        .join(' ') || '<span class="count">—</span>';

    $('baseStats').innerHTML = `
        <div class="stat-card">
            <div class="stat-num">${list.length}</div>
            <div class="stat-label">该类型基线</div>
            <div class="stat-sub">启用 ${enabled} · 停用 ${list.length - enabled}</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${enabled}</div>
            <div class="stat-label">启用中</div>
            <div class="stat-sub">${esc(meta.emoji || '')} ${esc(meta.label || state.inspection.baseType || '—')}</div>
        </div>
        <div class="stat-card">
            <div class="stat-num">${globalTotal}</div>
            <div class="stat-label">全部库类型合计</div>
            <div class="stat-sub">覆盖 ${Object.keys(byType).length} 种库类型</div>
        </div>
        <div class="stat-card">
            <div class="stat-label" style="margin-top:0">该类型风险分布</div>
            <div class="stat-chips">${riskChips}</div>
        </div>`;
}

function baselineExpectedText(b) {
    if ((b.operator || '').toUpperCase() === 'BETWEEN') {
        return `[${b.expectedValueMin ?? '?'}, ${b.expectedValueMax ?? '?'}]`;
    }
    return b.expectedValue === null || b.expectedValue === undefined ? '—' : String(b.expectedValue);
}

function renderBaselines() {
    const list = state.inspection.baselines || [];
    const enabled = list.filter(b => b.enabled).length;
    $('baseCount').textContent = `${list.length} 条（启用 ${enabled}）`;

    if (!list.length) {
        $('baseTableWrap').innerHTML =
            '<div class="empty" style="padding:24px">该类型暂无基线配置，点击「新建基线」添加</div>';
        return;
    }

    const rows = list.map(b => {
        const v = state.inspection.values[b.paramName];
        return `<tr class="${b.enabled ? '' : 'row-off'}">
            <td><strong class="mono">${esc(b.paramName)}</strong></td>
            <td class="shrink"><span class="op-chip">${esc(b.operator)}</span></td>
            <td class="shrink"><span class="expected">${esc(baselineExpectedText(b))}</span></td>
            <td class="shrink"><span class="risk risk-${esc(b.riskLevel || 'MEDIUM')}">${esc(b.riskLevel || '—')}</span></td>
            <td>${esc(b.descriptionZh || b.descriptionEn || '')}</td>
            <td class="shrink">
                <input class="mini-input" data-val="${esc(b.paramName)}"
                       value="${v === undefined ? '' : esc(v)}" placeholder="实测值">
            </td>
            <td class="right shrink">
                <button class="btn-link" data-base-toggle="${b.id}" data-on="${b.enabled ? 0 : 1}">${b.enabled ? '停用' : '启用'}</button>
                <button class="btn-link" data-base-edit="${b.id}">编辑</button>
                <button class="btn-link danger" data-base-del="${b.id}">删除</button>
            </td>
        </tr>`;
    }).join('');

    $('baseTableWrap').innerHTML = `<table class="table">
        <thead><tr>
            <th>参数名</th><th>比较</th><th>期望值</th><th>风险</th><th>说明</th>
            <th>实测值</th><th class="right">操作</th>
        </tr></thead>
        <tbody>${rows}</tbody>
    </table>`;

    // 实测值输入
    $('baseTableWrap').querySelectorAll('[data-val]').forEach(inp => {
        inp.addEventListener('input', () => {
            state.inspection.values[inp.dataset.val] = inp.value;
        });
    });
    $('baseTableWrap').querySelectorAll('[data-base-toggle]').forEach(b =>
        b.addEventListener('click', () => toggleBase(+b.dataset.baseToggle, b.dataset.on === '1')));
    $('baseTableWrap').querySelectorAll('[data-base-edit]').forEach(b =>
        b.addEventListener('click', () => openBaseModal(+b.dataset.baseEdit)));
    $('baseTableWrap').querySelectorAll('[data-base-del]').forEach(b =>
        b.addEventListener('click', () => deleteBase(+b.dataset.baseDel)));
}

function syncBaseOpFields() {
    const isBetween = $('baseOp').value.toUpperCase() === 'BETWEEN';
    $('fRange').hidden = !isBetween;
    $('fExpected').hidden = isBetween;
}

function openBaseModal(id) {
    const isEdit = id !== null;
    $('baseModalTitle').textContent = isEdit ? '编辑基线' : '新建基线';
    $('baseId').value = isEdit ? id : '';

    if (isEdit) {
        const b = state.inspection.baselines.find(x => x.id === id);
        if (!b) return;
        $('baseDbType').value = b.dbType;
        $('baseDbType').disabled = true;
        $('baseParam').value = b.paramName || '';
        $('baseOp').value = b.operator || '=';
        $('baseExpected').value = b.expectedValue ?? '';
        $('baseMin').value = b.expectedValueMin ?? '';
        $('baseMax').value = b.expectedValueMax ?? '';
        $('baseRisk').value = b.riskLevel || 'MEDIUM';
        $('baseSql').value = b.querySql || '';
        $('baseDescZh').value = b.descriptionZh || '';
        $('baseDescEn').value = b.descriptionEn || '';
        $('baseEnabled').value = b.enabled ? '1' : '0';
    } else {
        $('baseDbType').disabled = false;
        $('baseDbType').value = state.inspection.baseType || '';
        $('baseParam').value = '';
        $('baseOp').value = '=';
        $('baseExpected').value = '';
        $('baseMin').value = '';
        $('baseMax').value = '';
        $('baseRisk').value = 'MEDIUM';
        $('baseSql').value = '';
        $('baseDescZh').value = '';
        $('baseDescEn').value = '';
        $('baseEnabled').value = '1';
    }
    syncBaseOpFields();
    openModal('baseModal');
}

async function saveBase() {
    const id = $('baseId').value;
    const op = $('baseOp').value.toUpperCase();
    const body = {
        dbType: $('baseDbType').value,
        paramName: $('baseParam').value.trim(),
        operator: op,
        expectedValue: op === 'BETWEEN' ? null : ($('baseExpected').value.trim() || null),
        expectedValueMin: op === 'BETWEEN' ? parseFloat($('baseMin').value) : null,
        expectedValueMax: op === 'BETWEEN' ? parseFloat($('baseMax').value) : null,
        riskLevel: $('baseRisk').value,
        querySql: $('baseSql').value.trim(),
        descriptionZh: $('baseDescZh').value.trim(),
        descriptionEn: $('baseDescEn').value.trim(),
        enabled: $('baseEnabled').value === '1' ? 1 : 0
    };
    if (!body.dbType) { toast('请选择数据库类型', 'bad'); return; }
    if (!body.paramName) { toast('请填写参数名', 'bad'); return; }
    if (op === 'BETWEEN' && (isNaN(body.expectedValueMin) || isNaN(body.expectedValueMax))) {
        toast('BETWEEN 需填写完整的区间上下界', 'bad'); return;
    }

    try {
        if (id) {
            await api(`/api/inspection/baselines/${id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('基线已更新', 'ok');
        } else {
            await api('/api/inspection/baselines', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            toast('基线已创建', 'ok');
        }
        closeModal('baseModal');
        state.inspection.baseType = body.dbType;
        $('baseTypeSel').value = body.dbType;
        await loadBaselines();
        await refreshSummary();
    } catch (e) {
        toast('保存失败：' + e.message, 'bad');
    }
}

async function toggleBase(id, enabled) {
    const b = state.inspection.baselines.find(x => x.id === id);
    if (!b) return;
    try {
        await api(`/api/inspection/baselines/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: enabled ? 1 : 0 })
        });
        await loadBaselines();
    } catch (e) {
        toast('操作失败：' + e.message, 'bad');
    }
}

async function bulkBase(enabled) {
    const dbType = state.inspection.baseType;
    if (!dbType) return;
    if (!confirm(`确定将 ${typeMeta(dbType).label || dbType} 的全部基线${enabled ? '启用' : '停用'}？`)) return;
    try {
        const r = await api(
            `/api/inspection/baselines/enabled?dbType=${encodeURIComponent(dbType)}&enabled=${enabled}`,
            { method: 'POST' });
        toast(`已${enabled ? '启用' : '停用'} ${r.affected} 条基线`, 'ok');
        await loadBaselines();
    } catch (e) {
        toast('批量操作失败：' + e.message, 'bad');
    }
}

async function deleteBase(id) {
    const b = state.inspection.baselines.find(x => x.id === id);
    if (!confirm(`确定删除基线「${b ? b.paramName : id}」？`)) return;
    try {
        await api(`/api/inspection/baselines/${id}`, { method: 'DELETE' });
        toast('基线已删除', 'ok');
        await loadBaselines();
        await refreshSummary();
    } catch (e) {
        toast('删除失败：' + e.message, 'bad');
    }
}

async function refreshSummary() {
    try {
        state.inspection.summary = await api('/api/inspection/summary');
        renderInspStats();
    } catch (e) { /* 忽略 */ }
}

/* ---------------- 基线校验 ---------------- */

/** 依据比较符构造一个示例实测值；每 5 条制造一个不达标项，便于观察判定效果 */
function demoValue(b, i) {
    const op = (b.operator || '=').toUpperCase();
    const exp = b.expectedValue === null || b.expectedValue === undefined ? '' : String(b.expectedValue);
    const violate = i % 5 === 0;

    if (op === 'BETWEEN') {
        const min = b.expectedValueMin === null || b.expectedValueMin === undefined ? 0 : Number(b.expectedValueMin);
        const max = b.expectedValueMax === null || b.expectedValueMax === undefined ? 100 : Number(b.expectedValueMax);
        return violate ? String(max * 2) : String(min);
    }
    if (op === 'LIKE') {
        return violate ? 'ZZZ_NO_MATCH' : exp.replace(/%/g, 'X').replace(/_/g, 'X');
    }
    if (op === '!=') {
        return violate ? exp : 'OTHER';
    }
    const num = parseFloat(exp);
    if (!isNaN(num) && /^-?\d+(\.\d+)?$/.test(exp.trim())) {
        if (!violate) return exp;
        if (op === '<=' || op === '>=') return String(op === '<=' ? num * 2 : num / 2);
        if (op === '>') return String(num / 2);
        if (op === '<') return String(num * 2);
        return String(num + 1);
    }
    // 非数值字面量
    if (!violate) return exp;
    return op === '=' ? 'MISMATCH' : exp;
}

function fillDemoValues() {
    const list = state.inspection.baselines || [];
    if (!list.length) { toast('当前类型没有基线', 'bad'); return; }
    state.inspection.values = {};
    list.forEach((b, i) => {
        if (b.enabled) state.inspection.values[b.paramName] = demoValue(b, i);
    });
    renderBaselines();
    toast('已填入示例实测值，点击「运行校验」查看结论', 'ok');
}

async function runCheck() {
    const dbType = state.inspection.baseType;
    if (!dbType) { toast('请先选择数据库类型', 'bad'); return; }

    const wrap = $('checkResultWrap');
    wrap.innerHTML = '<div class="empty">校验中…</div>';

    try {
        const r = await api('/api/inspection/baselines/check', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dbType, values: state.inspection.values })
        });
        renderCheckResult(r);
    } catch (e) {
        wrap.innerHTML = `<div class="empty" style="color:var(--danger)">校验失败：${esc(e.message)}</div>`;
    }
}

function renderCheckResult(r) {
    const pct = r.compliancePct || 0;
    const cls = pct >= 90 ? 'good' : (pct >= 70 ? 'mid' : 'bad');
    const failRisk = Object.entries(r.failByRisk || {})
        .map(([k, v]) => `<span class="risk risk-${esc(k)}">${esc(k)} ${v}</span>`)
        .join(' ') || '<span class="count">无</span>';

    const rows = (r.results || []).map(x => {
        const state_ = !x.checked ? 'unchecked' : (x.pass ? 'pass' : 'fail');
        const badge = state_ === 'pass'
            ? '<span class="badge badge-ok">合规</span>'
            : state_ === 'fail'
                ? '<span class="badge badge-bad">不合规</span>'
                : '<span class="badge badge-soft">未采集</span>';
        const actual = x.actualValue === null || x.actualValue === undefined || x.actualValue === ''
            ? '<span class="null-val">—</span>'
            : esc(x.actualValue);
        return `<tr>
            <td><strong class="mono">${esc(x.paramName)}</strong></td>
            <td class="shrink"><span class="op-chip">${esc(x.operator)}</span></td>
            <td class="shrink"><span class="expected">${esc(x.expectedValue ?? '—')}</span></td>
            <td class="shrink"><span class="expected">${actual}</span></td>
            <td class="shrink"><span class="risk risk-${esc(x.riskLevel || 'MEDIUM')}">${esc(x.riskLevel || '—')}</span></td>
            <td class="shrink">${badge}</td>
            <td>${esc(x.descriptionZh || '')}<div class="hint">${esc(x.message || '')}</div></td>
        </tr>`;
    }).join('');

    $('checkResultWrap').innerHTML = `
        <div class="check-banner">
            <div>
                <div class="check-score ${cls}">${pct}%</div>
                <div class="stat-label">基线合规率</div>
            </div>
            <div class="check-metrics">
                <div>合规 <b style="color:var(--success)">${r.pass}</b></div>
                <div>不合规 <b style="color:var(--danger)">${r.fail}</b></div>
                <div>未采集 <b style="color:var(--text-muted)">${r.unchecked}</b></div>
                <div>合计 <b>${r.total}</b></div>
            </div>
            <div style="margin-left:auto">不合规风险分布：${failRisk}</div>
        </div>
        <table class="table">
            <thead><tr>
                <th>参数名</th><th>比较</th><th>期望值</th><th>实测值</th>
                <th>风险</th><th>结论</th><th>说明</th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table>`;
}

/* ---------------- 变更历史 ---------------- */

async function loadHistory() {
    const wrap = $('histWrap');
    wrap.innerHTML = '<div class="empty">加载中…</div>';
    try {
        state.inspection.history = await api('/api/inspection/history?limit=200') || [];
    } catch (e) {
        wrap.innerHTML = `<div class="empty" style="color:var(--danger)">加载失败：${esc(e.message)}</div>`;
        return;
    }
    renderHistory();
}

const HIST_TABLE_LABEL = {
    inspection_template: '模板',
    inspection_chapter: '章节',
    inspection_rule: '规则',
    inspection_chapter_rule: '章节引用',
    inspection_baseline: '基线'
};
const HIST_ACTION_LABEL = { INSERT: '新增', UPDATE: '修改', DELETE: '删除' };

function renderHistory() {
    const list = state.inspection.history || [];
    $('histCount').textContent = list.length + ' 条';

    if (!list.length) {
        $('histWrap').innerHTML = '<div class="empty" style="padding:24px">暂无变更记录</div>';
        return;
    }

    const rows = list.map(h => {
        const actCls = h.action === 'INSERT' ? 'badge-ok' : (h.action === 'DELETE' ? 'badge-bad' : 'badge-active');
        return `<tr>
            <td class="mono shrink">${esc(h.modifiedAt || '')}</td>
            <td class="shrink">${esc(HIST_TABLE_LABEL[h.tableName] || h.tableName)}</td>
            <td class="shrink mono">#${esc(h.recordId)}</td>
            <td class="shrink"><span class="badge ${actCls}">${esc(HIST_ACTION_LABEL[h.action] || h.action)}</span></td>
            <td><span class="hist-json" title="${esc(h.oldValue || '')}">${esc(h.oldValue || '—')}</span></td>
            <td><span class="hist-json" title="${esc(h.newValue || '')}">${esc(h.newValue || '—')}</span></td>
        </tr>`;
    }).join('');

    $('histWrap').innerHTML = `<table class="table">
        <thead><tr>
            <th>时间</th><th>对象</th><th>记录</th><th>操作</th><th>变更前</th><th>变更后</th>
        </tr></thead>
        <tbody>${rows}</tbody>
    </table>`;
}

/* ============================================================
   巡检执行
   ============================================================ */

const RUN_STATUS_LABEL = {
    SUCCESS: '成功',
    PARTIAL: '部分成功',
    FAILED: '失败'
};

const RUNQ_STATUS_LABEL = {
    OK: '成功',
    FAILED: '失败',
    SKIPPED: '已跳过'
};

function bindRunEvents() {
    $('runRefresh').addEventListener('click', () => loadRunExec(true));
    $('btnRunInspection').addEventListener('click', startRun);

    // 数据源变了，模板下拉跟着换成该库类型的模板
    $('runDsSel').addEventListener('change', () => {
        fillRunTplSelect();
        renderRunHint();
    });
}

async function ensureRunExec() {
    if (!state.datasources.length) {
        await loadDatasources();
    }
    if (state.run.loaded) {
        fillRunDsSelect();
        return;
    }
    await loadRunExec(false);
}

/* ---------------- 发起表单 ---------------- */

function fillRunDsSelect() {
    const sel = $('runDsSel');
    const keep = sel.value;
    const list = state.datasources || [];
    sel.innerHTML = list.length
        ? list.map(d => `<option value="${d.id}">${esc(d.name)} · ${esc(typeMeta(d.dbType).label || d.dbType)}</option>`).join('')
        : '<option value="">（暂无数据源，请先在「数据源纳管」中创建）</option>';
    if (keep && list.some(d => String(d.id) === String(keep))) sel.value = keep;
    fillRunTplSelect();
    renderRunHint();
}

/** 模板下拉：先按选中数据源的库类型过滤，再补上其它类型（便于跨类型试跑） */
function fillRunTplSelect() {
    const sel = $('runTplSel');
    const ds = (state.datasources || []).find(d => String(d.id) === $('runDsSel').value);
    const tpls = state.inspection.templates || [];
    const mine = ds ? tpls.filter(t => t.dbType === ds.dbType) : [];
    const other = ds ? tpls.filter(t => t.dbType !== ds.dbType) : tpls;

    const opt = (t) => `<option value="${t.id}"${t.isDefault ? ' selected' : ''}>` +
        `${esc(t.templateNameZh)} · ${esc(typeMeta(t.dbType).label || t.dbType)}</option>`;

    sel.innerHTML =
        (mine.length ? `<optgroup label="匹配当前数据源">${mine.map(opt).join('')}</optgroup>` : '') +
        (other.length ? `<optgroup label="其它库类型">${other.map(opt).join('')}</optgroup>` : '') ||
        '<option value="">（暂无巡检模板）</option>';

    // 默认模板优先
    const def = mine.find(t => t.isDefault) || mine[0];
    if (def) sel.value = def.id;
}

function renderRunHint() {
    const el = $('runHint');
    const ds = (state.datasources || []).find(d => String(d.id) === $('runDsSel').value);
    if (!ds) {
        el.className = 'run-hint bad';
        el.textContent = '还没有可用的数据源。请先到「数据源纳管」创建一个（内置 H2 自检库无需上传驱动，可用来验证整条链路）。';
        return;
    }
    const t = typeMeta(ds.dbType);
    el.className = 'run-hint';
    el.textContent = `目标：${ds.name}（${t.label || ds.dbType}）`
        + (t.driverBundled ? ' · 驱动随应用内置，无需上传 JAR' : ' · 需要已上传对应 JDBC 驱动')
        + '。巡检会真实连接该数据源执行只读 SQL。';
}

/* ---------------- 巡检执行：发起 + 本次结果 ----------------
   执行页只负责「发起」与「看本次跑出来的东西」；留痕与导出在「巡检历史」。
   这样职责清楚：执行页是动作，历史页是产物。 */

async function loadRunExec(force) {
    const run = state.run;
    try {
        const [templates, list] = await Promise.all([
            state.inspection.templates.length ? Promise.resolve(state.inspection.templates)
                : api('/api/inspection/templates'),
            api('/api/inspection/runs?limit=100')
        ]);
        state.inspection.templates = templates;
        run.list = list || [];
        run.loaded = true;
    } catch (e) {
        run.list = [];
        run.loaded = true;
        toast('加载巡检记录失败：' + e.message, 'bad');
    }

    fillRunDsSelect();

    // 展示哪一条：本次刚跑的 → 之前看过的 → 最新一条
    const target = (run.lastRunId && run.list.some(r => r.id === run.lastRunId)) ? run.lastRunId
        : (run.list.length ? run.list[0].id : null);
    if (target) {
        await showRunDetail(target, 'runDetail');
    } else {
        run.lastRunId = null;
        $('runDetail').innerHTML =
            '<div class="card"><div class="empty">还没有执行记录。选好数据源与模板后点「开始巡检」。</div></div>';
    }
    // 历史视图若已打开过，同步刷新，避免两个视图数据不一致
    if (run.histLoaded) renderRunHistoryList();
}

/** 拉一条执行记录的完整明细并渲染到指定容器 */
async function showRunDetail(id, containerId) {
    const el = $(containerId);
    el.innerHTML = '<div class="card"><div class="empty">加载中…</div></div>';
    let detail;
    try {
        detail = await api('/api/inspection/runs/' + id);
    } catch (e) {
        el.innerHTML = `<div class="card"><div class="empty">加载失败：${esc(e.message)}</div></div>`;
        return null;
    }
    if (containerId === 'runDetail') {
        state.run.detail = detail;
        state.run.activeId = id;
        state.run.lastRunId = id;
    } else {
        state.run.histDetail = detail;
        state.run.histActiveId = id;
    }
    renderRunDetail(detail, containerId);
    return detail;
}

/* ---------------- 巡检历史：列表 + 在线预览 + 导出 ----------------
   在线预览直接把导出的 HTML 报告塞进 iframe —— 看到的就是下载到的那份文件，
   不存在「预览一套、导出另一套」的偏差。 */

function bindRunHistoryEvents() {
    $('runsDsFilter').addEventListener('change', () => {
        state.run.filterDs = $('runsDsFilter').value;
        renderRunHistoryList();
    });
    $('runsTypeFilter').addEventListener('change', () => {
        state.run.filterType = $('runsTypeFilter').value;
        renderRunHistoryList();
    });

    document.querySelectorAll('#runsPreviewMode .seg-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            state.run.previewMode = btn.dataset.pmode;
            document.querySelectorAll('#runsPreviewMode .seg-btn')
                .forEach(b => b.classList.toggle('active', b === btn));
            renderRunsPreview();
        });
    });

    $('dlWord').addEventListener('click', () => downloadReport('word'));
    $('dlPdf').addEventListener('click', () => downloadReport('pdf'));
    $('dlHtml').addEventListener('click', () => downloadReport('html'));
}

async function ensureRunsHistory() {
    if (!state.datasources.length) await loadDatasources();
    if (!state.inspection.templates.length) {
        try { state.inspection.templates = await api('/api/inspection/templates') || []; } catch (e) { /* 容错 */ }
    }
    if (state.run.histLoaded) {
        renderRunHistoryList();
        return;
    }
    await loadRunHistory();
}

async function loadRunHistory() {
    const run = state.run;
    try {
        run.list = await api('/api/inspection/runs?limit=200') || [];
        run.histLoaded = true;
    } catch (e) {
        run.list = [];
        run.histLoaded = true;
        toast('加载巡检历史失败：' + e.message, 'bad');
    }
    fillRunsFilters();
    renderRunHistoryList();

    const visible = filteredRuns();
    const target = (run.histActiveId && visible.some(r => r.id === run.histActiveId)) ? run.histActiveId
        : (visible.length ? visible[0].id : null);
    if (target) {
        await selectHistoryRun(target);
    } else {
        run.histActiveId = null;
        run.histDetail = null;
        renderRunsPreview();
    }
}

/** 按筛选条件过滤后的记录（两个下拉都为空则返回全部） */
function filteredRuns() {
    const run = state.run;
    return (run.list || []).filter(r =>
        (!run.filterDs || String(r.dataSourceId) === String(run.filterDs)) &&
        (!run.filterType || r.dbType === run.filterType));
}

function fillRunsFilters() {
    const dsSel = $('runsDsFilter');
    const typeSel = $('runsTypeFilter');

    // 数据源下拉只列出「确实有执行记录」的那些，避免选到永远为空的条件
    const dsIds = new Set((state.run.list || []).map(r => String(r.dataSourceId)));
    const dsOpts = (state.datasources || []).filter(d => dsIds.has(String(d.id)));
    dsSel.innerHTML = '<option value="">全部数据源</option>' +
        dsOpts.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
    if (state.run.filterDs && !dsOpts.some(d => String(d.id) === state.run.filterDs)) {
        state.run.filterDs = '';
    }
    dsSel.value = state.run.filterDs;

    const types = [...new Set((state.run.list || []).map(r => r.dbType).filter(Boolean))];
    typeSel.innerHTML = '<option value="">全部库类型</option>' +
        types.map(t => `<option value="${esc(t)}">${esc(typeMeta(t).label || t)}</option>`).join('');
    if (state.run.filterType && !types.includes(state.run.filterType)) {
        state.run.filterType = '';
    }
    typeSel.value = state.run.filterType;
}

function renderRunHistoryList() {
    const list = filteredRuns();
    const total = (state.run.list || []).length;
    $('runsCount').textContent = list.length === total
        ? `${total} 条` : `${list.length} / ${total} 条`;

    const wrap = $('runsList');
    if (!list.length) {
        wrap.innerHTML = `<div class="empty" style="padding:22px">
            ${total ? '当前筛选条件下没有记录' : '暂无执行记录，先到「巡检执行」发起一次'}</div>`;
        return;
    }

    wrap.innerHTML = list.map(r => {
        const scored = (r.baselinesPass + r.baselinesFail) > 0;
        const pct = scored ? r.compliancePct + '%' : '—';
        return `<div class="run-item${r.id === state.run.histActiveId ? ' active' : ''}" data-run="${r.id}">
            <div class="ri-top">
                <span class="st st-${esc(r.status)}">${esc(RUN_STATUS_LABEL[r.status] || r.status)}</span>
                <span class="ri-title">${esc(r.templateName || '—')}</span>
            </div>
            <div class="ri-meta">
                <span>${esc(r.dataSourceName || '—')}</span>
                <span class="badge badge-soft">${esc(r.dbType || '')}</span>
                <span>合规 ${esc(pct)}</span>
                <span>${esc(shortTime(r.startedAt))}</span>
            </div>
        </div>`;
    }).join('');

    wrap.querySelectorAll('[data-run]').forEach(el => {
        el.addEventListener('click', () => selectHistoryRun(parseInt(el.dataset.run, 10)));
    });
}

async function selectHistoryRun(id) {
    state.run.histActiveId = id;
    renderRunHistoryList();
    await showRunDetail(id, 'runsDetailWrap');
    renderRunsPreview();
}

/** 报告导出地址（与后端 InspectionController#exportRun 的契约一致） */
function reportUrl(id, format, download) {
    return `/api/inspection/runs/${id}/export?format=${encodeURIComponent(format)}`
        + (download ? '&download=1' : '');
}

function renderRunsPreview() {
    const run = state.run;
    const id = run.histActiveId;
    const frame = $('runsPreviewFrame');
    const detailWrap = $('runsDetailWrap');
    const empty = $('runsPreviewEmpty');
    const openTab = $('runsOpenTab');

    const btns = { word: $('dlWord'), pdf: $('dlPdf'), html: $('dlHtml') };
    Object.values(btns).forEach(b => { b.disabled = !id; });

    if (!id) {
        frame.hidden = true;
        detailWrap.hidden = true;
        empty.hidden = false;
        openTab.hidden = true;
        $('runsPreviewTitle').textContent = '报告预览';
        frame.removeAttribute('src');
        return;
    }

    const r = (run.list || []).find(x => x.id === id);
    $('runsPreviewTitle').innerHTML =
        `报告预览 · <span class="badge badge-soft">#${id}</span> ${esc(r ? r.dataSourceName || '' : '')}`;

    const url = reportUrl(id, 'html', false);
    openTab.href = url;
    openTab.hidden = false;
    empty.hidden = true;

    if (run.previewMode === 'detail') {
        frame.hidden = true;
        frame.removeAttribute('src');   // 切走时清掉，避免后台继续加载
        detailWrap.hidden = false;
    } else {
        detailWrap.hidden = true;
        frame.hidden = false;
        // 只有 id 变化才重设 src，避免切换明细/原文时反复重载
        if (frame.getAttribute('src') !== url) frame.setAttribute('src', url);
    }
}

/**
 * 下载报告。
 *
 * 这里用 fetch + blob 而不是直接 window.open：导出接口出错时返回的是 JSON
 * （例如 PDF 找不到可嵌入的中文字体），直接开新窗口会把这些错误当文件存下来，
 * 用户拿到一个 0 字节的 .pdf 却不知道为什么。先读响应再决定是保存还是报错。
 */
async function downloadReport(format) {
    const id = state.run.histActiveId;
    if (!id) return;

    const btn = { word: $('dlWord'), pdf: $('dlPdf'), html: $('dlHtml') }[format];
    const label = { word: 'Word', pdf: 'PDF', html: 'HTML' }[format];
    const old = btn.textContent;
    btn.disabled = true;
    btn.textContent = '导出中…';

    try {
        const resp = await fetch(reportUrl(id, format, true));
        if (!resp.ok) {
            let msg = `HTTP ${resp.status}`;
            try {
                const j = await resp.json();
                if (j && j.message) msg = j.message;
            } catch (e) { /* 非 JSON 就保留状态码 */ }
            throw new Error(msg);
        }

        const blob = await resp.blob();
        const name = fileNameFromResponse(resp) || `巡检报告_${id}.${extOf(format)}`;

        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(a.href), 4000);

        toast(`${label} 报告已导出（${fmtSize(blob.size)}）`, 'ok');
    } catch (e) {
        toast(`${label} 导出失败：${e.message}`, 'bad');
    } finally {
        btn.disabled = false;
        btn.textContent = old;
    }
}

function extOf(format) {
    return format === 'word' ? 'docx' : format;
}

/** 从 Content-Disposition 里取服务端给的文件名（优先 RFC 5987 的 filename*） */
function fileNameFromResponse(resp) {
    const cd = resp.headers.get('Content-Disposition') || '';
    const star = /filename\*=UTF-8''([^;]+)/i.exec(cd);
    if (star) {
        try { return decodeURIComponent(star[1]); } catch (e) { /* 落到下面 */ }
    }
    const plain = /filename="?([^";]+)"?/i.exec(cd);
    return plain ? plain[1] : '';
}

/* ---------------- 报告渲染（执行页与历史页共用） ---------------- */

function renderRunDetail(r, containerId) {
    const el = $(containerId || 'runDetail');
    if (!r) return;

    const scored = (r.baselinesPass + r.baselinesFail) > 0;
    const pct = r.compliancePct || 0;
    const cls = !scored ? '' : (pct >= 90 ? 'good' : (pct >= 70 ? 'mid' : 'bad'));

    const riskChips = Object.entries(r.riskSummary || {}).map(([k, v]) =>
        `<span class="risk risk-${esc(k)}">${esc(k)} × ${esc(v)}</span>`).join(' ') || '<span class="ri-meta">无</span>';

    const skipped = (r.queries || []).filter(q => q.status === 'SKIPPED').length;

    let html = `<div class="card" style="margin-bottom:16px">
        <div class="report-head">
            <div>
                <h3>
                    <span class="st st-${esc(r.status)}">${esc(RUN_STATUS_LABEL[r.status] || r.status)}</span>
                    ${esc(r.dataSourceName || '—')}
                    <span class="badge badge-soft">${esc(r.dbType || '')}</span>
                </h3>
                <div class="rsub">
                    模板：${esc(r.templateName || '—')} ·
                    执行人：${esc(r.executedBy || '—')} ·
                    ${esc(shortTime(r.startedAt))} 起 ·
                    耗时 ${esc(r.durationMs == null ? '—' : r.durationMs + ' ms')}
                </div>
            </div>
            <div class="rmetrics">
                <div class="rmetric"><b class="${cls}">${scored ? pct + '%' : '—'}</b>合规率</div>
                <div class="rmetric"><b>${esc(r.okQueries)}/${esc(r.totalQueries)}</b>规则执行成功</div>
                <div class="rmetric"><b>${esc(r.baselinesPass)}/${esc(r.baselinesPass + r.baselinesFail)}</b>基线合规</div>
                <div class="rmetric"><b>${esc(r.baselinesUnchecked)}</b>未采集</div>
            </div>
        </div>`;

    if (r.errorMsg) {
        html += `<div class="run-error">${esc(r.errorMsg)}</div>`;
    }
    if (r.failedQueries > 0) {
        html += `<div class="run-notice">有 ${esc(r.failedQueries)} 条规则执行失败，已按「部分成功」记录，失败原因见对应规则。</div>`;
    }
    html += `<div class="base-toolbar">
        <span class="count">风险归集</span>${riskChips}
        <div class="spacer"></div>
        <span class="count">共 ${(r.queries || []).length} 条规则${skipped ? `（跳过 ${skipped} 条）` : ''} / ${(r.baselines || []).length} 条基线</span>
    </div></div>`;

    html += renderRunChapters(r);
    html += renderRunBaselines(r);
    el.innerHTML = html;

    // 章节折叠：绑定到当前容器，而不是写死 #runDetail——
    // 这份渲染结果在「巡检执行」和「巡检历史」两处复用，写死 id 会让其中一处点不动。
    el.querySelectorAll('.chap-head').forEach(head => {
        head.addEventListener('click', () => {
            const num = head.dataset.chap;
            state.run.openChapters[num] = !state.run.openChapters[num];
            head.closest('.chap').classList.toggle('open', state.run.openChapters[num]);
        });
    });
}

function renderRunChapters(r) {
    const queries = r.queries || [];
    if (!queries.length) {
        return `<div class="card"><div class="empty">本次执行没有产生规则结果</div></div>`;
    }

    // 按章节号聚合，保持后端返回的顺序
    const groups = new Map();
    queries.forEach(q => {
        const k = q.chapterNumber;
        if (!groups.has(k)) groups.set(k, { number: k, title: q.chapterTitle, items: [] });
        groups.get(k).items.push(q);
    });

    const chapters = [...groups.values()].map(g => {
        const okN = g.items.filter(q => q.status === 'OK').length;
        const failN = g.items.filter(q => q.status === 'FAILED').length;
        const skipN = g.items.filter(q => q.status === 'SKIPPED').length;
        const open = !!state.run.openChapters[g.number];
        const tally = [
            okN ? `<span class="badge badge-ok">成功 ${okN}</span>` : '',
            failN ? `<span class="badge badge-bad">失败 ${failN}</span>` : '',
            skipN ? `<span class="badge badge-soft">跳过 ${skipN}</span>` : ''
        ].filter(Boolean).join(' ');

        return `<div class="chap${open ? ' open' : ''}">
            <div class="chap-head" data-chap="${esc(g.number)}">
                <span class="chap-caret">▶</span>
                <span class="chap-num">${esc(g.number)}</span>
                <span class="chap-title">${esc(g.title || '未命名章节')}</span>
                <span class="chap-acts">${tally}</span>
            </div>
            <div class="chap-body">${g.items.map(renderRunQuery).join('')}</div>
        </div>`;
    }).join('');

    return `<div class="card" style="margin-bottom:16px">
        <div class="card-head">规则执行结果<span class="count">点击章节展开明细</span></div>
        ${chapters}
    </div>`;
}

function renderRunQuery(q) {
    const stCls = q.status === 'OK' ? 'OK' : (q.status === 'FAILED' ? 'FAILED' : 'SKIPPED');
    const stats = q.status === 'SKIPPED'
        ? `<span class="rq-stat">未执行</span>`
        : `<span class="rq-stat">${esc(q.rowCount)} 行 · ${esc(q.elapsedMs)} ms</span>`;

    let preview = '';
    if (q.rows && q.rows.length) {
        const cols = (q.columns || []).map(c => `<th>${esc(c)}</th>`).join('');
        const rows = q.rows.map(row => '<tr>' + row.map(cell =>
            cell === null || cell === undefined
                ? '<td class="null">NULL</td>'
                : `<td title="${esc(cell)}">${esc(cell)}</td>`).join('') + '</tr>').join('');
        const more = q.truncated
            ? `<div class="rq-more">结果已截断，仅显示前 ${q.rows.length} 行</div>` : '';
        preview = `<div class="rq-preview"><table class="mini-table">
            <thead><tr>${cols}</tr></thead><tbody>${rows}</tbody></table>${more}</div>`;
    } else if (q.status === 'OK' && q.rowCount === 0) {
        preview = `<div class="rq-preview"><div class="rq-more">查询成功，返回 0 行（空库或该维度无数据，属正常）</div></div>`;
    }

    const err = q.errorMsg
        ? `<div class="rq-err">${esc(q.errorMsg)}</div>` : '';

    return `<div class="q-row">
        <div class="q-row-top rq-meta">
            <span class="q-key">${esc(q.queryKey)}</span>
            <span class="st st-${esc(stCls)}">${esc(RUNQ_STATUS_LABEL[q.status] || q.status)}</span>
            ${stats}
        </div>
        ${q.descriptionZh ? `<div class="q-desc">${esc(q.descriptionZh)}</div>` : ''}
        <div class="q-sql">${esc(q.querySql || '')}</div>
        ${err}
        ${preview}
    </div>`;
}

function renderRunBaselines(r) {
    const list = r.baselines || [];
    if (!list.length) {
        return `<div class="card"><div class="empty">本次执行未采集基线（未勾选「含基线校验」，或连接失败）</div></div>`;
    }

    const rows = list.map(b => {
        const verdict = b.isChecked ? (b.isPass ? '合规' : '不合规') : '未采集';
        const cls = b.isChecked ? (b.isPass ? 'st-OK' : 'st-FAILED') : 'st-NONE';
        return `<tr class="${b.isChecked && !b.isPass ? 'rb-row-fail' : ''}">
            <td class="mono">${esc(b.paramName)}</td>
            <td class="shrink"><span class="op-chip">${esc(b.operator || '')}</span></td>
            <td class="shrink"><span class="expected">${esc(b.expectedValue || '')}</span></td>
            <td class="shrink"><span class="rb-actual">${b.actualValue === null || b.actualValue === undefined ? '—' : esc(b.actualValue)}</span></td>
            <td class="shrink"><span class="st ${cls}">${verdict}</span></td>
            <td class="shrink"><span class="risk risk-${esc(b.riskLevel || 'LOW')}">${esc(b.riskLevel || '')}</span></td>
            <td><span class="rb-msg" title="${esc(b.message || '')}">${esc(b.message || '')}</span></td>
        </tr>`;
    }).join('');

    return `<div class="card">
        <div class="card-head">基线判定<span class="count">多行结果逐行判定，任一行不合规即整条不合规</span></div>
        <table class="table">
            <thead><tr>
                <th>参数</th><th>比较符</th><th>期望值</th><th>实测值</th><th>结论</th><th>风险</th><th>说明</th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table>
    </div>`;
}

/* ---------------- 发起执行 ---------------- */

async function startRun() {
    const run = state.run;
    if (run.running) return;

    const dsId = $('runDsSel').value;
    if (!dsId) {
        toast('请先选择数据源', 'bad');
        return;
    }
    const tplId = $('runTplSel').value;

    const body = {
        dataSourceId: parseInt(dsId, 10),
        onlyEnabled: $('runOnlyEnabled').checked,
        includeBaselines: $('runBaselines').checked,
        executedBy: 'console'
    };
    if (tplId) body.templateId = parseInt(tplId, 10);

    const btn = $('btnRunInspection');
    run.running = true;
    btn.disabled = true;
    btn.textContent = '巡检中…';

    try {
        const result = await api('/api/inspection/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        // 直接展示本次结果，再刷新列表
        run.detail = result;
        run.activeId = result.id;
        run.lastRunId = result.id;
        run.openChapters = {};
        // 默认展开第一个有内容的章节，省一次点击
        const first = (result.queries || []).find(q => q.status !== 'SKIPPED');
        if (first) run.openChapters[first.chapterNumber] = true;

        renderRunDetail(result, 'runDetail');

        // 历史视图若已加载过，把新记录并进去并选中它，免得用户再手动找
        if (run.histLoaded) {
            run.list = await api('/api/inspection/runs?limit=200') || run.list;
            run.histActiveId = result.id;
            fillRunsFilters();
            renderRunHistoryList();
            await showRunDetail(result.id, 'runsDetailWrap');
            renderRunsPreview();
        }

        const kind = result.status === 'SUCCESS' ? 'ok'
            : (result.status === 'PARTIAL' ? 'warn' : 'bad');
        toast(`巡检完成：${RUN_STATUS_LABEL[result.status] || result.status}`
            + `（规则 ${result.okQueries}/${result.totalQueries}`
            + `，基线合规 ${result.baselinesPass}/${result.baselinesPass + result.baselinesFail}`
            + `，合规率 ${result.compliancePct}%）· 可到「巡检历史」导出报告`, kind);
    } catch (e) {
        toast('巡检发起失败：' + e.message, 'bad');
    } finally {
        run.running = false;
        btn.disabled = false;
        btn.textContent = '▶ 开始巡检';
    }
}

/* ---------------- start ---------------- */
boot();
