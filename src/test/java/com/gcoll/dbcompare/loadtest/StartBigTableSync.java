package com.gcoll.dbcompare.loadtest;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;

/**
 * 触发 6000 万表同步任务（需应用已启动，且数据源已在 UI 注册）。
 * <pre>
 * mvn -q -DskipTests exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.gcoll.dbcompare.loadtest.StartBigTableSync \
 *   -Dexec.args="http://localhost:8080 1 2 loadtest::big_sync_60m FULL_DDL 50000"
 * </pre>
 * 参数：baseUrl sourceDsId targetDsId includeTables [syncMode=FULL_DDL] [batchSize=50000]
 */
public final class StartBigTableSync {

    private StartBigTableSync() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法: baseUrl sourceDsId targetDsId includeTables [syncMode] [batchSize]");
            System.err.println("示例: http://localhost:8080 1 2 loadtest::big_sync_60m FULL_DDL 50000");
            System.exit(2);
        }
        String base = args[0].replaceAll("/$", "");
        long srcId = Long.parseLong(args[1]);
        long tgtId = Long.parseLong(args[2]);
        String include = args[3];
        String mode = args.length > 4 ? args[4] : "FULL_DDL";
        int batch = args.length > 5 ? Integer.parseInt(args[5]) : 50_000;

        String body = String.format(Locale.ROOT,
                "{\"taskName\":\"loadtest-60m\",\"sourceDataSourceId\":%d,\"targetDataSourceId\":%d,"
                        + "\"includeTables\":\"%s\",\"syncMode\":\"%s\",\"batchSize\":%d,\"parallelThreads\":1}",
                srcId, tgtId, include.replace("\"", "\\\""), mode, batch);

        URL url = new URL(base + "/sync/execute");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        String resp = read(conn);
        System.out.println("HTTP " + code);
        System.out.println(resp);
        if (code < 200 || code >= 300) {
            System.exit(1);
        }
        System.out.println("请打开 " + base + "/sync/list 查看进度；日志应出现【DataX流式】");
    }

    private static String read(HttpURLConnection conn) {
        try {
            java.io.InputStream in = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return "";
            }
            try (Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
                return sc.hasNext() ? sc.next() : "";
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
