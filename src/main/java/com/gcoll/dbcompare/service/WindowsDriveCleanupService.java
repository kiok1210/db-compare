package com.gcoll.dbcompare.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本机 Windows C 盘白名单缓存清理：先审计、确认后再删。
 * 不触碰文档/下载/Program Files/WinSxS。
 */
@Service
public class WindowsDriveCleanupService {

    public static final int DEFAULT_MIN_AGE_DAYS = 7;
    public static final int MAX_MIN_AGE_DAYS = 3650;
    public static final int DEFAULT_LARGE_MIN_MB = 50;
    static final int TOP_FILES_PER_TARGET = 20;
    static final int TOP_FOLDERS_PER_TARGET = 15;
    static final int TOP_FILES_GLOBAL = 50;
    static final int MAX_LARGE_FILES = 80;
    static final int MAX_DUP_GROUPS = 40;
    static final long MIN_DUP_BYTES = 512L * 1024;
    static final long MAX_DUP_HASH_BYTES = 32L * 1024 * 1024;
    static final int MAX_DUP_HASH_FILES = 160;
    static final int MAX_DUP_PER_SIZE = 12;

    private final PathLocator locator;
    private final DriveSpace driveSpace;
    private final Boolean windowsOverride;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Object progressLock = new Object();
    private ProgressSnapshot progress = ProgressSnapshot.idle();

    public WindowsDriveCleanupService() {
        this(PathLocator.system(), DriveSpace.system(), null);
    }

    WindowsDriveCleanupService(PathLocator locator, DriveSpace driveSpace, Boolean windowsOverride) {
        this.locator = locator;
        this.driveSpace = driveSpace;
        this.windowsOverride = windowsOverride;
    }

    public boolean isWindows() {
        if (windowsOverride != null) {
            return windowsOverride;
        }
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean win = isWindows();
        out.put("success", true);
        out.put("windows", win);
        out.put("osName", System.getProperty("os.name", ""));
        out.put("freeBytes", win ? driveSpace.freeBytes() : 0L);
        out.put("totalBytes", win ? driveSpace.totalBytes() : 0L);
        return out;
    }

