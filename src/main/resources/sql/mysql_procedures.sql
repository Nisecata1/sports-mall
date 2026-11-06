-- 运动装备商城的取消订单与本人月度报表存储过程。
-- 事务由Java调用者管理；过程内部不START TRANSACTION/COMMIT。
-- 月报按订单创建月份统计当前状态，已完成金额累计orders.amount。
-- IF NOT EXISTS不替换已有定义；本文件不修改已有订单。
USE sports_mall;

DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS sp_cancel_order(IN p_user_id BIGINT, IN p_order_id BIGINT)
MODIFIES SQL DATA
SQL SECURITY INVOKER
COMMENT 'Cancel one pending order owned by the supplied user'
BEGIN
    IF p_user_id IS NULL OR p_user_id < 1 OR p_order_id IS NULL OR p_order_id < 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_NOT_CANCELLABLE';
    END IF;

    UPDATE orders SET status = 2
    WHERE order_id = p_order_id AND user_id = p_user_id AND status = 0;

    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_NOT_CANCELLABLE';
    END IF;
END$$

CREATE PROCEDURE IF NOT EXISTS sp_monthly_order_report(IN p_user_id BIGINT, IN p_month_start DATE)
READS SQL DATA
SQL SECURITY INVOKER
COMMENT 'Summarize one users orders by creation month and current status'
BEGIN
    DECLARE v_month_end DATE;

    IF p_user_id IS NULL OR p_user_id < 1 OR p_month_start IS NULL
       OR p_month_start < '1000-01-01' OR DAYOFMONTH(p_month_start) <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_MONTHLY_REPORT_INPUT';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM users WHERE user_id = p_user_id AND status = 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MONTHLY_REPORT_USER_NOT_ACTIVE';
    END IF;

    -- MySQL的日期上限为9999年12月，最大月份使用空的结束上界。
    IF p_month_start = '9999-12-01' THEN
        SET v_month_end = NULL;
    ELSE
        SET v_month_end = DATE_ADD(p_month_start, INTERVAL 1 MONTH);
    END IF;

    SELECT p_user_id AS user_id, p_month_start AS month_start,
           COUNT(*) AS order_count,
           COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) AS pending_count,
           COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS completed_count,
           COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS cancelled_count,
           COALESCE(SUM(CASE WHEN status = 1 THEN amount ELSE 0.00 END), 0.00) AS completed_amount
    FROM orders
    WHERE user_id = p_user_id
      AND created_at >= p_month_start
      AND (v_month_end IS NULL OR created_at < v_month_end);
END$$

DELIMITER ;

SELECT ROUTINE_NAME, ROUTINE_TYPE
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = DATABASE()
  AND ROUTINE_NAME IN ('sp_cancel_order', 'sp_monthly_order_report')
ORDER BY ROUTINE_NAME;
