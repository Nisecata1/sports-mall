-- 运动装备商城：首个存储过程，封装取消本人待处理订单。
-- 事务由Java调用者管理；过程内部不START TRANSACTION/COMMIT。
-- 目前1个过程，课程要求至少2个，月报过程将在后续加入。
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

DELIMITER ;

SELECT ROUTINE_NAME, ROUTINE_TYPE
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = DATABASE() AND ROUTINE_NAME = 'sp_cancel_order';
