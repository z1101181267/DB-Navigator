/* ============================================================
   DOM 冒烟测试 —— 在 jsdom 里真实执行前端 app.js
   校验：boot 流程、侧栏三分组（大屏展示 / 数据库运维 / 配置与管理）、
        视图切换、驱动管理两栏布局、
        概览（KPI / 条形图 / 风险分布 / 最近执行 / 快捷入口）、
        数据库管理（实例列表 / 信息卡 / 边界标注）、数据库统计（各分布图）、
        定时巡检（空态 + 触发方式取自真实记录）、
        插件市场与 AI配置（骨架页的禁用态与边界声明）、
        巡检配置（页签 / 模板树 / 章节引用规则）、
        基线配置管理（独立菜单）、规则引擎（规则库 / 试跑入口）、
        巡检执行（发起 → 报告渲染）、
        巡检历史（列表 / 筛选 / 在线预览 / 三格式下载入口）
   用法：
     NODE_PATH=<node workspace>/node_modules node scripts/dom_smoke.js
   前置：后端已在 127.0.0.1:8080 运行（建议用 Java 后端，巡检能真实执行）
   ============================================================ */

const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const BASE = process.env.DBNAV_BASE || 'http://127.0.0.1:8080';
const ROOT = path.resolve(__dirname, '..');
const HTML = fs.readFileSync(path.join(ROOT, 'src/main/resources/static/index.html'), 'utf8');
const APPJS = fs.readFileSync(path.join(ROOT, 'src/main/resources/static/js/app.js'), 'utf8');

const pass = [], fail = [];
const consoleErrors = [];

