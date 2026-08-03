package com.gcoll.dbcompare.util;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.entity.DatabaseType;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 表名过滤：include / exclude 规则解析
 */
public final class TableFilterHelper {

    private TableFilterHelper() {
    }

    /**
     * 解析要比对的表列表
     * 支持三种模式：
     * 1. 单库: schema 为单个库名
     * 2. 多选: schema 为逗号分隔的多个库名，表名以 schema::table 格式返回
     * 3. 全库: schema 为空/null，枚举所有非系统库，表名以 schema::table 格式返回
     */
    public static List<String> resolveTables(
            Connection conn, String schema, DatabaseType dbType, CompareTask task) throws Exception {
        List<String> allTables = SchemaMappingHelper.resolveRawTables(conn, schema, dbType);
        return filterTables(allTables, task.getIncludeTables(), task.getExcludeTables());
    }

    public static List<String> resolveTables(
            Connection conn, String schema, DatabaseType dbType,
            String includeTables, String excludeTables) throws Exception {
        List<String> allTables = SchemaMappingHelper.resolveRawTables(conn, schema, dbType);
        return filterTables(allTables, includeTables, excludeTables);
    }

    /**
     * 对表名列表应用 include / exclude 规则
     */
    public static List<String> filterTables(List<String> allTables, CompareTask task) {
        return filterTables(allTables, task.getIncludeTables(), task.getExcludeTables());
    }

    public static List<String> filterTables(List<String> allTables, String includeTables, String excludeTables) {
        List<String> tables = new ArrayList<>(allTables);

        if (includeTables != null && !includeTables.trim().isEmpty()) {
            List<String> includePatterns = splitPatterns(includeTables);
            tables = tables.stream()
                .filter(name -> matchesAny(name, includePatterns))
                .collect(Collectors.toList());
            // 显式 schema::table / schema.table 列表在「单库裸表名」枚举下可能过滤为空：回退为规范化 include 键
            if (tables.isEmpty() && isExplicitTableList(includePatterns)) {
                tables = toQualifiedOrBareKeys(includePatterns, allTables);
            }
        }

        if (excludeTables != null && !excludeTables.trim().isEmpty()) {
            List<String> excludePatterns = splitPatterns(excludeTables);
            tables.removeIf(name -> matchesAny(name, excludePatterns));
        }

        return tables;
    }

    public static List<String> splitPatterns(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // 中文逗号/分号；* 视为 SQL LIKE 的 %
        String normalized = raw.replace('，', ',').replace('；', ';');
        return Arrays.stream(normalized.split("[,;\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(TableFilterHelper::normalizeWildcardPattern)
            .collect(Collectors.toList());
    }

    /** 将用户习惯的 * 通配规范为 SQL LIKE 的 % */
    static String normalizeWildcardPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return pattern;
        }
        return pattern.replace('*', '%');
    }

    /** 无 % 通配符的显式表名列表（一键重同步 / 勾选表场景；表名中的 _ 视为字面量） */
    static boolean isExplicitTableList(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String p : patterns) {
            if (p != null && p.contains("%")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 include 显式键转为可执行表名：优先保留 allTables 中已有形式（裸名或 schema::table）。
     */
    static List<String> toQualifiedOrBareKeys(List<String> includePatterns, List<String> allTables) {
        List<String> out = new ArrayList<>();
        for (String pattern : includePatterns) {
            if (pattern == null || pattern.trim().isEmpty()) {
                continue;
            }
            String key = normalizeTableKey(pattern);
            if (key.isEmpty() || "?".equals(key) || "unknown".equalsIgnoreCase(key)) {
                continue;
            }
            String matched = null;
            if (allTables != null) {
                for (String t : allTables) {
                    if (matchesAny(t, Collections.singletonList(pattern))) {
                        matched = t;
                        break;
                    }
                }
            }
            // 仅当枚举中能命中，或键本身带 schema:: / schema. 时才回退加入
            if (matched != null) {
                out.add(matched);
            } else if (key.contains("::")) {
                out.add(key);
            }
        }
        return out.stream().distinct().collect(Collectors.toList());
    }

    public static String normalizeTableKey(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim();
        if (s.isEmpty() || s.contains("::")) {
            return s;
        }
        int dot = s.indexOf('.');
        // 仅一个点时按 schema.table 规范化（报告展示名常见）
        if (dot > 0 && s.indexOf('.', dot + 1) < 0) {
            return s.substring(0, dot) + "::" + s.substring(dot + 1);
        }
        return s;
    }

    static String pureTableName(String name) {
        String n = normalizeTableKey(name);
        int idx = n.indexOf("::");
        return idx >= 0 ? n.substring(idx + 2) : n;
    }

    static boolean matchesAny(String tableName, List<String> patterns) {
        String full = normalizeTableKey(tableName);
        String pure = pureTableName(full);

        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            String pFull = normalizeTableKey(pattern);
            String pPure = pureTableName(pFull);
            // 全名 / 纯表名 / schema::t ↔ 裸 t / schema.t ↔ schema::t
            if (full.equalsIgnoreCase(pFull)
                    || pure.equalsIgnoreCase(pPure)
                    || full.equalsIgnoreCase(pPure)
                    || pure.equalsIgnoreCase(pFull)) {
                return true;
            }
            // 无 % 时不做 LIKE（表名中的 _ 是字面量，不是通配）
            if (!pFull.contains("%") && !pPure.contains("%") && !pattern.contains("%")) {
                continue;
            }
            // 模式仅含通配（如 % / %%）时匹配任意表
            if (isMatchAllWildcard(pFull) || isMatchAllWildcard(pPure)) {
                return true;
            }
            String regexFull = sqlWildcardToRegex(pFull);
            String regexPure = sqlWildcardToRegex(pPure);
            try {
                Pattern pf = Pattern.compile(regexFull, Pattern.CASE_INSENSITIVE);
                Pattern pp = Pattern.compile(regexPure, Pattern.CASE_INSENSITIVE);
                if (pf.matcher(full).matches() || pf.matcher(pure).matches()
                        || pp.matcher(full).matches() || pp.matcher(pure).matches()) {
                    return true;
                }
            } catch (PatternSyntaxException ignored) {
                // 非法模式忽略，不中断同步
            }
        }
        return false;
    }

    /** 是否为「匹配全部」通配（% / %% / 空段） */
    static boolean isMatchAllWildcard(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) != '%') {
                return false;
            }
        }
        return true;
    }

    /**
     * SQL LIKE 风格通配：% → .*，_ → .；其余字符按正则字面量转义。
     */
    static String sqlWildcardToRegex(String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') {
                sb.append(".*");
            } else if (c == '_') {
                sb.append('.');
            } else if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
