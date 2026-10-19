package com.gcoll.dbcompare.config;

import com.gcoll.dbcompare.entity.CompareTask;
import com.gcoll.dbcompare.ds.DataSourceConfig;
import com.gcoll.dbcompare.entity.DatabaseType;
import com.gcoll.dbcompare.repository.CompareTaskRepository;
import com.gcoll.dbcompare.ds.DatasourceConfigRepository;
import com.gcoll.dbcompare.util.CredentialEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * 演示数据初始化器。
 * 多表（约 12 张）+ 多库 schema，目标库故意制造多处「条数差异」，
 * 便于报告页手动测试「提取为同步任务」。
 */
@Component
@ConditionalOnProperty(prefix = "db-compare.demo", name = "enabled", havingValue = "true")
@Slf4j
public class DemoDataInitializer {

    /** 升级此版本号会触发演示库重灌（保留数据源注册，重建表与演示任务）。 */
    private static final String DEMO_DATA_VERSION = "multi-v3";

    private static final String DEMO_DIR = "./data/demo/";
    private static final String VERSION_FILE = DEMO_DIR + ".demo_version";
    private static final String H2_DB_PATH = DEMO_DIR + "source_h2";
    private static final String H2_DB_PATH2 = DEMO_DIR + "target_h2";
    private static final String SQLITE_DB_PATH = DEMO_DIR + "source_sqlite.db";
    private static final String SQLITE_DB_PATH2 = DEMO_DIR + "target_sqlite.db";

    @Autowired
    private DatasourceConfigRepository DatasourceConfigRepository;

    @Autowired
    private CompareTaskRepository taskRepository;

    @Autowired
    private CredentialEncryptor credentialEncryptor;

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            Path demoPath = Paths.get(DEMO_DIR);
            if (!Files.exists(demoPath)) {
                Files.createDirectories(demoPath);
            }

            DataSourceConfig existingH2 = DatasourceConfigRepository.findByName("演示H2源库");
            boolean needInit = existingH2 == null || !DEMO_DATA_VERSION.equals(readDemoVersion());

            if (!needInit) {
                log.info("演示数据已是 {}，跳过初始化", DEMO_DATA_VERSION);
                return;
            }

            log.info("==========================================");
            log.info("  初始化/升级多表演示库 ({}) ...", DEMO_DATA_VERSION);
            log.info("==========================================");

            if (existingH2 == null) {
                deleteIfExists(H2_DB_PATH + ".mv.db");
                deleteIfExists(H2_DB_PATH + ".trace.db");
                deleteIfExists(H2_DB_PATH2 + ".mv.db");
                deleteIfExists(H2_DB_PATH2 + ".trace.db");
                deleteIfExists(SQLITE_DB_PATH);
                deleteIfExists(SQLITE_DB_PATH2);
            }

            log.info("创建 H2 多表演示数据库...");
            createH2DemoDb(H2_DB_PATH, true);
            createH2DemoDb(H2_DB_PATH2, false);

            log.info("创建 SQLite 多表演示数据库...");
            createSqliteDemoDb(SQLITE_DB_PATH, true);
            createSqliteDemoDb(SQLITE_DB_PATH2, false);

            DataSourceConfig h2Source;
            DataSourceConfig h2Target;
            DataSourceConfig sqliteSource;
            DataSourceConfig sqliteTarget;

