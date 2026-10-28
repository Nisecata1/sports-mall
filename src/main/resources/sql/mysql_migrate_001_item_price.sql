-- 已有商城商品表的单价迁移；按区段检查后执行一次，不适用于已含 price 的新建库。
-- 本次已确认旧数据只有 item_id=1 的“训练篮球”，100.00 是演示单价，可在执行前调整。
-- 此文件不会由 Java 自动执行；建表 DDL 不替代既有表的 ALTER 迁移。
USE sports_mall;

-- 1. 先增加允许 NULL 的字段，给已有记录保留补价过程。
ALTER TABLE items ADD COLUMN price DECIMAL(10,2) NULL AFTER category_id;

-- 2. 只给已确认的这件商品回填演示价格，不统一覆盖其他商品或已填写价格。
UPDATE items SET price = 100.00
WHERE item_id = 1 AND price IS NULL;

SELECT item_id, title, price FROM items ORDER BY item_id;
SELECT COUNT(*) AS invalid_prices FROM items
WHERE price IS NULL OR price <= 0;

-- 3. 上述 invalid_prices 必须为 0；如不是0，先补齐实际商品单价再执行以下语句。
ALTER TABLE items
    MODIFY COLUMN price DECIMAL(10,2) NOT NULL,
    ADD CONSTRAINT chk_items_price CHECK (price > 0);
