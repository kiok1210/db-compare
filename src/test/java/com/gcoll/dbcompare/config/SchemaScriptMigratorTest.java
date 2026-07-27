package com.gcoll.dbcompare.config;

import com.gcoll.dbcompare.entity.AppMeta;
import com.gcoll.dbcompare.repository.AppMetaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaScriptMigratorTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private AppMetaRepository appMetaRepository;
    @Mock
    private Connection connection;
    @Mock
    private Statement statement;

    private SchemaScriptMigrator migrator;

    @BeforeEach
    void setUp() throws Exception {
        migrator = new SchemaScriptMigrator(dataSource, appMetaRepository);
    }

    @Test
    void parseVersion_ok() {
        assertEquals(6, SchemaScriptMigrator.parseVersion("V6__current_table_min_len.sql"));
        assertEquals(12, SchemaScriptMigrator.parseVersion("V12__foo.sql"));
        assertEquals(-1, SchemaScriptMigrator.parseVersion("readme.sql"));
        assertEquals(-1, SchemaScriptMigrator.parseVersion(null));
    }

    @Test
    void splitStatements_skipsComments() {
        String body = "-- comment\n"
                + "ALTER TABLE T ALTER COLUMN C VARCHAR(200);\n"
                + "UPDATE T SET C = 1 WHERE X > 0;\n";
        List<String> stmts = SchemaScriptMigrator.splitStatements(body);
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).toUpperCase().contains("ALTER TABLE"));
        assertTrue(stmts.get(1).toUpperCase().contains("UPDATE"));
    }

    @Test
    void apply_skipsWhenAlreadyAtTarget() {
        List<Integer> applied = migrator.apply(6, 6);
        assertTrue(applied.isEmpty());
        verifyNoInteractions(dataSource);
    }

    @Test
    void apply_executesClasspathV6AndSavesVersion() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(appMetaRepository.findById(AppMeta.KEY_SCHEMA_VERSION)).thenReturn(Optional.empty());
        when(appMetaRepository.save(any(AppMeta.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Integer> applied = migrator.apply(5, 6);

        assertEquals(Collections.singletonList(6), applied);
        verify(statement, atLeastOnce()).execute(anyString());
        ArgumentCaptor<AppMeta> cap = ArgumentCaptor.forClass(AppMeta.class);
        verify(appMetaRepository).save(cap.capture());
        assertEquals("6", cap.getValue().getMetaValue());
    }

    @Test
    void apply_stopsOnHardFailureWithoutSavingHigherVersion() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        doThrow(new RuntimeException("syntax error near FOO")).when(statement).execute(anyString());

        List<Integer> applied = migrator.apply(5, 6);

        assertTrue(applied.isEmpty());
        verify(appMetaRepository, never()).save(any());
    }
}