            if (existingH2 == null) {
                log.info("注册演示数据源...");
                h2Source = createDataSource(
                        "演示H2源库", DatabaseType.H2,
                        "jdbc:h2:file:" + new File(H2_DB_PATH).getAbsolutePath() + ";DB_CLOSE_DELAY=0;AUTO_SERVER=FALSE",
                        "org.h2.Driver", "sa", "", "PUBLIC",
                        "H2演示源库（PUBLIC+SHOP 共12表）");
                h2Target = createDataSource(
                        "演示H2目标库", DatabaseType.H2,
                        "jdbc:h2:file:" + new File(H2_DB_PATH2).getAbsolutePath() + ";DB_CLOSE_DELAY=0;AUTO_SERVER=FALSE",
                        "org.h2.Driver", "sa", "", "PUBLIC",
                        "H2演示目标库（多表条数差异，供提取同步测试）");
                sqliteSource = createDataSource(
                        "演示SQLite源库", DatabaseType.SQLITE,
                        "jdbc:sqlite:" + new File(SQLITE_DB_PATH).getAbsolutePath(),
                        "org.sqlite.JDBC", "", "", "",
                        "SQLite演示源库（12表）");
                sqliteTarget = createDataSource(
                        "演示SQLite目标库", DatabaseType.SQLITE,
                        "jdbc:sqlite:" + new File(SQLITE_DB_PATH2).getAbsolutePath(),
                        "org.sqlite.JDBC", "", "", "",
                        "SQLite演示目标库（含条数差异）");
            } else {
                h2Source = existingH2;
                h2Target = DatasourceConfigRepository.findByName("演示H2目标库");
                sqliteSource = DatasourceConfigRepository.findByName("演示SQLite源库");
                sqliteTarget = DatasourceConfigRepository.findByName("演示SQLite目标库");
                log.info("演示数据源已存在，已重灌库内表数据");
            }

            log.info("创建演示比对任务...");
            if (h2Source != null && h2Target != null) {
                savePendingCompareTask(
                        "【演示】多表条数差异比对（提取同步测试）",
                        h2Source.getId(), h2Target.getId(),
                        "", ""); // 空 schema = 全库，覆盖 PUBLIC + SHOP
            }
            if (sqliteSource != null && sqliteTarget != null) {
                savePendingCompareTask(
                        "【演示】SQLite多表比对（提取同步测试）",
                        sqliteSource.getId(), sqliteTarget.getId(),
                        "", "");
            }

            writeDemoVersion(DEMO_DATA_VERSION);

