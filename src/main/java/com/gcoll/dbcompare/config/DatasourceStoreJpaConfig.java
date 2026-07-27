package com.gcoll.dbcompare.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据源配置独立 H2 库：{@code ./data/datasource}（文件名为 datasource.mv.db）。
 * 与任务库 {@code ./data/dbcompare} 分离，同步程序到内网时勿覆盖此文件。
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.gcoll.dbcompare.ds",
        entityManagerFactoryRef = DatasourceStoreJpaConfig.EMF_BEAN,
        transactionManagerRef = DatasourceStoreJpaConfig.TM_BEAN
)
public class DatasourceStoreJpaConfig {

    public static final String DS_BEAN = "datasourceStoreDataSource";
    public static final String EMF_BEAN = "datasourceEntityManagerFactory";
    public static final String TM_BEAN = "datasourceTransactionManager";

    /**
     * 独立绑定，避免再注册一个 {@link org.springframework.boot.autoconfigure.jdbc.DataSourceProperties}
     * 与主库 {@code spring.datasource} 冲突。
     */
    @Bean
    @ConfigurationProperties(prefix = "db-compare.datasource-store")
    public DatasourceStoreSettings datasourceStoreSettings() {
        return new DatasourceStoreSettings();
    }

    @Bean(name = DS_BEAN)
    public DataSource datasourceStoreDataSource(DatasourceStoreSettings settings) {
        DataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(settings.getUrl())
                .username(settings.getUsername())
                .password(settings.getPassword() != null ? settings.getPassword() : "")
                .driverClassName(settings.getDriverClassName())
                .build();
        DatasourceStoreSchemaRepair.repair(ds);
        return ds;
    }

    @Bean(name = "datasourceStoreSchemaReady")
    public Object datasourceStoreSchemaReady(@Qualifier(DS_BEAN) DataSource dataSource) {
        // 确保 EMF 创建前已完成列名修复（依赖 DS_BEAN 初始化顺序）
        return Boolean.TRUE;
    }

    @Bean(name = EMF_BEAN)
    public LocalContainerEntityManagerFactoryBean datasourceEntityManagerFactory(
            @Qualifier(DS_BEAN) DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("com.gcoll.dbcompare.ds");
        emf.setPersistenceUnitName("datasourceStore");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setDatabasePlatform("org.hibernate.dialect.H2Dialect");
        adapter.setShowSql(false);
        emf.setJpaVendorAdapter(adapter);

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "update");
        props.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        props.put("hibernate.physical_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        props.put("hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        emf.setJpaPropertyMap(props);
        return emf;
    }

    @Bean(name = TM_BEAN)
    public PlatformTransactionManager datasourceTransactionManager(
            @Qualifier(EMF_BEAN) EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    public static class DatasourceStoreSettings {
        private String url;
        private String username = "sa";
        private String password = "";
        private String driverClassName = "org.h2.Driver";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }
}
