package com.gcoll.dbcompare.dictionary;

import java.util.*;

/**
 * 字段智能分类器
 * 
 * 功能：
 * 1. 自动识别金额字段（排除利率、费率等比例字段）
 * 2. 自动识别时间字段
 * 3. 自动识别枚举/状态字段
 * 
 * 识别策略：
 * - 金额字段：列名含 money/amount/amt/price/cost/fee/balance 等关键字，
 *   且数据类型为 DECIMAL/NUMERIC/FLOAT/DOUBLE/BIGINT
 * - 排除利率：列名含 rate/ratio/percent/pct 的字段
 * - 时间字段：数据类型为 DATE/TIME/TIMESTAMP 或列名含 time/date
 */
public class FieldClassifier {

    // 金额字段关键字（正向匹配）
    private static final Set<String> AMOUNT_KEYWORDS = new HashSet<>(Arrays.asList(
        "amount", "amt", "money", "price", "cost", "fee", "charge",
        "balance", "total", "sum", "income", "expense", "revenue",
        "payment", "paid", "pay", "salary", "bonus", "commission",
        "premium", "deposit", "withdraw", "loan", "capital", "principal",
        "interest", "tax", "penalty", "fine", "refund", "subsidy",
        "discount", "coupon", "value", "worth", "budget", "quota",
        "限额", "金额", "费用", "余额", "总额", "收入", "支出",
        "缴费", "支付", "工资", "奖金", "佣金", "保费", "存款",
        "贷款", "本金", "利息", "税款", "罚款", "退款", "补贴",
        "折扣", "优惠", "价值", "预算", "额度"
    ));

    // 利率/比例字段关键字（排除这些字段）
    private static final Set<String> RATE_KEYWORDS = new HashSet<>(Arrays.asList(
        "rate", "ratio", "percent", "pct", "interest_rate",
        "tax_rate", "fee_rate", "discount_rate", "exchange_rate",
        "apr", "apy", "yield", "return_rate", "growth_rate",
        "利率", "费率", "税率", "汇率", "比例", "百分比",
        "年化", "收益率", "增长率"
    ));

    // 金额数据类型
    private static final Set<String> AMOUNT_DATA_TYPES = new HashSet<>(Arrays.asList(
        "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "REAL",
        "BIGINT", "INTEGER", "INT", "SMALLINT", "TINYINT",
        "NUMBER"  // Oracle
    ));

    // 时间数据类型
    private static final Set<String> TIME_DATA_TYPES = new HashSet<>(Arrays.asList(
        "DATE", "TIME", "TIMESTAMP", "DATETIME", "TIMESTAMPTZ",
        "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITHOUT TIME ZONE"
    ));

    // 时间字段关键字
    private static final Set<String> TIME_KEYWORDS = new HashSet<>(Arrays.asList(
        "time", "date", "created", "updated", "modified", "deleted",
        "create_time", "update_time", "modify_time", "delete_time",
        "时间", "日期", "创建", "更新", "修改", "删除"
    ));

    /**
     * 分类结果
     */
    public static class FieldClassification {
        private List<DatabaseMetadataReader.ColumnMetadata> amountFields = new ArrayList<>();
        private List<DatabaseMetadataReader.ColumnMetadata> timeFields = new ArrayList<>();
        private List<DatabaseMetadataReader.ColumnMetadata> otherFields = new ArrayList<>();
        private List<String> amountFieldNames = new ArrayList<>();
        private List<String> timeFieldNames = new ArrayList<>();
        private boolean hasAmountField;

        public List<DatabaseMetadataReader.ColumnMetadata> getAmountFields() {
            return amountFields;
        }

        public void setAmountFields(List<DatabaseMetadataReader.ColumnMetadata> amountFields) {
            this.amountFields = amountFields;
        }

        public List<DatabaseMetadataReader.ColumnMetadata> getTimeFields() {
            return timeFields;
        }

        public void setTimeFields(List<DatabaseMetadataReader.ColumnMetadata> timeFields) {
            this.timeFields = timeFields;
        }

        public List<DatabaseMetadataReader.ColumnMetadata> getOtherFields() {
            return otherFields;
        }

        public void setOtherFields(List<DatabaseMetadataReader.ColumnMetadata> otherFields) {
            this.otherFields = otherFields;
        }

