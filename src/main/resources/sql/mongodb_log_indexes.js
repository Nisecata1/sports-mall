// 0.3.1增量：类型/级别和日期组合查询。仅创建索引。
db.getSiblingDB("sports_mall").system_logs.createIndex(
    { log_type: 1, log_level: 1, timestamp: -1, _id: -1 },
    { name: "idx_system_type_level_time" }
);