    public Map<String, Object> usage() {
        Map<String, Object> out = status();
        List<Map<String, Object>> reclaimable = new ArrayList<>();
        long sum = 0;
        if (isWindows()) {
            List<Target> targets = buildTargets(Preset.DEEP, true, true);
            for (Target t : targets) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", t.name);
                row.put("path", t.path == null ? "" : t.path.toString());
                if (t.path == null || !Files.isDirectory(t.path) || isForbiddenLocation(t.path)) {
                    row.put("exists", false);
                    row.put("totalMb", 0);
                } else {
                    long bytes = dirSize(t.path, 1200L);
                    row.put("exists", true);
                    row.put("totalMb", roundMb(bytes));
                    sum += bytes;
                }
                reclaimable.add(row);
            }
        }
        out.put("reclaimableMb", roundMb(sum));
        out.put("reclaimable", reclaimable);
        List<Map<String, String>> notes = new ArrayList<>();
        notes.add(usageNote("Program Files / (x86)", "约 27 GB", "已安装程序，不清理"));
        notes.add(usageNote("WinSxS", "约 8.9 GB", "Windows 组件存储，不清理"));
        notes.add(usageNote("Unity 等应用目录", "约 3 GB+", "程序自己的数据，不清理"));
        out.put("notCleaned", notes);
        out.put("hint", "白名单只能清 Temp/pip/npm/更新缓存等，通常再挤出数 GB；C 盘其余是系统和软件，不会也不该删。");
        return out;
    }

    private static Map<String, String> usageNote(String name, String size, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("size", size);
        m.put("reason", reason);
        return m;
    }

    static long dirSize(Path root) {
        return dirSize(root, 0L);
    }

    static long dirSize(Path root, long maxMillis) {
        if (root == null || !Files.isDirectory(root)) {
            return 0L;
        }
        final long deadline = maxMillis <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + maxMillis;
        final long[] n = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        n[0] += attrs.size();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (System.currentTimeMillis() > deadline) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return n[0];
    }

    public ProgressSnapshot progress() {
        synchronized (progressLock) {
            return copyProgress(progress);
        }
    }

    public Report run(Request req) {
        if (req == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (!isWindows()) {
            throw new IllegalArgumentException("当前主机不是 Windows，无法清理 C 盘");
        }
        Mode mode = req.mode == null ? Mode.AUDIT : req.mode;
        Preset preset = req.preset == null ? Preset.STANDARD : req.preset;
        int minAge = clampAge(req.minAgeDays);
        if (mode == Mode.CLEAN && !req.confirmClean) {
            throw new IllegalArgumentException("清理需先勾选确认（confirmClean=true）");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalArgumentException("已有审计或清理正在执行");
        }

        try {
            return runLocked(req, mode, preset, minAge);
        } finally {
            busy.set(false);
        }
    }

    private Report runLocked(Request req, Mode mode, Preset preset, int minAge) {
        long cutoff = System.currentTimeMillis() - minAge * 24L * 3600_000L;
        long before = driveSpace.freeBytes();
        List<Target> targets = buildTargets(preset, req.includeBrowserCaches, req.includeWeChatCaches);
        List<TargetRow> rows = new ArrayList<>();
        List<DetailRow> globalFiles = new ArrayList<>();
        List<FileRecord> index = new ArrayList<>();
        long deletedBytes = 0;
        long deletedFiles = 0;
        long skipped = 0;
        long eligibleSumBytes = 0;
        int total = Math.max(1, targets.size());

        publish(true, mode.name(), "准备扫描", "", 0, total, 0, 0, 0, 0, 0, 0,
                mode == Mode.AUDIT ? "开始审计" : "开始清理");

        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            TargetRow row = new TargetRow();
            row.setName(t.name);
            row.setPath(t.path == null ? "" : t.path.toString());
            row.setMinAgeDays(minAge);
            int basePct = mode == Mode.CLEAN ? (i * 40) / total : (i * 100) / total;
            publish(true, mode.name(), t.name, row.getPath(), i + 1, total, 0, 0, 0, 0, 0, basePct,
                    "扫描 " + t.name);

            if (t.path == null || !Files.isDirectory(t.path)) {
                row.setExists(false);
                row.setAction("skip-missing");
                rows.add(row);
                continue;
            }
            row.setExists(true);
            if (isForbiddenLocation(t.path)) {
                row.setAction("skip-forbidden");
                skipped++;
                rows.add(row);
                continue;
            }
            ScanResult scan = scan(t.path, t.filters, cutoff, t.name, i, total, mode);
            row.setTotalMb(roundMb(scan.totalBytes));
            row.setEligibleMb(roundMb(scan.eligibleBytes));
            row.setEligibleFiles(scan.eligible.size());
            row.setScannedFiles(scan.scannedFiles);
            row.setFolders(toFolderDetails(t.name, scan.folders));
            row.setFiles(toFileDetails(t.name, scan.topFiles.toListDesc(), false));
            eligibleSumBytes += scan.eligibleBytes;
            index.addAll(scan.allFiles);
            mergeTop(globalFiles, row.getFiles(), TOP_FILES_GLOBAL);

            if (mode == Mode.AUDIT) {
                row.setAction("audit");
            } else {
                row.setAction("clean");
                long targetDeleted = 0;
                long targetDeletedBytes = 0;
                int eligibleN = Math.max(1, scan.eligible.size());
                for (int f = 0; f < scan.eligible.size(); f++) {
                    Path file = scan.eligible.get(f);
                    if (!isSafeUnder(file, t.path) || isForbiddenLocation(file)) {
                        skipped++;
                        continue;
                    }
                    try {
                        long len = Files.size(file);
                        Files.delete(file);
                        deletedBytes += len;
                        deletedFiles++;
                        targetDeleted++;
                        targetDeletedBytes += len;
                    } catch (Exception e) {
                        skipped++;
                    }
                    if (f % 25 == 0 || f + 1 == scan.eligible.size()) {
                        int pct = 40 + (int) ((i * 60.0 + (f + 1) * 60.0 / eligibleN) / total);
                        publish(true, mode.name(), t.name, file.toString(), i + 1, total,
                                scan.scannedFiles, scan.eligible.size(), scan.eligibleBytes,
                                deletedFiles, deletedBytes, Math.min(99, pct),
                                "删除 " + t.name);
                    }
                }
                row.setDeletedFiles(targetDeleted);
                row.setDeletedMb(roundMb(targetDeletedBytes));
                markDeleted(row.getFiles());
            }
            rows.add(row);
        }

        int largeMin = req.largeMinMb == null || req.largeMinMb < 1 ? DEFAULT_LARGE_MIN_MB : req.largeMinMb;
        final int analyzeBase = mode == Mode.CLEAN ? 85 : 90;
        final int analyzeTotal = total;
        final long analyzeScanned = index.size();
        final long analyzeDeletedFiles = deletedFiles;
        final long analyzeDeletedBytes = deletedBytes;
        final Mode analyzeMode = mode;
        publish(true, analyzeMode.name(), "大文件/重复", "", analyzeTotal, analyzeTotal, analyzeScanned, 0, 0,
                analyzeDeletedFiles, analyzeDeletedBytes, analyzeBase, "分析大文件");
        List<LargeFileRow> largeFiles = pickLargeFiles(index, largeMin);
        publish(true, analyzeMode.name(), "大文件/重复", "", analyzeTotal, analyzeTotal, analyzeScanned, 0, 0,
                analyzeDeletedFiles, analyzeDeletedBytes, analyzeBase, "校验重复文件");
        List<DuplicateGroup> dupGroups = findDuplicates(index, new DupHashListener() {
            @Override
            public void onHash(int done, int planned) {
                int span = 8;
                int pct = analyzeBase + (planned <= 0 ? span : span * done / planned);
                publish(true, analyzeMode.name(), "大文件/重复", "", analyzeTotal, analyzeTotal, analyzeScanned, 0, 0,
                        analyzeDeletedFiles, analyzeDeletedBytes, Math.min(99, pct),
                        "校验重复文件 " + done + "/" + planned);
            }
        });
        double dupWaste = 0;
        for (DuplicateGroup g : dupGroups) {
            dupWaste += g.getWastedMb();
        }

        if (mode == Mode.CLEAN) {
            if (req.deleteDuplicateExtras) {
                for (DuplicateGroup g : dupGroups) {
                    if (g.getExtras() == null) {
                        continue;
                    }
                    for (LargeFileRow extra : g.getExtras()) {
                        Path p = extra.getFullPath() == null ? null : Paths.get(extra.getFullPath());
                        if (p == null || !Files.isRegularFile(p)) {
                            extra.setDeleted(true);
                            continue;
                        }
                        if (!canDeleteIndexed(p, targets)) {
                            skipped++;
                            continue;
                        }
                        try {
                            long len = Files.size(p);
                            Files.delete(p);
                            deletedBytes += len;
                            deletedFiles++;
                            extra.setDeleted(true);
                        } catch (Exception e) {
                            skipped++;
                        }
                    }
                }
            }
            if (req.largeFilePaths != null) {
                for (String raw : req.largeFilePaths) {
                    if (raw == null || raw.trim().isEmpty()) {
                        continue;
                    }
                    Path p = Paths.get(raw);
                    if (!canDeleteIndexed(p, targets)) {
                        skipped++;
                        continue;
                    }
                    boolean listed = false;
                    for (LargeFileRow lf : largeFiles) {
                        if (raw.equals(lf.getFullPath())) {
                            listed = true;
                            break;
                        }
                    }
                    if (!listed) {
                        skipped++;
                        continue;
                    }
                    try {
                        if (!Files.isRegularFile(p)) {
                            continue;
                        }
                        long len = Files.size(p);
                        Files.delete(p);
                        deletedBytes += len;
                        deletedFiles++;
                        for (LargeFileRow lf : largeFiles) {
                            if (raw.equals(lf.getFullPath())) {
                                lf.setDeleted(true);
                            }
                        }
                    } catch (Exception e) {
                        skipped++;
                    }
                }
            }
        }

        long after = driveSpace.freeBytes();
        Report report = new Report();
        report.setSuccess(true);
        report.setWindows(true);
        report.setMode(mode.name());
        report.setPreset(preset.name());
        report.setConfirmClean(req.confirmClean);
        report.setMinAgeDays(minAge);
        report.setIncludeBrowserCaches(req.includeBrowserCaches);
        report.setIncludeWeChatCaches(req.includeWeChatCaches);
        report.setDeleteDuplicateExtras(req.deleteDuplicateExtras);
        report.setLargeMinMb(largeMin);
        report.setLargeFiles(largeFiles);
        report.setDuplicateGroups(dupGroups);
        report.setDuplicateWastedMb(Math.round(dupWaste * 100.0) / 100.0);
        report.setFreeBeforeGb(roundGb(before));
        report.setFreeAfterGb(roundGb(after));
        report.setEstimatedEligibleMb(roundMb(eligibleSumBytes));
        report.setDeletedMb(roundMb(deletedBytes));
        report.setDeletedFiles(deletedFiles);
        report.setSkipped(skipped);
        report.setTargets(rows);
        report.setDetails(takeTop(globalFiles, TOP_FILES_GLOBAL));
        String doneMsg = mode == Mode.CLEAN
                ? ("清理完成，已删 " + deletedFiles + " 个文件")
                : ("审计完成，预计可回收 " + report.getEstimatedEligibleMb() + " MB（尚未删除）");
        publish(false, mode.name(), "", "", total, total, 0,
                rows.stream().mapToLong(TargetRow::getEligibleFiles).sum(),
                eligibleSumBytes, deletedFiles, deletedBytes, 100, doneMsg);
        return report;
    }

    private List<Target> buildTargets(Preset preset, boolean includeBrowser, boolean includeWeChat) {
        List<Target> list = new ArrayList<>();
        addUnique(list, "User TEMP", locator.userTemp(), "*");
        Path localTemp = locator.localAppData() == null ? null : locator.localAppData().resolve("Temp");
        if (localTemp != null && !samePath(localTemp, locator.userTemp())) {
            addUnique(list, "User Local Temp", localTemp, "*");
        }
        Path win = locator.windowsRoot();
        addUnique(list, "Windows Temp", win == null ? null : win.resolve("Temp"), "*");
        if (preset == Preset.DEEP) {
            addUnique(list, "Windows Update Download",
                    win == null ? null : win.resolve("SoftwareDistribution").resolve("Download"), "*");
            Path pd = locator.programData();
            addUnique(list, "Delivery Optimization",
                    pd == null ? null : pd.resolve("Microsoft").resolve("Windows").resolve("DeliveryOptimization").resolve("Cache"), "*");
            addUnique(list, "Windows Error Reporting",
                    pd == null ? null : pd.resolve("Microsoft").resolve("Windows").resolve("WER"), "*");
            Path la = locator.localAppData();
            addUnique(list, "Crash dumps", la == null ? null : la.resolve("CrashDumps"), "*");
            addUnique(list, "NVIDIA DXCache", la == null ? null : la.resolve("NVIDIA").resolve("DXCache"), "*");
            addUnique(list, "NVIDIA GLCache", la == null ? null : la.resolve("NVIDIA").resolve("GLCache"), "*");
            addUnique(list, "DirectX D3DSCache", la == null ? null : la.resolve("D3DSCache"), "*");
            addUnique(list, "Explorer thumbnail cache",
                    la == null ? null : la.resolve("Microsoft").resolve("Windows").resolve("Explorer"),
                    "thumbcache_*.db", "iconcache*.db");
            addUnique(list, "pip Cache", la == null ? null : la.resolve("pip").resolve("Cache"), "*");
            addUnique(list, "npm-cache", la == null ? null : la.resolve("npm-cache"), "*");
            addUnique(list, "Yarn Cache", la == null ? null : la.resolve("Yarn").resolve("Cache"), "*");
            addUnique(list, "pnpm store", la == null ? null : la.resolve("pnpm").resolve("store"), "*");
            addUnique(list, "Playwright browsers", la == null ? null : la.resolve("ms-playwright"), "*");
            Path home = locator.userHome();
            addUnique(list, "NuGet packages",
                    home == null ? null : home.resolve(".nuget").resolve("packages"), "*");
            addUnique(list, "Recycle Bin", locator.recycleRoot(), "*");
        }
        if (includeBrowser) {
            Path la = locator.localAppData();
            Path ad = locator.appData();
            if (la != null) {
                addUnique(list, "Chrome Cache", la.resolve("Google").resolve("Chrome").resolve("User Data").resolve("Default").resolve("Cache"), "*");
                addUnique(list, "Chrome Code Cache", la.resolve("Google").resolve("Chrome").resolve("User Data").resolve("Default").resolve("Code Cache"), "*");
                addUnique(list, "Chrome GPUCache", la.resolve("Google").resolve("Chrome").resolve("User Data").resolve("Default").resolve("GPUCache"), "*");
                addUnique(list, "Edge Cache", la.resolve("Microsoft").resolve("Edge").resolve("User Data").resolve("Default").resolve("Cache"), "*");
                addUnique(list, "Edge Code Cache", la.resolve("Microsoft").resolve("Edge").resolve("User Data").resolve("Default").resolve("Code Cache"), "*");
                addUnique(list, "Edge GPUCache", la.resolve("Microsoft").resolve("Edge").resolve("User Data").resolve("Default").resolve("GPUCache"), "*");
            }
            if (ad != null) {
                Path ff = ad.resolve("Mozilla").resolve("Firefox").resolve("Profiles");
                if (Files.isDirectory(ff)) {
                    try {
                        Files.list(ff).filter(Files::isDirectory).forEach(p ->
                                addUnique(list, "Firefox cache2 " + p.getFileName(), p.resolve("cache2"), "*"));
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        if (includeWeChat) {
            addWeChatTargets(list);
        }
        return list;
    }

    private void addWeChatTargets(List<Target> list) {
        Path ad = locator.appData();
        if (ad != null) {
            Path xw = ad.resolve("Tencent").resolve("xwechat");
            addUnique(list, "WeChat xwechat log", xw.resolve("log"), "*");
            addUnique(list, "WeChat xwechat radium", xw.resolve("radium"), "*");
            addUnique(list, "WeChat xwechat crashinfo", xw.resolve("crashinfo"), "*");
            addUnique(list, "WeChat xwechat XPlugin", xw.resolve("XPlugin"), "*");
            Path classic = ad.resolve("Tencent").resolve("WeChat");
            addUnique(list, "WeChat classic log", classic.resolve("log"), "*");
        }
        Path la = locator.localAppData();
        if (la != null) {
            addUnique(list, "WeChat Local Cache",
                    la.resolve("Tencent").resolve("WeChat").resolve("Cache"), "*");
        }
        Path home = locator.userHome();
        if (home == null) {
            return;
        }
        addWeChatAccountCaches(list, home.resolve("Documents").resolve("WeChat Files"),
                "WeChat Files Cache", "FileStorage", "Cache");
        addWeChatAccountCaches(list, home.resolve("Documents").resolve("WeChat Files"),
                "WeChat Files Temp", "FileStorage", "Temp");
        addWeChatAccountCaches(list, home.resolve("文档").resolve("WeChat Files"),
                "WeChat Files Cache", "FileStorage", "Cache");
        addWeChatAccountCaches(list, home.resolve("文档").resolve("WeChat Files"),
                "WeChat Files Temp", "FileStorage", "Temp");
        addWeChatAccountCaches(list, home.resolve("Documents").resolve("xwechat_files"),
                "xwechat_files cache", "cache");
        addWeChatAccountCaches(list, home.resolve("文档").resolve("xwechat_files"),
                "xwechat_files cache", "cache");
    }

    private void addWeChatAccountCaches(List<Target> list, Path accountsRoot, String labelPrefix, String... rel) {
        if (accountsRoot == null || !Files.isDirectory(accountsRoot) || rel == null || rel.length == 0) {
            return;
        }
        try {
            Files.list(accountsRoot).filter(Files::isDirectory).forEach(account -> {
                Path cur = account;
                for (String p : rel) {
                    cur = cur.resolve(p);
                }
                addUnique(list, labelPrefix + " " + account.getFileName(), cur, "*");
            });
        } catch (IOException ignored) {
        }
    }

    private static void addUnique(List<Target> list, String name, Path path, String... filters) {
        if (path == null) {
            return;
        }
        Path n = canonical(path);
        for (Target t : list) {
            if (t.path != null && samePath(t.path, n)) {
                return;
            }
        }
        list.add(new Target(name, n, filters));
    }

    static Path canonical(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (Files.exists(path)) {
                return path.toRealPath();
            }
        } catch (IOException ignored) {
        }
        return path.toAbsolutePath().normalize();
    }

    static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        return canonical(a).equals(canonical(b));
    }

    private ScanResult scan(final Path root, final String[] filters, final long cutoffMillis,
                            final String targetName, final int targetIndex, final int targetTotal,
                            final Mode mode) {
        final ScanResult result = new ScanResult();
        final Path rootNorm = canonical(root);
        try {
            Files.walkFileTree(rootNorm, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(rootNorm) && isForbiddenLocation(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isSafeUnder(file, rootNorm) || isForbiddenLocation(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!matchesFilter(file, filters)) {
                        return FileVisitResult.CONTINUE;
                    }
                    result.scannedFiles++;
                    long len = attrs.size();
                    result.totalBytes += len;
                    result.allFiles.add(new FileRecord(targetName, file.toString(), relPath(rootNorm, file),
                            len, attrs.lastModifiedTime().toMillis()));
                    if (attrs.lastModifiedTime().toMillis() < cutoffMillis) {
                        result.eligibleBytes += len;
                        result.eligible.add(file);
                        String folder = firstFolder(rootNorm, file);
                        FolderAgg agg = result.folders.get(folder);
                        if (agg == null) {
                            agg = new FolderAgg(folder);
                            result.folders.put(folder, agg);
                        }
                        agg.bytes += len;
                        agg.files++;
                        result.topFiles.add(new FileHit(file.toString(), relPath(rootNorm, file),
                                len, attrs.lastModifiedTime().toMillis()));
                    }
                    if (result.scannedFiles % 80 == 0) {
                        int pct = mode == Mode.CLEAN
                                ? (targetIndex * 40) / Math.max(1, targetTotal)
                                : (int) (((targetIndex + 0.5d) * 100) / Math.max(1, targetTotal));
                        publish(true, mode.name(), targetName, file.toString(), targetIndex + 1, targetTotal,
                                result.scannedFiles, result.eligible.size(), result.eligibleBytes,
                                0, 0, Math.min(99, pct), "扫描 " + targetName);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return result;
    }

    static String firstFolder(Path root, Path file) {
        try {
            Path rel = root.relativize(file.toAbsolutePath().normalize());
            if (rel.getNameCount() <= 1) {
                return "（根目录）";
            }
            return rel.getName(0).toString();
        } catch (Exception e) {
            return "（根目录）";
        }
    }

    static String relPath(Path root, Path file) {
        try {
            return root.relativize(file.toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            return file.toString();
        }
    }

    private static List<DetailRow> toFolderDetails(String target, Map<String, FolderAgg> folders) {
        List<FolderAgg> list = new ArrayList<>(folders.values());
        Collections.sort(list, new Comparator<FolderAgg>() {
            @Override
            public int compare(FolderAgg a, FolderAgg b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        List<DetailRow> out = new ArrayList<>();
        int n = Math.min(TOP_FOLDERS_PER_TARGET, list.size());
        for (int i = 0; i < n; i++) {
            FolderAgg f = list.get(i);
            DetailRow d = new DetailRow();
            d.setTarget(target);
            d.setKind("folder");
            d.setPath(f.name);
            d.setMb(roundMb(f.bytes));
            d.setFiles(f.files);
            out.add(d);
        }
        return out;
    }

    private static List<DetailRow> toFileDetails(String target, List<FileHit> hits, boolean deleted) {
        List<DetailRow> out = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (FileHit h : hits) {
            DetailRow d = new DetailRow();
            d.setTarget(target);
            d.setKind("file");
            d.setPath(h.relative);
            d.setFullPath(h.absolute);
            d.setMb(roundMb(h.bytes));
            d.setFiles(1);
            d.setModified(fmt.format(new Date(h.modified)));
            d.setDeleted(deleted);
            out.add(d);
        }
        return out;
    }

    private static void markDeleted(List<DetailRow> files) {
        if (files == null) {
            return;
        }
        for (DetailRow d : files) {
            if (d.getFullPath() == null) {
                continue;
            }
            d.setDeleted(!Files.exists(Paths.get(d.getFullPath())));
        }
    }

    private static void mergeTop(List<DetailRow> dest, List<DetailRow> add, int limit) {
        if (add != null) {
            dest.addAll(add);
        }
        List<DetailRow> kept = takeTop(dest, limit);
        dest.clear();
        dest.addAll(kept);
    }

    private static List<DetailRow> takeTop(List<DetailRow> src, int limit) {
        List<DetailRow> copy = new ArrayList<>(src);
        Collections.sort(copy, new Comparator<DetailRow>() {
            @Override
            public int compare(DetailRow a, DetailRow b) {
                return Double.compare(b.getMb(), a.getMb());
            }
        });
        if (copy.size() > limit) {
            return new ArrayList<>(copy.subList(0, limit));
        }
        return copy;
    }

    private void publish(boolean running, String phase, String currentTarget, String currentPath,
                         int targetIndex, int targetTotal, long scannedFiles, long eligibleFiles,
                         long eligibleBytes, long deletedFiles, long deletedBytes, int percent, String message) {
        ProgressSnapshot snap = new ProgressSnapshot();
        snap.setSuccess(true);
        snap.setRunning(running);
        snap.setPhase(phase);
        snap.setCurrentTarget(currentTarget == null ? "" : currentTarget);
        snap.setCurrentPath(currentPath == null ? "" : currentPath);
        snap.setTargetIndex(targetIndex);
        snap.setTargetTotal(targetTotal);
        snap.setScannedFiles(scannedFiles);
        snap.setEligibleFiles(eligibleFiles);
        snap.setEligibleMb(roundMb(eligibleBytes));
        snap.setDeletedFiles(deletedFiles);
        snap.setDeletedMb(roundMb(deletedBytes));
        snap.setPercent(Math.max(0, Math.min(100, percent)));
        snap.setMessage(message == null ? "" : message);
        synchronized (progressLock) {
            progress = snap;
        }
    }

    private static ProgressSnapshot copyProgress(ProgressSnapshot src) {
        ProgressSnapshot out = new ProgressSnapshot();
        out.setSuccess(src.isSuccess());
        out.setRunning(src.isRunning());
        out.setPhase(src.getPhase());
        out.setCurrentTarget(src.getCurrentTarget());
        out.setCurrentPath(src.getCurrentPath());
        out.setTargetIndex(src.getTargetIndex());
        out.setTargetTotal(src.getTargetTotal());
        out.setScannedFiles(src.getScannedFiles());
        out.setEligibleFiles(src.getEligibleFiles());
        out.setEligibleMb(src.getEligibleMb());
        out.setDeletedFiles(src.getDeletedFiles());
        out.setDeletedMb(src.getDeletedMb());
        out.setPercent(src.getPercent());
        out.setMessage(src.getMessage());
        return out;
    }

    static boolean isForbiddenLocation(Path path) {
        if (path == null) {
            return true;
        }
        if (isAllowedWeChatCache(path)) {
            return false;
        }
        for (Path part : path.toAbsolutePath().normalize()) {
            String s = part.toString().toLowerCase(Locale.ROOT);
            if ("documents".equals(s)
                    || "文档".equals(s)
                    || "downloads".equals(s)
                    || "desktop".equals(s)
                    || "program files".equals(s)
                    || "program files (x86)".equals(s)
                    || "winsxs".equals(s)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAllowedWeChatCache(Path path) {
        if (path == null) {
            return false;
        }
        String p = path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.contains("/tencent/xwechat/log")
                || p.contains("/tencent/xwechat/radium")
                || p.contains("/tencent/xwechat/crashinfo")
                || p.contains("/tencent/xwechat/xplugin")
                || p.contains("/tencent/wechat/log")
                || p.contains("/tencent/wechat/cache")) {
            return true;
        }
        if ((p.contains("/documents/wechat files/") || p.contains("/文档/wechat files/"))
                && (p.contains("/filestorage/cache") || p.contains("/filestorage/temp"))) {
            return !p.contains("/filestorage/msg") && !p.contains("/filestorage/file/");
        }
        return (p.contains("/documents/xwechat_files/") || p.contains("/文档/xwechat_files/"))
                && p.contains("/cache");
    }

    private boolean canDeleteIndexed(Path file, List<Target> targets) {
        if (file == null || !Files.isRegularFile(file) || isForbiddenLocation(file)) {
            return false;
        }
        for (Target t : targets) {
            if (t.path != null && isSafeUnder(file, t.path)) {
                return true;
            }
        }
        return false;
    }

    static List<LargeFileRow> pickLargeFiles(List<FileRecord> index, int largeMinMb) {
        List<LargeFileRow> out = new ArrayList<>();
        if (index == null || index.isEmpty()) {
            return out;
        }
        long minBytes = Math.max(1, largeMinMb) * 1024L * 1024L;
        List<FileRecord> copy = new ArrayList<>(index);
        Collections.sort(copy, new Comparator<FileRecord>() {
            @Override
            public int compare(FileRecord a, FileRecord b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        for (FileRecord r : copy) {
            if (r.bytes < minBytes) {
                continue;
            }
            out.add(toLargeRow(r, false));
            if (out.size() >= MAX_LARGE_FILES) {
                break;
            }
        }
        return out;
    }

    static List<DuplicateGroup> findDuplicates(List<FileRecord> index) {
        return findDuplicates(index, null);
    }

    static List<DuplicateGroup> findDuplicates(List<FileRecord> index, DupHashListener listener) {
        List<DuplicateGroup> groups = new ArrayList<>();
        if (index == null || index.isEmpty()) {
            return groups;
        }
        Map<Long, List<FileRecord>> bySize = new HashMap<>();
        for (FileRecord r : index) {
            if (r == null || r.bytes < MIN_DUP_BYTES || r.bytes > MAX_DUP_HASH_BYTES) {
                continue;
            }
            List<FileRecord> bucket = bySize.get(r.bytes);
            if (bucket == null) {
                bucket = new ArrayList<>();
                bySize.put(r.bytes, bucket);
            }
            bucket.add(r);
        }
        List<List<FileRecord>> buckets = new ArrayList<>();
        for (List<FileRecord> bucket : bySize.values()) {
            if (bucket.size() >= 2) {
                buckets.add(bucket);
            }
        }
        Collections.sort(buckets, new Comparator<List<FileRecord>>() {
            @Override
            public int compare(List<FileRecord> a, List<FileRecord> b) {
                long wa = a.get(0).bytes * (a.size() - 1L);
                long wb = b.get(0).bytes * (b.size() - 1L);
                return Long.compare(wb, wa);
            }
        });
        int planned = 0;
        for (List<FileRecord> bucket : buckets) {
            planned += Math.min(bucket.size(), MAX_DUP_PER_SIZE);
            if (planned >= MAX_DUP_HASH_FILES) {
                planned = MAX_DUP_HASH_FILES;
                break;
            }
        }
        Map<String, List<FileRecord>> byHash = new LinkedHashMap<>();
        int hashed = 0;
        for (List<FileRecord> bucket : buckets) {
            if (hashed >= MAX_DUP_HASH_FILES) {
                break;
            }
            int inBucket = 0;
            for (FileRecord r : bucket) {
                if (hashed >= MAX_DUP_HASH_FILES || inBucket >= MAX_DUP_PER_SIZE) {
                    break;
                }
                Path p = Paths.get(r.fullPath);
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String digest = md5Hex(p);
                if (digest == null) {
                    continue;
                }
                hashed++;
                inBucket++;
                if (listener != null && (hashed % 3 == 0 || hashed == planned)) {
                    listener.onHash(hashed, Math.max(planned, hashed));
                }
                List<FileRecord> same = byHash.get(digest);
                if (same == null) {
                    same = new ArrayList<>();
                    byHash.put(digest, same);
                }
                same.add(r);
            }
        }
        if (listener != null && hashed > 0) {
            listener.onHash(hashed, Math.max(planned, hashed));
        }
        for (Map.Entry<String, List<FileRecord>> e : byHash.entrySet()) {
            List<FileRecord> same = e.getValue();
            if (same.size() < 2) {
                continue;
            }
            Collections.sort(same, new Comparator<FileRecord>() {
                @Override
                public int compare(FileRecord a, FileRecord b) {
                    int c = Long.compare(a.modified, b.modified);
                    if (c != 0) {
                        return c;
                    }
                    return Integer.compare(a.fullPath.length(), b.fullPath.length());
                }
            });
            DuplicateGroup g = new DuplicateGroup();
            g.setHash(e.getKey().length() > 12 ? e.getKey().substring(0, 12) : e.getKey());
            g.setCopies(same.size());
            g.setMb(roundMb(same.get(0).bytes));
            g.setWastedMb(roundMb(same.get(0).bytes * (same.size() - 1L)));
            g.setKeep(toLargeRow(same.get(0), true));
            List<LargeFileRow> extras = new ArrayList<>();
            for (int i = 1; i < same.size(); i++) {
                extras.add(toLargeRow(same.get(i), false));
            }
            g.setExtras(extras);
            groups.add(g);
        }
        Collections.sort(groups, new Comparator<DuplicateGroup>() {
            @Override
            public int compare(DuplicateGroup a, DuplicateGroup b) {
                return Double.compare(b.getWastedMb(), a.getWastedMb());
            }
        });
        if (groups.size() > MAX_DUP_GROUPS) {
            return new ArrayList<>(groups.subList(0, MAX_DUP_GROUPS));
        }
        return groups;
    }

    private static LargeFileRow toLargeRow(FileRecord r, boolean keep) {
        LargeFileRow row = new LargeFileRow();
        row.setTarget(r.target);
        row.setPath(r.relative);
        row.setFullPath(r.fullPath);
        row.setMb(roundMb(r.bytes));
        row.setModified(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.modified)));
        row.setKeep(keep);
        return row;
    }

    static String md5Hex(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    static boolean isSafeUnder(Path file, Path root) {
        if (file == null || root == null) {
            return false;
        }
        Path f = file.toAbsolutePath().normalize();
        Path r = root.toAbsolutePath().normalize();
        return f.startsWith(r) && !f.equals(r);
    }

    static boolean matchesFilter(Path file, String[] filters) {
        if (filters == null || filters.length == 0) {
            return true;
        }
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        for (String f : filters) {
            if (f == null || "*".equals(f)) {
                return true;
            }
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + f);
            if (m.matches(name)) {
                return true;
            }
        }
        return false;
    }

    static int clampAge(Integer days) {
        if (days == null || days < 0) {
            return DEFAULT_MIN_AGE_DAYS;
        }
        return Math.min(days, MAX_MIN_AGE_DAYS);
    }

    private static double roundMb(long bytes) {
        return Math.round(bytes / 10485.76) / 100.0;
    }

    private static double roundGb(long bytes) {
        return Math.round(bytes / 10737418.24) / 100.0;
    }

    public enum Mode { AUDIT, CLEAN }

    public enum Preset { STANDARD, DEEP }

    @Data
    public static class Request {
        private Mode mode = Mode.AUDIT;
        private Preset preset = Preset.STANDARD;
        private boolean confirmClean;
        private Integer minAgeDays = DEFAULT_MIN_AGE_DAYS;
        private boolean includeBrowserCaches;
        private boolean includeWeChatCaches = true;
        private boolean deleteDuplicateExtras = true;
        private Integer largeMinMb = DEFAULT_LARGE_MIN_MB;
        private List<String> largeFilePaths;
    }

    @Data
    public static class TargetRow {
        private String name;
        private String path;
        private boolean exists;
        private double totalMb;
        private double eligibleMb;
        private long eligibleFiles;
        private long scannedFiles;
        private long deletedFiles;
        private double deletedMb;
        private String action;
        private int minAgeDays;
        private List<DetailRow> folders = new ArrayList<>();
        private List<DetailRow> files = new ArrayList<>();
    }

    @Data
    public static class DetailRow {
        private String target;
        private String kind;
        private String path;
        private String fullPath;
        private double mb;
        private long files;
        private String modified;
        private boolean deleted;
    }

    @Data
    public static class Report {
        private boolean success;
        private boolean windows;
        private String mode;
        private String preset;
        private boolean confirmClean;
        private int minAgeDays;
        private boolean includeBrowserCaches;
        private boolean includeWeChatCaches;
        private boolean deleteDuplicateExtras;
        private int largeMinMb;
        private double freeBeforeGb;
        private double freeAfterGb;
        private double estimatedEligibleMb;
        private double deletedMb;
        private long deletedFiles;
        private long skipped;
        private List<TargetRow> targets = new ArrayList<>();
        private List<DetailRow> details = new ArrayList<>();
        private List<LargeFileRow> largeFiles = new ArrayList<>();
        private List<DuplicateGroup> duplicateGroups = new ArrayList<>();
        private double duplicateWastedMb;
        private String msg;
    }

    @Data
    public static class LargeFileRow {
        private String target;
        private String path;
        private String fullPath;
        private double mb;
        private String modified;
        private boolean keep;
        private boolean deleted;
    }

    @Data
    public static class DuplicateGroup {
        private String hash;
        private int copies;
        private double mb;
        private double wastedMb;
        private LargeFileRow keep;
        private List<LargeFileRow> extras = new ArrayList<>();
    }

    @Data
    public static class ProgressSnapshot {
        private boolean success = true;
        private boolean running;
        private String phase = "IDLE";
        private String currentTarget = "";
        private String currentPath = "";
        private int targetIndex;
        private int targetTotal;
        private long scannedFiles;
        private long eligibleFiles;
        private double eligibleMb;
        private long deletedFiles;
        private double deletedMb;
        private int percent;
        private String message = "";

        static ProgressSnapshot idle() {
            ProgressSnapshot p = new ProgressSnapshot();
            p.setPhase("IDLE");
            p.setMessage("尚未开始");
            return p;
        }
    }

    static class Target {
        final String name;
        final Path path;
        final String[] filters;

        Target(String name, Path path, String... filters) {
            this.name = name;
            this.path = path;
            this.filters = filters == null || filters.length == 0 ? new String[]{"*"} : filters;
        }
    }

    static class ScanResult {
        long totalBytes;
        long eligibleBytes;
        long scannedFiles;
        final List<Path> eligible = new ArrayList<>();
        final Map<String, FolderAgg> folders = new LinkedHashMap<>();
        final TopFiles topFiles = new TopFiles(TOP_FILES_PER_TARGET);
        final List<FileRecord> allFiles = new ArrayList<>();
    }

    interface DupHashListener {
        void onHash(int done, int planned);
    }

    static class FileRecord {
        final String target;
        final String fullPath;
        final String relative;
        final long bytes;
        final long modified;

        FileRecord(String target, String fullPath, String relative, long bytes, long modified) {
            this.target = target;
            this.fullPath = fullPath;
            this.relative = relative;
            this.bytes = bytes;
            this.modified = modified;
        }
    }

    static class FolderAgg {
        final String name;
        long bytes;
        long files;

        FolderAgg(String name) {
            this.name = name;
        }
    }

    static class FileHit {
        final String absolute;
        final String relative;
        final long bytes;
        final long modified;

        FileHit(String absolute, String relative, long bytes, long modified) {
            this.absolute = absolute;
            this.relative = relative;
            this.bytes = bytes;
            this.modified = modified;
        }
    }

    static class TopFiles {
        private final int limit;
        private final PriorityQueue<FileHit> heap;

        TopFiles(int limit) {
            this.limit = limit;
            this.heap = new PriorityQueue<>(limit + 1, new Comparator<FileHit>() {
                @Override
                public int compare(FileHit a, FileHit b) {
                    return Long.compare(a.bytes, b.bytes);
                }
            });
        }

        void add(FileHit hit) {
            if (hit == null || hit.bytes <= 0) {
                return;
            }
            heap.offer(hit);
            if (heap.size() > limit) {
                heap.poll();
            }
        }

        List<FileHit> toListDesc() {
            List<FileHit> list = new ArrayList<>(heap);
            Collections.sort(list, new Comparator<FileHit>() {
                @Override
                public int compare(FileHit a, FileHit b) {
                    return Long.compare(b.bytes, a.bytes);
                }
            });
            return list;
        }
    }

    static class PathLocator {
        private final Path userTemp;
        private final Path localAppData;
        private final Path appData;
        private final Path windowsRoot;
        private final Path programData;

        private final Path userHome;
        private final Path recycleRoot;

        PathLocator(Path userTemp, Path localAppData, Path appData, Path windowsRoot, Path programData) {
            this(userTemp, localAppData, appData, windowsRoot, programData, null, null);
        }

        PathLocator(Path userTemp, Path localAppData, Path appData, Path windowsRoot, Path programData,
                    Path userHome, Path recycleRoot) {
            this.userTemp = userTemp;
            this.localAppData = localAppData;
            this.appData = appData;
            this.windowsRoot = windowsRoot;
            this.programData = programData;
            this.userHome = userHome;
            this.recycleRoot = recycleRoot;
        }

        static PathLocator system() {
            String tmp = firstNonBlank(System.getenv("TEMP"), System.getProperty("java.io.tmpdir"));
            String la = System.getenv("LOCALAPPDATA");
            String ad = System.getenv("APPDATA");
            String win = firstNonBlank(System.getenv("SystemRoot"), "C:\\Windows");
            String pd = System.getenv("ProgramData");
            String home = System.getProperty("user.home");
            return new PathLocator(
                    tmp == null ? null : Paths.get(tmp),
                    la == null ? null : Paths.get(la),
                    ad == null ? null : Paths.get(ad),
                    Paths.get(win),
                    pd == null ? null : Paths.get(pd),
                    home == null ? null : Paths.get(home),
                    Paths.get("C:\\$Recycle.Bin"));
        }

        Path userTemp() { return userTemp; }
        Path localAppData() { return localAppData; }
        Path appData() { return appData; }
        Path windowsRoot() { return windowsRoot; }
        Path programData() { return programData; }
        Path userHome() { return userHome; }
        Path recycleRoot() { return recycleRoot; }
    }

    interface DriveSpace {
        long freeBytes();
        long totalBytes();

        static DriveSpace system() {
            return new DriveSpace() {
                private File root() {
                    File[] roots = File.listRoots();
                    if (roots != null) {
                        for (File f : roots) {
                            if (f.getAbsolutePath().toUpperCase(Locale.ROOT).startsWith("C:")) {
                                return f;
                            }
                        }
                    }
                    return new File("C:\\");
                }

                @Override
                public long freeBytes() {
                    return root().getUsableSpace();
                }

                @Override
                public long totalBytes() {
                    return root().getTotalSpace();
                }
            };
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        return b;
    }
}
