package com.gcoll.dbcompare.service;

import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataViewerExportServiceTest {

    @Mock
    private DataSourceService dataSourceService;

    @InjectMocks
    private DataViewerExportService exportService;

    private Connection conn;
    private DataSourceConfig ds;

    @BeforeEach
    void setUp() throws Exception {
        String mem = "jdbc:h2:mem:viewer_exp_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        conn = DriverManager.getConnection(mem, "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t_user (id BIGINT PRIMARY KEY, name VARCHAR(32), amount DECIMAL(10,2))");
            st.execute("INSERT INTO t_user(id,name,amount) VALUES (1, 'Ada', 1.20)");
            st.execute("INSERT INTO t_user(id,name,amount) VALUES (2, NULL, NULL)");
        }
        ds = new DataSourceConfig();
        ds.setId(9L);
        ds.setName("demo-h2");
        ds.setDbType(DatabaseType.H2);
        ds.setUrl(mem);
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setDriverClass("org.h2.Driver");
        lenient().when(dataSourceService.getDataSourceById(9L)).thenReturn(ds);
        lenient().when(dataSourceService.createConnection(ds)).thenReturn(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    void export_emptyTables_throws() {
        ViewerExportRequest req = new ViewerExportRequest();
        req.setKind("DDL");
        assertThrows(IllegalArgumentException.class, () -> exportService.export(9L, req));
    }

    @Test
    void export_ddl_defaultDialect_containsCreate() {
        ViewerExportRequest req = ddlReq();
        DataViewerExportService.ExportFile file = exportService.export(9L, req);
        assertTrue(file.getFileName().contains("ddl"));
        assertTrue(file.getSql().contains("CREATE TABLE"));
        assertTrue(file.getSql().contains("t_user") || file.getSql().contains("T_USER"));
        assertTrue(file.getSql().contains("PRIMARY KEY"));
    }

    @Test
    void export_ddl_mysql_target_usesBackticks() {
        ViewerExportRequest req = ddlReq();
        req.setTargetDbType("MYSQL");
        String sql = exportService.export(9L, req).getSql();
        assertTrue(sql.contains("`t_user`") || sql.contains("`T_USER`"));
        assertTrue(sql.contains("目标方言: MySQL") || sql.contains("MYSQL") || sql.contains("MySQL"));
    }

    @Test
    void export_dml_includesNullAndValue() {
        ViewerExportRequest req = ddlReq();
        req.setKind("DML");
        String sql = exportService.export(9L, req).getSql();
        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("Ada"));
        assertTrue(sql.contains("NULL"));
    }

    @Test
    void export_selectedRows_onlyThoseRows() {
        ViewerExportRequest req = ddlReq();
        req.setKind("DML");
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        row.put("name", "Ada");
        row.put("amount", "1.20");
        req.setRows(Collections.singletonList(row));
        String sql = exportService.export(9L, req).getSql();
        assertTrue(sql.contains("Ada"));
        assertTrue(sql.split("INSERT INTO").length - 1 == 1);
    }

    private ViewerExportRequest ddlReq() {
        ViewerExportRequest req = new ViewerExportRequest();
        req.setKind("DDL");
        ViewerExportRequest.TableRef ref = new ViewerExportRequest.TableRef();
        ref.setName("t_user");
        req.setTables(Collections.singletonList(ref));
        return req;
    }
}
