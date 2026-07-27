package com.gcoll.dbcompare;

import com.gcoll.dbcompare.service.AppVersionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.net.InetAddress;

/**
 * 数据库比对工具 - Spring Boot 主应用
 * <p>
 * 任务库 / 数据源库双 JPA 见 {@link com.gcoll.dbcompare.config.AppJpaConfig}、
 * {@link com.gcoll.dbcompare.config.DatasourceStoreJpaConfig}（勿在此类上挂 {@code @EnableJpaRepositories}，否则 {@code @WebMvcTest} 会误加载仓库）。
 */
@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class
})
public class DbCompareApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbCompareApplication.class, args);
    }

    /**
     * 应用启动完成后打印访问地址与版本
     */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            host = "localhost";
        }

        String baseUrl = "http://" + host + ":" + port + contextPath;
        String version = "1.0.0";
        int schemaVersion = 0;
        boolean dbUpdated = false;
        try {
            AppVersionService vs = event.getApplicationContext().getBean(AppVersionService.class);
            version = vs.getAppVersion();
            schemaVersion = vs.getSchemaVersion();
            dbUpdated = vs.isDbUpdatedThisStartup();
        } catch (Exception ignored) {
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("   数据库全库比对工具 v" + version + " 已启动");
        System.out.println("========================================");
        System.out.println();
        System.out.println("  版本:         前端/后端 v" + version + "  ·  DB schema " + schemaVersion);
        if (dbUpdated) {
            System.out.println("  数据库:       本次启动已应用结构更新（详见页面版本日志）");
        }
        System.out.println("  前台首页:     " + baseUrl + "/");
        System.out.println("  数据源管理:   " + baseUrl + "/datasource/list");
        System.out.println("  比对任务:     " + baseUrl + "/compare/config");
        System.out.println("  同步任务:     " + baseUrl + "/sync/list");
        System.out.println("  版本接口:     " + baseUrl + "/api/version");
        System.out.println("  任务库文件:   ./data/dbcompare.mv.db");
        System.out.println("  数据源库文件: ./data/datasource.mv.db （同步程序时勿覆盖）");
        System.out.println();
        System.out.println("========================================");
        System.out.println();
    }
}