        public List<String> getAmountFieldNames() {
            return amountFieldNames;
        }

        public void setAmountFieldNames(List<String> amountFieldNames) {
            this.amountFieldNames = amountFieldNames;
        }

        public List<String> getTimeFieldNames() {
            return timeFieldNames;
        }

        public void setTimeFieldNames(List<String> timeFieldNames) {
            this.timeFieldNames = timeFieldNames;
        }

        public boolean isHasAmountField() {
            return hasAmountField;
        }

        public void setHasAmountField(boolean hasAmountField) {
            this.hasAmountField = hasAmountField;
        }
    }

    /**
     * 对表的字段进行智能分类
     *
     * @param columns 表的所有列元数据
     * @return 分类结果
     */
    public static FieldClassification classify(List<DatabaseMetadataReader.ColumnMetadata> columns) {
        FieldClassification result = new FieldClassification();

        for (DatabaseMetadataReader.ColumnMetadata col : columns) {
            String colName = col.getColumnName();
            String colNameLower = colName != null ? colName.toLowerCase() : "";
            String dataType = col.getDataType();
            String dataTypeUpper = dataType != null ? dataType.toUpperCase() : "";

            // 1. 判断是否为金额字段
            if (isAmountField(colNameLower, dataTypeUpper)) {
                result.getAmountFields().add(col);
                result.getAmountFieldNames().add(colName);
                continue;
            }

            // 2. 判断是否为时间字段
            if (isTimeField(colNameLower, dataTypeUpper)) {
                result.getTimeFields().add(col);
                result.getTimeFieldNames().add(colName);
                continue;
            }

            // 3. 其他字段
            result.getOtherFields().add(col);
        }

        result.setHasAmountField(!result.getAmountFields().isEmpty());
        return result;
    }

