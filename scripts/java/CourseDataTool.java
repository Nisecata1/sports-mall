import com.google.gson.GsonBuilder;
import com.mongodb.client.*;
import com.mongodb.client.model.UpdateOptions;
import com.sportsmall.dao.mongo.SystemLogDao;
import com.sportsmall.dao.mysql.*;
import com.sportsmall.model.AuthenticatedUser;
import com.sportsmall.service.*;
import com.sportsmall.util.PasswordHasher;
import com.zaxxer.hikari.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.Date;

/** 运维工具，不属于桌面前端。JDK源码运行模式复用后端JAR中的连接、哈希和导入实现。 */
public class CourseDataTool {
    static final String PREFIX="course_demo_v1_", MARKER="COURSE_DEMO_V1";
    static final Instant ANCHOR=Instant.parse("2026-11-08T12:00:00Z");
    static final String[] TABLES={"users","profiles","categories","items","orders","log_import_rows"};
    static final String[] COLLECTIONS={"item_details","comments","action_logs","system_logs"};
    static final Map<String,String> PRIMARY=Map.of("users","user_id","profiles","profile_id",
            "categories","category_id","items","item_id","orders","order_id");
    static final Map<String,String> COUNT_SQL=Map.of(
            "users","SELECT COUNT(*) FROM users", "profiles","SELECT COUNT(*) FROM profiles",
            "categories","SELECT COUNT(*) FROM categories", "items","SELECT COUNT(*) FROM items",
            "orders","SELECT COUNT(*) FROM orders", "log_import_rows","SELECT COUNT(*) FROM log_import_rows");
    static final Map<String,String> SNAPSHOT_SQL=Map.of(
            "users","SELECT * FROM users", "profiles","SELECT * FROM profiles",
            "categories","SELECT * FROM categories", "items","SELECT * FROM items",
            "orders","SELECT * FROM orders", "log_import_rows","SELECT * FROM log_import_rows");
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !Set.of("--apply-course","--isolated").contains(args[0]))
            throw new IllegalArgumentException("mode mysqlPort mongoPort report.json");
        int sqlPort=Integer.parseInt(args[1]), mongoPort=Integer.parseInt(args[2]);
        if(args[0].equals("--apply-course") && (sqlPort!=18306 || mongoPort!=27017))
            throw new IllegalArgumentException("Course mode only targets the configured loopback ports");
        if(args[0].equals("--isolated") && (sqlPort==18306 || mongoPort==27017))
            throw new IllegalArgumentException("Isolated mode rejects course ports");
        String password=System.getenv("MALL_DB_PASSWORD");
        if(args[0].equals("--apply-course") && (password==null || password.isEmpty()))
            throw new IllegalStateException("Enter the database password in the local launcher first");
        var config=new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:"+sqlPort+"/sports_mall?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
        config.setUsername("root"); config.setPassword(password==null?"":password);
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(5000);
        try(var pool=new HikariDataSource(config); var mongo=MongoClients.create("mongodb://127.0.0.1:"+mongoPort+"/?serverSelectionTimeoutMS=5000");
            var c=pool.getConnection()) {
            var db=mongo.getDatabase("sports_mall"); db.runCommand(new Document("ping",1));
            try(var lock=c.prepareStatement("SELECT GET_LOCK(?,5)")) {
                lock.setString(1,"sports_mall:course_demo_v1");
                try(var rows=lock.executeQuery()) { if(!rows.next() || rows.getInt(1)!=1) throw new IllegalStateException("Another data preparation is running"); }
            }
            try {
                var report=new LinkedHashMap<String,Object>();
                report.put("run_at",Instant.now().toString()); report.put("source",MARKER);
                report.put("mode",args[0]); report.put("mysql_port",sqlPort); report.put("mongo_port",mongoPort);
                report.put("before",counts(c,db));
                var oldSql=sqlFingerprints(c); var oldMongo=mongoFingerprints(db);
                AuthenticatedUser admin;
                try(var p=c.prepareStatement("SELECT user_id,username,role FROM users WHERE role='ADMIN' AND status=1 ORDER BY user_id LIMIT 1"); var r=p.executeQuery()) {
                    if(!r.next()) throw new IllegalStateException("An existing active administrator is required; this tool never grants ADMIN");
                    admin=new AuthenticatedUser(r.getLong(1),r.getString(2),r.getString(3));
                }
                long[] users=new long[10], categories=new long[10], items=new long[20], orders=new long[20];
                c.setAutoCommit(false);
                try {
                    for(int i=0;i<10;i++) {
                        String name=PREFIX+"u"+String.format("%02d",i+1);
                        users[i]=lookup(c,"SELECT user_id FROM users WHERE username=?",name);
                        if(users[i]==0) {
                            char[] random=UUID.randomUUID().toString().toCharArray();
                            String hash=PasswordHasher.hash(random); Arrays.fill(random,'\0');
                            users[i]=insert(c,"INSERT INTO users(username,password_hash,email,role,status) VALUES(?,?,?,'USER',1)",name,hash,name+"@example.invalid");
                        } else {
                            try(var p=c.prepareStatement("SELECT email,role FROM users WHERE user_id=?")) {
                                p.setLong(1,users[i]); try(var r=p.executeQuery()) { r.next();
                                    if(!r.getString(1).equals(name+"@example.invalid") || !r.getString(2).equals("USER"))
                                        throw new IllegalStateException("Reserved demo username collision"); }
                            }
                        }
                        if(lookup(c,"SELECT profile_id FROM profiles WHERE user_id=?",users[i])==0)
                            insert(c,"INSERT INTO profiles(user_id,real_name,address,notes) VALUES(?,?,?,?)",users[i],"课程演示用户"+(i+1),"虚构地址，仅用于课程演示",MARKER);
                        categories[i]=lookup(c,"SELECT category_id FROM categories WHERE name=?","【课程演示】分类"+(i+1));
                        if(categories[i]==0) categories[i]=insert(c,"INSERT INTO categories(name,parent_id) VALUES(?,?)","【课程演示】分类"+(i+1),i==0?null:categories[0]);
                    }
                    for(int i=0;i<20;i++) {
                        String title="【课程演示】运动装备"+String.format("%02d",i+1);
                        items[i]=lookup(c,"SELECT item_id FROM items WHERE title=?",title);
                        BigDecimal price=BigDecimal.valueOf(2000L+i*750L,2);
                        if(items[i]==0) items[i]=insert(c,"INSERT INTO items(title,category_id,price,status) VALUES(?,?,?,1)",title,categories[i%10],price);
                        orders[i]=lookup(c,"SELECT order_id FROM orders WHERE user_id=? AND item_id=?",users[i%10],items[i]);
                        if(orders[i]==0) {
                            // 从当前商品行取价，遵守现有插入触发器；随后执行合法状态转换。
                            orders[i]=insert(c,"INSERT INTO orders(user_id,item_id,amount,status) SELECT ?,item_id,price,0 FROM items WHERE item_id=?",users[i%10],items[i]);
                            if(i%3!=0) try(var p=c.prepareStatement("UPDATE orders SET status=? WHERE order_id=? AND status=0")) {
                                p.setInt(1,i%3); p.setLong(2,orders[i]); p.executeUpdate();
                            }
                        }
                    }
                    c.commit();
                } catch(Exception failure) { c.rollback(); throw failure; }
                finally { c.setAutoCommit(true); }
                // SQL已提交，MongoDB独立写入。稳定ObjectId及setOnInsert使中断后重跑保留已有内容。
                for(int i=0;i<20;i++) upsert(db,"item_details","detail:"+i,new Document("item_id",items[i])
                        .append("description","课程模拟商品说明，非真实商品。") .append("images",List.of())
                        .append("metadata",new Document("language","zh-CN").append("source",MARKER)));
                for(int i=0;i<40;i++) upsert(db,"comments","comment:"+i,new Document("user_id",users[i%10]).append("item_id",items[i%20])
                        .append("content","【课程演示】模拟评价"+(i+1)+"，不代表真实购买体验。")
                        .append("rating",i%5+1).append("tags",List.of("课程演示","运动装备"))
                        .append("created_at",Date.from(ANCHOR.minusSeconds(i*1800L))));
                for(int i=0;i<101;i++) upsert(db,"action_logs","view:"+i,new Document("user_id",users[i%10]).append("item_id",items[i%20])
                        .append("action_type","VIEW").append("duration_seconds",(long)(5+i%60))
                        .append("client_info",new Document("ip","127.0.0.1").append("client_type","COURSE_DEMO").append("device","synthetic"))
                        .append("created_at",Date.from(ANCHOR.minusSeconds(i*3600L))));
                // 不伪造SYNCED标记；通过已实现的导入Service完成JDBC批处理与Mongo同步。
                StringBuilder lines=new StringBuilder();
                for(int i=0;i<101;i++) lines.append(new Document("user_id",users[i%10]).append("timestamp",ANCHOR.minusSeconds(i*60L).toString())
                        .append("level","INFO").append("message","【课程演示】合成日志"+(i+1))
                        .append("operation",MARKER).toJson()).append('\n');
                // 普通JSON数字，避免Document.toJson把Long编码成Extended JSON对象。
                String text=lines.toString().replaceAll("\\{\\s*\\\"\\$numberLong\\\"\\s*:\\s*\\\"(\\d+)\\\"\\s*}","$1");
                var importer=new AdminLogService(new UserAccountService(new UserDao(pool)),new SystemLogDao(db),new LogImportDao(pool));
                var receipt=importer.importFile(admin,text);
                if(receipt.pending()!=0 || receipt.synced()!=101) throw new IllegalStateException("Log import pending; rerun the same tool to resume");
                report.put("log_import",receipt); report.put("demo_users",users); report.put("demo_items",items); report.put("demo_orders",orders);
                List<String> indexes=new ArrayList<>();
                ensureIndex(c,"items","idx_items_sale_category_price","status,category_id,price,item_id",
                        "CREATE INDEX idx_items_sale_category_price ON items(status,category_id,price,item_id)",indexes);
                ensureIndex(c,"orders","idx_orders_user_status_created","user_id,status,created_at,order_id",
                        "CREATE INDEX idx_orders_user_status_created ON orders(user_id,status,created_at,order_id)",indexes);
                report.put("index_actions",indexes);
                report.put("after",counts(c,db));
                boolean preserved=preserved(oldSql,sqlFingerprints(c)) && preserved(oldMongo,mongoFingerprints(db));
                report.put("existing_records_unchanged",preserved);
                report.put("quotas_passed",quotas(c,db));
                Path output=Path.of(args[3]).toAbsolutePath(); Files.createDirectories(output.getParent());
                Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);
                if(!preserved || !quotas(c,db)) throw new IllegalStateException("Data verification failed; inspect report without deleting data");
                System.out.println("COURSE_DATA_VERIFIED="+output);
            } finally { try(var p=c.prepareStatement("SELECT RELEASE_LOCK(?)")) {p.setString(1,"sports_mall:course_demo_v1");p.execute();} }
        }
    }
    static long lookup(Connection c,String sql,Object... values) throws Exception {
        try(var p=c.prepareStatement(sql)) { bind(p,values); try(var r=p.executeQuery()) {
            if(!r.next())return 0; long id=r.getLong(1); if(r.next())throw new IllegalStateException("Ambiguous demo natural key");return id;
        }}
    }
    static long insert(Connection c,String sql,Object... values) throws Exception {
        try(var p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            bind(p,values); if(p.executeUpdate()!=1)throw new SQLException("Insert did not affect exactly one row");
            try(var r=p.getGeneratedKeys()){if(!r.next())throw new SQLException("Missing generated key"); return r.getLong(1);}
        }
    }
    static void bind(PreparedStatement p,Object... values) throws SQLException {for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);}
    static ObjectId id(String key) throws Exception {return new ObjectId(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest((MARKER+":"+key).getBytes(StandardCharsets.UTF_8)),12));}
    static void upsert(MongoDatabase db,String collection,String key,Document doc) throws Exception {
        doc.append("source",MARKER); var id=id(key);
        db.getCollection(collection).updateOne(new Document("_id",id),new Document("$setOnInsert",doc),new UpdateOptions().upsert(true));
    }
    static Map<String,Long> counts(Connection c,MongoDatabase db) throws Exception {
        Map<String,Long> result=new LinkedHashMap<>();
        for(String table:TABLES)try(var p=c.prepareStatement(COUNT_SQL.get(table));var r=p.executeQuery()){r.next();result.put("mysql."+table,r.getLong(1));}
        for(String col:COLLECTIONS)result.put("mongo."+col,db.getCollection(col).countDocuments()); return result;
    }
    static boolean quotas(Connection c,MongoDatabase db) throws Exception {
        var counts=counts(c,db);return counts.entrySet().stream().allMatch(e->e.getValue()>=(e.getKey().startsWith("mysql.")?10:e.getKey().endsWith("logs")?101:20));
    }
    static String hash(String text) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    static Map<String,String> sqlFingerprints(Connection c) throws Exception {
        Map<String,String> result=new TreeMap<>();
        for(String table:TABLES)try(var p=c.prepareStatement(SNAPSHOT_SQL.get(table));var r=p.executeQuery()) {
            int columns=r.getMetaData().getColumnCount();
            while(r.next()) {String key=table+":"+(table.equals("log_import_rows")?r.getString("batch_id")+":"+r.getInt("row_no"):r.getString(PRIMARY.get(table)));
                List<String> fields=new ArrayList<>();for(int i=1;i<=columns;i++)fields.add(r.getString(i));
                result.put(key,hash(new GsonBuilder().serializeNulls().create().toJson(fields)));}
        } return result;
    }
    static Map<String,String> mongoFingerprints(MongoDatabase db) throws Exception {
        Map<String,String> result=new TreeMap<>();for(String col:COLLECTIONS)for(Document doc:db.getCollection(col).find())result.put(col+":"+doc.get("_id"),hash(doc.toJson()));return result;
    }
    static boolean preserved(Map<String,String> old,Map<String,String> current) {return old.entrySet().stream().allMatch(e->Objects.equals(e.getValue(),current.get(e.getKey())));}
    static void ensureIndex(Connection c,String table,String name,String columns,String ddl,List<String> actions)throws Exception {
        try(var p=c.prepareStatement("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX),MIN(NON_UNIQUE) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?")) {
            p.setString(1,table);p.setString(2,name);try(var r=p.executeQuery()){r.next();String actual=r.getString(1);
                if(actual!=null){if(!actual.equals(columns)||r.getInt(2)!=1)throw new IllegalStateException("Index definition mismatch: "+name);actions.add(name+": already present");return;}
            }
        }
        // DDL来自两个固定SQL模板，元数据查询中的表名、索引名使用参数绑定。
        try(var p=c.prepareStatement(ddl)){p.execute();}
        actions.add(name+": created");
    }
}
