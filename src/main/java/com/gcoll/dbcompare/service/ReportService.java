package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.entity.SyncTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * 报告生成服务
 * 支持生成 Markdown 和 HTML 格式的报告，集成 ECharts 可视化
 */
@Service
@Slf4j
public class ReportService {
    
    /**
     * 生成比对报告（Markdown + HTML + ECharts）
     */
    public String generateReport(CompareTask task, Map<String, Object> compareResult) throws IOException {
        String reportDir = "reports/" + task.getId() + "/";
        Path reportPath = Paths.get(reportDir);
        if (!reportPath.toFile().exists()) {
            reportPath.toFile().mkdirs();
        }
        
        // 统计比对结果
        Map<String, Object> stats = calculateStats(compareResult);
        
        // 1. 生成 Markdown 报告
        String markdownContent = generateMarkdownReport(task, compareResult, stats);
        String markdownFile = reportDir + "report.md";
        writeFile(markdownFile, markdownContent);
        
        // 2. 生成 HTML 报告（带 ECharts）
        String htmlContent = generateHtmlReport(task, compareResult, stats);
        String htmlFile = reportDir + "report.html";
        writeFile(htmlFile, htmlContent);
        
        log.info("报告生成成功: {}", htmlFile);
        return htmlFile;
    }

    /**
     * 生成同步报告（Markdown + HTML）。
     * successRows / failedRows 元素为字段 Map（displayName、tableIndex、rowCount、statusLabel、error 等）。
     */
    public String generateSyncReport(SyncTask task,
                                     List<Map<String, Object>> successRows,
                                     List<Map<String, Object>> failedRows) throws IOException {
        String reportDir = "reports/sync/" + task.getId() + "/";
        Path dir = Paths.get(reportDir);
        Files.createDirectories(dir);

        List<Map<String, Object>> success = successRows != null ? successRows : new ArrayList<>();
        List<Map<String, Object>> failed = failedRows != null ? failedRows : new ArrayList<>();

        String md = buildSyncMarkdown(task, success, failed);
        Files.write(dir.resolve("report.md"), md.getBytes(StandardCharsets.UTF_8));

        String html = buildSyncHtml(task, success, failed);
        Path htmlPath = dir.resolve("report.html");
        Files.write(htmlPath, html.getBytes(StandardCharsets.UTF_8));

        String htmlFile = reportDir + "report.html";
        log.info("同步报告生成成功: {}", htmlFile);
        return htmlFile;
    }

    private String buildSyncMarkdown(SyncTask task, List<Map<String, Object>> success, List<Map<String, Object>> failed) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("# 同步报告 — ").append(nullToEmpty(task.getTaskName())).append("\n\n");
        sb.append("- 任务 ID: ").append(task.getId()).append("\n");
        sb.append("- 状态: ").append(nullToEmpty(task.getStatus())).append("\n");
        sb.append("- 模式: ").append(SyncTask.modeDisplayName(task.getSyncMode())).append("\n");
        sb.append("- 并行: ").append(SyncTask.clampParallelThreads(task.getParallelThreads())).append("\n");
        sb.append("- 源库: ").append(formatSchemaDisplay(task.getSourceSchema())).append("\n");
        sb.append("- 目标库: ").append(formatSchemaDisplay(task.getTargetSchema())).append("\n");
        sb.append("- 开始: ").append(task.getStartTime() != null ? task.getStartTime().format(fmt) : "-").append("\n");
        sb.append("- 结束: ").append(task.getEndTime() != null ? task.getEndTime().format(fmt) : "-").append("\n");
        sb.append("- 耗时: ").append(formatSyncDuration(task)).append("\n");
        sb.append("- 成功/待重同步/总表: ")
                .append(nz(task.getSuccessTables())).append(" / ")
                .append(nz(task.getFailedTablesCount())).append(" / ")
                .append(nz(task.getTotalTables())).append("\n\n");
        if (task.getErrorMessage() != null && !task.getErrorMessage().isEmpty()) {
            sb.append("## 摘要\n\n").append(task.getErrorMessage()).append("\n\n");
        }
        sb.append("## 待重同步表 (").append(failed.size()).append(")\n\n");
        if (failed.isEmpty()) {
            sb.append("无\n\n");
        } else {
            sb.append("| # | 表 | 状态 | 阶段 | 行数 | 错误 |\n|---|---|---|---|---|---|\n");
            for (Map<String, Object> r : failed) {
                sb.append("| ").append(r.getOrDefault("tableIndex", ""))
                        .append(" | ").append(r.getOrDefault("displayName", r.get("table")))
                        .append(" | ").append(r.getOrDefault("statusLabel", r.get("status")))
                        .append(" | ").append(r.getOrDefault("phase", ""))
                        .append(" | ").append(r.getOrDefault("rowCount", 0))
                        .append(" | ").append(String.valueOf(r.getOrDefault("error", "")).replace("|", "\\|").replace("\n", " "))
                        .append(" |\n");
            }
            sb.append("\n");
        }
        sb.append("## 成功表 (").append(success.size()).append(")\n\n");
        if (success.isEmpty()) {
            sb.append("（仅统计成功数 ").append(nz(task.getSuccessTables())).append("，明细未保留）\n");
        } else {
            sb.append("| # | 表 | 行数 |\n|---|---|---|\n");
            for (Map<String, Object> r : success) {
                sb.append("| ").append(r.getOrDefault("tableIndex", ""))
                        .append(" | ").append(r.getOrDefault("displayName", r.get("table")))
                        .append(" | ").append(r.getOrDefault("rowCount", 0))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }

    private String buildSyncHtml(SyncTask task, List<Map<String, Object>> success, List<Map<String, Object>> failed) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int ok = nz(task.getSuccessTables());
        int fail = nz(task.getFailedTablesCount());
        int total = nz(task.getTotalTables());
        String status = nullToEmpty(task.getStatus());
        String statusClass = "SUCCESS".equals(status) ? "ok"
                : ("PARTIAL".equals(status) ? "partial" : "fail");

        StringBuilder failRows = new StringBuilder();
        if (failed.isEmpty()) {
            failRows.append("<tr><td colspan=\"7\" class=\"muted\">无待重同步表</td></tr>");
        } else {
            for (Map<String, Object> r : failed) {
                String tableKey = String.valueOf(r.getOrDefault("table", ""));
                failRows.append("<tr>")
                        .append("<td><input type=\"checkbox\" class=\"fail-chk\" value=\"")
                        .append(esc(tableKey)).append("\" checked></td>")
                        .append("<td>").append(esc(r.get("tableIndex"))).append("</td>")
                        .append("<td>").append(esc(r.getOrDefault("displayName", r.get("table")))).append("</td>")
                        .append("<td><span class=\"pill\">").append(esc(r.getOrDefault("statusLabel", r.get("status")))).append("</span></td>")
                        .append("<td>").append(esc(r.get("phase"))).append("</td>")
                        .append("<td>").append(esc(r.getOrDefault("rowCount", 0))).append("</td>")
                        .append("<td class=\"err\">").append(esc(r.get("error"))).append("</td>")
                        .append("</tr>");
            }
        }

        String resyncBar = "";
        if (!failed.isEmpty()) {
            resyncBar = "<div class=\"toolbar\" id=\"resyncBar\">"
                    + "<div class=\"toolbar-left\">"
                    + "<button type=\"button\" class=\"btn ghost\" onclick=\"toggleFailAll(true)\">全选</button>"
                    + "<button type=\"button\" class=\"btn ghost\" onclick=\"toggleFailAll(false)\">全不选</button>"
                    + "<span class=\"muted\" id=\"selCount\">已选 " + failed.size() + " / " + failed.size() + "</span>"
                    + "</div>"
                    + "<button type=\"button\" class=\"btn primary\" id=\"resyncFailedBtn\" onclick=\"resyncFailed()\">"
                    + "提取失败表并去确认</button>"
                    + "</div>"
                    + "<div class=\"box tip\" id=\"resyncMsg\" style=\"display:none;\"></div>";
        }

        StringBuilder okRows = new StringBuilder();
        if (success.isEmpty()) {
            okRows.append("<tr><td colspan=\"3\" class=\"muted\">成功 ").append(ok)
                    .append(" 张（历史任务可能无明细）</td></tr>");
        } else {
            for (Map<String, Object> r : success) {
                okRows.append("<tr>")
                        .append("<td>").append(esc(r.get("tableIndex"))).append("</td>")
                        .append("<td>").append(esc(r.getOrDefault("displayName", r.get("table")))).append("</td>")
                        .append("<td>").append(esc(r.getOrDefault("rowCount", 0))).append("</td>")
                        .append("</tr>");
            }
        }

        String errBlock = "";
        if (task.getErrorMessage() != null && !task.getErrorMessage().trim().isEmpty()) {
            errBlock = "<div class=\"box warn\"><strong>摘要</strong><pre>" + esc(task.getErrorMessage()) + "</pre></div>";
        }

        String srcSchema = formatSchemaDisplay(task.getSourceSchema());
        String tgtSchema = formatSchemaDisplay(task.getTargetSchema());

        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>同步报告 #" + task.getId() + " - 数据库比对工具</title>"
                + "<link href=\"/css/bootstrap.min.css\" rel=\"stylesheet\">"
                + "<link href=\"/css/app.css\" rel=\"stylesheet\">"
                + "<style>"
                + ":root{--primary:#4f46e5;--success:#059669;--danger:#dc2626;--warning:#f59e0b;"
                + "--gray:#6b7280;--radius:12px;--shadow:0 1px 3px rgba(0,0,0,.08);"
                + "--shadow-md:0 4px 6px rgba(0,0,0,.07),0 2px 4px rgba(0,0,0,.06);}"
                + "body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;background:#f1f5f9;color:#1e293b;margin:0;}"
                + ".rpt-wrap{max-width:1140px;margin:0 auto;padding:24px 20px 48px;}"
                + ".rpt-header{background:linear-gradient(135deg,#4f46e5,#6366f1);color:#fff;padding:28px 32px;"
                + "border-radius:var(--radius);margin-bottom:24px;box-shadow:var(--shadow-md);}"
                + ".rpt-header h1{margin:0 0 8px;font-size:1.5rem;font-weight:700;}"
                + ".rpt-header .meta{opacity:.9;font-size:.82rem;display:flex;gap:16px;flex-wrap:wrap;}"
                + ".db-info{display:inline-flex;padding:2px 8px;border-radius:4px;font-size:.75rem;background:rgba(255,255,255,.15);}"
                + ".stat-row{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-bottom:24px;}"
                + ".stat-card{background:#fff;border-radius:var(--radius);padding:20px 24px;box-shadow:var(--shadow);"
                + "text-align:center;border-left:4px solid var(--primary);}"
                + ".stat-card.sc-success{border-left-color:var(--success);} .stat-card.sc-danger{border-left-color:var(--danger);}"
                + ".stat-card .sc-num{font-size:2rem;font-weight:800;line-height:1.1;}"
                + ".stat-card .sc-label{font-size:.78rem;color:var(--gray);margin-top:4px;font-weight:600;}"
                + ".sc-num.c-primary{color:var(--primary);} .sc-num.c-success{color:var(--success);} .sc-num.c-danger{color:var(--danger);}"
                + ".meta-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;margin-bottom:20px;}"
                + ".meta-card{background:#fff;border-radius:var(--radius);padding:14px 16px;box-shadow:var(--shadow);}"
                + ".meta-card .label{font-size:.72rem;color:var(--gray);} .meta-card .val{font-size:.92rem;font-weight:600;margin-top:4px;}"
                + "h2.sec{font-size:1.05rem;font-weight:700;margin:28px 0 12px;}"
                + ".badge{display:inline-block;padding:.2rem .55rem;border-radius:999px;font-size:.75rem;font-weight:700;vertical-align:middle;}"
                + ".badge.ok{background:#d1fae5;color:#065f46;} .badge.partial{background:#fef3c7;color:#92400e;} .badge.fail{background:#fee2e2;color:#991b1b;}"
                + ".tbl-card{background:#fff;border-radius:var(--radius);box-shadow:var(--shadow);overflow:auto;padding:0;}"
                + "table{width:100%;border-collapse:collapse;}"
                + "th,td{padding:10px 12px;border-bottom:1px solid #eef2f7;font-size:.85rem;text-align:left;vertical-align:top;}"
                + "th{background:#f8fafc;color:#475569;font-weight:600;} .err{color:#b91c1c;word-break:break-all;}"
                + ".muted{color:#9ca3af;} .pill{background:#f3f4f6;padding:.1rem .4rem;border-radius:4px;font-size:.72rem;}"
                + ".box.warn{background:#fff7ed;border:1px solid #fed7aa;border-radius:var(--radius);padding:12px 14px;margin:12px 0;}"
                + ".box.tip{background:#eef2ff;border:1px solid #c7d2fe;border-radius:var(--radius);padding:12px 14px;margin:12px 0;font-size:.85rem;}"
                + "pre{white-space:pre-wrap;word-break:break-word;margin:8px 0 0;font-size:.8rem;}"
                + ".toolbar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;"
                + "margin:0 0 12px;padding:12px 14px;background:#fff;border-radius:var(--radius);box-shadow:var(--shadow);}"
                + ".toolbar-left{display:flex;flex-wrap:wrap;gap:8px;align-items:center;}"
                + ".btn{border:0;border-radius:8px;padding:.45rem .85rem;font-size:.82rem;font-weight:600;cursor:pointer;}"
                + ".btn:disabled{opacity:.55;cursor:not-allowed;}"
                + ".btn.primary{background:#4f46e5;color:#fff;} .btn.primary:hover{background:#4338ca;}"
                + ".btn.ghost{background:#f3f4f6;color:#374151;} .btn.ghost:hover{background:#e5e7eb;}"
                + "@media(max-width:768px){.stat-row{grid-template-columns:1fr;}}"
                + "</style></head><body>"
                + "<nav class=\"navbar navbar-expand-lg navbar-dark\" style=\"background:linear-gradient(135deg,#4f46e5,#6366f1);\">"
                + "<div class=\"app-container d-flex align-items-center justify-content-between w-100\">"
                + "<a class=\"navbar-brand\" href=\"/\">&#9670; 数据库比对工具</a>"
                + "<div class=\"d-flex align-items-center gap-1\">"
                + "<a class=\"nav-link text-white\" href=\"/datasource/list\">数据源管理</a>"
                + "<a class=\"nav-link text-white\" href=\"/compare/config\">比对任务</a>"
                + "<a class=\"nav-link text-white\" href=\"/sync/list\">同步任务</a>"
                + "<a class=\"nav-link text-white active\" href=\"/report/list?tab=sync\">比对报告</a>"
                + "<a class=\"nav-link text-white\" href=\"/viewer\">数据浏览</a>"
                + "<a class=\"nav-link text-white\" href=\"/cleanup\">C盘清理</a>"
                + "<a class=\"nav-link text-white\" href=\"/settings\">参数设置</a>"
                + "</div></div></nav>"
                + "<div class=\"rpt-wrap\">"
                + "<div class=\"mb-3\"><a class=\"back-link\" href=\"/report/list?tab=sync\">&#8592; 返回报告列表</a>"
                + " &nbsp;·&nbsp; <a href=\"/sync/progress/" + task.getId() + "\">任务详情</a></div>"
                + "<div class=\"rpt-header\">"
                + "<h1>同步报告 — " + esc(task.getTaskName()) + " "
                + "<span class=\"badge " + statusClass + "\">" + esc(status) + "</span></h1>"
                + "<div class=\"meta\">"
                + "<span>任务 #" + task.getId() + "</span>"
                + "<span><span class=\"db-info\">源库: " + esc(srcSchema) + "</span></span>"
                + "<span><span class=\"db-info\">目标库: " + esc(tgtSchema) + "</span></span>"
                + "<span>模式: " + esc(SyncTask.modeDisplayName(task.getSyncMode())) + "</span>"
                + "<span>并行: " + SyncTask.clampParallelThreads(task.getParallelThreads()) + "</span>"
                + "<span>耗时: " + formatSyncDuration(task) + "</span>"
                + "</div></div>"
                + "<div class=\"stat-row\">"
                + "<div class=\"stat-card sc-success\"><div class=\"sc-num c-success\">" + ok + "</div><div class=\"sc-label\">成功表</div></div>"
                + "<div class=\"stat-card sc-danger\"><div class=\"sc-num c-danger\">" + fail + "</div><div class=\"sc-label\">待重同步</div></div>"
                + "<div class=\"stat-card\"><div class=\"sc-num c-primary\">" + total + "</div><div class=\"sc-label\">总表数</div></div>"
                + "</div>"
                + "<div class=\"meta-grid\">"
                + metaCard("开始时间", task.getStartTime() != null ? task.getStartTime().format(fmt) : "-")
                + metaCard("结束时间", task.getEndTime() != null ? task.getEndTime().format(fmt) : "-")
                + metaCard("批次大小", String.valueOf(task.getBatchSize() != null ? task.getBatchSize() : "-"))
                + metaCard("生成时间", LocalDateTime.now().format(fmt))
                + "</div>"
                + errBlock
                + "<h2 class=\"sec\">待重同步表 (" + failed.size() + ")</h2>"
                + resyncBar
                + "<div class=\"tbl-card\"><table><thead><tr>"
                + "<th style=\"width:40px\"></th><th style=\"width:50px\">#</th><th>表</th><th>状态</th><th>阶段</th><th>行数</th><th>错误</th>"
                + "</tr></thead><tbody>" + failRows + "</tbody></table></div>"
                + "<h2 class=\"sec\">成功表 (" + (success.isEmpty() ? ok : success.size()) + ")</h2>"
                + "<div class=\"tbl-card\"><table><thead><tr>"
                + "<th style=\"width:50px\">#</th><th>表</th><th>同步行数</th>"
                + "</tr></thead><tbody>" + okRows + "</tbody></table></div>"
                + "<p class=\"muted\" style=\"margin-top:24px;font-size:.75rem;\">db-compare 同步报告</p>"
                + "</div>"
                + buildSyncReportScript(task.getId(), failed.size())
                + "<script src=\"/js/app-version.js\"></script>"
                + "</body></html>";
    }

