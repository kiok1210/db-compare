/**
 * 应用内确认框（替代浏览器原生 confirm，按钮文案与样式可控）
 * 用法：
 *   AppConfirm.open({
 *     title: '确认开始同步',
 *     message: '目标库将…',
 *     confirmText: '开始同步',
 *     cancelText: '再检查一下',
 *     danger: true,
 *     onConfirm: function(){ ... }
 *   });
 */
(function (global) {
    var STYLE_ID = 'appConfirmStyles';
    var ROOT_ID = 'appConfirmRoot';
    var pending = null;

    function ensureDom() {
        if (!document.getElementById(STYLE_ID)) {
            var css = document.createElement('style');
            css.id = STYLE_ID;
            css.textContent = [
                '#appConfirmRoot{position:fixed;inset:0;z-index:2000;display:none;align-items:center;justify-content:center;',
                'background:rgba(15,23,42,.48);padding:16px;}',
                '#appConfirmRoot.show{display:flex;}',
                '#appConfirmRoot .ac-dialog{width:min(440px,100%);background:#fff;border-radius:12px;',
                'box-shadow:0 20px 50px rgba(0,0,0,.22);overflow:hidden;animation:acIn .18s ease-out;}',
                '@keyframes acIn{from{opacity:0;transform:translateY(8px) scale(.98)}to{opacity:1;transform:none}}',
                '#appConfirmRoot .ac-hd{padding:16px 18px 8px;display:flex;gap:10px;align-items:flex-start;}',
                '#appConfirmRoot .ac-icon{width:36px;height:36px;border-radius:10px;display:flex;align-items:center;',
                'justify-content:center;font-size:1.1rem;flex-shrink:0;}',
                '#appConfirmRoot .ac-icon.warn{background:#fff7ed;color:#c2410c;}',
                '#appConfirmRoot .ac-icon.danger{background:#fef2f2;color:#dc2626;}',
                '#appConfirmRoot .ac-icon.info{background:#eef2ff;color:#4338ca;}',
                '#appConfirmRoot .ac-title{margin:0;font-size:1.05rem;font-weight:700;color:#0f172a;line-height:1.35;}',
                '#appConfirmRoot .ac-bd{padding:4px 18px 16px 64px;font-size:.88rem;color:#475569;line-height:1.55;}',
                '#appConfirmRoot .ac-ft{padding:12px 16px;background:#f8fafc;border-top:1px solid #e2e8f0;',
                'display:flex;justify-content:flex-end;gap:10px;flex-wrap:wrap;}',
                '#appConfirmRoot .ac-btn{border:0;border-radius:8px;padding:.5rem 1rem;font-size:.86rem;',
                'font-weight:600;cursor:pointer;min-width:96px;}',
                '#appConfirmRoot .ac-btn:focus{outline:2px solid #c7d2fe;outline-offset:1px;}',
                '#appConfirmRoot .ac-btn.cancel{background:#fff;color:#334155;border:1px solid #cbd5e1;}',
                '#appConfirmRoot .ac-btn.cancel:hover{background:#f1f5f9;}',
                '#appConfirmRoot .ac-btn.ok{background:#4f46e5;color:#fff;}',
                '#appConfirmRoot .ac-btn.ok:hover{background:#4338ca;}',
                '#appConfirmRoot .ac-btn.ok.danger{background:#dc2626;}',
                '#appConfirmRoot .ac-btn.ok.danger:hover{background:#b91c1c;}'
            ].join('');
            document.head.appendChild(css);
        }
        if (!document.getElementById(ROOT_ID)) {
            var root = document.createElement('div');
            root.id = ROOT_ID;
            root.setAttribute('role', 'dialog');
            root.setAttribute('aria-modal', 'true');
            root.innerHTML =
                '<div class="ac-dialog">' +
                '  <div class="ac-hd"><div class="ac-icon warn" id="acIcon">!</div>' +
                '    <h5 class="ac-title" id="acTitle">确认</h5></div>' +
                '  <div class="ac-bd" id="acMessage"></div>' +
                '  <div class="ac-ft">' +
                '    <button type="button" class="ac-btn cancel" id="acCancel">取消</button>' +
                '    <button type="button" class="ac-btn ok" id="acOk">确定</button>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(root);
            root.addEventListener('click', function (e) {
                if (e.target === root) close(false);
            });
            document.getElementById('acCancel').addEventListener('click', function () { close(false); });
            document.getElementById('acOk').addEventListener('click', function () { close(true); });
            document.addEventListener('keydown', function (e) {
                if (!pending) return;
                if (e.key === 'Escape') close(false);
            });
        }
    }

    function close(confirmed) {
        var root = document.getElementById(ROOT_ID);
        if (root) root.classList.remove('show');
        var cb = pending;
        pending = null;
        if (confirmed && cb && typeof cb.onConfirm === 'function') {
            cb.onConfirm();
        } else if (!confirmed && cb && typeof cb.onCancel === 'function') {
            cb.onCancel();
        }
    }

    function open(opts) {
        opts = opts || {};
        ensureDom();
        pending = opts;
        var danger = !!opts.danger;
        var tone = opts.tone || (danger ? 'danger' : 'warn');
        var icon = document.getElementById('acIcon');
        icon.className = 'ac-icon ' + tone;
        icon.textContent = tone === 'danger' ? '!' : (tone === 'info' ? 'i' : '!');
        document.getElementById('acTitle').textContent = opts.title || '请确认';
        var msgEl = document.getElementById('acMessage');
        if (opts.html) {
            msgEl.innerHTML = opts.html;
        } else {
            msgEl.textContent = opts.message || '';
        }
        var ok = document.getElementById('acOk');
        var cancel = document.getElementById('acCancel');
        ok.textContent = opts.confirmText || '确定';
        cancel.textContent = opts.cancelText || '取消';
        ok.className = 'ac-btn ok' + (danger ? ' danger' : '');
        // 危险操作：取消按钮更醒目靠左、确定用警示色
        document.getElementById(ROOT_ID).classList.add('show');
        cancel.focus();
    }

    global.AppConfirm = { open: open, close: close };
})(window);