    /**
     * 判断是否为金额字段（排除利率字段）
     */
    private static boolean isAmountField(String colNameLower, String dataTypeUpper) {
        // 必须是数值类型
        if (!AMOUNT_DATA_TYPES.contains(dataTypeUpper)) {
            return false;
        }

        // 排除利率/比例字段
        for (String keyword : RATE_KEYWORDS) {
            if (colNameLower.contains(keyword)) {
                return false;
            }
        }

        // 匹配金额关键字
        for (String keyword : AMOUNT_KEYWORDS) {
            if (colNameLower.contains(keyword)) {
                return true;
            }
        }

        // 对于 DECIMAL/NUMERIC/FLOAT/DOUBLE 类型，如果没有命中利率关键字，
        // 默认认为可能是金额字段（保守策略，减少遗漏）
        if ("DECIMAL".equals(dataTypeUpper) || "NUMERIC".equals(dataTypeUpper)) {
            // 只对高精度数值类型做默认匹配，避免整数类型误判
            if (!isExplicitNonAmount(colNameLower)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否为明确非金额的数值字段
     */
    private static boolean isExplicitNonAmount(String colNameLower) {
        // ID类字段
        if (colNameLower.endsWith("_id") || colNameLower.endsWith("id")
            || colNameLower.contains("_no") || colNameLower.contains("code")) {
            return true;
        }
        // 排序/序号字段
        if (colNameLower.contains("sort") || colNameLower.contains("seq")
            || colNameLower.contains("order") || colNameLower.contains("index")) {
            return true;
        }
        // 版本号
        if (colNameLower.contains("version") || colNameLower.contains("_ver")) {
            return true;
        }
        // 状态/类型编码
        if (colNameLower.contains("status") || colNameLower.contains("type")
            || colNameLower.contains("state") || colNameLower.contains("flag")) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为时间字段
     */
    private static boolean isTimeField(String colNameLower, String dataTypeUpper) {
        // 数据类型匹配
        if (TIME_DATA_TYPES.contains(dataTypeUpper)) {
            return true;
        }
        // 列名匹配
        for (String keyword : TIME_KEYWORDS) {
            if (colNameLower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建 SUM 聚合SQL（对金额字段求和）
     *
     * @param tableName   表名
     * @param amountFields 金额字段列表
     * @return SUM SQL语句
     */
    public static String buildSumSql(String tableName, List<String> amountFields) {
        if (amountFields == null || amountFields.isEmpty()) {
            return null;
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < amountFields.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("COALESCE(SUM(").append(amountFields.get(i)).append("), 0) AS SUM_")
               .append(amountFields.get(i));
        }
        sql.append(" FROM ").append(tableName);
        return sql.toString();
    }

    /**
     * 构建 COUNT + SUM 组合SQL（一次查询获取条数和金额总和）
     *
     * @param tableName   表名
     * @param amountFields 金额字段列表
     * @return 组合SQL语句
     */
    public static String buildCountAndSumSql(String tableName, List<String> amountFields) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt");
        if (amountFields != null && !amountFields.isEmpty()) {
            for (String field : amountFields) {
                sql.append(", COALESCE(SUM(").append(field).append("), 0) AS sum_").append(field);
            }
        }
        sql.append(" FROM ").append(tableName);
        return sql.toString();
    }

    /**
     * 构建行数据Hash SQL（用于快速比对数据一致性）
     * 使用 MD5 对所有列拼接后取Hash，避免逐行传输大量数据
     *
     * @param tableName 表名
     * @param columns   所有列名
     * @param dbType    数据库类型
     * @return Hash SQL语句
     */
    public static String buildRowHashSql(String tableName, List<String> columns, String dbType) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }

        String rowExpr;
        String hashSumExpr;

        if ("POSTGRESQL".equalsIgnoreCase(dbType)) {
            rowExpr = buildPgConcat(columns);
            hashSumExpr = "SUM(ABS(HASHTEXT(" + rowExpr + ")))";
        } else if ("ORACLE".equalsIgnoreCase(dbType)) {
            rowExpr = buildOracleConcat(columns);
            hashSumExpr = "SUM(ORA_HASH(" + rowExpr + "))";
        } else if ("SQLSERVER".equalsIgnoreCase(dbType)) {
            rowExpr = buildSqlServerConcat(columns);
            hashSumExpr = "SUM(ABS(CHECKSUM(" + rowExpr + ")))";
        } else if ("H2".equalsIgnoreCase(dbType)) {
            // H2 supports HASH function
            rowExpr = buildMysqlConcat(columns);
            hashSumExpr = "SUM(HASH('MD5', " + rowExpr + "))";
        } else if ("SQLITE".equalsIgnoreCase(dbType)) {
            // SQLite: no built-in hash, use concatenated string length as proxy
            rowExpr = buildSqliteConcat(columns);
            hashSumExpr = "SUM(LENGTH(" + rowExpr + "))";
        } else {
            // MySQL / GoldenDB
            rowExpr = buildMysqlConcat(columns);
            hashSumExpr = "SUM(CAST(CONV(SUBSTRING(MD5(" + rowExpr + "), 1, 8), 16, 10) AS DECIMAL(38,0)))";
        }

        return "SELECT COUNT(*) AS row_cnt, " + hashSumExpr + " AS total_hash FROM " + tableName;
    }

    private static String buildMysqlConcat(List<String> columns) {
        StringBuilder concat = new StringBuilder("CONCAT(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                concat.append(", '|', ");
            }
            concat.append("IFNULL(CAST(").append(columns.get(i)).append(" AS CHAR), 'NULL')");
        }
        concat.append(")");
        return concat.toString();
    }

    private static String buildPgConcat(List<String> columns) {
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                concat.append(" || '|' || ");
            }
            concat.append("COALESCE(CAST(").append(columns.get(i)).append(" AS TEXT), 'NULL')");
        }
        return concat.toString();
    }

    private static String buildOracleConcat(List<String> columns) {
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                concat.append(" || '|' || ");
            }
            concat.append("NVL(TO_CHAR(").append(columns.get(i)).append("), 'NULL')");
        }
        return concat.toString();
    }

    private static String buildSqlServerConcat(List<String> columns) {
        StringBuilder concat = new StringBuilder("CONCAT(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                concat.append(", '|', ");
            }
            concat.append("ISNULL(CAST(").append(columns.get(i)).append(" AS NVARCHAR(MAX)), 'NULL')");
        }
        concat.append(")");
        return concat.toString();
    }

    private static String buildSqliteConcat(List<String> columns) {
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                concat.append(" || '|' || ");
            }
            concat.append("IFNULL(CAST(").append(columns.get(i)).append(" AS TEXT), 'NULL')");
        }
        return concat.toString();
    }
}
