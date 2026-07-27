package com.gcoll.dbcompare.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DatasourceStoreJpaConfigTest {

    @Test
    void datasourceStoreSettings_defaultsAndSetters() {
        DatasourceStoreJpaConfig.DatasourceStoreSettings settings =
                new DatasourceStoreJpaConfig.DatasourceStoreSettings();
        assertEquals("sa", settings.getUsername());
        assertEquals("org.h2.Driver", settings.getDriverClassName());
        settings.setUrl("jdbc:h2:mem:ds_cfg_test");
        settings.setUsername("sa");
        settings.setPassword("");
        assertEquals("jdbc:h2:mem:ds_cfg_test", settings.getUrl());
        assertNotNull(settings.getPassword());
    }
}
