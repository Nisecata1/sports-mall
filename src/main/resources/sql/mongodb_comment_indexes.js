// 0.3.0已有课程库的增量：只增加两个评论查询索引，不创建或改写业务文档。
// 用mongosh --file执行；不依赖交互式use命令，也不要求重跑包含演示详情的初始化脚本。
db.getSiblingDB("sports_mall").comments.createIndex(
    { user_id: 1, created_at: -1, _id: -1 },
    { name: "idx_comments_user_time" }
);
db.getSiblingDB("sports_mall").comments.createIndex(
    { created_at: -1, _id: -1 },
    { name: "idx_comments_time" }
);
