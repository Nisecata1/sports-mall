# 运动装备商城MongoDB集合设计

软件0.3.3，2026-11-09。数据库名sports_mall。

## 1. 文档组织与关联

后端以共享MongoClient访问四个集合。MySQL负责用户、分类、商品、订单、档案及导入暂存，MongoDB负责描述扩展、互动和日志。后端用实际SQL编号组合两库结果。

编号字段采用BSON Long，评分采用Int32，业务时间采用Date。普通评论与浏览的_id使用ObjectId。导入系统日志使用由批次和行号组成的稳定字符串_id，因此system_logs的读取兼容两类编号。

## 2. 四个集合

| 集合 | 主要字段 | 嵌套对象与数组 |
| --- | --- | --- |
| item_details | item_id、description | images数组，metadata.language及可选扩展字段 |
| comments | user_id、item_id、content、rating、created_at、可选updated_at | tags数组 |
| action_logs | user_id、item_id、action_type、duration_seconds、created_at | client_info内的client_type、ip等 |
| system_logs | user_id、log_type、log_level、message、timestamp | action_detail内的operation、ip、来源或导入批次 |

item_details将同一商品一起读取的可变说明和图片组织在一份文档中。评论和浏览会持续增长，分别作为独立集合中的记录组织。MongoDB单文档更新以字段方式保存，已有扩展字段随维护保留。

## 3. BSON结构示例

以下字段结构可在mongosh中表达。编号在业务写入时取实际SQL值。

```javascript
{
  item_id: Long("12"),
  description: "课程演示商品说明",
  images: [],
  metadata: { language: "zh-CN" }
}
```

```javascript
{
  user_id: Long("4"), item_id: Long("12"),
  content: "课程演示评价", rating: 5,
  tags: ["课程演示", "训练"],
  created_at: ISODate("2026-11-08T12:00:00Z")
}
```

## 4. 索引设计

| 集合 | 索引键 | 用途 |
| --- | --- | --- |
| item_details | item_id升序，唯一 | 一件商品至多一份详情 |
| comments | item_id、created_at倒序、_id倒序 | 商品最新评论 |
| comments | user_id、created_at倒序、_id倒序 | 本人评论维护 |
| comments | created_at倒序、_id倒序 | 管理员最新评论列表 |
| action_logs | created_at、item_id | 时间窗口内热门统计 |
| action_logs | user_id、created_at | 本人活动范围 |
| system_logs | timestamp倒序、_id倒序 | 最新系统日志 |
| system_logs | user_id、timestamp倒序 | 指定用户日志 |
| system_logs | log_type、log_level、timestamp倒序、_id倒序 | 类型等级和时间组合筛选 |

唯一索引约束同一集合内item_id的重复。跨库引用由应用读取SQL实际对象后确认。索引顺序服务相应查询条件，条件变化时根据执行计划再评估。

## 5. 三条聚合管道

评论统计按item_id匹配，再分组计算评论条数、平均分和评分分布，结果返回给商品评论统计页。

热门排行匹配近7天VIEW，按item_id分组累计浏览次数与停留秒数，再排序和取前若干项。后端根据结果编号读取MySQL商品名称。相同浏览次数时比较累计时长，再比较商品编号。

本人活动匹配会话用户编号和近7天VIEW，分组得到浏览次数、累计时长、去重商品、活跃日期与最近时间。活跃日期按Asia/Shanghai计算。界面展示的范围和统计口径随结果返回。

## 6. 一致性与导入重试

详情保存采用单文档字段更新。评论编辑和删除将作者编号与文档编号放进同一写条件。系统日志导入通过MySQL log_import_rows记录确认状态，MongoDB使用稳定_id与setOnInsert进行可重试写入。

原批次出现部分成功时，同一管理员重新导入原内容会复用批次和行号。Mongo确认后再更新SQL为SYNCED，仍待确认的行以PENDING呈现。已确认日志后来被人工修改的情况需要独立核查。

## 7. 初始化与演示数据

mongodb_init.js建立集合和基础索引，两个增量索引脚本补齐评论维护和日志筛选索引。CourseDataTool取得实际SQL编号后准备20份详情、40条模拟评论、101条浏览和101条IMPORT日志。source或操作标记识别模拟记录，文档时间为固定演示样本时间。

主库最近核对为详情22、评论42、浏览103、系统日志152。性能报告的60040条评论属于独立临时负载，其测量样本与主库数量分别记录。演示数据回执、重跑检查和实际BSON类型检查见测评证据目录。
