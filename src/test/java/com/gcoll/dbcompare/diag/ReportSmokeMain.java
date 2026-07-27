package com.gcoll.dbcompare.diag;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.service.ReportService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 报告生成冒烟：不启动 Spring，直接调用 {@link ReportService#generateReport}。
 * <pre>
 * mvn -q -DskipTests exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.gcoll.dbcompare.diag.ReportSmokeMain
 * </pre>
 * 请在项目根目录执行（写入 {@code reports/}）。
 */
public final class ReportSmokeMain {

    private ReportSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        ReportService rs = new ReportService();
        CompareTask task = new CompareTask();
        task.setId(99999L);
        task.setTaskName("t");
        task.setCompareMode("FULL");
        task.setStatus("SUCCESS");
        task.setTotalTables(1);
        task.setStartTime(LocalDateTime.now());
        task.setEndTime(LocalDateTime.now());

        Map<String, Object> tr = new HashMap<>();
        tr.put("consistent", true);
        tr.put("schema", "一致");
        Map<String, Object> count = new HashMap<>();
        count.put("sourceCount", 100L);
        count.put("targetCount", 100L);
        count.put("diff", 0L);
        count.put("consistent", true);
        tr.put("count", count);
        tr.put("amount", "无金额字段");
        Map<String, Object> hash = new HashMap<>();
        hash.put("consistent", true);
        tr.put("rowHash", hash);
        Map<String, Object> result = new HashMap<>();
        result.put("user_info", tr);

        String p = rs.generateReport(task, result);
        String html = new String(Files.readAllBytes(Paths.get(p)));
        System.out.println("len=" + html.length() + " hasTitle=" + html.contains("数据库比对报告"));
        System.out.println(html.substring(0, Math.min(500, html.length())));
    }
}
