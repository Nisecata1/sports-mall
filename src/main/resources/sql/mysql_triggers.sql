-- 运动装备商城：两个订单规则触发器，适用本工程 MySQL 8.4。
-- 在已有五表基础上安装；不建库、不改表、不修改已有订单。
-- IF NOT EXISTS保留同名对象，重复运行不会升级已有定义。
USE sports_mall;

DELIMITER $$

CREATE TRIGGER IF NOT EXISTS trg_orders_validate_insert
BEFORE INSERT ON orders
FOR EACH ROW
BEGIN
    DECLARE v_user_status TINYINT DEFAULT NULL;
    DECLARE v_item_status TINYINT DEFAULT NULL;
    DECLARE v_item_price DECIMAL(10,2) DEFAULT NULL;

    IF NEW.status IS NULL OR NEW.status <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_INITIAL_STATUS_MUST_BE_PENDING';
    END IF;

    SELECT status INTO v_user_status
    FROM users WHERE user_id = NEW.user_id FOR SHARE;
    IF v_user_status IS NULL OR v_user_status <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_USER_NOT_ACTIVE';
    END IF;

    SELECT status, price INTO v_item_status, v_item_price
    FROM items WHERE item_id = NEW.item_id FOR SHARE;
    IF v_item_status IS NULL OR v_item_status <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_ITEM_NOT_AVAILABLE';
    END IF;
    IF NEW.amount IS NULL OR v_item_price IS NULL OR NEW.amount <> v_item_price THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_AMOUNT_MUST_MATCH_ITEM_PRICE';
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS trg_orders_validate_update
BEFORE UPDATE ON orders
FOR EACH ROW
BEGIN
    -- 历史订单的归属、商品、成交金额、编号与创建时间不随状态更新而改写。
    IF NOT (NEW.order_id <=> OLD.order_id)
       OR NOT (NEW.user_id <=> OLD.user_id)
       OR NOT (NEW.item_id <=> OLD.item_id)
       OR NOT (NEW.amount <=> OLD.amount)
       OR NOT (NEW.created_at <=> OLD.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_HISTORICAL_FIELDS_IMMUTABLE';
    END IF;

    -- 可以保留原状态，或从待处理0变为已完成1/已取消2；终态不能倒退。
    IF NEW.status IS NULL OR NOT (
        NEW.status = OLD.status OR (OLD.status = 0 AND NEW.status IN (1, 2))
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATUS_TRANSITION_INVALID';
    END IF;
END$$

DELIMITER ;

SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = DATABASE()
  AND TRIGGER_NAME IN ('trg_orders_validate_insert', 'trg_orders_validate_update')
ORDER BY TRIGGER_NAME;
