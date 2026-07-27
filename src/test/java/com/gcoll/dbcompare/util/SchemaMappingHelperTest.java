package com.gcoll.dbcompare.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMappingHelperTest {

    @Test
    void resolveCatalogs_mapsSourceSchemaToTargetSchema() {
        Map<String, String> schemaMap = new LinkedHashMap<>();
        schemaMap.put("db_a", "prd_db_a");

        SchemaMappingHelper.CatalogPair pair = SchemaMappingHelper.resolveCatalogs(
                "db_a::user",
                "db_a,db_b", "prd_db_a,prd_db_b",
                true, true, schemaMap, Collections.emptyMap());

        assertEquals("db_a", pair.srcCat);
        assertEquals("prd_db_a", pair.tgtCat);
        assertEquals("user", pair.pureTableName);
    }

    @Test
    void resolveCatalogs_singleTargetUsesSelectedTargetSchema() {
        SchemaMappingHelper.CatalogPair pair = SchemaMappingHelper.resolveCatalogs(
                "db_a::user",
                "db_a,db_b", "tgt_only",
                true, false, Collections.emptyMap(), Collections.emptyMap());

        assertEquals("db_a", pair.srcCat);
        assertEquals("tgt_only", pair.tgtCat);
        assertEquals("user", pair.pureTableName);
    }

    @Test
    void resolveCatalogs_fallsBackToTableCatalogMapWhenUnqualified() {
        Map<String, String> tgtMap = new LinkedHashMap<>();
        tgtMap.put("order", "tgt_order_db");

        SchemaMappingHelper.CatalogPair pair = SchemaMappingHelper.resolveCatalogs(
                "order",
                "src_db", null,
                false, true, Collections.emptyMap(), tgtMap);

        assertNull(pair.srcCat);
        assertEquals("tgt_order_db", pair.tgtCat);
        assertEquals("order", pair.pureTableName);
    }

    @Test
    void resolveCatalogs_fallsBackToSameNameWhenNoTargetList() {
        Map<String, String> tgtMap = new LinkedHashMap<>();
        tgtMap.put("user", "wrong_db");

        SchemaMappingHelper.CatalogPair pair = SchemaMappingHelper.resolveCatalogs(
                "src_db::user",
                "src_db", null,
                true, true, Collections.emptyMap(), tgtMap);

        assertEquals("src_db", pair.srcCat);
        assertEquals("src_db", pair.tgtCat);
        assertEquals("user", pair.pureTableName);
    }

    @Test
    void resolveCatalogs_infersPrdPrefixFromMultiTargetWhenMappingMissing() {
        SchemaMappingHelper.CatalogPair pair = SchemaMappingHelper.resolveCatalogs(
                "gcoll_acs::logrecord",
                "gcoll_acs,gcoll_aux", "prd_gcoll_acs,prd_gcoll_aux",
                true, true, Collections.emptyMap(), Collections.emptyMap());

        assertEquals("gcoll_acs", pair.srcCat);
        assertEquals("prd_gcoll_acs", pair.tgtCat);
        assertEquals("logrecord", pair.pureTableName);
    }

    @Test
    void suggestTargetSchema_prefersPrdPrefixOverSameName() {
        String tgt = SchemaMappingHelper.suggestTargetSchema(
                "gcoll_acs",
                Arrays.asList("gcoll_acs", "prd_gcoll_acs", "prd_gcoll_aux"),
                Collections.emptyMap());
        assertEquals("prd_gcoll_acs", tgt);
    }

    @Test
    void buildAutoSchemaMapping_fillsMissingPairs() {
        String mapping = SchemaMappingHelper.buildAutoSchemaMapping(
                "gcoll_acs,gcoll_aux",
                "prd_gcoll_acs,prd_gcoll_aux",
                null);
        assertTrue(mapping.contains("gcoll_acs:prd_gcoll_acs"));
        assertTrue(mapping.contains("gcoll_aux:prd_gcoll_aux"));
        assertEquals("prd_gcoll_acs,prd_gcoll_aux",
                SchemaMappingHelper.targetSchemasFromMapping(mapping));
    }

    @Test
    void parseSchemaMapping_parsesPairs() {
        Map<String, String> map = SchemaMappingHelper.parseSchemaMapping("a:b, c : d ");
        assertEquals("b", map.get("a"));
        assertEquals("d", map.get("c"));
    }
}
