package com.gcoll.dbcompare.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 应用元数据（schema 版本等）
 */
@Entity
@Table(name = "dbcompare_app_meta")
public class AppMeta {

    public static final String KEY_SCHEMA_VERSION = "SCHEMA_VERSION";
    public static final String KEY_LAST_APP_VERSION = "LAST_APP_VERSION";

    @Id
    @Column(length = 64)
    private String metaKey;

    @Column(length = 500)
    private String metaValue;

    @Column
    private LocalDateTime updateTime;

    public String getMetaKey() { return metaKey; }
    public void setMetaKey(String metaKey) { this.metaKey = metaKey; }

    public String getMetaValue() { return metaValue; }
    public void setMetaValue(String metaValue) { this.metaValue = metaValue; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
