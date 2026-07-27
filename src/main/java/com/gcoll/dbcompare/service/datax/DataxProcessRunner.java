package com.gcoll.dbcompare.service.datax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 调用本机 DataX：{@code python ${DATAX_HOME}/bin/datax.py job.json}
 */
public final class DataxProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(DataxProcessRunner.class);

    private DataxProcessRunner() {
    }

    /**
     * 解析 datax.py。支持常见解压嵌套：
     * <ul>
     *   <li>{@code HOME/bin/datax.py}</li>
     *   <li>{@code HOME/datax/bin/datax.py}（如 D:\...\datax\datax\bin）</li>
     *   <li>直接填写 {@code .../bin} 或 {@code .../datax.py}</li>
     * </ul>
     */
    public static Path resolveDataxPy(String dataxHome) {
        if (dataxHome == null || dataxHome.trim().isEmpty()) {
            throw new IllegalArgumentException("未配置 DataX 安装目录（参数设置 → DataX 安装目录）");
        }
        Path home = Paths.get(dataxHome.trim()).toAbsolutePath().normalize();
        List<Path> tried = new ArrayList<>();

        if (Files.isRegularFile(home) && "datax.py".equalsIgnoreCase(name(home))) {
            return home;
        }
        if (Files.isDirectory(home) && "bin".equalsIgnoreCase(name(home))) {
            Path inBin = home.resolve("datax.py");
            tried.add(inBin);
            if (Files.isRegularFile(inBin)) {
                return inBin;
            }
        }

        Path[] candidates = new Path[] {
                home.resolve("bin").resolve("datax.py"),
                home.resolve("datax").resolve("bin").resolve("datax.py")
        };
        for (Path c : candidates) {
            tried.add(c);
            if (Files.isRegularFile(c)) {
                if (!c.equals(candidates[0])) {
                    log.info("DataX 入口使用嵌套路径: {}", c);
                }
                return c;
            }
        }

        StringBuilder sb = new StringBuilder("未找到 DataX 入口。已尝试:");
        for (Path t : tried) {
            sb.append("\n  - ").append(t);
        }
        sb.append("\n请将「DataX 安装目录」设为含 bin/datax.py 的目录，")
                .append("例如 D:\\工具\\数据库\\datax\\datax （不要少一层 datax）");
        throw new IllegalArgumentException(sb.toString());
    }

    private static String name(Path p) {
        Path fn = p.getFileName();
        return fn == null ? "" : fn.toString();
    }

    /**
     * @param cancelCheck 返回 true 时销毁进程
     * @param lineConsumer 接收 stdout/stderr 行（可为 null）
     * @param pollHeartbeat 每轮 wait 轮询调用（工作线程，用于看门狗心跳；可为 null）
     * @return 进程退出码（0 成功）
     */
    public static int run(Path dataxPy, Path jobJson, BooleanSupplier cancelCheck,
                          Consumer<String> lineConsumer, Runnable pollHeartbeat)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvePython());
        cmd.add(dataxPy.toString());
        cmd.add(jobJson.toAbsolutePath().toString());

        log.info("启动 DataX: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(dataxPy.getParent().getParent().toFile());

        Process process = pb.start();
        AtomicReference<Process> ref = new AtomicReference<>(process);
        Thread reader = new Thread(() -> {
            Charset cs = Charset.defaultCharset();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), cs))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (lineConsumer != null) {
                        lineConsumer.accept(line);
                    }
                }
            } catch (IOException e) {
                log.warn("读取 DataX 输出失败: {}", e.getMessage());
            }
        }, "datax-stdout");
        reader.setDaemon(true);
        reader.start();

        try {
            while (true) {
                if (pollHeartbeat != null) {
                    try {
                        pollHeartbeat.run();
                    } catch (Exception e) {
                        log.debug("DataX poll heartbeat: {}", e.getMessage());
                    }
                }
                if (cancelCheck != null && cancelCheck.getAsBoolean()) {
                    log.warn("取消 DataX，销毁进程");
                    destroyQuietly(ref.get());
                    throw new InterruptedException("DataX 任务已取消");
                }
                if (process.waitFor(2, TimeUnit.SECONDS)) {
                    break;
                }
            }
            reader.join(5000L);
            return process.exitValue();
        } finally {
            destroyQuietly(ref.get());
        }
    }

    /** 兼容旧调用 */
    public static int run(Path dataxPy, Path jobJson, BooleanSupplier cancelCheck,
                          Consumer<String> lineConsumer) throws IOException, InterruptedException {
        return run(dataxPy, jobJson, cancelCheck, lineConsumer, null);
    }

    private static String resolvePython() {
        String override = System.getenv("DATAX_PYTHON");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return "python";
    }

    private static void destroyQuietly(Process p) {
        if (p == null) {
            return;
        }
        try {
            if (p.isAlive()) {
                p.destroy();
                if (!p.waitFor(5, TimeUnit.SECONDS) && p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
        }
    }
}
