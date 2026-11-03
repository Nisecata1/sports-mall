-- 项目011：用户资料及本人订单汇总两个视图，供Java概览查询使用。
-- 依赖已有users/profiles/orders，不新增或改写业务记录。
-- 重放会更新同名视图的查询定义，五张原始表及数据保留。
USE sports_mall;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_user_details AS
SELECT u.user_id, u.username, u.email, u.phone, u.role, u.status,
       u.created_at AS user_created_at, u.updated_at AS user_updated_at,
       p.profile_id, p.real_name, p.id_card, p.address, p.notes
FROM users u
LEFT JOIN profiles p ON p.user_id = u.user_id;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_user_order_summary AS
SELECT u.user_id,
       COUNT(o.order_id) AS order_count,
       SUM(CASE WHEN o.status = 0 THEN 1 ELSE 0 END) AS pending_count,
       SUM(CASE WHEN o.status = 1 THEN 1 ELSE 0 END) AS completed_count,
       SUM(CASE WHEN o.status = 2 THEN 1 ELSE 0 END) AS cancelled_count,
       COALESCE(SUM(CASE WHEN o.status = 1 THEN o.amount ELSE 0.00 END), 0.00) AS completed_amount
FROM users u
LEFT JOIN orders o ON o.user_id = u.user_id
GROUP BY u.user_id;

SELECT TABLE_NAME, SECURITY_TYPE
FROM information_schema.VIEWS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('v_user_details', 'v_user_order_summary')
ORDER BY TABLE_NAME;
