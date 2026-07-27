-- 6000 万行压测表 DDL（MySQL / GoldenDB 兼容）
-- 源库与目标库各执行一次；目标可先空表。

CREATE DATABASE IF NOT EXISTS loadtest DEFAULT CHARACTER SET utf8mb4;
USE loadtest;

DROP TABLE IF EXISTS big_sync_60m;
CREATE TABLE big_sync_60m (
  id          BIGINT       NOT NULL,
  c1          VARCHAR(64)  NOT NULL,
  c2          INT          NOT NULL,
  c3          DECIMAL(18,2) NOT NULL,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;
