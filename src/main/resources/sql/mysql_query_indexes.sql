-- 增量查询索引；不增加列、不更新业务记录。先在隔离库对应用真实查询实测。
-- MySQL没有CREATE INDEX IF NOT EXISTS：由Prepare-CourseData.ps1调用的工具核对同名定义后执行。
USE sports_mall;
CREATE INDEX idx_items_sale_category_price ON items(status,category_id,price,item_id);
CREATE INDEX idx_orders_user_status_created ON orders(user_id,status,created_at,order_id);