            log.info("==========================================");
            log.info("  多表演示环境就绪！");
            log.info("  H2: PUBLIC(6表) + SHOP(6表)，目标库约 8~10 处条数差异");
            log.info("  请执行比对任务后打开报告 → 条数差异 →「提取为同步任务」");
            log.info("==========================================");

        } catch (Exception e) {
            log.error("演示数据初始化失败", e);
        }
    }

    private void savePendingCompareTask(String name, Long srcId, Long tgtId,
                                        String sourceSchema, String targetSchema) {
        CompareTask task = new CompareTask();
        task.setTaskName(name);
        task.setSourceDataSourceId(srcId);
        task.setTargetDataSourceId(tgtId);
        task.setCompareMode("FULL");
        task.setCompareSchema(true);
        task.setCompareCount(true);
        task.setCompareData(true);
        task.setCompareIndex(true);
        task.setCompareFunction(false);
        task.setCompareProcedure(false);
        task.setBatchSize(1000);
        task.setSampleRate(0.1);
        task.setSourceSchema(sourceSchema);
        task.setTargetSchema(targetSchema);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setCreateTime(LocalDateTime.now());
        taskRepository.save(task);
    }

    private void createH2DemoDb(String dbPath, boolean isSource) throws Exception {
        String url = "jdbc:h2:file:" + new File(dbPath).getAbsolutePath() + ";DB_CLOSE_DELAY=0;AUTO_SERVER=FALSE";
        try (Connection conn = DriverManager.getConnection(url, "sa", "");
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP ALL OBJECTS");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS SHOP");

            // ---- PUBLIC ----
            stmt.execute("CREATE TABLE PUBLIC.users ("
                    + "id BIGINT PRIMARY KEY, username VARCHAR(50) NOT NULL, email VARCHAR(100), "
                    + "balance DECIMAL(12,2) DEFAULT 0, status VARCHAR(20) DEFAULT 'ACTIVE', "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE INDEX idx_users_email ON PUBLIC.users(email)");
            stmt.execute("CREATE INDEX idx_users_status ON PUBLIC.users(status)");

            stmt.execute("CREATE TABLE PUBLIC.orders ("
                    + "id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, order_no VARCHAR(32) NOT NULL, "
                    + "amount DECIMAL(12,2) NOT NULL, discount DECIMAL(5,2) DEFAULT 0, "
                    + "status VARCHAR(20) DEFAULT 'PENDING', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE INDEX idx_orders_user ON PUBLIC.orders(user_id)");

            stmt.execute("CREATE TABLE PUBLIC.products ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, price DECIMAL(10,2) NOT NULL, "
                    + "stock INT DEFAULT 0, category VARCHAR(50), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.execute("CREATE TABLE PUBLIC.access_logs ("
                    + "id BIGINT PRIMARY KEY, user_id BIGINT, action VARCHAR(50), ip_address VARCHAR(45), "
                    + "detail VARCHAR(500), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.execute("CREATE TABLE PUBLIC.departments ("
                    + "id BIGINT PRIMARY KEY, code VARCHAR(32) NOT NULL, name VARCHAR(80) NOT NULL)");

            stmt.execute("CREATE TABLE PUBLIC.categories ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL, parent_id BIGINT)");

            // ---- SHOP ----
            stmt.execute("CREATE TABLE SHOP.inventory ("
                    + "id BIGINT PRIMARY KEY, sku VARCHAR(40) NOT NULL, qty INT NOT NULL, warehouse VARCHAR(40))");
            stmt.execute("CREATE TABLE SHOP.coupons ("
                    + "id BIGINT PRIMARY KEY, code VARCHAR(32) NOT NULL, amount DECIMAL(10,2), used INT DEFAULT 0)");
            stmt.execute("CREATE TABLE SHOP.payments ("
                    + "id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, pay_amount DECIMAL(12,2), channel VARCHAR(20))");
            stmt.execute("CREATE TABLE SHOP.notifications ("
                    + "id BIGINT PRIMARY KEY, user_id BIGINT, title VARCHAR(120), body VARCHAR(500), read_flag INT DEFAULT 0)");
            stmt.execute("CREATE TABLE SHOP.device_tokens ("
                    + "id BIGINT PRIMARY KEY, user_id BIGINT, token VARCHAR(128), platform VARCHAR(20))");
            stmt.execute("CREATE TABLE SHOP.audit_trail ("
                    + "id BIGINT PRIMARY KEY, actor VARCHAR(50), action VARCHAR(50), detail VARCHAR(500))");

            Random rnd = new Random(42);
            String[] statuses = {"ACTIVE", "INACTIVE", "SUSPENDED"};
            String[] categories = {"电子", "服装", "食品", "家居", "运动"};
            String[] actions = {"LOGIN", "LOGOUT", "VIEW", "PURCHASE", "SEARCH"};
            String[] channels = {"ALIPAY", "WECHAT", "CARD"};

            for (int i = 1; i <= 100; i++) {
                stmt.execute(String.format(
                        "INSERT INTO PUBLIC.users(id, username, email, balance, status) VALUES (%d, 'user_%d', 'user%d@demo.com', %.2f, '%s')",
                        i, i, i, rnd.nextDouble() * 10000, statuses[rnd.nextInt(statuses.length)]));
            }
            for (int i = 1; i <= 500; i++) {
                stmt.execute(String.format(
                        "INSERT INTO PUBLIC.orders(id, user_id, order_no, amount, discount, status) VALUES (%d, %d, 'ORD%08d', %.2f, %.2f, '%s')",
                        i, rnd.nextInt(100) + 1, i, rnd.nextDouble() * 5000 + 10, rnd.nextDouble() * 100,
                        statuses[rnd.nextInt(statuses.length)]));
            }
            for (int i = 1; i <= 50; i++) {
                stmt.execute(String.format(
                        "INSERT INTO PUBLIC.products(id, name, price, stock, category) VALUES (%d, '商品_%d', %.2f, %d, '%s')",
                        i, i, rnd.nextDouble() * 2000 + 10, rnd.nextInt(1000), categories[rnd.nextInt(categories.length)]));
            }
            for (int i = 1; i <= 1000; i++) {
                stmt.execute(String.format(
                        "INSERT INTO PUBLIC.access_logs(id, user_id, action, ip_address, detail) VALUES (%d, %d, '%s', '192.168.1.%d', '操作详情_%d')",
                        i, rnd.nextInt(100) + 1, actions[rnd.nextInt(actions.length)], rnd.nextInt(255) + 1, i));
            }
            for (int i = 1; i <= 20; i++) {
                stmt.execute(String.format(
                        "INSERT INTO PUBLIC.departments(id, code, name) VALUES (%d, 'D%03d', '部门_%d')", i, i, i));
            }
            for (int i = 1; i <= 30; i++) {
                stmt.execute(String.format(
                        "INSERT INTO PUBLIC.categories(id, name, parent_id) VALUES (%d, '分类_%d', %s)",
                        i, i, i <= 5 ? "NULL" : String.valueOf((i % 5) + 1)));
            }
            for (int i = 1; i <= 200; i++) {
                stmt.execute(String.format(
                        "INSERT INTO SHOP.inventory(id, sku, qty, warehouse) VALUES (%d, 'SKU%05d', %d, 'WH%d')",
                        i, i, rnd.nextInt(500), (i % 3) + 1));
            }
            for (int i = 1; i <= 80; i++) {
                stmt.execute(String.format(
                        "INSERT INTO SHOP.coupons(id, code, amount, used) VALUES (%d, 'C%05d', %.2f, %d)",
                        i, i, rnd.nextDouble() * 50 + 5, rnd.nextInt(2)));
            }
            for (int i = 1; i <= 300; i++) {
                stmt.execute(String.format(
                        "INSERT INTO SHOP.payments(id, order_id, pay_amount, channel) VALUES (%d, %d, %.2f, '%s')",
                        i, rnd.nextInt(500) + 1, rnd.nextDouble() * 3000 + 1, channels[rnd.nextInt(channels.length)]));
            }
            for (int i = 1; i <= 150; i++) {
                stmt.execute(String.format(
                        "INSERT INTO SHOP.notifications(id, user_id, title, body, read_flag) VALUES (%d, %d, '通知_%d', '内容_%d', %d)",
                        i, rnd.nextInt(100) + 1, i, i, rnd.nextInt(2)));
            }
            for (int i = 1; i <= 120; i++) {
                stmt.execute(String.format(
                        "INSERT INTO SHOP.device_tokens(id, user_id, token, platform) VALUES (%d, %d, 'tok_%d_%d', '%s')",
                        i, rnd.nextInt(100) + 1, i, rnd.nextInt(9999), i % 2 == 0 ? "IOS" : "ANDROID"));
            }
            for (int i = 1; i <= 500; i++) {
                stmt.execute(String.format(
                        "INSERT INTO SHOP.audit_trail(id, actor, action, detail) VALUES (%d, 'user_%d', '%s', '审计_%d')",
                        i, rnd.nextInt(100) + 1, actions[rnd.nextInt(actions.length)], i));
            }

            if (!isSource) {
                makeH2Differences(stmt, rnd);
            }
        }
    }

    private void makeH2Differences(Statement stmt, Random rnd) throws Exception {
        // 条数差异（提取同步主场景）
        stmt.execute("DELETE FROM PUBLIC.users WHERE id = 50");                 // -1
        stmt.execute("INSERT INTO PUBLIC.products(id, name, price, stock, category) VALUES (999, '额外商品_target', 12.34, 10, '电子')"); // +1
        for (int i = 991; i <= 1000; i++) {
            stmt.execute("DELETE FROM PUBLIC.access_logs WHERE id = " + i);      // -10
        }
        for (int i = 196; i <= 200; i++) {
            stmt.execute("DELETE FROM SHOP.inventory WHERE id = " + i);          // -5
        }
        stmt.execute("INSERT INTO SHOP.coupons(id, code, amount, used) VALUES (901, 'EXTRA1', 9.9, 0)");
        stmt.execute("INSERT INTO SHOP.coupons(id, code, amount, used) VALUES (902, 'EXTRA2', 19.9, 0)");
        stmt.execute("INSERT INTO SHOP.coupons(id, code, amount, used) VALUES (903, 'EXTRA3', 29.9, 0)"); // +3
        for (int i = 281; i <= 300; i++) {
            stmt.execute("DELETE FROM SHOP.payments WHERE id = " + i);           // -20
        }
        for (int i = 151; i <= 165; i++) {
            stmt.execute(String.format(
                    "INSERT INTO SHOP.notifications(id, user_id, title, body, read_flag) VALUES (%d, 1, '额外_%d', 'x', 0)",
                    i, i));                                                     // +15
        }
        for (int i = 113; i <= 120; i++) {
            stmt.execute("DELETE FROM SHOP.device_tokens WHERE id = " + i);      // -8
        }
        for (int i = 451; i <= 500; i++) {
            stmt.execute("DELETE FROM SHOP.audit_trail WHERE id = " + i);        // -50
        }
        // 非条数差异
        stmt.execute("UPDATE PUBLIC.users SET balance = 9999.99 WHERE id = 10");
        stmt.execute("UPDATE PUBLIC.orders SET amount = 8888.88 WHERE id = 100");
        try {
            stmt.execute("DROP INDEX IF EXISTS idx_users_status");
        } catch (Exception ignored) {
        }
        log.info("  H2目标库差异: 8处条数差 + 金额/索引差异（departments/categories/orders 条数一致）");
    }

    private void createSqliteDemoDb(String dbPath, boolean isSource) throws Exception {
        deleteIfExists(dbPath);
        String url = "jdbc:sqlite:" + new File(dbPath).getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            String[] ddl = {
                    "CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT, email TEXT, balance REAL, status TEXT)",
                    "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, order_no TEXT, amount REAL, discount REAL, status TEXT)",
                    "CREATE TABLE products (id INTEGER PRIMARY KEY, name TEXT, price REAL, stock INTEGER, category TEXT)",
                    "CREATE TABLE access_logs (id INTEGER PRIMARY KEY, user_id INTEGER, action TEXT, ip_address TEXT, detail TEXT)",
                    "CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT, name TEXT)",
                    "CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT, parent_id INTEGER)",
                    "CREATE TABLE inventory (id INTEGER PRIMARY KEY, sku TEXT, qty INTEGER, warehouse TEXT)",
                    "CREATE TABLE coupons (id INTEGER PRIMARY KEY, code TEXT, amount REAL, used INTEGER)",
                    "CREATE TABLE payments (id INTEGER PRIMARY KEY, order_id INTEGER, pay_amount REAL, channel TEXT)",
                    "CREATE TABLE notifications (id INTEGER PRIMARY KEY, user_id INTEGER, title TEXT, body TEXT, read_flag INTEGER)",
                    "CREATE TABLE device_tokens (id INTEGER PRIMARY KEY, user_id INTEGER, token TEXT, platform TEXT)",
                    "CREATE TABLE audit_trail (id INTEGER PRIMARY KEY, actor TEXT, action TEXT, detail TEXT)"
            };
            for (String sql : ddl) {
                stmt.execute(sql);
            }

            Random rnd = new Random(42);
            for (int i = 1; i <= 100; i++) {
                stmt.execute(String.format(
                        "INSERT INTO users VALUES (%d,'user_%d','u%d@d.com',%.2f,'ACTIVE')", i, i, i, rnd.nextDouble() * 100));
            }
            for (int i = 1; i <= 200; i++) {
                stmt.execute(String.format(
                        "INSERT INTO orders VALUES (%d,%d,'ORD%d',%.2f,0,'OK')", i, (i % 100) + 1, i, rnd.nextDouble() * 100));
            }
            for (int i = 1; i <= 40; i++) {
                stmt.execute(String.format("INSERT INTO products VALUES (%d,'P%d',%.2f,%d,'A')", i, i, rnd.nextDouble() * 50, i));
            }
            for (int i = 1; i <= 300; i++) {
                stmt.execute(String.format("INSERT INTO access_logs VALUES (%d,%d,'VIEW','1.1.1.1','d%d')", i, (i % 100) + 1, i));
            }
            for (int i = 1; i <= 15; i++) {
                stmt.execute(String.format("INSERT INTO departments VALUES (%d,'D%d','部门%d')", i, i, i));
            }
            for (int i = 1; i <= 20; i++) {
                stmt.execute(String.format("INSERT INTO categories VALUES (%d,'C%d',NULL)", i, i));
            }
            for (int i = 1; i <= 100; i++) {
                stmt.execute(String.format("INSERT INTO inventory VALUES (%d,'S%d',%d,'W1')", i, i, i));
            }
            for (int i = 1; i <= 50; i++) {
                stmt.execute(String.format("INSERT INTO coupons VALUES (%d,'C%d',%.2f,0)", i, i, rnd.nextDouble() * 10));
            }
            for (int i = 1; i <= 120; i++) {
                stmt.execute(String.format("INSERT INTO payments VALUES (%d,%d,%.2f,'CARD')", i, i, rnd.nextDouble() * 80));
            }
            for (int i = 1; i <= 80; i++) {
                stmt.execute(String.format("INSERT INTO notifications VALUES (%d,%d,'t%d','b',0)", i, (i % 100) + 1, i));
            }
            for (int i = 1; i <= 60; i++) {
                stmt.execute(String.format("INSERT INTO device_tokens VALUES (%d,%d,'tok%d','IOS')", i, (i % 100) + 1, i));
            }
            for (int i = 1; i <= 200; i++) {
                stmt.execute(String.format("INSERT INTO audit_trail VALUES (%d,'a','ACT','d%d')", i, i));
            }

            if (!isSource) {
                stmt.execute("DELETE FROM users WHERE id = 30");
                stmt.execute("INSERT INTO products VALUES (999,'extra',1.0,1,'X')");
                for (int i = 291; i <= 300; i++) {
                    stmt.execute("DELETE FROM access_logs WHERE id = " + i);
                }
                for (int i = 96; i <= 100; i++) {
                    stmt.execute("DELETE FROM inventory WHERE id = " + i);
                }
                stmt.execute("INSERT INTO coupons VALUES (901,'X1',1,0)");
                stmt.execute("INSERT INTO coupons VALUES (902,'X2',2,0)");
                for (int i = 101; i <= 120; i++) {
                    stmt.execute("DELETE FROM payments WHERE id = " + i);
                }
                for (int i = 81; i <= 90; i++) {
                    stmt.execute(String.format("INSERT INTO notifications VALUES (%d,1,'ex%d','x',0)", i, i));
                }
                for (int i = 53; i <= 60; i++) {
                    stmt.execute("DELETE FROM device_tokens WHERE id = " + i);
                }
                for (int i = 151; i <= 200; i++) {
                    stmt.execute("DELETE FROM audit_trail WHERE id = " + i);
                }
                stmt.execute("UPDATE users SET balance = 5555.55 WHERE id = 5");
                log.info("  SQLite目标库: 多表条数差异已制造");
            }
        }
    }

    private DataSourceConfig createDataSource(String name, DatabaseType dbType, String url,
                                              String driverClass, String username, String password,
                                              String schema, String description) {
        DataSourceConfig config = new DataSourceConfig();
        config.setName(name);
        config.setDbType(dbType);
        config.setUrl(url);
        config.setDriverClass(driverClass);
        config.setUsername(username);
        config.setPassword(credentialEncryptor.encrypt(password));
        config.setSchema(schema);
        config.setDescription(description);
        config.setEnabled(true);
        config.setCreateTime(LocalDateTime.now());
        config.setUpdateTime(LocalDateTime.now());
        return DatasourceConfigRepository.save(config);
    }

    private String readDemoVersion() {
        try {
            Path p = Paths.get(VERSION_FILE);
            if (!Files.exists(p)) {
                return "";
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void writeDemoVersion(String version) throws Exception {
        Files.write(Paths.get(VERSION_FILE), version.getBytes(StandardCharsets.UTF_8));
    }

    private void deleteIfExists(String path) {
        File f = new File(path);
        if (f.exists() && !f.delete()) {
            log.warn("无法删除演示库文件: {}", path);
        }
    }
}