    private String buildSyncReportScript(Long taskId, int failedCount) {
        if (failedCount <= 0) {
            return "";
        }
        return "<script src=\"/js/app-toast.js\"></script>"
                + "<script src=\"/js/confirm-dialog.js\"></script>"
                + "<script>"
                + "var TASK_ID=" + taskId + ";"
                + "function qs(s){return document.querySelectorAll(s);}"
                + "function updateSelCount(){"
                + "var all=qs('.fail-chk'),n=0;all.forEach(function(c){if(c.checked)n++;});"
                + "var el=document.getElementById('selCount');"
                + "if(el)el.textContent='已选 '+n+' / '+all.length;"
                + "}"
                + "function toggleFailAll(on){qs('.fail-chk').forEach(function(c){c.checked=!!on;});updateSelCount();}"
                + "document.addEventListener('change',function(e){if(e.target&&e.target.classList.contains('fail-chk'))updateSelCount();});"
                + "function showMsg(t,ok){var m=document.getElementById('resyncMsg');if(!m)return;"
                + "m.style.display='block';m.textContent=t;m.style.background=ok?'#ecfdf5':'#fff7ed';"
                + "m.style.borderColor=ok?'#a7f3d0':'#fed7aa';"
                + "if(window.AppToast)AppToast.show(t,ok?'success':'warning');}"
                + "function doResync(tables){"
                + "var btn=document.getElementById('resyncFailedBtn');if(btn){btn.disabled=true;btn.textContent='正在创建…';}"
                + "fetch('/sync/resync-failed/'+TASK_ID,{method:'POST',"
                + "headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({tables:tables})})"
                + ".then(function(r){return r.json();})"
                + ".then(function(res){"
                + "if(res&&res.success&&res.taskId){"
                + "showMsg((res.msg||'已创建待确认任务')+'，正在跳转…',true);"
                + "var url=res.confirmUrl||('/sync/confirm/'+res.taskId);"
                + "setTimeout(function(){location.href=url;},500);"
                + "}else{"
                + "showMsg((res&&res.msg)?res.msg:'创建失败',false);"
                + "if(btn){btn.disabled=false;btn.textContent='提取失败表并去确认';}"
                + "}})"
                + ".catch(function(e){"
                + "showMsg('请求失败: '+(e&&e.message?e.message:e),false);"
                + "if(btn){btn.disabled=false;btn.textContent='提取失败表并去确认';}"
                + "});"
                + "}"
                + "function resyncFailed(){"
                + "var tables=[];qs('.fail-chk').forEach(function(c){if(c.checked&&c.value)tables.push(c.value);});"
                + "if(!tables.length){showMsg('请至少选择一张待重同步表',false);return;}"
                + "if(window.AppConfirm){"
                + "AppConfirm.open({title:'提取失败表并新建任务',"
                + "html:'将提取所选 <strong>'+tables.length+'</strong> 张表，创建<strong>待确认</strong>同步任务（不会立即执行）。',"
                + "confirmText:'去确认页',cancelText:'取消',tone:'info',"
                + "onConfirm:function(){doResync(tables);}});return;}"
                + "doResync(tables);"
                + "}"
                + "</script>";
    }

    private static String metaCard(String label, String value) {
        return "<div class=\"meta-card\"><div class=\"label\">" + label + "</div><div class=\"val\">"
                + (value == null ? "-" : value) + "</div></div>";
    }

    private String formatSyncDuration(SyncTask task) {
        if (task.getStartTime() == null || task.getEndTime() == null) {
            return "-";
        }
        long sec = Math.max(0, Duration.between(task.getStartTime(), task.getEndTime()).getSeconds());
        return formatDuration(sec);
    }

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * 计算统计信息（支持多维度比对结果）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> calculateStats(Map<String, Object> compareResult) {
        int consistentCount = 0;
        int inconsistentCount = 0;
        int skippedCount = 0;
        int countDiffCount = 0;
        int amountDiffCount = 0;
        int hashDiffCount = 0;
        int indexDiffCount = 0;
        List<String> inconsistentTables = new ArrayList<>();
        List<String> skippedTables = new ArrayList<>();
        List<String> consistentTableList = new ArrayList<>();

        // 按维度收集详情
        List<Map<String, Object>> countDiffTables = new ArrayList<>();
        List<Map<String, Object>> amountDiffTables = new ArrayList<>();
        List<Map<String, Object>> hashDiffTables = new ArrayList<>();
        List<Map<String, Object>> indexDiffTables = new ArrayList<>();
        // 全部表（含条数一致），供条数卡片在无差异时仍可打开列表做详细比对
        List<Map<String, Object>> allTables = new ArrayList<>();

        // 提取函数/过程比对结果
        boolean functionConsistent = true;
        boolean procedureConsistent = true;

        for (Map.Entry<String, Object> entry : compareResult.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> tableResult = (Map<String, Object>) entry.getValue();

            // 跳过全局结果键
            if ("__functions__".equals(key)) {
                functionConsistent = isRoutineConsistent(tableResult);
                continue;
            }
            if ("__procedures__".equals(key)) {
                procedureConsistent = isRoutineConsistent(tableResult);
                continue;
            }

            Boolean consistent = (Boolean) tableResult.get("consistent");

            // 全表清单（无论是否一致），便于条数一致时下钻详细比对
            Map<String, Object> allRow = new LinkedHashMap<>();
            allRow.put("table", key);
            Object countObjAll = tableResult.get("count");
            if (countObjAll instanceof Map) {
                Map<String, Object> cmAll = (Map<String, Object>) countObjAll;
                allRow.put("sourceCount", cmAll.get("sourceCount"));
                allRow.put("targetCount", cmAll.get("targetCount"));
                allRow.put("diff", cmAll.get("diff"));
                allRow.put("countConsistent", cmAll.get("consistent"));
            } else {
                allRow.put("sourceCount", "-");
                allRow.put("targetCount", "-");
                allRow.put("diff", 0);
                allRow.put("countConsistent", true);
            }
            allRow.put("consistent", consistent);
            allTables.add(allRow);

            if (consistent != null && consistent) {
                consistentCount++;
                consistentTableList.add(key);
            } else if (tableResult.containsKey("error")) {
                skippedCount++;
                skippedTables.add(key);
            } else {
                inconsistentCount++;
                inconsistentTables.add(key);

                // 收集条数差异详情
                Object countObj = tableResult.get("count");
                if (countObj instanceof Map) {
                    Map<String, Object> cm = (Map<String, Object>) countObj;
                    Boolean c = (Boolean) cm.get("consistent");
                    if (c != null && !c) {
                        countDiffCount++;
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("table", key);
                        detail.put("sourceCount", cm.get("sourceCount"));
                        detail.put("targetCount", cm.get("targetCount"));
                        detail.put("diff", cm.get("diff"));
                        countDiffTables.add(detail);
                    }
                }

                // 收集金额差异详情
                Object amountObj = tableResult.get("amount");
                if (amountObj instanceof Map) {
                    Map<String, Object> am = (Map<String, Object>) amountObj;
                    Boolean a = (Boolean) am.get("consistent");
                    if (a != null && !a) {
                        amountDiffCount++;
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("table", key);
                        detail.put("diffFields", am.get("diffFields"));
                        amountDiffTables.add(detail);
                    }
                }

                // 收集 Hash 差异详情（超大表跳过不算差异）
                Object hashObj = tableResult.get("rowHash");
                if (hashObj instanceof Map) {
                    Map<String, Object> hm = (Map<String, Object>) hashObj;
                    if (!Boolean.TRUE.equals(hm.get("skipped"))) {
                        Boolean h = (Boolean) hm.get("consistent");
                        if (h != null && !h) {
                            hashDiffCount++;
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("table", key);
                            detail.put("sourceHash", hm.get("sourceHash"));
                            detail.put("targetHash", hm.get("targetHash"));
                            hashDiffTables.add(detail);
                        }
                    }
                }

                // 收集索引差异详情
                Object idxObj = tableResult.get("indexes");
                if (idxObj instanceof Map) {
                    Map<String, Object> im = (Map<String, Object>) idxObj;
                    Boolean idx = (Boolean) im.get("consistent");
                    if (idx != null && !idx) {
                        indexDiffCount++;
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("table", key);
                        detail.put("diffs", im.get("diffs"));
                        indexDiffTables.add(detail);
                    }
                }
            }
        }
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("consistentCount", consistentCount);
        stats.put("inconsistentCount", inconsistentCount);
        stats.put("skippedCount", skippedCount);
        stats.put("countDiffCount", countDiffCount);
        stats.put("amountDiffCount", amountDiffCount);
        stats.put("hashDiffCount", hashDiffCount);
        stats.put("indexDiffCount", indexDiffCount);
        stats.put("functionConsistent", functionConsistent);
        stats.put("procedureConsistent", procedureConsistent);
        stats.put("inconsistentTables", inconsistentTables);
        stats.put("consistentTableList", consistentTableList);
        stats.put("skippedTables", skippedTables);
        stats.put("countDiffTables", countDiffTables);
        stats.put("amountDiffTables", amountDiffTables);
        stats.put("hashDiffTables", hashDiffTables);
        stats.put("indexDiffTables", indexDiffTables);
        stats.put("allTables", allTables);
        
        return stats;
    }

