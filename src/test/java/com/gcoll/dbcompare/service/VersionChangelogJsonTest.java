package com.gcoll.dbcompare.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止 version-changelog.json 语法损坏导致启动回退到 1.0.0 / 空更新日志。
 */
class VersionChangelogJsonTest {

    @Test
    void changelog_parsesAndHasReleases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource("version-changelog.json").getInputStream()) {
            Map<String, Object> root = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            assertNotNull(root.get("version"));
            assertFalse("1.0.0".equals(root.get("version")),
                    "changelog 不应回落到占位版本；检查 JSON 是否损坏");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> releases = (List<Map<String, Object>>) root.get("releases");
            assertNotNull(releases);
            assertTrue(releases.size() >= 10, "releases 应包含历史版本记录");
            assertTrue(releases.stream().allMatch(r -> r.get("version") != null),
                    "每条 release 必须有 version 字段");
        }
    }
}