function check(name, cond, extra) {
    (cond ? pass : fail).push(name);
    console.log((cond ? '  \u2714 ' : '  \u2718 ') + name + (cond ? '' : '   ' + (extra === undefined ? '' : String(extra).slice(0, 300))));
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* 等待条件成立，最多 3s */
async function until(fn, label) {
    for (let i = 0; i < 60; i++) {
        try { if (fn()) return true; } catch (e) { /* 继续等 */ }
        await sleep(50);
    }
    return false;
}

/* 模板列表按库类型分组渲染，分组标题后面紧跟该组的模板项 */
function tplItemOf(doc, label) {
    const labels = [...doc.getElementById('tplList').querySelectorAll('.tpl-group-label')];
    const hit = labels.find(l => l.textContent.includes(label));
    return hit ? hit.nextElementSibling : null;
}

(async () => {
    const vc = new VirtualConsole();
    vc.on('jsdomError', (e) => consoleErrors.push('jsdomError: ' + (e && e.message)));
    vc.on('error', (...a) => consoleErrors.push('console.error: ' + a.join(' ')));

    const dom = new JSDOM(HTML, {
        url: BASE + '/',
        runScripts: 'outside-only',
        pretendToBeVisual: true,
        virtualConsole: vc
    });
    const { window } = dom;

    // 把 fetch 桥接到真实 devserver（相对路径 → 绝对）
    window.fetch = (input, init) => {
        const url = String(input).startsWith('http') ? String(input) : BASE + String(input);
        return fetch(url, init);
    };
    window.confirm = () => true;          // 自动确认删除类操作
    window.alert = () => {};

    const doc = window.document;
    const $ = (id) => doc.getElementById(id);

    // 在 jsdom 全局里执行 app.js
    window.eval(APPJS);

    console.log('\n\u2500\u2500 boot \u2500\u2500');
    const booted = await until(() => $('statusText').textContent === '\u5728\u7ebf', 'boot');
    check('boot 完成并识别到后端（在线）', booted, $('statusText').textContent);
    // setMode() 早于 bindEvents()，必须等 boot 整体收尾后事件才可用
    await until(() => $('dbChips').children.length > 0 && $('dsTable').querySelector('tbody').innerHTML.length > 0, 'boot-finish');
    await sleep(300);
    check('数据库类型下拉已填充', $('dsType').options.length >= 5, $('dsType').options.length);
    check('侧边栏数据库 chips 已渲染', $('dbChips').children.length >= 5, $('dbChips').children.length);
    check('数据源表格已渲染', $('dsTable').querySelector('tbody').innerHTML.length >= 0);

    console.log('\n\u2500\u2500 侧栏三分组 \u2500\u2500');
    const groups = [...doc.querySelectorAll('.nav-group')];
    check('侧栏分为 3 个分组', groups.length === 3, groups.length);
    check('分组名称依次为 大屏展示 / 数据库运维 / 配置与管理',
        groups.map(g => g.querySelector('.nav-group-name').textContent.trim()).join('|')
        === '大屏展示|数据库运维|配置与管理',
        groups.map(g => g.querySelector('.nav-group-name').textContent.trim()).join('|'));

    const groupItems = (name) => {
        const g = groups.find(x => x.querySelector('.nav-group-name').textContent.trim() === name);
        return g ? [...g.querySelectorAll('.nav-item')].map(i => i.textContent.trim()) : [];
    };
    // textContent 会把图标 span 的字符一起带出来（📊概览），所以用包含判断
    check('大屏展示下只有「概览」',
        groupItems('大屏展示').length === 1 && groupItems('大屏展示')[0].includes('概览'),
        groupItems('大屏展示').join('|'));
    check('数据库运维下 6 项（含保留的巡检执行与 SQL 编辑器）',
        groupItems('数据库运维').length === 6, groupItems('数据库运维').join('|'));
    check('配置与管理下 7 项（含插件市场与 AI配置）',
        groupItems('配置与管理').length === 7, groupItems('配置与管理').join('|'));
    check('全站共 14 个导航入口', doc.querySelectorAll('.nav-item').length === 14,
        doc.querySelectorAll('.nav-item').length);
    check('每个导航入口都有对应的视图区块',
        [...doc.querySelectorAll('.nav-item')].every(b => !!$('view-' + b.dataset.view)),
        [...doc.querySelectorAll('.nav-item')].filter(b => !$('view-' + b.dataset.view))
            .map(b => b.dataset.view).join(','));

    // 收起 / 展开。这里只断言 class 变化：jsdom 不加载外部 CSS，
    // 算不出 display，样式层面的验证交给 ui_guard.js（真浏览器）
    const opsGroup = groups.find(x => x.dataset.group === 'ops');
    const opsHead = opsGroup.querySelector('.nav-group-head');
    const clickHead = () => opsHead.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    clickHead();
    check('点分组标题可收起', opsGroup.classList.contains('collapsed'));
    clickHead();
    check('再点一次可展开', !opsGroup.classList.contains('collapsed'));

    // 收起状态下切到该分组里的视图：必须自动展开，
    // 否则高亮项藏在收起的分组里，点了菜单看起来像没反应
    clickHead();
    doc.querySelector('.nav-item[data-view="databases"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('切到收起分组下的视图会自动展开', !opsGroup.classList.contains('collapsed'));
    check('切换后导航高亮唯一且落在目标项上',
        doc.querySelectorAll('.nav-item.active').length === 1
        && doc.querySelector('.nav-item.active').dataset.view === 'databases',
        doc.querySelector('.nav-item.active') && doc.querySelector('.nav-item.active').dataset.view);

    console.log('\n\u2500\u2500 切换到「巡检配置」视图 \u2500\u2500');
    const navInsp = doc.querySelector('.nav-item[data-view="inspection"]');
    check('导航存在「巡检配置」入口', !!navInsp);
    navInsp.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

    const statsOk = await until(() => $('inspStats').querySelector('.stat-num'), 'stats');
    check('统计条已渲染', statsOk);

    if (statsOk) {
        const nums = [...$('inspStats').querySelectorAll('.stat-num')].map(e => e.textContent.trim());
        check('统计条含模板/章节/规则库/基线四个数字', nums.length === 4, JSON.stringify(nums));
        check('模板数 = 6', nums[0] === '6', nums[0]);
        check('章节数 = 113', nums[1] === '113', nums[1]);
        check('规则库 = 160', nums[2] === '160', nums[2]);
        check('基线数 = 88', nums[3] === '88', nums[3]);
    }

    const listOk = await until(() => $('tplList').querySelectorAll('[data-tpl]').length > 0, 'tplList');
    check('模板列表已渲染', listOk, $('tplList').querySelectorAll('[data-tpl]').length);
    check('模板列表共 6 项', $('tplList').querySelectorAll('[data-tpl]').length === 6,
        $('tplList').querySelectorAll('[data-tpl]').length);
    check('模板列表按库类型分组', $('tplList').querySelectorAll('.tpl-group-label').length === 6,
        $('tplList').querySelectorAll('.tpl-group-label').length);

    const detailOk = await until(() => $('tplDetail').querySelectorAll('.chap').length > 0, 'tplDetail');
    check('模板详情已渲染章节', detailOk, $('tplDetail').querySelectorAll('.chap').length);
    check('章节默认折叠（chap-body 未展开）',
        $('tplDetail').querySelectorAll('.chap.open').length === 0);

    console.log('\n\u2500\u2500 展开章节 → 规则引用可见 \u2500\u2500');
    const firstChapHead = $('tplDetail').querySelector('[data-chap-toggle]');
    firstChapHead.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(120);
    check('点击后章节展开', $('tplDetail').querySelectorAll('.chap.open').length === 1,
        $('tplDetail').querySelectorAll('.chap.open').length);
    check('展开后规则行可见', $('tplDetail').querySelectorAll('.q-row').length > 0,
        $('tplDetail').querySelectorAll('.q-row').length);
    check('章节里提供「引用规则」入口（不再内嵌新建规则）',
        $('tplDetail').querySelectorAll('[data-bind-rule]').length > 0,
        $('tplDetail').querySelectorAll('[data-bind-rule]').length);

    console.log('\n\u2500\u2500 切换模板（MySQL 分组 → 21 章） \u2500\u2500');
    const mysqlItem = tplItemOf(doc, 'MySQL');
    check('模板列表含 MySQL 分组', !!mysqlItem);
    if (mysqlItem) {
        mysqlItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        await sleep(400);
        check('切换模板后详情已刷新（21 章）', $('tplDetail').querySelectorAll('.chap').length === 21,
            $('tplDetail').querySelectorAll('.chap').length);
        check('切换模板后 active 唯一', $('tplList').querySelectorAll('.tpl-item.active').length === 1,
            $('tplList').querySelectorAll('.tpl-item.active').length);
    }

    console.log('\n\u2500\u2500 基线配置管理（独立顶级菜单） \u2500\u2500');
    const navBase = doc.querySelector('.nav-item[data-view="baselines"]');
    check('导航存在「基线配置管理」入口', !!navBase);
    navBase.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const baseOk = await until(() => $('baseTableWrap').querySelectorAll('tbody tr').length > 0, 'baselines');
    check('基线表格已渲染', baseOk, $('baseTableWrap').querySelectorAll('tbody tr').length);
    check('基线类型下拉已填充', $('baseTypeSel').options.length === 6, $('baseTypeSel').options.length);
    check('基线行含实测值输入框', $('baseTableWrap').querySelectorAll('[data-val]').length > 0,
        $('baseTableWrap').querySelectorAll('[data-val]').length);
    check('基线配置管理页有统计条', $('baseStats').querySelectorAll('.stat-card').length === 4,
        $('baseStats').querySelectorAll('.stat-card').length);
    check('基线已从巡检配置页签中移除',
        !doc.querySelector('#view-inspection .tab[data-tab="base"]')
        && !doc.getElementById('pane-base'));
    check('巡检配置页只剩两个页签',
        doc.querySelectorAll('#view-inspection .tab').length === 2,
        doc.querySelectorAll('#view-inspection .tab').length);

    console.log('\n\u2500\u2500 规则引擎（独立顶级菜单） \u2500\u2500');
    const navRules = doc.querySelector('.nav-item[data-view="rules"]');
    check('导航存在「规则引擎」入口', !!navRules);
    navRules.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

    const ruleOk = await until(() => $('ruleTableWrap').querySelectorAll('tbody tr').length > 0, 'rules');
    check('规则库表格已渲染', ruleOk, $('ruleTableWrap').querySelectorAll('tbody tr').length);
    check('规则类型下拉已填充', $('ruleTypeSel').options.length === 6, $('ruleTypeSel').options.length);
    check('规则库统计条 3 张卡', $('ruleStats').querySelectorAll('.stat-card').length === 3,
        $('ruleStats').querySelectorAll('.stat-card').length);
    check('规则表列出「被谁引用」',
        $('ruleTableWrap').innerHTML.includes('\u88ab\u8c01\u5f15\u7528'));
    check('规则表有启停操作', $('ruleTableWrap').querySelectorAll('[data-toggle-rule]').length > 0,
        $('ruleTableWrap').querySelectorAll('[data-toggle-rule]').length);
    check('规则表有试跑操作', $('ruleTableWrap').querySelectorAll('[data-test-rule]').length > 0,
        $('ruleTableWrap').querySelectorAll('[data-test-rule]').length);
    check('试跑规则下拉已填充', $('ruleTestSel').options.length > 1, $('ruleTestSel').options.length);
    check('试跑数据源下拉已填充', $('ruleTestDs').options.length > 1, $('ruleTestDs').options.length);
    check('规则库不占用巡检配置的页签', !doc.getElementById('pane-rules'));

    console.log('\n\u2500\u2500 填入示例值 → 运行校验 \u2500\u2500');
    $('btnFillDemo').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(120);
    const filled = [...$('baseTableWrap').querySelectorAll('[data-val]')].filter(i => i.value !== '').length;
    check('示例值已填入', filled > 0, filled);

    $('btnRunCheck').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const checkOk = await until(() => $('checkResultWrap').querySelector('.check-score'), 'check');
    check('校验结果已渲染', checkOk);
    if (checkOk) {
        const score = $('checkResultWrap').querySelector('.check-score').textContent.trim();
        check('合规率为百分比数值', /^\d+(\.\d+)?%$/.test(score), score);
        const rows = $('checkResultWrap').querySelectorAll('tbody tr').length;
        check('校验结果逐项列出', rows > 0, rows);
        const badges = $('checkResultWrap').innerHTML;
        check('结果包含合规/不合规/未采集标记',
            badges.includes('\u5408\u89c4') || badges.includes('\u4e0d\u5408\u89c4'));
    }

    console.log('\n\u2500\u2500 页签：变更历史 \u2500\u2500');
    doc.querySelector('#view-inspection .tab[data-tab="hist"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    // histWrap 初始就有「加载中…」占位，不能只看 .empty/table —— 必须等计数被写入
    const histOk = await until(() => /^\d+ \u6761$/.test($('histCount').textContent.trim()), 'history');
    check('变更历史已渲染（表格或空状态）',
        !!$('histWrap').querySelector('table') || !!$('histWrap').querySelector('.empty'));
    check('变更历史计数已同步', histOk, JSON.stringify($('histCount').textContent));

    console.log('\n\u2500\u2500 弹窗打开（模板 / 章节 / 规则 / 基线） \u2500\u2500');
    $('btnNewTemplate').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(60);
    check('新建模板弹窗可打开', $('tplModal').hidden === false);
    doc.querySelector('#tplModal [data-close]').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    check('模板弹窗可关闭', $('tplModal').hidden === true);

    doc.querySelector('#view-inspection .tab[data-tab="tpl"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(120);
    $('tplDetail').querySelector('[data-act="new-ch"]').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(60);
    check('新增章节弹窗可打开', $('chModal').hidden === false);
    check('新增章节自动带出下一个章节号', parseInt($('chNumber').value, 10) === 22, $('chNumber').value);

    console.log('\n\u2500\u2500 基线弹窗（BETWEEN 区间联动） \u2500\u2500');
    $('btnNewBaseline').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(60);
    check('新建基线弹窗可打开', $('baseModal').hidden === false);
    check('BETWEEN 未选中时期望值字段可见', $('fExpected').hidden === false && $('fRange').hidden === true);
    $('baseOp').value = 'BETWEEN';
    $('baseOp').dispatchEvent(new window.Event('change', { bubbles: true }));
    await sleep(40);
    check('切换到 BETWEEN 后区间字段显示', $('fRange').hidden === false && $('fExpected').hidden === true);

    console.log('\n\u2500\u2500 切换到「巡检执行」视图 \u2500\u2500');
    const navRun = doc.querySelector('.nav-item[data-view="inspectionRun"]');
    check('导航存在「巡检执行」入口', !!navRun);
    navRun.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

    // 执行页现在只负责「发起 + 本次结果」，历史列表搬到了「巡检历史」
    check('执行页不再内嵌历史列表', !doc.getElementById('runList'));
    // 数据源/模板下拉是异步填的（ensureRunExec → loadDatasources），必须等，
    // 不能拿「runDetail 里有 .card」当就绪信号 —— 那个占位卡片一开始就在
    const execSelectsOk = await until(() => $('runDsSel').options.length > 0, 'execSelects');
    check('执行页数据源下拉已填充', execSelectsOk, $('runDsSel').options.length);
    check('数据源下拉有默认选中', $('runDsSel').value !== '', JSON.stringify($('runDsSel').value));
    check('模板下拉已填充', $('runTplSel').options.length > 0, $('runTplSel').options.length);
    check('发起提示已渲染', $('runHint').textContent.trim().length > 0, $('runHint').textContent);

    console.log('\n\u2500\u2500 发起巡检 → 报告渲染 \u2500\u2500');
    $('btnRunInspection').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

    const reportOk = await until(() => $('runDetail').querySelector('.report-head'), 'report');
    check('报告已渲染', reportOk);
    if (reportOk) {
        check('报告含执行状态徽标',
            !!$('runDetail').querySelector('.st-SUCCESS, .st-PARTIAL, .st-FAILED'));
        check('报告含四项关键指标', $('runDetail').querySelectorAll('.rmetric').length === 4,
            $('runDetail').querySelectorAll('.rmetric').length);
        check('报告含合规率', !!$('runDetail').querySelector('.rmetric .good, .rmetric .mid, .rmetric .bad')
            || $('runDetail').innerHTML.includes('\u5408\u89c4\u7387'));

        const chapCount = $('runDetail').querySelectorAll('.chap').length;
        check('报告按章节列出规则结果', chapCount > 0, chapCount);
        check('报告含基线判定表', !!$('runDetail').querySelector('table.table'));

        const openBefore = $('runDetail').querySelectorAll('.chap.open').length;
        $('runDetail').querySelector('.chap-head')
            .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        await sleep(120);
        const openAfter = $('runDetail').querySelectorAll('.chap.open').length;
        check('报告章节可展开/收起', openBefore !== openAfter, `${openBefore} -> ${openAfter}`);
    }

    console.log('\n\u2500\u2500 驱动管理：两栏布局 \u2500\u2500');
    doc.querySelector('.nav-item[data-view="drivers"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const drvOk = await until(() => $('drvTypeList').querySelectorAll('.drivers-type-item').length > 0, 'drvTypes');
    check('左栏列出全部库类型', drvOk, $('drvTypeList').querySelectorAll('.drivers-type-item').length);
    check('左栏类型数 = 6', $('drvTypeList').querySelectorAll('.drivers-type-item').length === 6,
        $('drvTypeList').querySelectorAll('.drivers-type-item').length);
    check('左栏默认选中一个有驱动的类型',
        !!$('drvTypeList').querySelector('.drivers-type-item.active'));
    check('右栏表头为四列（版本/驱动类/JAR/操作）',
        [...doc.querySelectorAll('.drivers-table thead th')].map(t => t.textContent.trim()).join('|')
        === '\u9a71\u52a8\u7248\u672c|\u9a71\u52a8\u7c7b|\u9a71\u52a8 JAR \u5305|\u64cd\u4f5c',
        [...doc.querySelectorAll('.drivers-table thead th')].map(t => t.textContent.trim()).join('|'));
    check('右栏已渲染驱动行', $('drvTbody').querySelectorAll('tr').length > 0,
        $('drvTbody').querySelectorAll('tr').length);
    check('激活行有 is-active 标记', !!$('drvTbody').querySelector('tr.is-active'));

    // 点一个「没有驱动的类型」应看到引导文案而不是空表格
    const emptyType = [...$('drvTypeList').querySelectorAll('.drivers-type-item')]
        .find(el => /未\u914d\u7f6e|\u5185\u7f6e/.test(el.textContent));
    if (emptyType) {
        const want = emptyType.dataset.drvType;
        emptyType.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        await sleep(150);
        check('切到未配置类型后左栏高亮跟随',
            $('drvTypeList').querySelector('.drivers-type-item.active').dataset.drvType === want);
        check('未配置类型给出引导文案（非空表）',
            $('drvTbody').textContent.includes('drivers/') || $('drvTbody').textContent.includes('\u5185\u7f6e'),
            $('drvTbody').textContent.trim().slice(0, 80));
    }
    // 切回 oracle 供后续步骤使用
    const oracleItem = [...$('drvTypeList').querySelectorAll('.drivers-type-item')]
        .find(el => el.dataset.drvType === 'oracle');
    if (oracleItem) oracleItem.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(150);

    console.log('\n\u2500\u2500 巡检历史：列表 / 预览 / 下载 \u2500\u2500');
    doc.querySelector('.nav-item[data-view="runs"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const histListOk = await until(() => /^\d+/.test($('runsCount').textContent.trim()), 'runsList');
    check('历史记录计数已同步', histListOk, JSON.stringify($('runsCount').textContent));
    check('历史列表已渲染记录', $('runsList').querySelectorAll('.run-item').length > 0,
        $('runsList').querySelectorAll('.run-item').length);
    check('数据源筛选下拉可用', $('runsDsFilter').options.length >= 1, $('runsDsFilter').options.length);
    check('库类型筛选下拉可用', $('runsTypeFilter').options.length >= 1, $('runsTypeFilter').options.length);

    const previewOk = await until(() => $('runsPreviewFrame').getAttribute('src'), 'preview');
    check('默认选中一条记录并加载在线预览', previewOk, $('runsPreviewFrame').getAttribute('src'));
    check('预览指向 HTML 导出接口',
        /\/api\/inspection\/runs\/\d+\/export\?format=html$/.test($('runsPreviewFrame').getAttribute('src') || ''),
        $('runsPreviewFrame').getAttribute('src'));
    check('预览 iframe 可见', $('runsPreviewFrame').hidden === false);
    check('三个下载按钮已启用',
        !$('dlWord').disabled && !$('dlPdf').disabled && !$('dlHtml').disabled);
    check('「在新标签打开」指向同一份 HTML',
        $('runsOpenTab').getAttribute('href') === $('runsPreviewFrame').getAttribute('src'),
        $('runsOpenTab').getAttribute('href'));

    // 切换到结构化明细
    doc.querySelector('#runsPreviewMode .seg-btn[data-pmode="detail"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const histDetailOk = await until(() => $('runsDetailWrap').querySelector('.report-head'), 'detail');
    check('切到结构化明细后渲染报告头', histDetailOk);
    check('切到明细后 iframe 隐藏', $('runsPreviewFrame').hidden === true);
    check('明细含章节与基线表',
        $('runsDetailWrap').querySelectorAll('.chap').length > 0
        && !!$('runsDetailWrap').querySelector('table.table'));

    // 切回报告原文
    doc.querySelector('#runsPreviewMode .seg-btn[data-pmode="report"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(150);
    check('切回报告原文后 iframe 复现',
        $('runsPreviewFrame').hidden === false && $('runsDetailWrap').hidden === true);

    // 筛选：先断言「标签计数 == 实际渲染条数」，再用一个不存在的库类型
    // 断言筛选真的会过滤掉记录。
    // 不能断言「筛选后条数变少」—— 当前夹具里所有记录都是 h2，按 h2 筛不会少任何一条，
    // 那种断言会随着数据分布变化而假失败。
    const typeOpts = [...$('runsTypeFilter').options].map(o => o.value).filter(Boolean);
    if (typeOpts.length) {
        $('runsTypeFilter').value = typeOpts[0];
        $('runsTypeFilter').dispatchEvent(new window.Event('change', { bubbles: true }));
        await sleep(150);
        const shown = $('runsList').querySelectorAll('.run-item').length;
        const labelN = parseInt($('runsCount').textContent.trim(), 10);
        check('筛选后标签计数与实际渲染条数一致', shown === labelN,
            `渲染 ${shown} 条 / 标签 ${$('runsCount').textContent.trim()}`);

        // 造一个一定不存在的库类型，走真实的事件路径
        const bogus = doc.createElement('option');
        bogus.value = '__NOSUCHDB__';
        bogus.textContent = '__NOSUCHDB__';
        $('runsTypeFilter').appendChild(bogus);
        $('runsTypeFilter').value = '__NOSUCHDB__';
        $('runsTypeFilter').dispatchEvent(new window.Event('change', { bubbles: true }));
        await sleep(150);
        check('不存在的库类型筛选出空列表',
            $('runsList').querySelectorAll('.run-item').length === 0,
            $('runsList').querySelectorAll('.run-item').length);
        check('空筛选结果给出提示文案',
            $('runsList').textContent.includes('\u6ca1\u6709\u8bb0\u5f55')
            || $('runsList').textContent.includes('\u7b5b\u9009'),
            $('runsList').textContent.trim().slice(0, 60));
        check('筛选计数显示 0 / 总数',
            /^0 \/ \d+ \u6761$/.test($('runsCount').textContent.trim()),
            $('runsCount').textContent.trim());
        bogus.remove();

        $('runsTypeFilter').value = '';
        $('runsTypeFilter').dispatchEvent(new window.Event('change', { bubbles: true }));
        await sleep(150);
        check('清空筛选后恢复全部',
            !$('runsCount').textContent.includes('/')
            && $('runsList').querySelectorAll('.run-item').length > 0,
            $('runsCount').textContent.trim());
    }

    console.log('\n\u2500\u2500 概览（大屏展示） \u2500\u2500');
    doc.querySelector('.nav-item[data-view="overview"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const ovOk = await until(() => $('ovStats').querySelectorAll('.stat-card').length >= 6, 'overview');
    check('概览 KPI 卡已渲染', ovOk, $('ovStats').querySelectorAll('.stat-card').length);
    check('概览 KPI 含「规则库」与「基线规则」',
        $('ovStats').textContent.includes('规则库') && $('ovStats').textContent.includes('基线规则'));
    check('概览资产条形图已渲染', $('ovAssetChart').querySelectorAll('.bar-row').length > 0,
        $('ovAssetChart').querySelectorAll('.bar-row').length);
    check('条形图每行都给了非零宽度（不是空壳）',
        [...$('ovAssetChart').querySelectorAll('.bar-fill')]
            .every(el => /width:\s*\d+%/.test(el.getAttribute('style') || '')),
        $('ovAssetChart').querySelector('.bar-fill').getAttribute('style'));
    check('概览风险分布 4 个等级齐备', $('ovRiskChart').querySelectorAll('.risk-box').length === 4,
        $('ovRiskChart').querySelectorAll('.risk-box').length);
    check('概览最近执行区已渲染（表格或空态）',
        !!$('ovRuns').querySelector('table.table') || !!$('ovRuns').querySelector('.empty'));
    check('概览常用入口 6 个', $('ovLinks').querySelectorAll('.quick-link').length === 6,
        $('ovLinks').querySelectorAll('.quick-link').length);

    // 快捷入口是真能跳的，不是装饰
    const gotoDs = [...$('ovLinks').querySelectorAll('.quick-link')]
        .find(b => b.dataset.goto === 'datasources');
    gotoDs.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(200);
    check('概览快捷入口能切换视图',
        doc.querySelector('.nav-item.active').dataset.view === 'datasources'
        && $('view-datasources').classList.contains('active'),
        doc.querySelector('.nav-item.active').dataset.view);

    console.log('\n\u2500\u2500 数据库管理（数据库运维） \u2500\u2500');
    doc.querySelector('.nav-item[data-view="databases"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const dbOk = await until(() => $('dbInstList').querySelectorAll('.pick-item').length > 0, 'dbmgmt');
    check('实例列表已渲染', dbOk, $('dbInstList').querySelectorAll('.pick-item').length);
    check('默认选中第一个实例', !!$('dbInstList').querySelector('.pick-item.active'));
    check('实例信息卡给出库类型与连接状态',
        $('dbInfo').textContent.includes('库类型') && $('dbInfo').textContent.includes('连接状态'));
    check('实例信息卡给出地址与库名',
        $('dbInfo').textContent.includes('地址') && $('dbInfo').textContent.includes('库 / 服务'));
    check('提供连接测试入口', !!$('btnDbTest'));
    check('对象浏览如实标注接口尚未接入',
        $('view-databases').textContent.includes('结构浏览接口尚未接入'));
    check('没有伪造对象树', !$('view-databases').querySelector('.obj-tree'));

    console.log('\n\u2500\u2500 数据库统计（数据库运维） \u2500\u2500');
    doc.querySelector('.nav-item[data-view="dbStats"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const stOk = await until(() => $('statByType').querySelectorAll('.bar-row').length > 0, 'dbStats');
    check('按库类型的规则分布已渲染', stOk, $('statByType').querySelectorAll('.bar-row').length);
    check('基线覆盖图已渲染', $('statBaseline').querySelectorAll('.bar-row').length > 0,
        $('statBaseline').querySelectorAll('.bar-row').length);
    check('规则启用状态三项齐备', $('statRuleState').querySelectorAll('.bar-row').length === 3,
        $('statRuleState').querySelectorAll('.bar-row').length);
    check('规则归类 Top 10 不超过 10 行',
        $('statCategory').querySelectorAll('.bar-row').length <= 10,
        $('statCategory').querySelectorAll('.bar-row').length);
    check('统计 KPI 含平均合规率', $('statStats').textContent.includes('平均合规率'));

    console.log('\n\u2500\u2500 定时巡检（数据库运维） \u2500\u2500');
    doc.querySelector('.nav-item[data-view="schedules"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    const schOk = await until(() => $('schedTriggerWrap').querySelectorAll('.bar-row').length > 0, 'schedules');
    check('触发方式分布已渲染（取自真实执行记录）', schOk,
        $('schedTriggerWrap').querySelectorAll('.bar-row').length);
    check('任务表保持空态而非伪造任务',
        !!$('schedTableWrap').querySelector('.empty')
        && $('schedTableWrap').querySelectorAll('tbody tr').length === 1,
        $('schedTableWrap').querySelectorAll('tbody tr').length);
    check('任务表列出了接入后要用的列',
        ['任务名', '目标数据源', '巡检模板', '周期', '上次执行', '状态']
            .every(h => $('schedTableWrap').textContent.includes(h)));
    check('新建任务按钮为禁用态', $('btnNewSchedule').disabled === true);
    check('页面明确说明调度器尚未接入',
        $('view-schedules').textContent.includes('调度器尚未接入'));

    console.log('\n\u2500\u2500 插件市场（配置与管理） \u2500\u2500');
    doc.querySelector('.nav-item[data-view="plugins"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(150);
    check('插件列表为空态且未伪造插件',
        !!$('pluginGrid').querySelector('.empty')
        && $('pluginGrid').querySelectorAll('.plugin-card').length === 0,
        $('pluginGrid').querySelectorAll('.plugin-card').length);
    check('搜索与分类控件已就位但禁用',
        $('pluginKeyword').disabled === true && $('pluginCatSel').disabled === true);
    check('插件计数显示 0 个插件', $('pluginCount').textContent.trim() === '0 个插件',
        $('pluginCount').textContent.trim());
    check('页面说明插件源尚未接入', $('view-plugins').textContent.includes('插件源尚未接入'));

    console.log('\n\u2500\u2500 AI配置（配置与管理） \u2500\u2500');
    doc.querySelector('.nav-item[data-view="aiConfig"]')
        .dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await sleep(150);
    check('保存与测试连接按钮均为禁用态',
        $('btnAiSave').disabled === true && $('btnAiTest').disabled === true);
    check('表单字段齐备（供应商 / Base URL / 模型名 / API Key）',
        !!$('aiProvider') && !!$('aiBaseUrl') && !!$('aiModel') && !!$('aiApiKey'));
    check('页面声明配置不会被保存', $('view-aiConfig').textContent.includes('不会保存'));
    check('页面声明模型不改判合规率',
        $('view-aiConfig').textContent.includes('不参与合规率计算'));

    console.log('\n\u2500\u2500 运行时错误 \u2500\u2500');
    const realErrors = consoleErrors.filter(e => !/Not implemented|Could not parse CSS/.test(e));
    check('无未捕获的 JS 运行时错误', realErrors.length === 0, realErrors.join(' | '));

    console.log('\n' + '='.repeat(58));
    console.log(`通过 ${pass.length} 项，失败 ${fail.length} 项`);
    if (fail.length) { console.log('失败项：'); fail.forEach(f => console.log('  \u2718 ' + f)); }
    console.log('='.repeat(58));

    window.close();
    process.exit(fail.length ? 1 : 0);
})().catch(e => {
    console.error('冒烟测试异常终止：', e);
    process.exit(2);
});
