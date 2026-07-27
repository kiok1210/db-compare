-- 目标库初始化：同结构空表
CREATE DATABASE IF NOT EXISTS loadtest DEFAULT CHARACTER SET utf8mb4;
USE loadtest;

CREATE TABLE IF NOT EXISTS big_sync_60m (
  id          BIGINT       NOT NULL,
  c1          VARCHAR(64)  NOT NULL,
  c2          INT          NOT NULL,
  c3          DECIMAL(18,2) NOT NULL,
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

GRANT ALL PRIVILEGES ON loadtest.* TO 'sync'@'%';
FLUSH PRIVILEGES;
