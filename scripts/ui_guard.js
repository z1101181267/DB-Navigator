/* ============================================================
   真实浏览器 UI 守卫 —— headless Edge/Chrome + CDP，零依赖

   为什么需要它：jsdom 冒烟测试不跑 CSS，抓不到「类规则覆盖 hidden 属性」这类缺陷。
   实测 jsdom 30 的 getComputedStyle 对带 hidden 属性的元素**一律**返回 display:none，
   即使作者样式表里写着 .modal-mask{display:flex} —— 它没有忠实实现级联，
   因此**不能**用来验证这类问题。必须用真实浏览器。

   本脚本用 Node 22 内置的 WebSocket 直连 CDP，不安装 playwright/puppeteer。
   它做的是 jsdom 做不到的事：真实布局（getBoundingClientRect）+ 真实 CSS 级联。

   覆盖的缺陷类型（都属于「元素该藏起来却显示」）：
     1. 首屏六个弹窗因 .modal-mask{display:flex} 覆盖 hidden 而全部常显并堆叠
     2. SQL 编辑器的耗时徽标 .badge{display:inline-block} 空徽标常显
     3. 弹窗内条件字段（.field{display:flex}）不随下拉切换显隐
        - 基线弹窗：BETWEEN 区间字段
        - 数据源弹窗：Oracle 专属的 SID / Service Name

   用法：
     node scripts/ui_guard.js
     DBNAV_BASE=http://127.0.0.1:8090 node scripts/ui_guard.js   # 打预览服务
   ============================================================ */

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const BASE = (process.env.DBNAV_BASE || 'http://127.0.0.1:8080').replace(/\/$/, '');
const PORT = Number(process.env.DBNAV_CDP_PORT || 9333);
const ROOT = path.resolve(__dirname, '..');
const SHOT_DIR = path.join(ROOT, 'target', 'probe', 'shots');

const BROWSERS = [
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
];

const pass = [], fail = [];
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

function check(name, cond, extra) {
    (cond ? pass : fail).push(name);
    console.log((cond ? '  \u2714 ' : '  \u2718 ') + name +
        (cond ? '' : '   ' + (extra === undefined ? '' : String(extra).slice(0, 400))));
}

/* ---------------- 极简 CDP 客户端 ---------------- */

class CDP {
    constructor(ws) {
        this.ws = ws;
        this.seq = 0;
        this.pending = new Map();
        this.handlers = new Map();
    }

    static async connect(url) {
        const ws = new WebSocket(url);
        await new Promise((res, rej) => {
            ws.onopen = res;
            ws.onerror = () => rej(new Error('CDP WebSocket 连接失败'));
        });
        const c = new CDP(ws);
        ws.onmessage = (ev) => {
            const msg = JSON.parse(ev.data);
            if (msg.id && c.pending.has(msg.id)) {
                const { res, rej } = c.pending.get(msg.id);
                c.pending.delete(msg.id);
                if (msg.error) rej(new Error(JSON.stringify(msg.error)));
                else res(msg.result);
            } else if (msg.method && c.handlers.has(msg.method)) {
                c.handlers.get(msg.method)(msg.params);
            }
        };
        return c;
    }

    send(method, params = {}) {
        const id = ++this.seq;
        return new Promise((res, rej) => {
            this.pending.set(id, { res, rej });
            this.ws.send(JSON.stringify({ id, method, params }));
        });
    }

    once(method, timeout = 20000) {
        return new Promise((res, rej) => {
            const t = setTimeout(() => rej(new Error('等待事件 ' + method + ' 超时')), timeout);
            this.handlers.set(method, (p) => { clearTimeout(t); res(p); });
        });
    }

    close() { try { this.ws.close(); } catch (e) { /* ignore */ } }
}

/* 在页面里求值；表达式须自带 return */
async function evaluate(cdp, body) {
    // 用 async 包裹：这样页面内的断言脚本可以 await（例如 fetch 探测导出接口的状态码）。
    // awaitPromise:true 让同步 return 的脚本同样正常工作。
    const r = await cdp.send('Runtime.evaluate', {
        expression: `(async function(){ ${body} })()`,
        awaitPromise: true,
        returnByValue: true,
    });
    if (r.exceptionDetails) {
        throw new Error(r.exceptionDetails.exception?.description || '页面内 JS 异常');
    }
    return r.result.value;
}

/* 注入到页面里的可见性判定。
   disp() 只看元素自身计算出的 display——用于精确断言「hidden 属性是否被类规则覆盖」。
   vis()  额外看祖先与真实布局盒——用于断言「用户是否真的看得见」。 */
