package com.gcoll.dbcompare.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 比对任务实体
 */
@Data
@Entity
@Table(name = "dbcompare_task")
public class CompareTask {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 任务名称
     */
    @Column(nullable = false)
    private String taskName;
    
    /**
     * 源数据源ID
     */
    @Column(nullable = false)
    private Long sourceDataSourceId;
    
    /**
     * 目标数据源ID
     */
    @Column(nullable = false)
    private Long targetDataSourceId;
    
    /**
     * 比对模式：FULL(全量)、SAMPLE(抽样)、INCREMENTAL(增量)
     */
    @Column(nullable = false)
    private String compareMode = "FULL";
    
    /**
     * 抽样比例（仅SAMPLE模式有效）
     */
    private Double sampleRate = 0.1;
    
    /**
     * 是否比对表结构
     */
    private Boolean compareSchema = true;
    
    /**
     * 是否比对数据量
     */
    private Boolean compareCount = true;
    
    /**
     * 是否比对数据内容
     */
    private Boolean compareData = true;
    
    /**
     * 是否比对索引
     */
    private Boolean compareIndex = true;
    
    /**
     * 是否比对函数
     */
    private Boolean compareFunction = true;
    
    /**
     * 是否比对存储过程
     */
    private Boolean compareProcedure = true;
    
    /**
     * 源数据源的库/Schema（用户在比对配置页选择的，不一定是数据源配置中存储的）
     */
    private String sourceSchema;
    
    /**
     * 目标数据源的库/Schema（用户在比对配置页选择的）
     */
    private String targetSchema;

    /**
     * 源库到目标库的映射（多库模式下库名不一致时使用）
     * 格式: srcSchema1:targetSchema1,srcSchema2:targetSchema2,...
     * 未映射的库默认使用同名
     */
    @Column(columnDefinition = "TEXT")
    private String schemaMapping;
    
    /**
     * 批次大小（逐行比对时每批处理的记录数，默认1000）
     */
    private Integer batchSize = 1000;
    
    /**
     * 指定比对的表（空则全比）
     */
    @Column(columnDefinition = "TEXT")
    private String includeTables;
    
    /**
     * 排除的表
     */
    @Column(columnDefinition = "TEXT")
    private String excludeTables;
    
    /**
     * 任务状态：PENDING、RUNNING、SUCCESS、FAILED
     */
    @Column(nullable = false)
    private String status = "PENDING";
    
    /**
     * 进度百分比
     */
    private Integer progress = 0;
    
    /**
     * 当前处理的表名
     */
    private String currentTable;
    
    /**
     * 已处理表数
     */
    private Integer processedTables = 0;
    
    /**
     * 总表数
     */
    private Integer totalTables = 0;
    
    /**
     * 错误信息
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * 报告文件路径
     */
    private String reportPath;
    
    /**
     * 创建时间
     */
    @Column(updatable = false)
    private LocalDateTime createTime;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
