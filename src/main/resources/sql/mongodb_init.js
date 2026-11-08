// 项目011：MongoDB 阶段性初始化脚本，供 mongosh 执行，Java 不会自动执行。
// 当前范围：四个业务集合、首批查询索引、训练篮球的一条详情。
// comments、action_logs、system_logs 本轮只建集合与索引，暂不填充用户相关记录。
// 完整初始化仍须补齐各集合测试数据；本文件不表示数据量、聚合或业务功能全部完成。
// item_id=1 来自用户已确认的 MySQL 商品；MongoDB 不会自动检查跨库外键。
// 重复运行保留已有详情，不清库、不覆盖后续业务修改。
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

    // 3. 仅在这件商品还没有详情时插入；Long 与 MySQL BIGINT 对应。
    // 没有实际图片时保留空数组，不填写无法访问的示例图片地址。
    const result = mallDb.item_details.updateOne(
        { item_id: Long("1") },
        {
            $setOnInsert: {
                description: "训练用篮球，适合日常运球与投篮练习。",
                images: [],
                metadata: { language: "zh-CN" }
            }
        },
        { upsert: true }
    );

    // 4. 显示实际写入结果与刚保存的文档，不使用固定的“成功”输出替代结果。
    print("初始化写入结果：");
    printjson(result);
    print("训练篮球的实际详情：");
    printjson(mallDb.item_details.findOne({ item_id: Long("1") }));
    print("当前集合：");
    printjson(mallDb.getCollectionNames());
    print("各业务集合的实际文档数（空集合允许为0，不能据此声称测试数据量达标）：");
    printjson(Object.fromEntries(collectionNames.map(name => [name, mallDb.getCollection(name).countDocuments({})])));
})();