const VIS_HELPER = `
    function disp(sel) {
        const el = document.querySelector(sel);
        return el ? getComputedStyle(el).display : 'MISSING';
    }
    function vis(sel) {
        const el = document.querySelector(sel);
        if (!el) return 'MISSING';
        const cs = getComputedStyle(el);
        if (cs.display === 'none') return 'hidden';
        if (cs.visibility === 'hidden') return 'hidden';
        // 祖先隐藏时自身 display 仍可能是 flex/inline-block，需单独识别
        if (typeof el.checkVisibility === 'function' && !el.checkVisibility()) return 'hidden-ancestor';
        const r = el.getBoundingClientRect();
        return (r.width > 0 && r.height > 0) ? 'visible' : 'zero';
    }
`;

async function waitReady(cdp, timeout = 20000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
        try {
            const ok = await evaluate(cdp, `
                return typeof state !== 'undefined'
                    && Array.isArray(state.dbTypes) && state.dbTypes.length > 0
                    && document.querySelectorAll('.nav-item').length >= 5;
            `);
            if (ok) return true;
        } catch (e) { /* 页面尚未就绪 */ }
        await sleep(200);
    }
    return false;
}

/* ---------------- 主流程 ---------------- */

(async function main() {
    const exe = BROWSERS.find(p => { try { return fs.existsSync(p); } catch (e) { return false; } });
    if (!exe) {
        console.error('✘ 未找到 Edge / Chrome，无法进行真实浏览器验证');
        process.exit(1);
    }

    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dbnav-ui-guard-'));
    const child = spawn(exe, [
        '--headless=new',
        '--disable-gpu',
        '--no-first-run',
        '--no-default-browser-check',
        '--disable-extensions',
        '--hide-scrollbars',
        '--window-size=1440,900',
        '--remote-debugging-port=' + PORT,
        '--user-data-dir=' + profile,
        'about:blank',
    ], { stdio: 'ignore' });

    let cdp = null;
    const cleanup = () => {
        if (cdp) cdp.close();
        try { child.kill(); } catch (e) { /* ignore */ }
        try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) { /* ignore */ }
    };

    try {
        // 等 CDP 端口就绪
        let target = null;
        const deadline = Date.now() + 20000;
        while (Date.now() < deadline) {
            try {
                const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
                target = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
                if (target) break;
            } catch (e) { /* 还没起来 */ }
            await sleep(200);
        }
        if (!target) throw new Error('CDP 端口未就绪');

        cdp = await CDP.connect(target.webSocketDebuggerUrl);
        await cdp.send('Page.enable');
        await cdp.send('Runtime.enable');

        console.log(`浏览器 : ${path.basename(exe)}`);
        console.log(`目标   : ${BASE}`);
        console.log();

        const loaded = cdp.once('Page.loadEventFired');
        await cdp.send('Page.navigate', { url: BASE + '/' });
        await loaded;

        if (!await waitReady(cdp)) {
            throw new Error('页面 20 秒内未就绪（后端未启动或前端报错）');
        }
        await sleep(400);

        /* ---- 1. 首屏：所有弹窗必须不可见 ---- */
        console.log('── 首屏：弹窗不得常显 ──');
        const masks = await evaluate(cdp, `
            return [...document.querySelectorAll('.modal-mask')].map(m => {
                const cs = getComputedStyle(m);
                const r = m.getBoundingClientRect();
                return { id: m.id, hasAttr: m.hasAttribute('hidden'), display: cs.display,
                         w: Math.round(r.width), h: Math.round(r.height) };
            });
        `);
        check(`页面上有 ${masks.length} 个弹窗（应为 6 个）`, masks.length === 6, JSON.stringify(masks));
        for (const m of masks) {
            check(`弹窗 #${m.id} 首屏不可见（display=${m.display}, 尺寸 ${m.w}×${m.h}）`,
                m.display === 'none' && m.h === 0,
                JSON.stringify(m));
        }

        /* ---- 2. SQL 编辑器：耗时徽标不得空显 ---- */
        console.log();
        console.log('── SQL 编辑器：耗时徽标不得空显 ──');
        await evaluate(cdp, `
            switchView('query');
            return true;
        `);
        await sleep(300);
        // 先切到 SQL 视图让祖先可见，才能隔离出「hidden 属性自身是否生效」
        const timing = await evaluate(cdp, `${VIS_HELPER} return { d: disp('#queryTiming'), v: vis('#queryTiming') };`);
        check(`带 hidden 属性时 #queryTiming 自身 display 为 none，未被 .badge 的 inline-block 覆盖（实际 ${timing.d}）`,
            timing.d === 'none', JSON.stringify(timing));
        check(`未执行 SQL 时耗时徽标不占据可见空间（实际 ${timing.v}）`,
            timing.v !== 'visible', JSON.stringify(timing));

        /* ---- 3. 基线弹窗：BETWEEN 区间字段联动（基线已是独立菜单） ---- */
        console.log();
        console.log('── 基线规则页 + BETWEEN 区间字段联动 ──');
        await evaluate(cdp, `switchView('baselines'); return true;`);
        await sleep(600);
        const baseView = await evaluate(cdp, `
            return {
                navActive: (document.querySelector('.nav-item.active') || {}).dataset?.view || null,
                viewVisible: getComputedStyle(document.getElementById('view-baselines')).display !== 'none',
                stats: document.querySelectorAll('#baseStats .stat-card').length,
                rows: document.querySelectorAll('#baseTableWrap tbody tr').length
            };
        `);
        check(`切到「基线规则」后导航高亮正确（${baseView.navActive}）`, baseView.navActive === 'baselines',
            JSON.stringify(baseView));
        check(`基线规则视图可见且有统计条（${baseView.stats} 张卡）`,
            baseView.viewVisible && baseView.stats === 4, JSON.stringify(baseView));
        check(`基线表格已渲染（${baseView.rows} 行）`, baseView.rows > 0, JSON.stringify(baseView));

        await evaluate(cdp, `document.getElementById('btnNewBaseline').click(); return true;`);
        await sleep(300);

        const baseOpened = await evaluate(cdp, `
            const m = document.getElementById('baseModal');
            return getComputedStyle(m).display !== 'none';
        `);
        check('点「新建基线」后弹窗可见', baseOpened === true, String(baseOpened));

        const setOp = (op) => evaluate(cdp, `
            const s = document.getElementById('baseOp');
            s.value = ${JSON.stringify(op)};
            s.dispatchEvent(new Event('change', { bubbles: true }));
            return s.value;
        `);

        await setOp('=');
        let st = await evaluate(cdp, `
            ${VIS_HELPER}
            return { range: vis('#fRange'), expected: vis('#fExpected') };
        `);
        check(`比较符 = 时区间字段隐藏、期望值字段可见（${st.range} / ${st.expected}）`,
            st.range === 'hidden' && st.expected === 'visible', JSON.stringify(st));

        await setOp('BETWEEN');
        st = await evaluate(cdp, `
            ${VIS_HELPER}
            return { range: vis('#fRange'), expected: vis('#fExpected') };
        `);
        check(`比较符 BETWEEN 时区间字段可见、期望值字段隐藏（${st.range} / ${st.expected}）`,
            st.range === 'visible' && st.expected === 'hidden', JSON.stringify(st));

        await setOp('LIKE');
        st = await evaluate(cdp, `${VIS_HELPER} return { range: vis('#fRange') };`);
        check(`比较符改回 LIKE 后区间字段重新隐藏（${st.range}）`, st.range === 'hidden', JSON.stringify(st));

        await evaluate(cdp, `closeModal('baseModal'); return true;`);

        /* ---- 4. 数据源弹窗：Oracle 专属字段联动 ---- */
        console.log();
        console.log('── 数据源弹窗：Oracle 专属字段联动 ──');
        await evaluate(cdp, `
            switchView('datasources');
            document.getElementById('btnNewDs').click();
            return true;
        `);
        await sleep(300);

        const setType = (t) => evaluate(cdp, `
            const s = document.getElementById('dsType');
            s.value = ${JSON.stringify(t)};
            s.dispatchEvent(new Event('change', { bubbles: true }));
            return s.value;
        `);

        const hasMysql = await evaluate(cdp, `
            return [...document.getElementById('dsType').options].some(o => o.value === 'mysql');
        `);
        check('数据源类型下拉含 mysql 选项', hasMysql === true, String(hasMysql));

        await setType('mysql');
        let ds = await evaluate(cdp, `
            ${VIS_HELPER}
            return { svc: vis('#fService'), sid: vis('#fSid'), db: vis('#fDatabase') };
        `);
        check(`选 MySQL 时 Oracle 专属字段隐藏、库名字段可见（${ds.svc} / ${ds.sid} / ${ds.db}）`,
            ds.svc === 'hidden' && ds.sid === 'hidden' && ds.db === 'visible', JSON.stringify(ds));

        await setType('oracle');
        ds = await evaluate(cdp, `
            ${VIS_HELPER}
            return { svc: vis('#fService'), sid: vis('#fSid'), db: vis('#fDatabase') };
        `);
        check(`选 Oracle 时 SID/Service 字段可见、库名字段隐藏（${ds.svc} / ${ds.sid} / ${ds.db}）`,
            ds.svc === 'visible' && ds.sid === 'visible' && ds.db === 'hidden', JSON.stringify(ds));

        await evaluate(cdp, `closeModal('dsModal'); return true;`);
        await sleep(300);

        /* ---- 5. 弹窗关闭后不得残留 ---- */
        console.log();
        console.log('── 弹窗关闭后不得残留 ──');
        const after = await evaluate(cdp, `
            return [...document.querySelectorAll('.modal-mask')]
                .filter(m => getComputedStyle(m).display !== 'none').map(m => m.id);
        `);
        check(`关闭后无残留可见弹窗（${after.length} 个）`, after.length === 0, JSON.stringify(after));

        /* ---- 6. 驱动管理：两栏布局的真实几何 ---- */
        console.log();
        console.log('── 驱动管理：两栏布局的真实几何 ──');
        await evaluate(cdp, `switchView('drivers'); return true;`);
        await sleep(900);

        const drv = await evaluate(cdp, `
            const split = document.querySelector('.drivers-split');
            const left = document.querySelector('.drivers-types-pane');
            const right = document.querySelector('.drivers-list-pane');
            const items = document.querySelectorAll('#drvTypeList .drivers-type-item');
            const rows = document.querySelectorAll('#drvTbody tr');
            const lr = left.getBoundingClientRect(), rr = right.getBoundingClientRect();
            const thead = document.querySelector('.drivers-table thead th');
            return {
                cols: getComputedStyle(split).gridTemplateColumns,
                leftW: Math.round(lr.width), rightW: Math.round(rr.width),
                leftX: Math.round(lr.x), rightX: Math.round(rr.x),
                leftH: Math.round(lr.height), rightH: Math.round(rr.height),
                items: items.length, rows: rows.length,
                // 表头必须真的粘在滚动容器顶部（position:sticky 生效）
                sticky: getComputedStyle(thead).position
            };
        `);
        check(`两栏网格左列为 280px（实际 ${drv.cols}）`, /^280px/.test(drv.cols), drv.cols);
        check(`左栏实际宽度为 280px（实际 ${drv.leftW}px）`, drv.leftW === 280, drv.leftW);
        check(`右栏在左栏右侧（left.x=${drv.leftX} < right.x=${drv.rightX}）`,
            drv.rightX > drv.leftX + drv.leftW - 2, JSON.stringify(drv));
        check(`两栏等高（左 ${drv.leftH} / 右 ${drv.rightH}）`,
            Math.abs(drv.leftH - drv.rightH) <= 2, JSON.stringify(drv));
        check(`左栏列出 6 个库类型（实际 ${drv.items}）`, drv.items === 6, drv.items);
        check(`右栏渲染驱动行（实际 ${drv.rows}）`, drv.rows > 0, drv.rows);
        check(`驱动表头 position:sticky（实际 ${drv.sticky}）`, drv.sticky === 'sticky', drv.sticky);

        /* ---- 7. 巡检历史：在线预览与下载按钮 ---- */
        console.log();
        console.log('── 巡检历史：在线预览与下载按钮 ──');
        await evaluate(cdp, `switchView('runs'); return true;`);
        await sleep(2200);

        const runs = await evaluate(cdp, `
            const f = document.getElementById('runsPreviewFrame');
            const fr = f.getBoundingClientRect();
            const btn = (id) => {
                const el = document.getElementById(id);
                const cs = getComputedStyle(el);
                return { bg: cs.backgroundColor, color: cs.color, disabled: el.disabled,
                         w: Math.round(el.getBoundingClientRect().width) };
            };
            return {
                listItems: document.querySelectorAll('#runsList .run-item').length,
                frameSrc: f.getAttribute('src'), frameHidden: f.hidden,
                frameW: Math.round(fr.width), frameH: Math.round(fr.height),
                // 报告真的加载进来了吗（同源，可以读 contentDocument）
                docTitle: (() => { try { return f.contentDocument.title; } catch (e) { return 'X-ORIGIN'; } })(),
                reportH1: (() => {
                    try { const h = f.contentDocument.querySelector('h1'); return h ? h.textContent.trim() : null; }
                    catch (e) { return 'X-ORIGIN'; }
                })(),
                // 预览服务模式下 iframe 里是 501 的说明文本
                frameText: (() => {
                    try { return (f.contentDocument.body.textContent || '').slice(0, 400); }
                    catch (e) { return 'X-ORIGIN'; }
                })(),
                word: btn('dlWord'), pdf: btn('dlPdf'), html: btn('dlHtml')
            };
        `);

        // 导出能力探测：Java 产出真实文件（200），预览服务如实 501。
        // 两条分支各自都要有实质断言 —— 不能写成「200 也过、501 也过」，
        // 那就成了不会失败的假守卫。
        const exportStatus = await evaluate(cdp, `
            const r = await fetch(${JSON.stringify(runs.frameSrc)});
            return r.status;
        `);

        check(`历史列表已渲染（${runs.listItems} 条）`, runs.listItems > 0, runs.listItems);
        check(`预览 iframe 有真实尺寸（${runs.frameW}×${runs.frameH}）`,
            runs.frameW > 300 && runs.frameH > 300, JSON.stringify(runs));

        if (exportStatus === 200) {
            check(`导出接口可用（HTTP ${exportStatus}）：iframe 已加载报告（标题「${runs.docTitle}」）`,
                /^\u5de1\u68c0\u62a5\u544a/.test(runs.docTitle || ''), runs.docTitle);
            check(`iframe 内报告正文标题为「数据库巡检报告」（实际 ${runs.reportH1}）`,
                runs.reportH1 === '\u6570\u636e\u5e93\u5de1\u68c0\u62a5\u544a', runs.reportH1);
        } else if (exportStatus === 501) {
            check(`导出接口如实返回 501（预览服务无 POI/openhtmltopdf，属能力边界）`,
                runs.frameText.includes('\u4e0d\u751f\u6210\u62a5\u544a\u6587\u4ef6'), runs.frameText.slice(0, 160));
            check('501 时 iframe 里不是一份伪装成报告的内容',
                runs.reportH1 === null && !runs.frameText.includes('\u6570\u636e\u5e93\u5de1\u68c0\u62a5\u544a'),
                JSON.stringify({ h1: runs.reportH1, text: runs.frameText.slice(0, 80) }));
        } else {
            check(`导出接口状态码异常（实际 ${exportStatus}）`, false, exportStatus);
        }

        check(`Word 按钮为深蓝 #0a2a6e（实际 ${runs.word.bg}）`,
            runs.word.bg === 'rgb(10, 42, 110)', runs.word.bg);
        check(`PDF 按钮为红色 #dc2626（实际 ${runs.pdf.bg}）`,
            runs.pdf.bg === 'rgb(220, 38, 38)', runs.pdf.bg);
        check(`HTML 按钮为青绿 #00a184（实际 ${runs.html.bg}）`,
            runs.html.bg === 'rgb(0, 161, 132)', runs.html.bg);
        check('三个下载按钮均为启用态且可点击（宽度 > 0）',
            !runs.word.disabled && !runs.pdf.disabled && !runs.html.disabled
            && runs.word.w > 0 && runs.pdf.w > 0 && runs.html.w > 0,
            JSON.stringify([runs.word, runs.pdf, runs.html]));

        // 切到结构化明细，iframe 必须真正让位（不只是叠在上面）
        await evaluate(cdp, `
            document.querySelector('#runsPreviewMode .seg-btn[data-pmode="detail"]').click();
            return true;
        `);
        await sleep(900);
        const detailMode = await evaluate(cdp, `
            ${VIS_HELPER}
            return { frame: vis('#runsPreviewFrame'), detail: vis('#runsDetailWrap'),
                     chapters: document.querySelectorAll('#runsDetailWrap .chap').length };
        `);
        check(`切到结构化明细后 iframe 不可见（${detailMode.frame}）`,
            detailMode.frame !== 'visible', JSON.stringify(detailMode));
        check(`结构化明细可见且含章节（${detailMode.chapters} 章）`,
            detailMode.detail === 'visible' && detailMode.chapters > 0, JSON.stringify(detailMode));

        await evaluate(cdp, `
            document.querySelector('#runsPreviewMode .seg-btn[data-pmode="report"]').click();
            return true;
        `);
        await sleep(900);
        const backToReport = await evaluate(cdp, `${VIS_HELPER} return vis('#runsPreviewFrame');`);
        check(`切回报告原文后 iframe 复现（${backToReport}）`, backToReport === 'visible', backToReport);

        /* ---- 截图留证 ---- */
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const shotPath = path.join(SHOT_DIR, 'ui_guard.png');
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
        fs.writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));
        console.log();
        console.log(`截图   : ${shotPath}`);

    } catch (e) {
        check('执行过程无异常', false, e.message);
    } finally {
        cleanup();
    }

    console.log();
    console.log('==========================================================');
    console.log(`通过 ${pass.length} 项，失败 ${fail.length} 项`);
    console.log('==========================================================');
    process.exit(fail.length === 0 ? 0 : 1);
})();
