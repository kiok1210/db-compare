package com.gcoll.dbcompare.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsDriveCleanupServiceTest {

    @TempDir
    Path tmp;

    @Test
    void clampAge_nullOrNegative_defaultsToSeven() {
        assertEquals(7, WindowsDriveCleanupService.clampAge(null));
        assertEquals(7, WindowsDriveCleanupService.clampAge(-1));
        assertEquals(0, WindowsDriveCleanupService.clampAge(0));
        assertEquals(3650, WindowsDriveCleanupService.clampAge(99999));
    }

    @Test
    void isForbiddenLocation_blocksPersonalAndSystemDirs() {
        assertTrue(WindowsDriveCleanupService.isForbiddenLocation(tmp.resolve("Documents")));
        assertTrue(WindowsDriveCleanupService.isForbiddenLocation(tmp.resolve("Downloads").resolve("x.txt")));
        assertTrue(WindowsDriveCleanupService.isForbiddenLocation(tmp.resolve("Program Files").resolve("App")));
        assertTrue(WindowsDriveCleanupService.isForbiddenLocation(tmp.resolve("Windows").resolve("WinSxS")));
        assertFalse(WindowsDriveCleanupService.isForbiddenLocation(tmp.resolve("Temp").resolve("cache.tmp")));
        Path wxCache = tmp.resolve("Documents").resolve("WeChat Files").resolve("wxid_a")
                .resolve("FileStorage").resolve("Cache").resolve("img.dat");
        assertTrue(WindowsDriveCleanupService.isAllowedWeChatCache(wxCache));
        assertFalse(WindowsDriveCleanupService.isForbiddenLocation(wxCache));
        Path wxMsg = tmp.resolve("Documents").resolve("WeChat Files").resolve("wxid_a")
                .resolve("Msg").resolve("msg.db");
        assertFalse(WindowsDriveCleanupService.isAllowedWeChatCache(wxMsg));
        assertTrue(WindowsDriveCleanupService.isForbiddenLocation(wxMsg));
    }

    @Test
    void isSafeUnder_rejectsEscape() {
        Path root = tmp.resolve("allow");
        assertFalse(WindowsDriveCleanupService.isSafeUnder(root, root));
        assertTrue(WindowsDriveCleanupService.isSafeUnder(root.resolve("a.txt"), root));
        assertFalse(WindowsDriveCleanupService.isSafeUnder(tmp.resolve("other").resolve("a.txt"), root));
    }

    @Test
    void run_nonWindows_throws() {
        WindowsDriveCleanupService svc = newService(false, tmp.resolve("t"), tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.run(new WindowsDriveCleanupService.Request()));
        assertTrue(ex.getMessage().contains("Windows"));
        Map<String, Object> st = svc.status();
        assertEquals(Boolean.FALSE, st.get("windows"));
        assertEquals(0L, st.get("freeBytes"));
    }

    @Test
    void auditThenClean_deletesOldAllowlistedFilesOnly() throws Exception {
        Path userTemp = tmp.resolve("userTemp");
        Path winTemp = tmp.resolve("Windows").resolve("Temp");
        Files.createDirectories(userTemp);
        Files.createDirectories(winTemp);

        Path oldFile = userTemp.resolve("old.log");
        Path nestedDir = userTemp.resolve("nested");
        Files.createDirectories(nestedDir);
        Path nestedOld = nestedDir.resolve("cache.bin");
        Path newFile = userTemp.resolve("new.log");
        Files.write(oldFile, new byte[2 * 1024 * 1024]);
        Files.write(nestedOld, new byte[3 * 1024 * 1024]);
        Files.write(newFile, new byte[1024]);
        FileTime oldTime = FileTime.fromMillis(System.currentTimeMillis() - 10L * 24 * 3600_000L);
        Files.setLastModifiedTime(oldFile, oldTime);
        Files.setLastModifiedTime(nestedOld, oldTime);
        Files.setLastModifiedTime(newFile, FileTime.fromMillis(System.currentTimeMillis()));

        Path documents = tmp.resolve("Documents");
        Files.createDirectories(documents);
        Path secret = documents.resolve("keep.txt");
        Files.write(secret, new byte[]{1, 2, 3});

        WindowsDriveCleanupService svc = newService(true, userTemp, tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));

        WindowsDriveCleanupService.Request audit = new WindowsDriveCleanupService.Request();
        audit.setMode(WindowsDriveCleanupService.Mode.AUDIT);
        WindowsDriveCleanupService.Report audited = svc.run(audit);
        assertTrue(audited.isSuccess());
        assertEquals("AUDIT", audited.getMode());
        assertTrue(audited.getTargets().stream().anyMatch(t -> t.getEligibleFiles() >= 1));
        assertTrue(audited.getEstimatedEligibleMb() > 0);
        WindowsDriveCleanupService.TargetRow tempRow = audited.getTargets().stream()
                .filter(t -> "User TEMP".equals(t.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(tempRow.getFolders().stream().anyMatch(d -> "nested".equals(d.getPath())));
        assertTrue(tempRow.getFiles().stream().anyMatch(d -> d.getPath().contains("cache.bin")));
        assertFalse(audited.getDetails().isEmpty());
        WindowsDriveCleanupService.ProgressSnapshot done = svc.progress();
        assertFalse(done.isRunning());
        assertEquals(100, done.getPercent());
        assertTrue(Files.exists(oldFile));

        WindowsDriveCleanupService.Request refuse = new WindowsDriveCleanupService.Request();
        refuse.setMode(WindowsDriveCleanupService.Mode.CLEAN);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> svc.run(refuse));
        assertTrue(ex.getMessage().contains("确认"));
        assertTrue(Files.exists(oldFile));

        WindowsDriveCleanupService.Request clean = new WindowsDriveCleanupService.Request();
        clean.setMode(WindowsDriveCleanupService.Mode.CLEAN);
        clean.setConfirmClean(true);
        WindowsDriveCleanupService.Report cleaned = svc.run(clean);
        assertTrue(cleaned.isSuccess());
        assertEquals("CLEAN", cleaned.getMode());
        assertTrue(cleaned.getDeletedFiles() >= 1);
        assertFalse(Files.exists(oldFile));
        assertFalse(Files.exists(nestedOld));
        assertTrue(Files.exists(newFile));
        assertTrue(Files.exists(secret));
    }

    @Test
    void run_documentsAsTarget_skipForbiddenAndKeepsFiles() throws Exception {
        Path docs = tmp.resolve("Documents");
        Files.createDirectories(docs);
        Path keep = docs.resolve("keep.txt");
        Files.write(keep, new byte[2048]);
        Files.setLastModifiedTime(keep, FileTime.fromMillis(System.currentTimeMillis() - 10L * 24 * 3600_000L));

        WindowsDriveCleanupService svc = newService(true, docs, tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        WindowsDriveCleanupService.Request req = new WindowsDriveCleanupService.Request();
        req.setMode(WindowsDriveCleanupService.Mode.CLEAN);
        req.setConfirmClean(true);
        WindowsDriveCleanupService.Report report = svc.run(req);
        WindowsDriveCleanupService.TargetRow userTemp = report.getTargets().stream()
                .filter(t -> "User TEMP".equals(t.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("skip-forbidden", userTemp.getAction());
        assertTrue(Files.exists(keep));
        assertEquals(0, report.getDeletedFiles());
    }

    @Test
    void deepPreset_includesCrashDumps_standardDoesNot() throws Exception {
        Path local = tmp.resolve("Local");
        Path crash = local.resolve("CrashDumps");
        Files.createDirectories(crash);
        Path dump = crash.resolve("app.dmp");
        Files.write(dump, new byte[512]);
        Files.setLastModifiedTime(dump, FileTime.fromMillis(System.currentTimeMillis() - 10L * 24 * 3600_000L));

        WindowsDriveCleanupService svc = newService(true, tmp.resolve("userTemp"), local,
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));

        WindowsDriveCleanupService.Request standard = new WindowsDriveCleanupService.Request();
        standard.setPreset(WindowsDriveCleanupService.Preset.STANDARD);
        WindowsDriveCleanupService.Report std = svc.run(standard);
        assertFalse(std.getTargets().stream().anyMatch(t -> "Crash dumps".equals(t.getName())));

        WindowsDriveCleanupService.Request deep = new WindowsDriveCleanupService.Request();
        deep.setPreset(WindowsDriveCleanupService.Preset.DEEP);
        WindowsDriveCleanupService.Report d = svc.run(deep);
        assertTrue(d.getTargets().stream().anyMatch(t -> "Crash dumps".equals(t.getName())));
        assertTrue(d.getTargets().stream().anyMatch(t -> "pip Cache".equals(t.getName())));
        assertTrue(d.getTargets().stream().anyMatch(t -> "npm-cache".equals(t.getName())));
    }

    @Test
    void usage_reportsTempSize() throws Exception {
        Path userTemp = tmp.resolve("userTemp");
        Files.createDirectories(userTemp);
        Files.write(userTemp.resolve("a.bin"), new byte[1024 * 1024]);
        WindowsDriveCleanupService svc = newService(true, userTemp, tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        Map<String, Object> usage = svc.usage();
        assertEquals(Boolean.TRUE, usage.get("success"));
        assertTrue(((Number) usage.get("reclaimableMb")).doubleValue() >= 0.5);
    }

    @Test
    void missingDir_isSkipMissing() {
        Path missing = tmp.resolve("no-such-temp");
        WindowsDriveCleanupService svc = newService(true, missing, tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        WindowsDriveCleanupService.Report report = svc.run(new WindowsDriveCleanupService.Request());
        WindowsDriveCleanupService.TargetRow userTemp = report.getTargets().stream()
                .filter(t -> "User TEMP".equals(t.getName()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertFalse(userTemp.isExists());
        assertEquals("skip-missing", userTemp.getAction());
    }

    @Test
    void canonical_samePath_dedupesShortAndLongNames() throws Exception {
        Path dir = tmp.resolve("userTemp");
        Files.createDirectories(dir);
        Path a = dir;
        Path b = dir.toAbsolutePath().normalize();
        assertTrue(WindowsDriveCleanupService.samePath(a, b));
        assertEquals(WindowsDriveCleanupService.canonical(a), WindowsDriveCleanupService.canonical(b));
    }

    @Test
    void firstFolder_usesImmediateChildName() throws Exception {
        Path root = tmp.resolve("root");
        Path nested = root.resolve("sub").resolve("a.bin");
        Files.createDirectories(nested.getParent());
        Files.write(nested, new byte[]{1});
        assertEquals("sub", WindowsDriveCleanupService.firstFolder(root, nested));
        assertEquals("（根目录）", WindowsDriveCleanupService.firstFolder(root, root.resolve("x.txt")));
    }

    @Test
    void clean_weChatCache_deletesCacheKeepsChatDb() throws Exception {
        Path roaming = tmp.resolve("Roaming");
        Path xlog = roaming.resolve("Tencent").resolve("xwechat").resolve("log");
        Files.createDirectories(xlog);
        Path logFile = xlog.resolve("xwechat.log");
        Files.write(logFile, new byte[2 * 1024 * 1024]);
        Files.setLastModifiedTime(logFile, FileTime.fromMillis(System.currentTimeMillis() - 10L * 24 * 3600_000L));

        Path cacheDir = tmp.resolve("Documents").resolve("WeChat Files").resolve("wxid_test")
                .resolve("FileStorage").resolve("Cache");
        Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve("img.dat");
        Files.write(cacheFile, new byte[2 * 1024 * 1024]);
        Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis() - 10L * 24 * 3600_000L));

        Path msgDir = tmp.resolve("Documents").resolve("WeChat Files").resolve("wxid_test").resolve("Msg");
        Files.createDirectories(msgDir);
        Path msg = msgDir.resolve("msg.db");
        Files.write(msg, new byte[]{9, 8, 7});

        WindowsDriveCleanupService svc = newService(true, tmp.resolve("userTemp"), tmp.resolve("Local"),
                roaming, tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        WindowsDriveCleanupService.Request req = new WindowsDriveCleanupService.Request();
        req.setMode(WindowsDriveCleanupService.Mode.CLEAN);
        req.setConfirmClean(true);
        req.setIncludeWeChatCaches(true);
        req.setDeleteDuplicateExtras(false);
        WindowsDriveCleanupService.Report report = svc.run(req);
        assertTrue(report.getTargets().stream().anyMatch(t -> t.getName().startsWith("WeChat")));
        assertFalse(Files.exists(logFile));
        assertFalse(Files.exists(cacheFile));
        assertTrue(Files.exists(msg));
    }

    @Test
    void clean_duplicateExtras_keepsOldestCopy() throws Exception {
        Path userTemp = tmp.resolve("userTemp");
        Files.createDirectories(userTemp.resolve("copy"));
        byte[] payload = new byte[2 * 1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 17);
        }
        Path keep = userTemp.resolve("keep.bin");
        Path extra = userTemp.resolve("copy").resolve("extra.bin");
        Files.write(keep, payload);
        Files.write(extra, payload);
        Files.setLastModifiedTime(keep, FileTime.fromMillis(System.currentTimeMillis() - 40_000L));
        Files.setLastModifiedTime(extra, FileTime.fromMillis(System.currentTimeMillis() - 10_000L));

        WindowsDriveCleanupService svc = newService(true, userTemp, tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        WindowsDriveCleanupService.Request req = new WindowsDriveCleanupService.Request();
        req.setMode(WindowsDriveCleanupService.Mode.CLEAN);
        req.setConfirmClean(true);
        req.setMinAgeDays(3650);
        req.setIncludeWeChatCaches(false);
        req.setDeleteDuplicateExtras(true);
        WindowsDriveCleanupService.Report report = svc.run(req);
        assertFalse(report.getDuplicateGroups().isEmpty());
        assertTrue(Files.exists(keep));
        assertFalse(Files.exists(extra));
        assertTrue(report.getDeletedFiles() >= 1);
    }

    @Test
    void findDuplicates_skipsTinyIdenticalFiles() throws Exception {
        Path a = tmp.resolve("a.dat");
        Path b = tmp.resolve("b.dat");
        Files.write(a, new byte[200]);
        Files.write(b, new byte[200]);
        java.util.List<WindowsDriveCleanupService.FileRecord> idx = java.util.Arrays.asList(
                new WindowsDriveCleanupService.FileRecord("t", a.toString(), "a.dat", 200, 1L),
                new WindowsDriveCleanupService.FileRecord("t", b.toString(), "b.dat", 200, 2L)
        );
        assertTrue(WindowsDriveCleanupService.findDuplicates(idx).isEmpty());
    }

    @Test
    void clean_selectedLargeFile_deletesOnlyChecked() throws Exception {
        Path userTemp = tmp.resolve("userTemp");
        Files.createDirectories(userTemp);
        Path big = userTemp.resolve("big.bin");
        Path other = userTemp.resolve("other.bin");
        Files.write(big, new byte[2 * 1024 * 1024]);
        Files.write(other, new byte[2 * 1024 * 1024]);

        WindowsDriveCleanupService svc = newService(true, userTemp, tmp.resolve("Local"),
                tmp.resolve("Roaming"), tmp.resolve("Windows"), tmp.resolve("ProgramData"));
        WindowsDriveCleanupService.Request audit = new WindowsDriveCleanupService.Request();
        audit.setMinAgeDays(3650);
        audit.setIncludeWeChatCaches(false);
        audit.setDeleteDuplicateExtras(false);
        audit.setLargeMinMb(1);
        WindowsDriveCleanupService.Report listed = svc.run(audit);
        assertTrue(listed.getLargeFiles().size() >= 2);
        String chosen = listed.getLargeFiles().stream()
                .filter(f -> f.getPath() != null && f.getPath().contains("big.bin"))
                .findFirst()
                .orElseThrow(AssertionError::new)
                .getFullPath();

        WindowsDriveCleanupService.Request clean = new WindowsDriveCleanupService.Request();
        clean.setMode(WindowsDriveCleanupService.Mode.CLEAN);
        clean.setConfirmClean(true);
        clean.setMinAgeDays(3650);
        clean.setIncludeWeChatCaches(false);
        clean.setDeleteDuplicateExtras(false);
        clean.setLargeMinMb(1);
        clean.setLargeFilePaths(java.util.Collections.singletonList(chosen));
        svc.run(clean);
        assertFalse(Files.exists(big));
        assertTrue(Files.exists(other));
    }

    private WindowsDriveCleanupService newService(boolean windows, Path userTemp, Path localAppData,
                                                  Path appData, Path windowsRoot, Path programData) {
        final AtomicLong free = new AtomicLong(6L * 1024 * 1024 * 1024);
        return new WindowsDriveCleanupService(
                new WindowsDriveCleanupService.PathLocator(userTemp, localAppData, appData, windowsRoot, programData,
                        tmp, tmp.resolve("recycle")),
                new WindowsDriveCleanupService.DriveSpace() {
                    @Override
                    public long freeBytes() {
                        return free.get();
                    }

                    @Override
                    public long totalBytes() {
                        return 20L * 1024 * 1024 * 1024;
                    }
                },
                windows);
    }
}
