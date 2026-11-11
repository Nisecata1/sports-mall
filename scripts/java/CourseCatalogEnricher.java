import com.google.gson.GsonBuilder;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Append-only course catalog. Uses stable natural keys and document IDs for resumption. */
public class CourseCatalogEnricher {
    static final String SOURCE = "COURSE_CATALOG_V2";
    static final String PREFIX = "【商城演示】";
    static final Instant ANCHOR = Instant.parse("2026-11-10T04:00:00Z");
    static final String[] CATEGORIES = {"篮球装备", "羽毛球装备", "足球装备", "跑步装备",
            "力量训练", "瑜伽拉伸", "游泳装备", "户外徒步"};
    record Product(String name, int category, String price, String specification, String use) {}
    static final List<Product> PRODUCTS = List.of(
            new Product("7号室内训练篮球", 0, "129.00", "7号球；PU表面；室内木地板适用", "运球、传接球和投篮训练"),
            new Product("7号室外耐磨篮球", 0, "69.00", "7号球；橡胶表面；室外场地适用", "校园室外球场日常练习"),
            new Product("篮球训练背心", 0, "39.90", "聚酯纤维；M/L/XL可选；网眼透气", "分组训练和日常运动"),
            new Product("便携篮球收纳包", 0, "29.90", "单球容量；可调肩带；侧袋收纳", "训练往返时携带篮球及小物件"),
            new Product("入门轻量羽毛球拍", 1, "159.00", "4U级；碳纤维复合材料；已穿训练线", "初学者基础挥拍和对打练习"),
            new Product("耐打训练羽毛球12只装", 1, "59.00", "12只/筒；训练用混合羽毛；标准球形", "日常多球练习和业余对打"),
            new Product("羽毛球吸汗手胶3条装", 1, "19.90", "3条/包；PU材质；带封口胶", "更换球拍握把的吸汗外层"),
            new Product("双拍位羽毛球拍包", 1, "89.00", "双拍位；独立配件袋；手提/单肩携带", "收纳球拍、球筒和训练用品"),
            new Product("5号标准训练足球", 2, "99.00", "5号球；机缝PU表面；训练用", "校园足球和基础传射练习"),
            new Product("青少年4号足球", 2, "79.00", "4号球；轻量设计；耐磨表面", "青少年控球及传接球练习"),
            new Product("足球护胫板", 2, "35.00", "成人中码；硬质外壳；缓冲内衬", "足球运动时的胫部防护"),
            new Product("足球敏捷训练标志碟10只装", 2, "24.90", "10只/套；多色；柔韧PE材质", "布置绕桩、变向和敏捷训练路线"),
            new Product("日常缓震跑鞋", 3, "239.00", "合成织物鞋面；EVA中底；39至44码", "日常慢跑和城市健走"),
            new Product("速干跑步短袖", 3, "59.00", "聚酯纤维；S至XXL码；轻量速干", "公路慢跑及日常有氧训练"),
            new Product("贴身跑步腰包", 3, "45.00", "可调腰围；拉链主袋；反光细节", "跑步时收纳钥匙、手机等小物件"),
            new Product("运动中筒袜3双装", 3, "29.90", "3双/包；棉混纺；足底加厚", "跑步、健身和日常运动穿着"),
            new Product("2公斤包胶哑铃一对", 4, "89.00", "2kg×2；包胶表面；防滚六角形", "初级力量和肩臂训练"),
            new Product("5公斤包胶哑铃一对", 4, "169.00", "5kg×2；包胶表面；金属握柄", "基础力量与居家抗阻训练"),
            new Product("三档阻力弹力带", 4, "49.00", "轻/中/重三档；乳胶材质；收纳袋", "辅助热身和不同强度的抗阻练习"),
            new Product("可调节计数跳绳", 4, "39.00", "绳长可调；机械计数；防滑握把", "心肺与协调性训练"),
            new Product("6毫米TPE瑜伽垫", 5, "89.00", "183×61×0.6cm；TPE材质；双面纹理", "日常瑜伽和地面拉伸"),
            new Product("8毫米加厚训练垫", 5, "119.00", "183×80×0.8cm；加宽；含绑带", "居家地面训练和伸展练习"),
            new Product("EVA瑜伽砖两块装", 5, "35.00", "两块/套；EVA泡沫；圆角边缘", "辅助支撑和伸展动作"),
            new Product("中硬度泡沫轴", 5, "69.00", "长度33cm；中硬度；纹理表面", "训练后的肌肉放松和日常滚压"),
            new Product("防雾训练泳镜", 6, "69.00", "可调镜带；硅胶密封圈；透明镜片", "泳池日常训练"),
            new Product("硅胶游泳帽", 6, "25.00", "硅胶材质；成人通用；弹性帽身", "泳池训练时收拢头发"),
            new Product("游泳训练浮板", 6, "45.00", "EVA材质；成人训练尺寸；握孔设计", "泳池打腿和基础动作练习"),
            new Product("速干运动毛巾", 6, "39.90", "80×130cm；超细纤维；含收纳袋", "游泳和健身后的擦拭与收纳"),
            new Product("20升轻量徒步背包", 7, "159.00", "20L容量；胸带；双侧水壶袋", "城市近郊和单日轻装徒步"),
            new Product("三节伸缩登山杖", 7, "89.00", "铝合金；三节伸缩；可调腕带", "徒步时辅助保持节奏和平衡"),
            new Product("750毫升运动水壶", 7, "49.00", "750ml；Tritan材质；提环瓶盖", "日常运动及徒步补水"),
            new Product("轻量户外遮阳帽", 7, "59.00", "可调帽围；透气网片；宽檐设计", "户外步行和日常遮阳")
    );

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("--apply-course", "--isolated").contains(args[0])) {
            throw new IllegalArgumentException("mode mysqlPort mongoPort backupDirectory");
        }
        int sqlPort = Integer.parseInt(args[1]);
        int mongoPort = Integer.parseInt(args[2]);
        boolean course = args[0].equals("--apply-course");
        if (course != (sqlPort == 18306 && mongoPort == 27017)
                || (!course && (sqlPort == 18306 || mongoPort == 27017))) {
            throw new IllegalArgumentException("Database ports do not match the selected mode");
        }
        String password = System.getenv("MALL_DB_PASSWORD");
        if (course && (password == null || password.isEmpty())) {
            throw new IllegalStateException("Enter the MySQL password in the local launcher");
        }
        Path directory = Path.of(args[3]).toAbsolutePath();
        Files.createDirectories(directory);
        try (Connection sql = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + sqlPort
                + "/sports_mall?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=UTF-8",
                "root", password == null ? "" : password);
             var mongo = MongoClients.create("mongodb://127.0.0.1:" + mongoPort
                     + "/?directConnection=true&serverSelectionTimeoutMS=5000")) {
            MongoDatabase db = mongo.getDatabase("sports_mall");
            db.runCommand(new Document("ping", 1));
            try (var lock = sql.prepareStatement("SELECT GET_LOCK(?,5)")) {
                lock.setString(1, "sports_mall:course_demo_v1");
                try (var rows = lock.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException("Data preparation is busy");
                }
            }
            try {
                enrich(sql, db, directory);
            } finally {
                try (var release = sql.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, "sports_mall:course_demo_v1");
                    release.execute();
                }
            }
        }
    }

    static void enrich(Connection sql, MongoDatabase db, Path directory) throws Exception {
        Path backup = directory.resolve("before.json");
        if (Files.exists(backup)) throw new IllegalStateException("Use a new backup directory for each execution");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", SOURCE);
        report.put("run_at", Instant.now().toString());
        report.put("before", CourseDataTool.counts(sql, db));
        Map<String, String> oldSql = CourseDataTool.sqlFingerprints(sql);
        Map<String, String> oldMongo = CourseDataTool.mongoFingerprints(db);
        backup(sql, db, backup);
        report.put("backup", backup.toString());
        report.put("stage", "BACKED_UP");
        write(directory.resolve("receipt.json"), report);

        long[] users = new long[10];
        long[] categories = new long[CATEGORIES.length];
        long[] items = new long[PRODUCTS.size()];
        List<Long> orders = new ArrayList<>();
        sql.setAutoCommit(false);
        try {
            for (int i = 0; i < users.length; i++) {
                String username = CourseDataTool.PREFIX + "u" + String.format("%02d", i + 1);
                users[i] = CourseDataTool.lookup(sql,
                        "SELECT user_id FROM users WHERE username=? AND email=? AND role='USER' AND status=1",
                        username, username + "@example.invalid");
                if (users[i] == 0) throw new IllegalStateException("Existing active course demo accounts are required");
            }
            long root = category(sql, PREFIX + "运动装备", null);
            for (int i = 0; i < categories.length; i++) {
                categories[i] = category(sql, PREFIX + CATEGORIES[i], root);
            }
            for (int i = 0; i < PRODUCTS.size(); i++) {
                Product product = PRODUCTS.get(i);
                BigDecimal price = new BigDecimal(product.price());
                items[i] = CourseDataTool.lookup(sql, "SELECT item_id FROM items WHERE title=?", PREFIX + product.name());
                if (items[i] == 0) {
                    items[i] = CourseDataTool.insert(sql,
                            "INSERT INTO items(title,category_id,price,status) VALUES(?,?,?,1)",
                            PREFIX + product.name(), categories[product.category()], price);
                }
                for (int month = 9; month <= 11; month++) {
                    int day = 1 + i % (month == 11 ? 9 : 26);
                    Timestamp created = Timestamp.valueOf(LocalDate.of(2026, month, day).atTime(10 + i % 7, i % 60));
                    long user = users[(i + month) % users.length];
                    long order = CourseDataTool.lookup(sql,
                            "SELECT order_id FROM orders WHERE user_id=? AND item_id=? AND created_at=?", user, items[i], created);
                    if (order == 0) {
                        order = CourseDataTool.insert(sql,
                                "INSERT INTO orders(user_id,item_id,amount,status,created_at) SELECT ?,item_id,price,0,? FROM items WHERE item_id=?",
                                user, created, items[i]);
                        int status = (i + month) % 3;
                        if (status != 0) {
                            try (var change = sql.prepareStatement("UPDATE orders SET status=? WHERE order_id=? AND status=0")) {
                                change.setInt(1, status);
                                change.setLong(2, order);
                                if (change.executeUpdate() != 1) throw new IllegalStateException("Order transition failed");
                            }
                        }
                    }
                    orders.add(order);
                }
            }
            sql.commit();
        } catch (Exception failure) {
            sql.rollback();
            throw failure;
        } finally {
            sql.setAutoCommit(true);
        }
        report.put("stage", "SQL_COMMITTED");
        report.put("item_ids", items);
        report.put("order_ids", orders);
        write(directory.resolve("receipt.json"), report);

        String[] reviews = {"尺寸和用途说明清楚，适合日常练习。", "收纳方便，训练时使用比较顺手。",
                "基础功能够用，希望后续有更多配色。", "已经用于几次常规练习，整体符合预期。"};
        int views = 0;
        for (int i = 0; i < PRODUCTS.size(); i++) {
            Product product = PRODUCTS.get(i);
            String description = product.name() + "\n参考单价 " + product.price() + "元\n规格 "
                    + product.specification() + "\n适用场景 " + product.use()
                    + "\n使用提示 按个人尺码和训练强度选择，使用后清洁晾干并检查磨损。"
                    + "\n课程演示说明 商品参数、售价、订单及评价均为合成样本。";
            put(db, "item_details", "detail-" + i, new Document("item_id", items[i])
                    .append("description", description).append("images", List.of())
                    .append("metadata", new Document("language", "zh-CN").append("source", SOURCE)
                            .append("category", CATEGORIES[product.category()]).append("specification", product.specification())));
            for (int j = 0; j < 4; j++) {
                int rating = j == 2 ? 2 + i % 3 : 4 + (i + j) % 2;
                put(db, "comments", "comment-" + i + "-" + j,
                        new Document("user_id", users[(i + j) % users.length]).append("item_id", items[i])
                                .append("content", "【商城演示·模拟评价】" + product.name() + "：" + reviews[j])
                                .append("rating", rating).append("tags", List.of(CATEGORIES[product.category()], "模拟评价"))
                                .append("created_at", Date.from(ANCHOR.minusSeconds(i * 2700L + j * 21600L))));
            }
            int count = 3 + i * 7 % 10;
            for (int j = 0; j < count; j++) {
                put(db, "action_logs", "view-" + i + "-" + j,
                        new Document("user_id", users[(i + j) % users.length]).append("item_id", items[i])
                                .append("action_type", "VIEW").append("duration_seconds", 15L + (i * 13L + j * 17L) % 180L)
                                .append("client_info", new Document("client_type", "COURSE_DEMO").append("ip", "127.0.0.1"))
                                .append("created_at", Date.from(ANCHOR.minusSeconds(i * 1800L + j * 10800L))));
                views++;
            }
        }
        report.put("catalog_products", PRODUCTS.size());
        report.put("catalog_comments", PRODUCTS.size() * 4);
        report.put("catalog_views", views);
        report.put("after", CourseDataTool.counts(sql, db));
        boolean preserved = CourseDataTool.preserved(oldSql, CourseDataTool.sqlFingerprints(sql))
                && CourseDataTool.preserved(oldMongo, CourseDataTool.mongoFingerprints(db));
        report.put("existing_records_unchanged", preserved);
        report.put("stage", preserved ? "VERIFIED" : "VERIFY_FAILED");
        write(directory.resolve("receipt.json"), report);
        if (!preserved) throw new IllegalStateException("Existing records changed; inspect the backup and receipt");
        System.out.println("CATALOG_VERIFIED=" + directory.resolve("receipt.json"));
    }

    static long category(Connection sql, String name, Long parent) throws Exception {
        long id = CourseDataTool.lookup(sql, "SELECT category_id FROM categories WHERE name=?", name);
        return id == 0 ? CourseDataTool.insert(sql, "INSERT INTO categories(name,parent_id) VALUES(?,?)", name, parent) : id;
    }

    static void put(MongoDatabase db, String collection, String key, Document data) throws Exception {
        ObjectId id = new ObjectId(CourseDataTool.hash(SOURCE + ":" + key).substring(0, 24));
        Document filter = new Document("_id", id).append("source", SOURCE);
        db.getCollection(collection).updateOne(filter, new Document("$setOnInsert", data.append("source", SOURCE)),
                new UpdateOptions().upsert(true));
    }

    static void backup(Connection sql, MongoDatabase db, Path path) throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> tables = new LinkedHashMap<>();
        for (String table : CourseDataTool.TABLES) {
            List<Map<String, String>> records = new ArrayList<>();
            try (var query = sql.prepareStatement(CourseDataTool.SNAPSHOT_SQL.get(table)); var rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String, String> record = new LinkedHashMap<>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                        record.put(rows.getMetaData().getColumnLabel(i), rows.getString(i));
                    }
                    records.add(record);
                }
            }
            tables.put(table, records);
        }
        Map<String, Object> documents = new LinkedHashMap<>();
        for (String collection : CourseDataTool.COLLECTIONS) {
            List<String> records = new ArrayList<>();
            for (Document document : db.getCollection(collection).find()) records.add(document.toJson());
            documents.put(collection, records);
        }
        snapshot.put("mysql_rows", tables);
        snapshot.put("mongodb_extended_json", documents);
        write(path, snapshot);
    }

    static void write(Path path, Object value) throws Exception {
        Files.writeString(path, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(value), StandardCharsets.UTF_8);
    }
}
