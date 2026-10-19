/**
 * 应用版本展示：导航栏版本号 + 更新日志弹窗 + 数据库变更提示
 * FRONTEND_VERSION 需与 version-changelog.json / 后端保持一致
 */
(function (global) {
    var FRONTEND_VERSION = '1.2.106';
    var versionInfo = null;
    var modalReady = false;

    function ensureUi() {
        if (document.getElementById('appVersionBadge')) return;
        injectStyles();
        injectBadge();
        injectModal();
        injectDbBanner();
        modalReady = true;
    }

    function injectStyles() {
        if (document.getElementById('appVersionStyles')) return;
        var css = document.createElement('style');
        css.id = 'appVersionStyles';
        css.textContent = [
            '.app-ver-badge{margin-left:10px;padding:2px 10px;border-radius:999px;font-size:.72rem;font-weight:700;',
            'background:rgba(255,255,255,.18);color:#fff;border:1px solid rgba(255,255,255,.35);cursor:pointer;',
            'text-decoration:none!important;white-space:nowrap;}',
            '.app-ver-badge:hover{background:rgba(255,255,255,.28);color:#fff;}',
            '.app-ver-db-banner{position:fixed;left:50%;transform:translateX(-50%);top:12px;z-index:1080;',
            'max-width:920px;width:calc(100% - 24px);box-shadow:0 8px 24px rgba(0,0,0,.12);}',
            '.ver-modal-backdrop{position:fixed;inset:0;background:rgba(15,23,42,.45);z-index:1090;display:none;}',
            '.ver-modal{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:1091;',
            'width:min(720px,calc(100% - 24px));max-height:82vh;background:#fff;border-radius:12px;',
            'box-shadow:0 20px 50px rgba(0,0,0,.2);display:none;flex-direction:column;overflow:hidden;}',
            '.ver-modal.show,.ver-modal-backdrop.show{display:flex;}',
            '.ver-modal-backdrop.show{display:block;}',
            '.ver-modal-hd{padding:14px 18px;border-bottom:1px solid #e2e8f0;display:flex;justify-content:space-between;align-items:center;}',
            '.ver-modal-bd{padding:14px 18px;overflow:auto;font-size:.86rem;}',
            '.ver-rel{border:1px solid #e2e8f0;border-radius:10px;padding:12px 14px;margin-bottom:12px;}',
            '.ver-rel h6{margin:0 0 6px;font-weight:700;}',
            '.ver-rel .meta{color:#64748b;font-size:.75rem;margin-bottom:8px;}',
            '.ver-rel ul{margin:0;padding-left:1.1rem;}',
            '.ver-rel li{margin-bottom:4px;}',
            '.ver-db{background:#fffbeb;border:1px solid #fcd34d;border-radius:8px;padding:8px 10px;margin-bottom:8px;}',
            '.ver-db strong{color:#92400e;}'
        ].join('');
        document.head.appendChild(css);
    }

    function injectBadge() {
        var nav = document.querySelector('.navbar .app-container') || document.querySelector('.navbar');
        if (!nav) return;
        var a = document.createElement('a');
        a.href = 'javascript:void(0)';
        a.id = 'appVersionBadge';
        a.className = 'app-ver-badge';
        a.title = '查看更新内容';
        a.textContent = 'v' + FRONTEND_VERSION;
        a.onclick = function () { openChangelog(); };
        // 优先放在品牌旁
        var brand = nav.querySelector('.navbar-brand');
        if (brand && brand.parentNode === nav) {
            brand.insertAdjacentElement('afterend', a);
        } else {
            nav.appendChild(a);
        }
    }

    function injectModal() {
        if (document.getElementById('verModal')) return;
        var backdrop = document.createElement('div');
        backdrop.id = 'verModalBackdrop';
        backdrop.className = 'ver-modal-backdrop';
        backdrop.onclick = closeChangelog;
        var modal = document.createElement('div');
        modal.id = 'verModal';
        modal.className = 'ver-modal';
        modal.innerHTML = [
            '<div class="ver-modal-hd">',
            '  <div><strong>更新日志</strong> <span id="verModalCurrent" class="text-muted" style="font-size:.8rem;"></span></div>',
            '  <button type="button" class="btn btn-sm btn-outline-secondary" id="verModalClose">关闭</button>',
            '</div>',
            '<div class="ver-modal-bd" id="verModalBody"><div class="text-muted">加载中…</div></div>'
        ].join('');
        document.body.appendChild(backdrop);
        document.body.appendChild(modal);
        document.getElementById('verModalClose').onclick = closeChangelog;
    }

    function injectDbBanner() {
        if (document.getElementById('appVerDbBanner')) return;
        var div = document.createElement('div');
        div.id = 'appVerDbBanner';
        div.className = 'alert alert-warning app-ver-db-banner';
        div.style.display = 'none';
        div.innerHTML = [
            '<div class="d-flex justify-content-between align-items-start gap-3">',
            '  <div><strong>数据库结构已更新</strong>',
            '  <div id="appVerDbBannerText" class="small mt-1"></div></div>',
            '  <div class="d-flex gap-2">',
            '    <button type="button" class="btn btn-sm btn-outline-secondary" id="appVerDbDetail">查看详情</button>',
            '    <button type="button" class="btn btn-sm btn-warning" id="appVerDbAck">知道了</button>',
            '  </div></div>'
        ].join('');
        document.body.appendChild(div);
        document.getElementById('appVerDbDetail').onclick = openChangelog;
        document.getElementById('appVerDbAck').onclick = function () {
            div.style.display = 'none';
            try {
                sessionStorage.setItem('dbUpdateAck_' + (versionInfo && versionInfo.version), '1');
            } catch (e) {}
            fetch('/api/version/ack-db-update', { method: 'POST' }).catch(function () {});
        };
    }

    function openChangelog() {
        ensureUi();
        document.getElementById('verModalBackdrop').classList.add('show');
        document.getElementById('verModal').classList.add('show');
        renderChangelogBody();
    }

    function closeChangelog() {
        var b = document.getElementById('verModalBackdrop');
        var m = document.getElementById('verModal');
        if (b) b.classList.remove('show');
        if (m) m.classList.remove('show');
    }

    function esc(s) {
        return String(s || '')
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function renderChangelogBody() {
        var body = document.getElementById('verModalBody');
        var cur = document.getElementById('verModalCurrent');
        if (!versionInfo) {
            body.innerHTML = '<div class="text-muted">暂无版本信息</div>';
            return;
        }
        cur.textContent = '当前 v' + versionInfo.version +
            '  · 前端 ' + versionInfo.frontendVersion +
            '  · 后端 ' + versionInfo.backendVersion +
            '  · DB schema ' + versionInfo.schemaVersion;

        var html = '';
        if (versionInfo.dbUpdated && versionInfo.dbChanges && versionInfo.dbChanges.length) {
            html += '<div class="ver-db"><strong>本次启动数据库变更</strong><ul>';
            versionInfo.dbChanges.forEach(function (c) { html += '<li>' + esc(c) + '</li>'; });
            html += '</ul></div>';
        }

        var releases = versionInfo.releases || [];
        if (!releases.length) {
            html += '<div class="text-muted">暂无更新记录</div>';
        } else {
            releases.forEach(function (r) {
                html += '<div class="ver-rel">';
                html += '<h6>v' + esc(r.version) + '</h6>';
                html += '<div class="meta">' + esc(r.date || '') +
                    (r.schemaVersion != null ? ' · schema ' + esc(r.schemaVersion) : '') + '</div>';
                if (r.dbChanges && r.dbChanges.length) {
                    html += '<div class="ver-db" style="margin-top:6px;"><strong>数据库变更</strong><ul>';
                    r.dbChanges.forEach(function (c) { html += '<li>' + esc(c) + '</li>'; });
                    html += '</ul></div>';
                }
                if (r.changes && r.changes.length) {
                    html += '<ul>';
                    r.changes.forEach(function (c) { html += '<li>' + esc(c) + '</li>'; });
                    html += '</ul>';
                }
                html += '</div>';
            });
        }
        body.innerHTML = html;
    }

    function applyVersionInfo(info) {
        versionInfo = info || {};
        ensureUi();
        var badge = document.getElementById('appVersionBadge');
        if (badge) {
            var v = versionInfo.version || FRONTEND_VERSION;
            badge.textContent = 'v' + v;
            if (versionInfo.frontendVersion && versionInfo.frontendVersion !== FRONTEND_VERSION) {
                badge.title = '前端静态版本 ' + FRONTEND_VERSION + ' 与配置 ' + versionInfo.frontendVersion + ' 不一致，请刷新或重新部署';
            }
        }
        maybeShowDbBanner();
    }

    function maybeShowDbBanner() {
        if (!versionInfo || !versionInfo.dbUpdated) return;
        var key = 'dbUpdateAck_' + versionInfo.version;
        try {
            if (sessionStorage.getItem(key)) return;
        } catch (e) {}
        var banner = document.getElementById('appVerDbBanner');
        var text = document.getElementById('appVerDbBannerText');
        if (!banner || !text) return;
        var from = versionInfo.previousSchemaVersion != null ? versionInfo.previousSchemaVersion : '?';
        var to = versionInfo.schemaVersion;
        var lines = (versionInfo.dbChanges || []).slice(0, 3).join('；');
        text.textContent = 'Schema ' + from + ' → ' + to + (lines ? '：' + lines : '') +
            (versionInfo.dbChanges && versionInfo.dbChanges.length > 3 ? '…' : '');
        banner.style.display = 'block';
    }

    function load() {
        ensureUi();
        fetch('/api/version')
            .then(function (r) { return r.json(); })
            .then(applyVersionInfo)
            .catch(function () {
                applyVersionInfo({
                    version: FRONTEND_VERSION,
                    frontendVersion: FRONTEND_VERSION,
                    backendVersion: FRONTEND_VERSION,
                    schemaVersion: 0,
                    dbUpdated: false,
                    releases: []
                });
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', load);
    } else {
        load();
    }

    global.AppVersion = {
        FRONTEND_VERSION: FRONTEND_VERSION,
        open: openChangelog,
        reload: load
    };
})(window);