    @SuppressWarnings("unchecked")
    private boolean isRoutineConsistent(Map<String, Object> routineResult) {
        if (routineResult == null) return true;
        Boolean c = (Boolean) routineResult.get("consistent");
        return c == null || c;
    }
    
    /**
     * 生成 Markdown 格式报告（多维度比对结果）
     */
    @SuppressWarnings("unchecked")
    private String generateMarkdownReport(CompareTask task, Map<String, Object> compareResult, 
                                           Map<String, Object> stats) {
        StringBuilder md = new StringBuilder();
        
        // 标题
        md.append("# 数据库比对报告\n\n");
        md.append("**生成时间**: ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        
        // 任务信息
        md.append("## 任务信息\n\n");
        md.append("- **任务名称**: ").append(task.getTaskName()).append("\n");
        md.append("- **比对模式**: ").append(task.getCompareMode()).append("\n");
        md.append("- **开始时间**: ").append(task.getStartTime()).append("\n");
        md.append("- **结束时间**: ").append(task.getEndTime()).append("\n");
        md.append("- **状态**: ").append(task.getStatus()).append("\n\n");
        
        // 比对结果概览
        md.append("## 比对结果概览\n\n");
        md.append("| 指标 | 数值 |\n");
        md.append("|------|------|\n");
        md.append("| 总表数 | ").append(task.getTotalTables()).append(" |\n");
        md.append("| 一致表数 | ").append(stats.get("consistentCount")).append(" |\n");
        md.append("| 不一致表数 | ").append(stats.get("inconsistentCount")).append(" |\n");
        md.append("| 跳过表数 | ").append(stats.get("skippedCount")).append(" |\n");
        md.append("| 条数不一致 | ").append(stats.get("countDiffCount")).append(" |\n");
        md.append("| 金额不一致 | ").append(stats.get("amountDiffCount")).append(" |\n");
        md.append("| Hash不一致 | ").append(stats.get("hashDiffCount")).append(" |\n");
        md.append("| 索引不一致 | ").append(stats.get("indexDiffCount")).append(" |\n");
        md.append("| 函数一致 | ").append(stats.get("functionConsistent")).append(" |\n");
        md.append("| 过程一致 | ").append(stats.get("procedureConsistent")).append(" |\n\n");
        
        // 不一致的表列表
        List<String> inconsistentTables = (List<String>) stats.get("inconsistentTables");
        if (!inconsistentTables.isEmpty()) {
            md.append("### 不一致的表\n\n");
            for (String tableName : inconsistentTables) {
                md.append("- ").append(tableName).append("\n");
            }
            md.append("\n");
        }
        
        // 函数/过程结果
        if (compareResult.containsKey("__functions__")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fr = (Map<String, Object>) compareResult.get("__functions__");
            md.append("## 函数比对\n\n");
            md.append("| 指标 | 数值 |\n");
            md.append("|------|------|\n");
            md.append("| 源库函数数 | ").append(fr.getOrDefault("sourceCount", "N/A")).append(" |\n");
            md.append("| 目标库函数数 | ").append(fr.getOrDefault("targetCount", "N/A")).append(" |\n");
            md.append("| 共有函数数 | ").append(fr.getOrDefault("commonCount", "N/A")).append(" |\n");
            md.append("| 一致 | ").append(fr.getOrDefault("consistent", "N/A")).append(" |\n\n");
        }
        if (compareResult.containsKey("__procedures__")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pr = (Map<String, Object>) compareResult.get("__procedures__");
            md.append("## 存储过程比对\n\n");
            md.append("| 指标 | 数值 |\n");
            md.append("|------|------|\n");
            md.append("| 源库存储过程数 | ").append(pr.getOrDefault("sourceCount", "N/A")).append(" |\n");
            md.append("| 目标库存储过程数 | ").append(pr.getOrDefault("targetCount", "N/A")).append(" |\n");
            md.append("| 共有存储过程数 | ").append(pr.getOrDefault("commonCount", "N/A")).append(" |\n");
            md.append("| 一致 | ").append(pr.getOrDefault("consistent", "N/A")).append(" |\n\n");
        }

        // 详细比对结果
        md.append("## 详细比对结果\n\n");
        for (Map.Entry<String, Object> entry : compareResult.entrySet()) {
            String tableName = entry.getKey();

            // 跳过全局结果键
            if ("__functions__".equals(tableName) || "__procedures__".equals(tableName)) {
                continue;
            }

            Map<String, Object> tableResult = (Map<String, Object>) entry.getValue();
            
            md.append("### 表: ").append(tableName).append("\n\n");
            md.append("- **表结构比对**: ").append(tableResult.getOrDefault("schema", "N/A")).append("\n");

            // 索引比对
            Object idxObj = tableResult.get("indexes");
            if (idxObj instanceof Map) {
                Map<String, Object> idxInfo = (Map<String, Object>) idxObj;
                md.append("- **索引比对**: [").append(idxInfo.getOrDefault("consistent", "N/A")).append("]");
                if (idxInfo.containsKey("diffs")) {
                    @SuppressWarnings("unchecked")
                    List<String> diffs = (List<String>) idxInfo.get("diffs");
                    if (diffs != null) md.append(" ").append(String.join("; ", diffs));
                }
                md.append("\n");
            }
            
            // 数据条数
            Object countObj = tableResult.get("count");
            if (countObj instanceof Map) {
                Map<String, Object> countInfo = (Map<String, Object>) countObj;
                md.append("- **数据条数**: 源库=").append(countInfo.get("sourceCount"))
                  .append(", 目标库=").append(countInfo.get("targetCount"))
                  .append(", 差异=").append(formatDiff(countInfo.get("diff")))
                  .append(" [").append(countInfo.get("consistent")).append("]\n");
            } else {
                md.append("- **数据条数**: ").append(tableResult.getOrDefault("count", "N/A")).append("\n");
            }

            // 金额SUM
            Object amountObj = tableResult.get("amount");
            if (amountObj instanceof Map) {
                Map<String, Object> amountInfo = (Map<String, Object>) amountObj;
                md.append("- **金额SUM比对**: [").append(amountInfo.get("consistent")).append("]");
                List<String> diffFields = (List<String>) amountInfo.get("diffFields");
                if (diffFields != null && !diffFields.isEmpty()) {
                    md.append(" 差异字段: ").append(String.join(", ", diffFields));
                }
                md.append("\n");
            } else {
                md.append("- **金额SUM比对**: ").append(tableResult.getOrDefault("amount", "N/A")).append("\n");
            }

            // 行Hash
            Object hashObj = tableResult.get("rowHash");
            if (hashObj instanceof Map) {
                Map<String, Object> hashInfo = (Map<String, Object>) hashObj;
                md.append("- **行Hash比对**: 源库=").append(hashInfo.get("sourceHash"))
                  .append(", 目标库=").append(hashInfo.get("targetHash"))
                  .append(" [").append(hashInfo.get("consistent")).append("]\n");
            }
            
            if (tableResult.containsKey("error")) {
                md.append("- **错误信息**: ").append(tableResult.get("error")).append("\n");
            }
            
            md.append("\n");
        }
        
        return md.toString();
    }
    
    /**
     * 生成 HTML 报告（集成 ECharts，多维度比对结果）
     */
    @SuppressWarnings("unchecked")
    private String generateHtmlReport(CompareTask task, Map<String, Object> compareResult, 
                                       Map<String, Object> stats) {
        StringBuilder html = new StringBuilder();
        
        // HTML 头部
        html.append("<!DOCTYPE html>\n")
            .append("<html>\n")
            .append("<head>\n")
            .append("  <meta charset='UTF-8'>\n")
            .append("  <title>数据库比对报告</title>\n")
            .append("  <script src='/js/echarts.min.js'></script>\n")
            .append("  <link href='/css/bootstrap.min.css' rel='stylesheet'>\n")
            .append("  <style>\n")
            .append("    :root {\n")
            .append("      --primary: #4f46e5; --success: #059669; --danger: #dc2626;\n")
            .append("      --warning: #f59e0b; --gray: #6b7280; --light: #f9fafb;\n")
            .append("      --radius: 12px; --shadow: 0 1px 3px rgba(0,0,0,.08), 0 1px 2px rgba(0,0,0,.06);\n")
            .append("      --shadow-md: 0 4px 6px rgba(0,0,0,.07), 0 2px 4px rgba(0,0,0,.06);\n")
            .append("    }\n")
            .append("    * { box-sizing: border-box; }\n")
            .append("    body { font-family: 'Inter', -apple-system, 'Segoe UI', sans-serif; background: #f1f5f9; color: #1e293b; margin: 0; padding: 24px 0; }\n")
            .append("    .container { max-width: 1140px; margin: 0 auto; padding: 0 20px; }\n")
            .append("    /* Header */\n")
            .append("    .rpt-header { background: linear-gradient(135deg, #4f46e5, #6366f1); color: #fff; padding: 28px 32px; border-radius: var(--radius); margin-bottom: 24px; box-shadow: var(--shadow-md); }\n")
            .append("    .rpt-header-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }\n")
            .append("    .rpt-header h1 { margin: 0 0 6px; font-size: 1.6rem; font-weight: 700; }\n")
            .append("    .rpt-header-top h1 { margin: 0; }\n")
            .append("    .rpt-header .btn-reexec { display: inline-flex; align-items: center; gap: 6px; background: rgba(255,255,255,.18); color: #fff; border: 1px solid rgba(255,255,255,.45); border-radius: 8px; padding: 8px 16px; font-size: .86rem; font-weight: 600; cursor: pointer; white-space: nowrap; }\n")
            .append("    .rpt-header .btn-reexec:hover { background: rgba(255,255,255,.28); }\n")
            .append("    .rpt-header .btn-reexec:disabled { opacity: .55; cursor: not-allowed; }\n")
            .append("    .rpt-header .meta { opacity: .85; font-size: .82rem; display: flex; gap: 20px; flex-wrap: wrap; margin-top: 10px; }\n")
            .append("    .rpt-header .meta span { display: inline-flex; align-items: center; gap: 4px; }\n")
            .append("    /* Stat cards - clickable */\n")
            .append("    .stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }\n")
            .append("    .stat-card { background: #fff; border-radius: var(--radius); padding: 20px 24px; box-shadow: var(--shadow); text-align: center; border-left: 4px solid var(--primary); transition: transform .15s, box-shadow .15s; cursor: pointer; text-decoration: none; display: block; }\n")
            .append("    .stat-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }\n")
            .append("    .stat-card.sc-success { border-left-color: var(--success); }\n")
            .append("    .stat-card.sc-danger { border-left-color: var(--danger); }\n")
            .append("    .stat-card.sc-warning { border-left-color: var(--warning); }\n")
            .append("    .stat-card.active-filter { outline: 3px solid var(--primary); outline-offset: 2px; }\n")
            .append("    .stat-card .sc-num { font-size: 2rem; font-weight: 800; line-height: 1.1; }\n")
            .append("    .stat-card .sc-label { font-size: .78rem; color: var(--gray); margin-top: 4px; font-weight: 600; text-transform: uppercase; letter-spacing: .5px; }\n")
            .append("    .stat-card .sc-num.c-primary { color: var(--primary); }\n")
            .append("    .stat-card .sc-num.c-success { color: var(--success); }\n")
            .append("    .stat-card .sc-num.c-danger { color: var(--danger); }\n")
            .append("    .stat-card .sc-num.c-warning { color: var(--warning); }\n")
            .append("    /* Section */\n")
            .append("    .section { background: #fff; border-radius: var(--radius); padding: 24px; margin-bottom: 24px; box-shadow: var(--shadow); }\n")
            .append("    .section h2 { font-size: 1.15rem; font-weight: 700; margin: 0 0 16px; color: #1e293b; }\n")
            .append("    .section h2 .badge { font-size: .7rem; padding: 2px 10px; border-radius: 20px; font-weight: 600; vertical-align: middle; }\n")
            .append("    .badge-ok { background: #d1fae5; color: #065f46; }\n")
            .append("    .badge-ng { background: #fee2e2; color: #991b1b; }\n")
            .append("    /* Diff dimension cards - clickable */\n")
            .append("    .dim-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 24px; }\n")
            .append("    .dim-card { background: #fff; border-radius: var(--radius); padding: 16px 18px; box-shadow: var(--shadow); border: 1px solid #e2e8f0; cursor: pointer; transition: transform .15s, box-shadow .15s; }\n")
            .append("    .dim-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }\n")
            .append("    .dim-card.dim-err { border-color: #fecaca; background: #fff5f5; }\n")
            .append("    .dim-card.active-filter { outline: 3px solid var(--primary); outline-offset: 2px; }\n")
            .append("    .dim-card .dim-icon { font-size: 1.3rem; margin-bottom: 6px; }\n")
            .append("    .dim-card .dim-title { font-size: .76rem; color: var(--gray); font-weight: 600; text-transform: uppercase; }\n")
            .append("    .dim-card .dim-val { font-size: 1.4rem; font-weight: 800; }\n")
            .append("    .dim-card .dim-val.danger { color: var(--danger); }\n")
            .append("    .dim-card .dim-val.success { color: var(--success); }\n")
            .append("    /* Detail modal overlay */\n")
            .append("    .modal-overlay { display: none; position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 3000; overflow-y: auto; padding: 8vh 0; word-break: break-all; }\n")
            .append("    .modal-overlay.show { display: block; }\n")
            // 注意：勿与 Bootstrap .modal-dialog 冲突（其 pointer-events:none，且需 .modal-content 才能点）
            .append("    .dim-modal-dialog { background: #fff; border-radius: var(--radius); width: 90%; max-width: 960px; margin: 0 auto; box-shadow: 0 20px 60px rgba(0,0,0,.15); animation: fadeIn .2s; pointer-events: auto; }\n")
            .append("    .dim-modal-dialog, .dim-modal-dialog * { pointer-events: auto; }\n")
            .append("    .dim-modal-dialog.wide { max-width: 1100px; }\n")
            .append("    .modal-body { padding: 16px 20px; }\n")
            .append("    @keyframes fadeIn { from { opacity: 0; transform: translateY(-10px); } to { opacity: 1; transform: translateY(0); } }\n")
            .append("    .modal-header { padding: 16px 20px; border-bottom: 1px solid #e2e8f0; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; background: #fff; z-index: 1; border-radius: var(--radius) var(--radius) 0 0; }\n")
            .append("    .modal-header h3 { margin: 0; font-size: 1.1rem; font-weight: 700; }\n")
            .append("    .modal-close { background: none; border: none; font-size: 1.4rem; cursor: pointer; color: var(--gray); padding: 4px 8px; border-radius: 4px; }\n")
            .append("    .modal-close:hover { background: #f1f5f9; }\n")
            .append("    .modal-tbl { width: 100%; border-collapse: collapse; font-size: .84rem; }\n")
            .append("    .modal-tbl thead th { background: #f1f5f9; color: #475569; font-weight: 700; font-size: .75rem; padding: 8px 12px; text-align: left; border-bottom: 2px solid #e2e8f0; }\n")
            .append("    .modal-tbl tbody td { padding: 8px 12px; border-bottom: 1px solid #f1f5f9; }\n")
            .append("    .modal-tbl tbody tr:hover { background: #f8fafc; }\n")
            .append("    .modal-tbl .m-src { color: #059669; font-weight: 600; }\n")
            .append("    .modal-tbl .m-tgt { color: #dc2626; font-weight: 600; }\n")
            .append("    .modal-tbl .m-diff { color: var(--warning); font-weight: 600; }\n")
            .append("    .modal-footer { padding: 12px 20px; border-top: 1px solid #e2e8f0; display: none; gap: 10px; justify-content: space-between; align-items: center; flex-wrap: wrap; background: #f8fafc; border-radius: 0 0 var(--radius) var(--radius); }\n")
            .append("    .modal-footer.show { display: flex; }\n")
            .append("    .modal-toolbar { display: none; padding: 10px 20px; border-bottom: 1px solid #e2e8f0; gap: 10px; justify-content: space-between; align-items: center; flex-wrap: wrap; background: #eef2ff; position: sticky; top: 0; z-index: 2; }\n")
            .append("    .modal-toolbar.show { display: flex; }\n")
            .append("    .modal-toolbar .hint { font-size: .78rem; color: #4338ca; }\n")
            .append("    .btn-extract { background: var(--primary); color: #fff; border: none; border-radius: 6px; padding: 8px 16px; font-size: .84rem; font-weight: 600; cursor: pointer; }\n")
            .append("    .btn-extract:hover { opacity: .92; }\n")
            .append("    .btn-extract:disabled { opacity: .55; cursor: not-allowed; }\n")
            .append("    .btn-row-diff { background: #fff; color: var(--primary); border: 1px solid #c7d2fe; border-radius: 6px; padding: 4px 10px; font-size: .78rem; font-weight: 600; cursor: pointer; white-space: nowrap; }\n")
            .append("    .btn-row-diff:hover { background: #eef2ff; }\n")
            .append("    .btn-row-diff:disabled { opacity: .55; cursor: not-allowed; }\n")
            .append("    .btn-back-dim { background: #f1f5f9; color: #334155; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 12px; font-size: .82rem; cursor: pointer; }\n")
            .append("    .diff-type-src { color: #059669; font-weight: 600; }\n")
            .append("    .diff-type-tgt { color: #dc2626; font-weight: 600; }\n")
            .append("    .pk-chip { display: inline-block; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 4px; padding: 1px 6px; margin: 1px 4px 1px 0; font-family: ui-monospace,Consolas,monospace; font-size: .78rem; }\n")
            .append("    .modal-footer .hint { font-size: .78rem; color: var(--gray); }\n")
            .append("    /* Reset filter button */\n")
            .append("    .filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; font-size: .82rem; }\n")
            .append("    .filter-bar .filter-tag { display: inline-flex; align-items: center; gap: 4px; padding: 2px 10px; border-radius: 20px; font-size: .73rem; font-weight: 600; background: #e0e7ff; color: #3730a3; }\n")
            .append("    .filter-bar .filter-tag .ft-close { cursor: pointer; margin-left: 2px; font-weight: 700; opacity: .6; }\n")
            .append("    .filter-bar .filter-tag .ft-close:hover { opacity: 1; }\n")
            .append("    .btn-reset { background: none; border: 1px solid #e2e8f0; padding: 4px 12px; border-radius: 6px; font-size: .78rem; cursor: pointer; color: var(--gray); }\n")
            .append("    .btn-reset:hover { background: #f1f5f9; }\n")
            .append("    .table-hidden { display: none; }\n")
            .append("    /* Table */\n")
            .append("    .tbl-wrap { overflow-x: auto; }\n")
            .append("    .tbl { width: 100%; border-collapse: collapse; font-size: .85rem; }\n")
            .append("    .tbl thead th { background: #f1f5f9; color: #475569; font-weight: 700; font-size: .75rem; text-transform: uppercase; letter-spacing: .4px; padding: 10px 14px; text-align: left; border-bottom: 2px solid #e2e8f0; position: sticky; top: 0; z-index: 1; }\n")
            .append("    .tbl tbody td { padding: 10px 14px; border-bottom: 1px solid #f1f5f9; }\n")
            .append("    .tbl tbody tr:hover { background: #f8fafc; }\n")
            .append("    .tbl tbody tr.row-err { background: #fff5f5; }\n")
            .append("    .tbl tbody tr.row-err:hover { background: #fef2f2; }\n")
            .append("    .tbl tbody tr.row-skip { background: #fffbeb; }\n")
            .append("    .pill { display: inline-block; padding: 2px 10px; border-radius: 20px; font-size: .73rem; font-weight: 600; }\n")
            .append("    .pill-ok { background: #d1fae5; color: #065f46; }\n")
            .append("    .pill-ng { background: #fee2e2; color: #991b1b; }\n")
            .append("    .pill-skip { background: #fef3c7; color: #92400e; }\n")
            .append("    /* Detail row */\n")
            .append("    .detail-row { cursor: pointer; user-select: none; }\n")
            .append("    .detail-row .arrow { display: inline-block; transition: transform .2s; margin-right: 4px; font-size: .7rem; }\n")
            .append("    .detail-row.open .arrow { transform: rotate(90deg); }\n")
            .append("    .detail-content { display: none; }\n")
            .append("    .detail-content.open { display: table-row; }\n")
            .append("    .detail-inner { padding: 14px 20px; background: #f8fafc; border-radius: 6px; margin: 0; font-size: .82rem; line-height: 1.7; }\n")
            .append("    .detail-inner hr { border: none; border-top: 1px solid #e2e8f0; margin: 12px 0; }\n")
            .append("    .detail-inner .kv { display: inline-block; margin-right: 16px; }\n")
            .append("    .detail-inner .kv .k { font-weight: 600; color: #64748b; }\n")
            .append("    .detail-inner .kv .v { font-family: monospace; }\n")
            .append("    /* Diff table inside detail */\n")
            .append("    .diff-tbl { width: 100%; font-size: .78rem; border-collapse: collapse; margin-top: 8px; border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }\n")
            .append("    .diff-tbl thead th { background: #fef2f2; color: #991b1b; font-weight: 700; padding: 8px 10px; text-align: left; border-bottom: 2px solid #fecaca; }\n")
            .append("    .diff-tbl tbody td { padding: 8px 10px; border-bottom: 1px solid #f1f5f9; vertical-align: middle; word-break: break-all; max-width: 220px; }\n")
            .append("    .diff-tbl tbody tr.row-miss { background: #fef2f2; }\n")
            .append("    .diff-tbl tbody tr.row-mism { background: #fffbeb; }\n")
            .append("    .diff-src { color: #059669; font-family: monospace; font-weight: 600; }\n")
            .append("    .diff-tgt { color: #dc2626; font-family: monospace; font-weight: 600; }\n")
            .append("    /* Chart */\n")
            .append("    .chart-wrap { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 24px; }\n")
            .append("    .chart-box { background: #fff; border-radius: var(--radius); padding: 16px; box-shadow: var(--shadow); }\n")
            .append("    .chart-box .chart-title { font-size: .82rem; font-weight: 700; color: #475569; margin-bottom: 8px; text-transform: uppercase; letter-spacing: .4px; }\n")
            .append("    .chart { width: 100%; height: 360px; }\n")
            .append("    /* Routine section */\n")
            .append("    .routine-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 24px; }\n")
            .append("    .routine-card { background: #fff; border-radius: var(--radius); padding: 20px 24px; box-shadow: var(--shadow); border-top: 3px solid var(--primary); }\n")
            .append("    .routine-card.rc-ok { border-top-color: var(--success); }\n")
            .append("    .routine-card.rc-ng { border-top-color: var(--danger); }\n")
            .append("    .routine-card .rc-title { font-size: .88rem; font-weight: 700; margin-bottom: 10px; }\n")
            .append("    .routine-card .rc-stats { display: flex; gap: 16px; flex-wrap: wrap; font-size: .82rem; }\n")
            .append("    .routine-card .rc-stats .rc-item { text-align: center; }\n")
            .append("    .routine-card .rc-stats .rc-num { font-size: 1.3rem; font-weight: 800; }\n")
            .append("    .routine-card .rc-stats .rc-lbl { font-size: .7rem; color: var(--gray); }\n")
            .append("    .routine-card .rc-extras { margin-top: 8px; font-size: .76rem; }\n")
            .append("    .tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: .7rem; font-weight: 600; background: #fef3c7; color: #92400e; margin-right: 4px; margin-bottom: 2px; }\n")
            .append("    /* DB info inline */\n")
            .append("    .db-info { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 4px; font-size: .75rem; background: rgba(255,255,255,.15); }\n")
            .append("    @media (max-width: 768px) {\n")
            .append("      .stat-row, .dim-row, .chart-wrap, .routine-row { grid-template-columns: 1fr 1fr; }\n")
            .append("    }\n")
            .append("    @media (max-width: 480px) {\n")
            .append("      .stat-row, .dim-row, .chart-wrap, .routine-row { grid-template-columns: 1fr; }\n")
            .append("    }\n")
            .append("  </style>\n")
            .append("</head>\n")
            .append("<body>\n");
        
        // 标题区域
        String srcSchema = formatSchemaDisplay(task.getSourceSchema());
        String tgtSchema = formatSchemaDisplay(task.getTargetSchema());
        html.append("  <div class='container'>\n")
            .append("    <div class='rpt-header'>\n")
            .append("      <div class='rpt-header-top'>\n")
            .append("        <h1>📊 数据库比对报告</h1>\n")
            .append("        <button type='button' class='btn-reexec' id='btnReExecuteCompare' data-dbcompare-reexec-btn='1'>↻ 重新执行</button>\n")
            .append("      </div>\n")
            .append("      <div class='meta'>\n")
            .append("        <span>📋 <strong>").append(escapeHtml(task.getTaskName())).append("</strong></span>\n")
            .append("        <span>🕐 ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("</span>\n")
            .append("        <span><span class='db-info'>源库: ").append(escapeHtml(srcSchema)).append("</span></span>\n")
            .append("        <span><span class='db-info'>目标库: ").append(escapeHtml(tgtSchema)).append("</span></span>\n");
        if (task.getStartTime() != null) {
            html.append("        <span>▶️ ").append(task.getStartTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))).append("</span>\n");
        }
        if (task.getEndTime() != null) {
            long seconds = java.time.Duration.between(task.getStartTime(), task.getEndTime()).getSeconds();
            html.append("        <span>⏱️ 耗时 ").append(formatDuration(seconds)).append("</span>\n");
        }
        html.append("      </div>\n")
            .append("    </div>\n");
        
        // 统计卡片
        int consistentCount = (int) stats.get("consistentCount");
        int inconsistentCount = (int) stats.get("inconsistentCount");
        int skippedCount = (int) stats.get("skippedCount");
        int countDiffCount = (int) stats.get("countDiffCount");
        int amountDiffCount = (int) stats.get("amountDiffCount");
        int hashDiffCount = (int) stats.get("hashDiffCount");
        int indexDiffCount = stats.get("indexDiffCount") != null ? (int) stats.get("indexDiffCount") : 0;
        boolean functionConsistent = stats.get("functionConsistent") != null ? (boolean) stats.get("functionConsistent") : true;
        boolean procedureConsistent = stats.get("procedureConsistent") != null ? (boolean) stats.get("procedureConsistent") : true;
        int total = task.getTotalTables() != null ? task.getTotalTables() : (consistentCount + inconsistentCount + skippedCount);
        
        html.append("    <div class='stat-row'>\n")
            .append("      <div class='stat-card' onclick='filterByStatus(\"all\")' id='statAll'>\n")
            .append("        <div class='sc-num c-primary'>").append(total).append("</div>\n")
            .append("        <div class='sc-label'>总表数</div>\n")
            .append("      </div>\n")
            .append("      <div class='stat-card sc-success' onclick='filterByStatus(\"consistent\")' id='statConsistent'>\n")
            .append("        <div class='sc-num c-success'>").append(consistentCount).append("</div>\n")
            .append("        <div class='sc-label'>一致</div>\n")
            .append("      </div>\n")
            .append("      <div class='stat-card sc-danger' onclick='filterByStatus(\"inconsistent\")' id='statInconsistent'>\n")
            .append("        <div class='sc-num c-danger'>").append(inconsistentCount).append("</div>\n")
            .append("        <div class='sc-label'>不一致</div>\n")
            .append("      </div>\n")
            .append("      <div class='stat-card sc-warning' onclick='filterByStatus(\"skipped\")' id='statSkipped'>\n")
            .append("        <div class='sc-num c-warning'>").append(skippedCount).append("</div>\n")
            .append("        <div class='sc-label'>跳过</div>\n")
            .append("      </div>\n")
            .append("    </div>\n");
        
        // 维度分析卡片 - 可点击查看详情
        html.append("    <div class='dim-row'>\n")
            .append("      <div class='dim-card").append(countDiffCount > 0 ? " dim-err" : "").append("' onclick='openDimModal(\"count\")'>\n")
            .append("        <div class='dim-icon'>📋</div>\n")
            .append("        <div class='dim-title'>条数差异</div>\n")
            .append("        <div class='dim-val ").append(countDiffCount > 0 ? "danger" : "success").append("'>").append(countDiffCount).append("</div>\n")
            .append("      </div>\n")
            .append("      <div class='dim-card").append(amountDiffCount > 0 ? " dim-err" : "").append("' onclick='openDimModal(\"amount\")'>\n")
            .append("        <div class='dim-icon'>💰</div>\n")
            .append("        <div class='dim-title'>金额差异</div>\n")
            .append("        <div class='dim-val ").append(amountDiffCount > 0 ? "danger" : "success").append("'>").append(amountDiffCount).append("</div>\n")
            .append("      </div>\n")
            .append("      <div class='dim-card").append(hashDiffCount > 0 ? " dim-err" : "").append("' onclick='openDimModal(\"hash\")'>\n")
            .append("        <div class='dim-icon'>🔍</div>\n")
            .append("        <div class='dim-title'>Hash 差异</div>\n")
            .append("        <div class='dim-val ").append(hashDiffCount > 0 ? "danger" : "success").append("'>").append(hashDiffCount).append("</div>\n")
            .append("      </div>\n")
            .append("      <div class='dim-card").append(indexDiffCount > 0 ? " dim-err" : "").append("' onclick='openDimModal(\"index\")'>\n")
            .append("        <div class='dim-icon'>📑</div>\n")
            .append("        <div class='dim-title'>索引差异</div>\n")
            .append("        <div class='dim-val ").append(indexDiffCount > 0 ? "danger" : "success").append("'>").append(indexDiffCount).append("</div>\n")
            .append("      </div>\n")
            .append("    </div>\n")
            .append("    <p style='font-size:.8rem;color:#64748b;margin:8px 0 0;'>提示：条数一致时仍可能存在状态/业务日期/金额等行级差异，可在下方结果表对任意表点「详细比对」。</p>\n");
        
        // ECharts 图表
        html.append("    <div class='chart-wrap'>\n")
            .append("      <div class='chart-box'>\n")
            .append("        <div class='chart-title'>比对结果分布</div>\n")
            .append("        <div id='chart1' class='chart'></div>\n")
            .append("      </div>\n")
            .append("      <div class='chart-box'>\n")
            .append("        <div class='chart-title'>多维度差异分析</div>\n")
            .append("        <div id='chart2' class='chart'></div>\n")
            .append("      </div>\n")
            .append("    </div>\n");
        
        // ECharts JavaScript 配置
        html.append("    <script>\n")
            .append("      // 图表1：饼图\n")
            .append("      var chart1 = echarts.init(document.getElementById('chart1'));\n")
            .append("      var option1 = {\n")
            .append("        title: { text: '比对结果分布', left: 'center' },\n")
            .append("        tooltip: { trigger: 'item' },\n")
            .append("        legend: { orient: 'vertical', left: 'left' },\n")
            .append("        series: [{\n")
            .append("          type: 'pie',\n")
            .append("          radius: '50%',\n")
            .append("          data: [\n")
            .append("            { value: ").append(consistentCount).append(", name: '一致', itemStyle: { color: '#28a745' } },\n")
            .append("            { value: ").append(inconsistentCount).append(", name: '不一致', itemStyle: { color: '#dc3545' } },\n")
            .append("            { value: ").append(skippedCount).append(", name: '跳过', itemStyle: { color: '#6c757d' } }\n")
            .append("          ]\n")
            .append("        }]\n")
            .append("      };\n")
            .append("      chart1.setOption(option1);\n\n")
            .append("      // 图表2：多维度差异分析（雷达图）\n")
            .append("      var chart2 = echarts.init(document.getElementById('chart2'));\n")
            .append("      var option2 = {\n")
            .append("        title: { text: '多维度差异分析', left: 'center' },\n")
            .append("        tooltip: {},\n")
            .append("        radar: {\n")
            .append("          indicator: [\n")
            .append("            { name: '条数差异', max: ").append(Math.max(countDiffCount, 1)).append(" },\n")
            .append("            { name: '金额差异', max: ").append(Math.max(amountDiffCount, 1)).append(" },\n")
            .append("            { name: 'Hash差异', max: ").append(Math.max(hashDiffCount, 1)).append(" },\n")
            .append("            { name: '跳过', max: ").append(Math.max(skippedCount, 1)).append(" }\n")
            .append("          ]\n")
            .append("        },\n")
            .append("        series: [{\n")
            .append("          type: 'radar',\n")
            .append("          data: [{\n")
            .append("            value: [").append(countDiffCount).append(", ").append(amountDiffCount)
            .append(", ").append(hashDiffCount).append(", ").append(skippedCount).append("],\n")
            .append("            name: '差异分布'\n")
            .append("          }]\n")
            .append("        }]\n")
            .append("      };\n")
            .append("      chart2.setOption(option2);\n")
            .append("    </script>\n");
        
        // 函数/过程比对摘要
        html.append("    <div class='routine-row'>\n");
        if (compareResult.containsKey("__functions__")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> funcResult = (Map<String, Object>) compareResult.get("__functions__");
            html.append("      <div class='routine-card ").append(functionConsistent ? "rc-ok" : "rc-ng").append("'>\n")
                .append("        <div class='rc-title'>⚙️ 函数比对</div>\n");
            if (funcResult.containsKey("error")) {
                html.append("        <p style='color:#dc2626;font-size:.82rem;'>错误: ").append(escapeHtml(funcResult.get("error"))).append("</p>\n");
            } else {
                html.append("        <div class='rc-stats'>\n")
                    .append("          <div class='rc-item'><div class='rc-num'>").append(funcResult.get("sourceCount")).append("</div><div class='rc-lbl'>源库</div></div>\n")
                    .append("          <div class='rc-item'><div class='rc-num'>").append(funcResult.get("targetCount")).append("</div><div class='rc-lbl'>目标库</div></div>\n")
                    .append("          <div class='rc-item'><div class='rc-num'>").append(funcResult.get("commonCount")).append("</div><div class='rc-lbl'>共有</div></div>\n")
                    .append("        </div>\n");
                @SuppressWarnings("unchecked")
                List<String> onlySrc = (List<String>) funcResult.get("onlyInSource");
                @SuppressWarnings("unchecked")
                List<String> onlyTgt = (List<String>) funcResult.get("onlyInTarget");
                if ((onlySrc != null && !onlySrc.isEmpty()) || (onlyTgt != null && !onlyTgt.isEmpty())) {
                    html.append("        <div class='rc-extras'>\n");
                    if (onlySrc != null) {
                        for (String s : onlySrc) {
                            html.append("          <span class='tag'>仅源: ").append(escapeHtml(s)).append("</span>\n");
                        }
                    }
                    if (onlyTgt != null) {
                        for (String t : onlyTgt) {
                            html.append("          <span class='tag'>仅目标: ").append(escapeHtml(t)).append("</span>\n");
                        }
                    }
                    html.append("        </div>\n");
                }
            }
            html.append("      </div>\n");
        }
        if (compareResult.containsKey("__procedures__")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> procResult = (Map<String, Object>) compareResult.get("__procedures__");
            html.append("      <div class='routine-card ").append(procedureConsistent ? "rc-ok" : "rc-ng").append("'>\n")
                .append("        <div class='rc-title'>📦 存储过程比对</div>\n");
            if (procResult.containsKey("error")) {
                html.append("        <p style='color:#dc2626;font-size:.82rem;'>错误: ").append(escapeHtml(procResult.get("error"))).append("</p>\n");
            } else {
                html.append("        <div class='rc-stats'>\n")
                    .append("          <div class='rc-item'><div class='rc-num'>").append(procResult.get("sourceCount")).append("</div><div class='rc-lbl'>源库</div></div>\n")
                    .append("          <div class='rc-item'><div class='rc-num'>").append(procResult.get("targetCount")).append("</div><div class='rc-lbl'>目标库</div></div>\n")
                    .append("          <div class='rc-item'><div class='rc-num'>").append(procResult.get("commonCount")).append("</div><div class='rc-lbl'>共有</div></div>\n")
                    .append("        </div>\n");
                @SuppressWarnings("unchecked")
                List<String> onlySrc = (List<String>) procResult.get("onlyInSource");
                @SuppressWarnings("unchecked")
                List<String> onlyTgt = (List<String>) procResult.get("onlyInTarget");
                if ((onlySrc != null && !onlySrc.isEmpty()) || (onlyTgt != null && !onlyTgt.isEmpty())) {
                    html.append("        <div class='rc-extras'>\n");
                    if (onlySrc != null) {
                        for (String s : onlySrc) {
                            html.append("          <span class='tag'>仅源: ").append(escapeHtml(s)).append("</span>\n");
                        }
                    }
                    if (onlyTgt != null) {
                        for (String t : onlyTgt) {
                            html.append("          <span class='tag'>仅目标: ").append(escapeHtml(t)).append("</span>\n");
                        }
                    }
                    html.append("        </div>\n");
                }
            }
            html.append("      </div>\n");
        }
        html.append("    </div>\n");

        // 详细比对结果表格
        html.append("    <div class='section'>\n")
            .append("      <h2>📋 详细比对结果 <span class='badge badge-ok'>").append(consistentCount).append(" 一致</span>")
            .append(inconsistentCount > 0 ? " <span class='badge badge-ng'>" + inconsistentCount + " 不一致</span>" : "")
            .append(skippedCount > 0 ? " <span class='badge badge-ng'>" + skippedCount + " 跳过</span>" : "")
            .append("</h2>\n")
            .append("      <div class='filter-bar' id='filterBar' style='display:none;'>\n")
            .append("        <span style='color:var(--gray);'>当前筛选：</span>\n")
            .append("        <span class='filter-tag' id='filterTag'><span id='filterLabel'></span><span class='ft-close' onclick='clearFilter()' title='清除筛选'>✕</span></span>\n")
            .append("        <button class='btn-reset' onclick='clearFilter()'>显示全部</button>\n")
            .append("      </div>\n")
            .append("      <div class='tbl-wrap'>\n")
            .append("      <table class='tbl' id='detailTable'>\n")
            .append("        <thead>\n")
            .append("          <tr>\n")
            .append("            <th>表名</th>\n")
            .append("            <th>表结构</th>\n")
            .append("            <th>索引</th>\n")
            .append("            <th>条数(源/目标)</th>\n")
            .append("            <th>金额SUM</th>\n")
            .append("            <th>行Hash</th>\n")
            .append("            <th>状态</th>\n")
            .append("            <th style=\"width:110px;\">操作</th>\n")
            .append("          </tr>\n")
            .append("        </thead>\n")
            .append("        <tbody>\n");
        
        for (Map.Entry<String, Object> entry : compareResult.entrySet()) {
            String tableName = entry.getKey();

            // 跳过全局结果键
            if ("__functions__".equals(tableName) || "__procedures__".equals(tableName)) {
                continue;
            }

            Map<String, Object> tableResult = (Map<String, Object>) entry.getValue();
            
            String schemaResult = String.valueOf(tableResult.getOrDefault("schema", "N/A"));
            Boolean consistent = (Boolean) tableResult.get("consistent");

            // 索引信息
            String indexDisplay = "N/A";
            String indexClass = "";
            Map<String, Object> indexInfo = null;
            Object idxObj = tableResult.get("indexes");
            if (idxObj instanceof Map) {
                indexInfo = (Map<String, Object>) idxObj;
                Boolean ic = (Boolean) indexInfo.get("consistent");
                if (ic != null && ic) {
                    indexDisplay = "一致";
                    indexClass = "consistent";
                } else {
                    indexDisplay = "不一致";
                    indexClass = "inconsistent";
                }
            }
            
            // 条数信息
            String countDisplay = "N/A";
            String countClass = "";
            Map<String, Object> countInfo = null;
            Object countObj = tableResult.get("count");
            if (countObj instanceof Map) {
                countInfo = (Map<String, Object>) countObj;
                Boolean cc = (Boolean) countInfo.get("consistent");
                countDisplay = countInfo.get("sourceCount") + " / " + countInfo.get("targetCount");
                countClass = (cc != null && cc) ? "consistent" : "inconsistent";
            }

            // 金额信息
            String amountDisplay = "无金额字段";
            String amountClass = "";
            Map<String, Object> amountInfo = null;
            Object amountObj = tableResult.get("amount");
            if (amountObj instanceof Map) {
                amountInfo = (Map<String, Object>) amountObj;
                Boolean ac = (Boolean) amountInfo.get("consistent");
                List<String> diffFields = (List<String>) amountInfo.get("diffFields");
                if (diffFields != null && !diffFields.isEmpty()) {
                    amountDisplay = "差异: " + String.join(", ", diffFields);
                } else {
                    amountDisplay = "一致";
                }
                amountClass = (ac != null && ac) ? "consistent" : "inconsistent";
            } else if (amountObj instanceof String) {
                amountDisplay = String.valueOf(amountObj);
            }

            // Hash信息
            String hashDisplay = "N/A";
            String hashClass = "";
            Map<String, Object> hashInfo = null;
            Object hashObj = tableResult.get("rowHash");
            if (hashObj instanceof Map) {
                hashInfo = (Map<String, Object>) hashObj;
                if (Boolean.TRUE.equals(hashInfo.get("skipped"))) {
                    hashDisplay = "已跳过";
                    hashClass = "skipped";
                } else {
                    Boolean hc = (Boolean) hashInfo.get("consistent");
                    hashDisplay = hc != null && hc ? "一致" : "不一致";
                    hashClass = (hc != null && hc) ? "consistent" : "inconsistent";
                }
            }
            
            String statusClass = "";
            String statusText = "";
            if (consistent != null && consistent) {
                statusClass = "consistent";
                statusText = "✅ 一致";
            } else if (tableResult.containsKey("error")) {
                statusText = "⚠️ 错误";
            } else {
                statusClass = "inconsistent";
                statusText = "❌ 不一致";
            }
            
            String rowExtraClass = (consistent == null || !consistent) ? " row-err" : "";
            String pillClass = (consistent != null && consistent) ? "pill-ok" : "pill-ng";
            String rowStatus = (consistent != null && consistent) ? "consistent" 
                : (tableResult.containsKey("error") ? "skipped" : "inconsistent");
            
            // 收集该行涉及的差异维度
            List<String> dims = new ArrayList<>();
            if (!"consistent".equals(countClass)) dims.add("count");
            if (!"consistent".equals(amountClass)) dims.add("amount");
            if ("inconsistent".equals(hashClass)) dims.add("hash");
            if ("inconsistent".equals(indexClass)) dims.add("index");
            String dimsStr = String.join(",", dims);
            String hashPillClass = "consistent".equals(hashClass) ? "pill-ok"
                    : ("skipped".equals(hashClass) ? "pill-ok" : "pill-ng");
            
            html.append("          <tr class='detail-row").append(rowExtraClass)
                .append("' onclick='toggleDetail(this)' data-status='").append(rowStatus)
                .append("' data-dims='").append(dimsStr).append("'>\n")
                .append("            <td><strong>").append(escapeHtml(tableName)).append("</strong></td>\n")
                .append("            <td>").append(escapeHtml(schemaResult)).append("</td>\n")
                .append("            <td><span class='pill ").append("一致".equals(indexDisplay) ? "pill-ok" : "pill-ng").append("'>").append(indexDisplay).append("</span></td>\n")
                .append("            <td><span class='pill ").append("consistent".equals(countClass) ? "pill-ok" : "pill-ng").append("'>").append(countDisplay).append("</span></td>\n")
                .append("            <td><span class='pill ").append("consistent".equals(amountClass) ? "pill-ok" : "pill-ng").append("'>").append(amountDisplay).append("</span></td>\n")
                .append("            <td><span class='pill ").append(hashPillClass).append("'>").append(hashDisplay).append("</span></td>\n")
                .append("            <td><span class='pill ").append(pillClass).append("'>").append(statusText).append("</span></td>\n")
                .append("            <td onclick='event.stopPropagation()'>\n")
                .append("              <button type='button' class='btn-row-diff' data-table='")
                .append(escapeHtml(tableName)).append("'>详细比对</button>\n")
                .append("            </td>\n")
                .append("          </tr>\n");
            
            // 详细差异内容（可展开）
            if (consistent == null || !consistent) {
                html.append("          <tr class='detail-content' id='detail-").append(tableName).append("'>\n")
                    .append("            <td colspan='8'>\n");
                if (countInfo instanceof Map && countInfo.get("sourceCount") != null) {
                    Object diffVal = countInfo.get("diff");
                    String diffStr = diffVal == null ? "" : formatDiff(diffVal);
                    html.append("              <strong>条数:</strong> 源库=").append(countInfo.get("sourceCount"))
                        .append(", 目标库=").append(countInfo.get("targetCount"))
                        .append(", 差异=").append(diffStr).append("<br>\n");
                }
                if (amountInfo instanceof Map) {
                    Map<String, Object> sourceSums = (Map<String, Object>) amountInfo.get("sourceSums");
                    Map<String, Object> targetSums = (Map<String, Object>) amountInfo.get("targetSums");
                    if (sourceSums != null && targetSums != null) {
                        html.append("              <strong>金额SUM详情:</strong><br>\n");
                        for (String field : sourceSums.keySet()) {
                            Object sv = sourceSums.get(field);
                            Object tv = targetSums.get(field);
                            html.append("              &nbsp;&nbsp;").append(field).append(": 源=").append(sv)
                                .append(", 目标=").append(tv).append("<br>\n");
                        }
                    }
                }
                if (hashInfo instanceof Map) {
                    if (Boolean.TRUE.equals(hashInfo.get("skipped"))) {
                        html.append("              <strong>行Hash:</strong> ")
                            .append(hashInfo.get("message") != null ? hashInfo.get("message") : "超大表已跳过")
                            .append("<br>\n");
                    } else {
                        html.append("              <strong>行Hash:</strong> 源=").append(hashInfo.get("sourceHash"))
                            .append(", 目标=").append(hashInfo.get("targetHash")).append("<br>\n");
                    }
                }
                if (indexInfo instanceof Map && !Boolean.TRUE.equals(indexInfo.get("consistent"))) {
                    @SuppressWarnings("unchecked")
                    List<String> diffs = (List<String>) indexInfo.get("diffs");
                    if (diffs != null && !diffs.isEmpty()) {
                        html.append("              <strong>索引差异:</strong><br>\n");
                        for (String d : diffs) {
                            html.append("              &nbsp;&nbsp;⚠️ ").append(escapeHtml(d)).append("<br>\n");
                        }
                    }
                }

                // 逐行比对的详细差异数据
                Object detailObj = tableResult.get("detail");
                if (detailObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> detailInfo = (Map<String, Object>) detailObj;
                    Object diffRowsObj = detailInfo.get("diffRows");
                    Number diffCount = (Number) detailInfo.get("diffCount");
                    Number mismatchCount = (Number) detailInfo.get("mismatchCount");
                    Number totalScanned = (Number) detailInfo.get("totalScanned");
                    Object pkObj = detailInfo.get("primaryKeys");

                    if (diffCount != null && diffCount.longValue() > 0) {
                        html.append("              <hr style='margin:8px 0;border-color:#e2e8f0;'>\n");
                        html.append("              <strong>🔍 逐行数据差异</strong>");
                        html.append(" (扫描 ").append(totalScanned).append(" 行，发现 ");
                        html.append("<span style='color:#dc3545;font-weight:700;'>").append(diffCount).append("</span> 处差异");
                        if (mismatchCount != null) {
                            html.append("，其中 <span style='color:#f59e0b;'>").append(mismatchCount).append("</span> 处数据不一致");
                        }
                        html.append(")<br>\n");

                        if (diffRowsObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> diffRows = (List<Map<String, Object>>) diffRowsObj;
                            @SuppressWarnings("unchecked")
                            List<String> pkList = pkObj instanceof List ? (List<String>) pkObj : null;

                            if (diffRows != null && !diffRows.isEmpty()) {
                                html.append("              <div style='max-height:400px;overflow-y:auto;margin-top:8px;'>\n");
                                html.append("              <table class='table table-sm table-bordered diff-table' style='font-size:.78rem;margin-bottom:0;'>\n");
                                html.append("                <thead class='table-danger'><tr><th>#</th><th>类型</th>");
                                if (pkList != null) {
                                    for (String pk : pkList) {
                                        html.append("<th>").append(escapeHtml(pk)).append("</th>");
                                    }
                                }
                                html.append("<th>差异字段</th></tr></thead><tbody>\n");

                                int rowNum = 0;
                                for (Map<String, Object> diffRow : diffRows) {
                                    rowNum++;
                                    String type = (String) diffRow.get("type");
                                    String rowClass = "mismatch".equals(type) ? "table-warning" : "table-danger";
                                    String typeLabel = "mismatch".equals(type) ? "🔸 不一致" : "🔴 缺失";

                                    html.append("                <tr class='").append(rowClass).append("'>\n");
                                    html.append("                  <td>").append(rowNum).append("</td>\n");
                                    html.append("                  <td>").append(typeLabel).append("</td>\n");

                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> pkVals = (Map<String, Object>) diffRow.get("pk");
                                    if (pkList != null) {
                                        for (String pk : pkList) {
                                            Object pv = pkVals != null ? pkVals.get(pk) : null;
                                            html.append("                  <td><code>").append(pv).append("</code></td>\n");
                                        }
                                    }

                                    html.append("                  <td style='font-size:.75rem;'>\n");
                                    if ("missing".equals(type)) {
                                        html.append("                    <span class='text-danger'>目标库中不存在此行</span>\n");
                                    } else {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Map<String, Object>> diffFields =
                                            (Map<String, Map<String, Object>>) diffRow.get("diffFields");
                                        if (diffFields != null) {
                                            for (Map.Entry<String, Map<String, Object>> df : diffFields.entrySet()) {
                                                String col = escapeHtml(df.getKey());
                                                Map<String, Object> pair = df.getValue();
                                                html.append("                    <div style='margin-bottom:3px;'>");
                                                html.append("<strong>").append(col).append("</strong>: ");
                                                html.append("<span class='diff-src'>源=").append(pair.get("source")).append("</span>");
                                                html.append(" / ");
                                                html.append("<span class='diff-tgt'>目标=").append(pair.get("target")).append("</span>");
                                                html.append("</div>\n");
                                            }
                                        }
                                    }
                                    html.append("                  </td>\n");
                                    html.append("                </tr>\n");
                                }
                                html.append("              </tbody></table></div>\n");
                            }
                        }
                        Number maxRows = (Number) detailInfo.get("maxDiffRows");
                        if (maxRows != null && diffRowsObj instanceof List && ((List<?>) diffRowsObj).size() >= maxRows.intValue()) {
                            html.append("              <small class='text-muted'>⚠️ 仅展示前 ").append(maxRows).append(" 条差异，实际差异可能更多</small><br>\n");
                        }
                    }
                }
                html.append("            </td>\n")
                    .append("          </tr>\n");
            }
        }
        
        html.append("        </tbody>\n")
            .append("      </table>\n")
            .append("    </div>\n");
        
        // 行展开/折叠脚本
        html.append("    <script>\n")
            .append("      function toggleDetail(row) {\n")
            .append("        var next = row.nextElementSibling;\n")
            .append("        if (next && next.classList.contains('detail-content')) {\n")
            .append("          next.style.display = next.style.display === 'table-row' ? 'none' : 'table-row';\n")
            .append("        }\n")
            .append("      }\n");
        
        // 状态筛选 + 维度弹窗脚本
        html.append("      var currentFilter = null;\n")
            .append("      function filterByStatus(status) {\n")
            .append("        currentFilter = status;\n")
            .append("        var rows = document.querySelectorAll('#detailTable tbody tr.detail-row');\n")
            .append("        document.querySelectorAll('.stat-card').forEach(function(c) { c.classList.remove('active-filter'); });\n")
            .append("        document.getElementById('filterBar').style.display = 'flex';\n");
        if (total > 0) {
            html.append("        if (status === 'all') {\n")
                .append("          document.getElementById('filterLabel').textContent = '全部表 (").append(total).append(")';\n")
                .append("          document.getElementById('statAll').classList.add('active-filter');\n")
                .append("          rows.forEach(function(r) { r.style.display = ''; var n = r.nextElementSibling; if (n && n.classList.contains('detail-content')) n.style.display = 'none'; });\n")
                .append("        } else if (status === 'consistent') {\n")
                .append("          document.getElementById('filterLabel').textContent = '一致 (").append(consistentCount).append(")';\n")
                .append("          document.getElementById('statConsistent').classList.add('active-filter');\n")
                .append("          rows.forEach(function(r) { if (r.dataset.status === 'consistent') { r.style.display = ''; } else { r.style.display = 'none'; var n = r.nextElementSibling; if (n && n.classList.contains('detail-content')) n.style.display = 'none'; } });\n")
                .append("        } else if (status === 'inconsistent') {\n")
                .append("          document.getElementById('filterLabel').textContent = '不一致 (").append(inconsistentCount).append(")';\n")
                .append("          document.getElementById('statInconsistent').classList.add('active-filter');\n")
                .append("          rows.forEach(function(r) { if (r.dataset.status === 'inconsistent') { r.style.display = ''; } else { r.style.display = 'none'; var n = r.nextElementSibling; if (n && n.classList.contains('detail-content')) n.style.display = 'none'; } });\n")
                .append("        } else if (status === 'skipped') {\n")
                .append("          document.getElementById('filterLabel').textContent = '跳过 (").append(skippedCount).append(")';\n")
                .append("          document.getElementById('statSkipped').classList.add('active-filter');\n")
                .append("          rows.forEach(function(r) { if (r.dataset.status === 'skipped') { r.style.display = ''; } else { r.style.display = 'none'; var n = r.nextElementSibling; if (n && n.classList.contains('detail-content')) n.style.display = 'none'; } });\n")
                .append("        }\n");
        }
        html.append("      }\n")
            .append("      function clearFilter() {\n")
            .append("        currentFilter = null;\n")
            .append("        var rows = document.querySelectorAll('#detailTable tbody tr.detail-row');\n")
            .append("        rows.forEach(function(r) { r.style.display = ''; });\n")
            .append("        document.querySelectorAll('.stat-card').forEach(function(c) { c.classList.remove('active-filter'); });\n")
            .append("        document.getElementById('filterBar').style.display = 'none';\n")
            .append("      }\n");
        
        // 维度详情弹窗
        html.append("      var dimData = {};\n")
            .append("      dimData['count'] = ").append(toJson((List<Map<String, Object>>) stats.get("countDiffTables"))).append(";\n")
            .append("      dimData['amount'] = ").append(toJson((List<Map<String, Object>>) stats.get("amountDiffTables"))).append(";\n")
            .append("      dimData['hash'] = ").append(toJson((List<Map<String, Object>>) stats.get("hashDiffTables"))).append(";\n")
            .append("      dimData['index'] = ").append(toJson((List<Map<String, Object>>) stats.get("indexDiffTables"))).append(";\n")
            .append("      dimData['all'] = ").append(toJson((List<Map<String, Object>>) stats.get("allTables"))).append(";\n")
            .append("      var srcSchema = '").append(escapeHtml(srcSchema)).append("';\n")
            .append("      var tgtSchema = '").append(escapeHtml(tgtSchema)).append("';\n")
            .append("      var compareTaskId = ").append(task.getId() != null ? task.getId() : 0).append(";\n");
        
        // 弹窗 HTML
        html.append("      var modal = document.createElement('div');\n")
            .append("      modal.className = 'modal-overlay';\n")
            .append("      modal.id = 'dimModal';\n")
            .append("      modal.innerHTML = '<div class=\"dim-modal-dialog\"><div class=\"modal-header\"><h3 id=\"modalTitle\"></h3><button class=\"modal-close\" onclick=\"closeDimModal()\">&times;</button></div><div class=\"modal-toolbar\" id=\"modalToolbar\"></div><div class=\"modal-body\" id=\"modalBody\"></div><div class=\"modal-footer\" id=\"modalFooter\"></div></div>';\n")
            .append("      document.body.appendChild(modal);\n")
            .append("      modal.addEventListener('click', function(e) {\n")
            .append("        if (window.__dbcompareExtracting) return;\n")
            .append("        if (e.target !== modal) return;\n")
            .append("        closeDimModal();\n")
            .append("      });\n")
            .append("      \n")
            .append("      function openDimModal(type) {\n")
            .append("        var titles = {count: '条数差异详情', amount: '金额差异详情', hash: 'Hash差异详情', index: '索引差异详情'};\n")
            .append("        document.getElementById('modalTitle').textContent = titles[type] || '差异详情';\n")
            .append("        var data = dimData[type] || [];\n")
            .append("        var body = document.getElementById('modalBody');\n")
            .append("        var footer = document.getElementById('modalFooter');\n")
            .append("        var toolbar = document.getElementById('modalToolbar');\n")
            .append("        footer.classList.remove('show');\n")
            .append("        footer.innerHTML = '';\n")
            .append("        toolbar.classList.remove('show');\n")
            .append("        toolbar.innerHTML = '';\n")
            .append("        // 条数无差异时回退展示全部表，便于详细比对（状态等行级差异）\n")
            .append("        if (type === 'count' && data.length === 0 && (dimData['all'] || []).length > 0) {\n")
            .append("          data = dimData['all'];\n")
            .append("          document.getElementById('modalTitle').textContent = '全部表（条数一致，可详细比对）';\n")
            .append("        }\n")
            .append("        if (data.length === 0) {\n")
            .append("          body.innerHTML = '<p style=\"color:var(--gray);text-align:center;padding:20px;\">无差异</p>';\n")
            .append("        } else {\n")
            .append("          var h = '<p style=\"font-size:.82rem;color:var(--gray);margin-bottom:12px;\">源库: <strong>' + srcSchema + '</strong> &nbsp;|&nbsp; 目标库: <strong>' + tgtSchema + '</strong></p>';\n")
            .append("          if (type === 'count') {\n")
            .append("            var hasCountDiff = (dimData['count'] || []).length > 0;\n")
            .append("            if (hasCountDiff) {\n")
            .append("              toolbar.innerHTML = '<span class=\"hint\">勾选后提取为同步草稿（不会自动运行）；也可对单表「详细比对」</span>' +\n")
            .append("                '<button type=\"button\" class=\"btn-extract\" id=\"extractCountBtn\" data-dbcompare-extract=\"1\" ' +\n")
            .append("                'onclick=\"event.preventDefault();event.stopPropagation();' +\n")
            .append("                'if(window.extractCountDiffSync){window.extractCountDiffSync(event);}return false;\">提取为同步任务</button>';\n")
            .append("              toolbar.classList.add('show');\n")
            .append("              h += '<div style=\"margin-bottom:10px;display:flex;gap:12px;align-items:center;font-size:.82rem;\"><label style=\"cursor:pointer;\"><input type=\"checkbox\" id=\"countSelectAll\" checked onchange=\"toggleCountSelectAll(this.checked)\"> 全选</label><span class=\"hint\" id=\"countSelHint\"></span></div>';\n")
            .append("              h += '<table class=\"modal-tbl\"><thead><tr><th style=\"width:36px;\"></th><th>表名</th><th>源库条数</th><th>目标库条数</th><th>差异</th><th class=\"th-row-diff\" style=\"width:110px;\">操作</th></tr></thead><tbody>';\n")
            .append("              data.forEach(function(d) {\n")
            .append("                h += '<tr><td><input type=\"checkbox\" class=\"count-tbl-cb\" value=\"' + esc(d.table) + '\" checked onchange=\"updateCountSelHint()\"></td><td><strong>' + esc(d.table) + '</strong></td><td class=\"m-src\">' + d.sourceCount + '</td><td class=\"m-tgt\">' + d.targetCount + '</td><td class=\"m-diff\">' + fmtDiff(d.diff) + '</td><td><button type=\"button\" class=\"btn-row-diff\" data-table=\"' + esc(d.table) + '\">详细比对</button></td></tr>';\n")
            .append("              });\n")
            .append("            } else {\n")
            .append("              toolbar.innerHTML = '<span class=\"hint\">条数均一致，仍可对下表做「详细比对」检查状态/业务日期/金额等行级差异</span>';\n")
            .append("              toolbar.classList.add('show');\n")
            .append("              h += '<table class=\"modal-tbl\"><thead><tr><th>表名</th><th>源库条数</th><th>目标库条数</th><th>差异</th><th class=\"th-row-diff\" style=\"width:110px;\">操作</th></tr></thead><tbody>';\n")
            .append("              data.forEach(function(d) {\n")
            .append("                h += '<tr><td><strong>' + esc(d.table) + '</strong></td><td class=\"m-src\">' + d.sourceCount + '</td><td class=\"m-tgt\">' + d.targetCount + '</td><td class=\"m-diff\">' + fmtDiff(d.diff) + '</td><td><button type=\"button\" class=\"btn-row-diff\" data-table=\"' + esc(d.table) + '\">详细比对</button></td></tr>';\n")
            .append("              });\n")
            .append("            }\n")
            .append("            h += '</tbody></table>';\n")
            .append("          } else if (type === 'amount') {\n")
            .append("            h += '<table class=\"modal-tbl\"><thead><tr><th>表名</th><th>差异字段</th><th style=\"width:110px;\">操作</th></tr></thead><tbody>';\n")
            .append("            data.forEach(function(d) {\n")
            .append("              h += '<tr><td><strong>' + esc(d.table) + '</strong></td><td>' + esc(d.diffFields || '') + '</td>' +\n")
            .append("                '<td><button type=\"button\" class=\"btn-row-diff\" data-table=\"' + esc(d.table) + '\">详细比对</button></td></tr>';\n")
            .append("            });\n")
            .append("            h += '</tbody></table>';\n")
            .append("          } else if (type === 'hash') {\n")
            .append("            h += '<table class=\"modal-tbl\"><thead><tr><th>表名</th><th>源Hash</th><th>目标Hash</th><th style=\"width:110px;\">操作</th></tr></thead><tbody>';\n")
            .append("            data.forEach(function(d) {\n")
            .append("              h += '<tr><td><strong>' + esc(d.table) + '</strong></td><td class=\"m-src\">' + d.sourceHash + '</td><td class=\"m-tgt\">' + d.targetHash + '</td>' +\n")
            .append("                '<td><button type=\"button\" class=\"btn-row-diff\" data-table=\"' + esc(d.table) + '\">详细比对</button></td></tr>';\n")
            .append("            });\n")
            .append("            h += '</tbody></table>';\n")
            .append("          } else if (type === 'index') {\n")
            .append("            h += '<table class=\"modal-tbl\"><thead><tr><th>表名</th><th>差异详情</th><th style=\"width:110px;\">操作</th></tr></thead><tbody>';\n")
            .append("            data.forEach(function(d) {\n")
            .append("              var diffs = d.diffs;\n")
            .append("              var diffHtml = '';\n")
            .append("              if (Array.isArray(diffs)) { diffHtml = diffs.map(function(x) { return esc(x); }).join('<br>'); }\n")
            .append("              else { diffHtml = esc(diffs || ''); }\n")
            .append("              h += '<tr><td><strong>' + esc(d.table) + '</strong></td><td>' + diffHtml + '</td>' +\n")
            .append("                '<td><button type=\"button\" class=\"btn-row-diff\" data-table=\"' + esc(d.table) + '\">详细比对</button></td></tr>';\n")
            .append("            });\n")
            .append("            h += '</tbody></table>';\n")
            .append("          }\n")
            .append("          body.innerHTML = h;\n")
            .append("          if (type === 'count') {\n")
            .append("            updateCountSelHint();\n")
            .append("            var extractBtn = document.getElementById('extractCountBtn');\n")
            .append("            if (extractBtn && typeof window.extractCountDiffSync === 'function') {\n")
            .append("              extractBtn.onclick = function(ev) {\n")
            .append("                if (ev) { ev.preventDefault(); if (ev.stopPropagation) ev.stopPropagation(); }\n")
            .append("                return window.extractCountDiffSync(ev);\n")
            .append("              };\n")
            .append("            }\n")
            .append("          }\n")
            .append("        }\n")
            .append("        document.getElementById('dimModal').classList.add('show');\n")
            .append("      }\n")
            .append("      function closeDimModal() {\n")
            .append("        document.getElementById('dimModal').classList.remove('show');\n")
            .append("      }\n")
            .append("      function toggleCountSelectAll(checked) {\n")
            .append("        document.querySelectorAll('.count-tbl-cb').forEach(function(cb) { cb.checked = checked; });\n")
            .append("        updateCountSelHint();\n")
            .append("      }\n")
            .append("      function updateCountSelHint() {\n")
            .append("        var all = document.querySelectorAll('.count-tbl-cb');\n")
            .append("        var sel = document.querySelectorAll('.count-tbl-cb:checked');\n")
            .append("        var hint = document.getElementById('countSelHint');\n")
            .append("        if (hint) hint.textContent = '已选 ' + sel.length + ' / ' + all.length + ' 张表';\n")
            .append("        var allCb = document.getElementById('countSelectAll');\n")
            .append("        if (allCb) allCb.checked = all.length > 0 && sel.length === all.length;\n")
            .append("      }\n")
            .append("      function toastMsg(msg, type) {\n")
            .append("        if (window.AppToast && AppToast.show) AppToast.show(msg, type || 'info');\n")
            .append("      }\n")
            .append("      function extractCountDiffSync() {\n")
            .append("        if (!compareTaskId) { toastMsg('无法识别比对任务 ID', 'danger'); return; }\n")
            .append("        var tables = [];\n")
            .append("        document.querySelectorAll('.count-tbl-cb:checked').forEach(function(cb) { tables.push(cb.value); });\n")
            .append("        if (tables.length === 0) { toastMsg('请至少选择一张条数差异表', 'warning'); return; }\n")
            .append("        var btn = document.getElementById('extractCountBtn');\n")
            .append("        if (btn) { btn.disabled = true; btn.textContent = '创建中…'; }\n")
            .append("        fetch('/sync/from-compare/' + compareTaskId, {\n")
            .append("          method: 'POST',\n")
            .append("          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },\n")
            .append("          credentials: 'same-origin',\n")
            .append("          body: JSON.stringify({ tables: tables, syncMode: 'FULL_DML' })\n")
            .append("        }).then(function(r) {\n")
            .append("          return r.text().then(function(txt) {\n")
            .append("            var res = {};\n")
            .append("            try { res = txt ? JSON.parse(txt) : {}; } catch (e) {\n")
            .append("              throw new Error(r.ok ? '响应不是 JSON' : ('HTTP ' + r.status));\n")
            .append("            }\n")
            .append("            if (!r.ok) throw new Error(res.msg || ('HTTP ' + r.status));\n")
            .append("            return res;\n")
            .append("          });\n")
            .append("        }).then(function(res) {\n")
            .append("          if (res && res.success && res.taskId) {\n")
            .append("            var url = res.confirmUrl || ('/sync/confirm/' + res.taskId);\n")
            .append("            if (btn) btn.textContent = '已创建，正在跳转…';\n")
            .append("            try { window.top.location.assign(url); } catch (e1) {\n")
            .append("              try { window.location.assign(url); } catch (e2) { window.location.href = url; }\n")
            .append("            }\n")
            .append("            setTimeout(function() { try { window.top.location.href = url; } catch (e3) { window.location.href = url; } }, 400);\n")
            .append("          } else {\n")
            .append("            toastMsg((res && res.msg) ? res.msg : '创建失败', 'danger');\n")
            .append("            if (btn) { btn.disabled = false; btn.textContent = '提取为同步任务'; }\n")
            .append("          }\n")
            .append("        }).catch(function(e) {\n")
            .append("          toastMsg('请求失败: ' + (e && e.message ? e.message : e), 'danger');\n")
            .append("          if (btn) { btn.disabled = false; btn.textContent = '提取为同步任务'; }\n")
            .append("        });\n")
            .append("      }\n")
            .append("      window.extractCountDiffSync = extractCountDiffSync;\n")
            .append("      function esc(s) {\n")
            .append("        if (s === null || s === undefined) return '';\n")
            .append("        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\"/g, '&quot;');\n")
            .append("      }\n")
            .append("      function fmtDiff(v) {\n")
            .append("        if (v === null || v === undefined) return '';\n")
            .append("        var n = Number(v);\n")
            .append("        return n > 0 ? '+' + n : '' + n;\n")
            .append("      }\n")
            .append("    </script>\n");
        
        html.append("  </div>\n")
            .append("</body>\n")
            .append("</html>");
        
        Long id = task.getId() != null ? task.getId() : 0L;
        return CompareReportHtmlSupport.enhance(html.toString(), id);
    }
    
