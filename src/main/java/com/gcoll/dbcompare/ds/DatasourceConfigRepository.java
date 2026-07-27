package com.gcoll.dbcompare.ds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 数据源配置 Repository（绑定独立库 datasource）。
 * Bean 名刻意不用 dataSourceRepository，避免与 Boot 自动扫描或旧包冲突。
 */
@Repository("datasourceConfigRepository")
public interface DatasourceConfigRepository extends JpaRepository<DataSourceConfig, Long> {

    DataSourceConfig findByName(String name);
}
