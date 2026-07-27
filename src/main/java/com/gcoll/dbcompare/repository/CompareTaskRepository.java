package com.gcoll.dbcompare.repository;

import com.gcoll.dbcompare.entity.CompareTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 比对任务Repository
 */
@Repository
public interface CompareTaskRepository extends JpaRepository<CompareTask, Long> {

    /**
     * 根据状态查询任务
     */
    List<CompareTask> findByStatus(String status);

    /**
     * 统计指定状态的任务数量
     */
    @Query("SELECT COUNT(t) FROM CompareTask t WHERE t.status = ?1")
    long countByStatus(String status);

    /**
     * 查询最近的任务
     */
    List<CompareTask> findTop10ByOrderByCreateTimeDesc();
}
