-- 在源库生成约 6000 万行（分批，避免单事务过大）
-- 用法：mysql -h... -u... -p loadtest < 02_generate_60m.sql
-- 可改 @target_rows / @chunk

USE loadtest;

SET @target_rows := 60000000;
SET @chunk := 50000;
SET @start_id := IFNULL((SELECT MAX(id) FROM big_sync_60m), 0);

DROP PROCEDURE IF EXISTS sp_gen_big_sync_60m;
DELIMITER $$
CREATE PROCEDURE sp_gen_big_sync_60m()
BEGIN
  DECLARE v_cur BIGINT DEFAULT @start_id;
  DECLARE v_end BIGINT;
  DECLARE v_batch INT;

  SET v_end = @target_rows;
  WHILE v_cur < v_end DO
    SET v_batch = LEAST(@chunk, v_end - v_cur);
    INSERT INTO big_sync_60m (id, c1, c2, c3, created_at)
    SELECT
      n.id,
      CONCAT('r', n.id),
      CAST(n.id % 1000 AS SIGNED),
      ROUND((n.id % 10000) / 100, 2),
      NOW()
    FROM (
      SELECT (@row := @row + 1) AS id
      FROM information_schema.columns c1
      CROSS JOIN information_schema.columns c2
      CROSS JOIN (SELECT @row := v_cur) r
      LIMIT v_batch
    ) n;
    SET v_cur = v_cur + v_batch;
    -- 进度：每批提交
    COMMIT;
  END WHILE;
END$$
DELIMITER ;

-- 若库开启自动提交可直接 CALL；长跑建议 screen/tmux
CALL sp_gen_big_sync_60m();

SELECT COUNT(*) AS cnt, MIN(id) AS min_id, MAX(id) AS max_id FROM big_sync_60m;