    /**
     * 加载报告元数据
     */
    public Map<String, Object> loadReport(CompareTask task) {
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getId());
        result.put("taskName", task.getTaskName());
        result.put("status", task.getStatus());
        result.put("errorMessage", task.getErrorMessage());

        if (task.getReportPath() != null) {
            String normalized = task.getReportPath().replace("\\", "/");
            result.put("htmlUrl", "/" + normalized);
            result.put("markdownUrl", "/" + normalized.replace("report.html", "report.md"));

            java.nio.file.Path path = java.nio.file.Paths.get(task.getReportPath());
            if (java.nio.file.Files.exists(path)) {
                try {
                    result.put("htmlExists", true);
                    result.put("fileSize", java.nio.file.Files.size(path));
                } catch (IOException e) {
                    log.warn("读取报告文件信息失败: {}", task.getReportPath(), e);
                }
            }
        }
        return result;
    }
    
    /**
     * 写入文件（强制 UTF-8，避免 Windows 默认 GBK 导致报告乱码）
     */
    private void writeFile(String filePath, String content) throws IOException {
        Files.write(Paths.get(filePath), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 格式化差异值（保留正负号）
     */
    private String formatDiff(Object diff) {
        if (diff == null) return "";
        if (diff instanceof Number) {
            long n = ((Number) diff).longValue();
            return n > 0 ? "+" + n : String.valueOf(n);
        }
        return String.valueOf(diff);
    }

    /**
     * HTML 转义（防止 XSS）
     */
    private String escapeHtml(Object obj) {
        if (obj == null) return "null";
        String s = String.valueOf(obj);
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 格式化 schema 显示名
     * null → "默认库", "" → "全部", "a,b" → "a, b"
     */
    private String formatSchemaDisplay(String schema) {
        if (schema == null) return "默认库";
        if (schema.isEmpty()) return "全部";
        return schema.replace(",", ", ");
    }

    /**
     * 格式化耗时为人类可读字符串
     */
    private String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        long hr = totalSeconds / 3600;
        long min = (totalSeconds % 3600) / 60;
        long sec = totalSeconds % 60;
        if (hr > 0) return hr + "h " + min + "m";
        if (min > 0) return min + "m " + sec + "s";
        return sec + "s";
    }

    /**
     * 将 List<Map> 转为简单 JSON 字符串（用于前端 JS 变量注入）
     */
    @SuppressWarnings("unchecked")
    private String toJson(List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map<String, Object> item : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            boolean firstKv = true;
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                if (!firstKv) sb.append(",");
                firstKv = false;
                sb.append("\"").append(jsonEscape(entry.getKey())).append("\":");
                Object val = entry.getValue();
                if (val == null) {
                    sb.append("null");
                } else if (val instanceof Number) {
                    sb.append(val);
                } else if (val instanceof List) {
                    sb.append("[");
                    boolean firstEl = true;
                    for (Object el : (List<?>) val) {
                        if (!firstEl) sb.append(",");
                        firstEl = false;
                        if (el == null) sb.append("null");
                        else sb.append("\"").append(jsonEscape(String.valueOf(el))).append("\"");
                    }
                    sb.append("]");
                } else {
                    sb.append("\"").append(jsonEscape(String.valueOf(val))).append("\"");
                }
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
