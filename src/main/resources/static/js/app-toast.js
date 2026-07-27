/**
 * 应用内轻提示（替代浏览器原生 alert）
 * 用法：AppToast.show('已保存', 'success');
 *       AppToast.show('请至少选择一张表', 'danger');
 */
(function (global) {
    var STYLE_ID = 'appToastStyles';
    var ROOT_ID = 'appToastRoot';

    function ensureDom() {
        if (!document.getElementById(STYLE_ID)) {
            var css = document.createElement('style');
            css.id = STYLE_ID;
            css.textContent = [
                '#appToastRoot{position:fixed;top:20px;right:20px;z-index:10050;',
                'display:flex;flex-direction:column;gap:8px;pointer-events:none;',
                'max-width:min(420px,calc(100vw - 32px));}',
                '#appToastRoot .at-msg{padding:.85rem 1.15rem;border-radius:10px;color:#fff;',
                'font-size:.85rem;font-weight:600;line-height:1.45;box-shadow:0 12px 28px rgba(15,23,42,.18);',
                'pointer-events:auto;animation:atIn .32s cubic-bezier(.4,0,.2,1);word-break:break-word;}',
                '@keyframes atIn{from{transform:translateX(110%);opacity:0}to{transform:none;opacity:1}}',
                '@keyframes atOut{to{transform:translateX(110%);opacity:0}}',
                '#appToastRoot .at-msg.hiding{animation:atOut .28s ease forwards;}',
                '#appToastRoot .at-success{background:linear-gradient(135deg,#059669,#10b981);}',
                '#appToastRoot .at-danger{background:linear-gradient(135deg,#dc2626,#ef4444);}',
                '#appToastRoot .at-warning{background:linear-gradient(135deg,#d97706,#f59e0b);}',
                '#appToastRoot .at-info{background:linear-gradient(135deg,#4f46e5,#6366f1);}'
            ].join('');
            document.head.appendChild(css);
        }
        if (!document.getElementById(ROOT_ID)) {
            var root = document.createElement('div');
            root.id = ROOT_ID;
            root.setAttribute('aria-live', 'polite');
            document.body.appendChild(root);
        }
        return document.getElementById(ROOT_ID);
    }

    function show(msg, type, duration) {
        type = type || 'info';
        if (type === 'error') type = 'danger';
        if (type === 'warn') type = 'warning';
        duration = duration == null ? (type === 'danger' || type === 'warning' ? 4500 : 3000) : duration;
        var root = ensureDom();
        var el = document.createElement('div');
        el.className = 'at-msg at-' + type;
        el.textContent = msg == null ? '' : String(msg);
        root.appendChild(el);
        var timer = setTimeout(function () {
            el.classList.add('hiding');
            setTimeout(function () {
                if (el.parentNode) el.parentNode.removeChild(el);
            }, 280);
        }, Math.max(1200, duration));
        el.addEventListener('click', function () {
            clearTimeout(timer);
            el.classList.add('hiding');
            setTimeout(function () {
                if (el.parentNode) el.parentNode.removeChild(el);
            }, 280);
        });
        return el;
    }

    /** 将原生 alert 重定向到友好提示（无法拦截已内联的同步逻辑） */
    function patchNativeAlert() {
        if (global.__appToastAlertPatched) return;
        global.__appToastAlertPatched = true;
        global.alert = function (msg) {
            show(msg, 'info');
        };
    }

    global.AppToast = { show: show, patchNativeAlert: patchNativeAlert };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', patchNativeAlert);
    } else {
        patchNativeAlert();
    }
})(window);
