/**
 * 库名映射 UI 逻辑（比对/同步共用）
 * 库对库、表同名；系统库默认不参与映射
 */
(function(global) {
    var SYSTEM_SCHEMAS = ['information_schema', 'mysql', 'performance_schema', 'sys', '_gdb_sysdb'];

    function isSystemSchema(name) {
        if (!name) return true;
        var lower = String(name).toLowerCase();
        if (SYSTEM_SCHEMAS.indexOf(lower) >= 0) return true;
        return lower.indexOf('_gdb_') === 0;
    }

    function filterBusinessSchemas(names) {
        if (!names || !names.length) return [];
        return names.filter(function(n) { return !isSystemSchema(n); });
    }

    function suggestTargetSchema(srcName, tgtList, savedMap) {
        if (savedMap && savedMap[srcName]) return savedMap[srcName];
        if (!tgtList || !tgtList.length) return null;

        // 迁移场景优先 prd_/prod_ 前缀，避免目标上误选与源同名的库
        var prefixes = ['prd_', 'prod_', 'pro_'];
        for (var p = 0; p < prefixes.length; p++) {
            var preferred = prefixes[p] + srcName;
            if (tgtList.indexOf(preferred) >= 0) return preferred;
        }

        var best = null, bestScore = 0;
        for (var i = 0; i < tgtList.length; i++) {
            var tgt = tgtList[i];
            if (isSystemSchema(tgt)) continue;
            var srcParts = srcName.split('_');
            var tgtParts = tgt.split('_');
            var matchLen = 0;
            for (var si = srcParts.length - 1, ti = tgtParts.length - 1; si >= 0 && ti >= 0; si--, ti--) {
                if (srcParts[si] === tgtParts[ti]) matchLen++;
                else break;
            }
            if (matchLen > bestScore) {
                bestScore = matchLen;
                best = tgt;
            }
        }
        if (bestScore >= 2) return best;

        for (var j = 0; j < tgtList.length; j++) {
            if (isSystemSchema(tgtList[j])) continue;
            var tgtName = tgtList[j];
            if (tgtName.length > srcName.length && tgtName.substring(tgtName.length - srcName.length) === srcName) {
                return tgtName;
            }
            if (srcName.length > tgtName.length && srcName.substring(srcName.length - tgtName.length) === tgtName) {
                return tgtName;
            }
        }
        if (tgtList.indexOf(srcName) >= 0) return srcName;
        return null;
    }

    /** 按源/目标勾选列表自动生成 schemaMapping（面板隐藏时也能持久化） */
    function buildAutoSchemaMapping(srcList, tgtList, savedMapping) {
        var savedMap = parseSchemaMapping(savedMapping);
        var pairs = [];
        (srcList || []).forEach(function(src) {
            if (!src) return;
            var tgt = suggestTargetSchema(src, tgtList || [], savedMap);
            if (tgt) pairs.push(src + ':' + tgt);
        });
        return pairs.join(',');
    }

    function shouldShowSchemaMapping(srcChecked, tgtChecked, allSrc, allTgt) {
        var srcList = filterBusinessSchemas(srcChecked.length > 0 ? srcChecked : allSrc);
        var tgtList = filterBusinessSchemas(tgtChecked.length > 0 ? tgtChecked : allTgt);
        if (srcList.length <= 1 || tgtList.length <= 1) return false;

        for (var i = 0; i < srcList.length; i++) {
            if (tgtList.indexOf(srcList[i]) < 0) return true;
        }
        return false;
    }

    function renderSchemaMappingPanel(options) {
        var srcChecked = options.srcChecked || [];
        var tgtChecked = options.tgtChecked || [];
        var allSrc = options.allSrc || [];
        var allTgt = options.allTgt || [];
        var savedMapping = options.savedMapping || '';
        var panelSelector = options.panelSelector || '#schemaMappingPanel';
        var rowsSelector = options.rowsSelector || '#schemaMappingRows';
        var escHtml = options.escHtml || function(s) { return String(s || ''); };

        if (!shouldShowSchemaMapping(srcChecked, tgtChecked, allSrc, allTgt)) {
            $(panelSelector).hide();
            $(rowsSelector).empty();
            return;
        }

        var srcList = filterBusinessSchemas(srcChecked.length > 0 ? srcChecked : allSrc);
        var tgtList = filterBusinessSchemas(tgtChecked.length > 0 ? tgtChecked : allTgt);
        if (srcList.length <= 1 || tgtList.length <= 1) {
            $(panelSelector).hide();
            $(rowsSelector).empty();
            return;
        }

        var savedMap = {};
        if (savedMapping) {
            savedMapping.split(',').forEach(function(pair) {
                var parts = pair.split(':');
                if (parts.length === 2) savedMap[parts[0].trim()] = parts[1].trim();
            });
        }

        var html = '<div class="table-responsive"><table class="table table-sm mb-0" style="font-size:.82rem;">';
        html += '<thead><tr><th style="width:5%;">#</th><th>源库</th><th style="width:8%;">&#8594;</th><th>目标库</th></tr></thead><tbody>';
        for (var i = 0; i < srcList.length; i++) {
            var sel = suggestTargetSchema(srcList[i], tgtList, savedMap);
            var opts = '<option value="">-- 请选择目标库 --</option>';
            for (var j = 0; j < tgtList.length; j++) {
                opts += '<option value="' + escHtml(tgtList[j]) + '"' + (tgtList[j] === sel ? ' selected' : '') + '>' + escHtml(tgtList[j]) + '</option>';
            }
            html += '<tr><td>' + (i + 1) + '</td><td><span class="badge bg-primary">' + escHtml(srcList[i]) + '</span></td>';
            html += '<td class="text-center">&#8594;</td>';
            html += '<td><select class="form-select form-select-sm schema-map-sel" data-src="' + escHtml(srcList[i]) + '" style="font-size:.8rem;">' + opts + '</select></td></tr>';
        }
        html += '</tbody></table></div>';
        $(rowsSelector).html(html);
        $(panelSelector).show();
    }

    function getSchemaMapping(rowsSelector) {
        rowsSelector = rowsSelector || '#schemaMappingRows';
        var rows = $(rowsSelector + ' .schema-map-sel');
        if (rows.length === 0) return '';
        var pairs = [];
        rows.each(function() {
            var src = $(this).data('src');
            var tgt = $(this).val();
            if (src && tgt) pairs.push(src + ':' + tgt);
        });
        return pairs.join(',');
    }

    function getSchemaMappingDisplay(rowsSelector) {
        rowsSelector = rowsSelector || '#schemaMappingRows';
        var rows = $(rowsSelector + ' .schema-map-sel');
        if (rows.length === 0) return '无（同名库自动对应）';
        var pairs = [];
        rows.each(function() {
            var src = $(this).data('src');
            var tgt = $(this).val();
            if (src && tgt) pairs.push(src + ' → ' + tgt);
        });
        return pairs.length ? pairs.join('，') : '无';
    }

    function validateSchemaMapping(rowsSelector) {
        rowsSelector = rowsSelector || '#schemaMappingRows';
        var rows = $(rowsSelector + ' .schema-map-sel');
        if (rows.length === 0) return true;
        var ok = true;
        rows.each(function() {
            if (!$(this).val()) ok = false;
        });
        return ok;
    }

    function defaultSchemaChecked(name) {
        return !isSystemSchema(name);
    }

    function parseSchemaMapping(savedMapping) {
        var savedMap = {};
        if (!savedMapping) return savedMap;
        String(savedMapping).split(',').forEach(function(pair) {
            var parts = pair.split(':');
            if (parts.length === 2) {
                var s = parts[0].trim();
                var t = parts[1].trim();
                if (s && t) savedMap[s] = t;
            }
        });
        return savedMap;
    }

    /** 从 schema::table 列表提取源库名（去重保序） */
    function schemasFromTableKeys(tables) {
        var seen = {};
        var out = [];
        (tables || []).forEach(function(t) {
            var name = typeof t === 'string' ? t : (t && t.name ? t.name : '');
            if (!name || name.indexOf('::') < 0) return;
            var schema = name.split('::')[0].trim();
            if (schema && !seen[schema]) {
                seen[schema] = true;
                out.push(schema);
            }
        });
        return out;
    }

    /**
     * 按源库列表自动匹配目标库（优先已存映射，否则 suggestTargetSchema）
     */
    function matchTargetSchemas(srcSchemas, allTgt, savedMapping) {
        var savedMap = parseSchemaMapping(savedMapping);
        var seen = {};
        var out = [];
        (srcSchemas || []).forEach(function(src) {
            var tgt = suggestTargetSchema(src, allTgt || [], savedMap);
            if (tgt && !seen[tgt]) {
                seen[tgt] = true;
                out.push(tgt);
            }
        });
        return out;
    }

    global.SchemaMappingUi = {
        isSystemSchema: isSystemSchema,
        filterBusinessSchemas: filterBusinessSchemas,
        shouldShowSchemaMapping: shouldShowSchemaMapping,
        renderSchemaMappingPanel: renderSchemaMappingPanel,
        getSchemaMapping: getSchemaMapping,
        getSchemaMappingDisplay: getSchemaMappingDisplay,
        validateSchemaMapping: validateSchemaMapping,
        defaultSchemaChecked: defaultSchemaChecked,
        suggestTargetSchema: suggestTargetSchema,
        parseSchemaMapping: parseSchemaMapping,
        schemasFromTableKeys: schemasFromTableKeys,
        matchTargetSchemas: matchTargetSchemas,
        buildAutoSchemaMapping: buildAutoSchemaMapping
    };
})(window);
