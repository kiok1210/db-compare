package com.gcoll.dbcompare.repository;

import com.gcoll.dbcompare.entity.SyncTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SyncTaskRepository extends JpaRepository<SyncTask, Long> {

    List<SyncTask> findAllByOrderByCreateTimeDesc();

    List<SyncTask> findByStatus(String status);
}
