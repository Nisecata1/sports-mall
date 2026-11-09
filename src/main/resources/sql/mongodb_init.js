// 项目011 MongoDB结构初始化，建立四个集合及查询索引。
// 商品关联数据由CourseDataTool或业务界面使用实际MySQL编号写入。
(() => {
    const mallDb = db.getSiblingDB("sports_mall");

    // 1. 创建尚缺的集合。已有集合保持不变，不清空数据或改写既有校验规则。
    const collectionNames = ["item_details", "comments", "action_logs", "system_logs"];
    const existingNames = new Set(mallDb.getCollectionNames());
    for (const name of collectionNames) {
        if (!existingNames.has(name)) {
            mallDb.createCollection(name);
        }
    }

    // 2. 工程规则：同一商品至多保存一份详情。不是指向 MySQL 的外键。
    mallDb.item_details.createIndex(
        { item_id: 1 },
        { name: "uq_item_details_item_id", unique: true }
    );

    // 一个商品可有多条评论；按商品筛选，再按创建时间及 _id 稳定排序。
    mallDb.comments.createIndex(
        { item_id: 1, created_at: -1, _id: -1 },
        { name: "idx_comments_item_time" }
    );
    mallDb.comments.createIndex(
        { user_id: 1, created_at: -1, _id: -1 },
        { name: "idx_comments_user_time" }
    );
    mallDb.comments.createIndex(
        { created_at: -1, _id: -1 },
        { name: "idx_comments_time" }
    );

    // 行为日志：时间窗口内统计热度；按某用户及时间查询活动。
    mallDb.action_logs.createIndex(
        { created_at: 1, item_id: 1 },
        { name: "idx_actions_time_item" }
    );
    mallDb.action_logs.createIndex(
        { user_id: 1, created_at: 1 },
        { name: "idx_actions_user_time" }
    );

    // 系统日志：最新日志稳定分页；读取某用户的登录和业务操作记录。
    mallDb.system_logs.createIndex(
        { timestamp: -1, _id: -1 },
        { name: "idx_system_time" }
    );
    mallDb.system_logs.createIndex(
        { user_id: 1, timestamp: -1 },
        { name: "idx_system_user_time" }
    );

    print("当前集合：");
    printjson(mallDb.getCollectionNames());
    print("各业务集合的实际文档数（空集合允许为0，不能据此声称测试数据量达标）：");
    printjson(Object.fromEntries(collectionNames.map(name => [name, mallDb.getCollection(name).countDocuments({})])));
})();
