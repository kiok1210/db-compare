package com.gcoll.dbcompare.service.datax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataxProcessRunnerResolveTest {

    @TempDir
    Path temp;

    @Test
    void resolve_nestedDataxFolder() throws Exception {
        Path py = temp.resolve("datax").resolve("bin").resolve("datax.py");
        Files.createDirectories(py.getParent());
        Files.write(py, "#".getBytes("UTF-8"));
        Path resolved = DataxProcessRunner.resolveDataxPy(temp.toString());
        assertEquals(py.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolve_directHome() throws Exception {
        Path py = temp.resolve("bin").resolve("datax.py");
        Files.createDirectories(py.getParent());
        Files.write(py, "#".getBytes("UTF-8"));
        assertEquals(py.toAbsolutePath().normalize(),
                DataxProcessRunner.resolveDataxPy(temp.toString()));
    }

    @Test
    void resolve_missing_listsTriedPaths() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DataxProcessRunner.resolveDataxPy(temp.toString()));
        assertTrue(ex.getMessage().contains("已尝试"));
        assertTrue(ex.getMessage().contains("bin"));
    }
}
