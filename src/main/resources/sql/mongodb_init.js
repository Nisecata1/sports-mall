// 项目011：MongoDB 阶段性初始化脚本，供 mongosh 执行，Java 不会自动执行。
// 当前范围：item_details 集合、一商品一详情的唯一索引、训练篮球的一条详情。
// 完整初始化仍须补齐另外三个集合、各集合测试数据与查询索引；本文件不表示全部完成。
// item_id=1 来自用户已确认的 MySQL 商品；MongoDB 不会自动检查跨库外键。
// 重复运行保留已有详情，不清库、不覆盖后续业务修改。
(() => {
    const mallDb = db.getSiblingDB("sports_mall");

    // 1. 创建集合。这一步之后，数据库与集合会实际存在。
    if (!mallDb.getCollectionNames().includes("item_details")) {
        mallDb.createCollection("item_details");
    }

    // 2. 工程规则：同一商品至多保存一份详情。不是指向 MySQL 的外键。
    mallDb.item_details.createIndex(
        { item_id: 1 },
        { name: "uq_item_details_item_id", unique: true }
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
})();
