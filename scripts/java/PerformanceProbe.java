import com.google.gson.GsonBuilder;
import com.mongodb.client.*;
import org.bson.Document;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Date;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** 只允许在运行器创建并核对物理路径的临时实例运行。所有数据均为合成负载。 */
public class PerformanceProbe {
    static final int REPEATS=21;
    public static void main(String[] args) throws Exception {
        int sqlPort=Integer.parseInt(args[0]), mongoPort=Integer.parseInt(args[2]);
        if(sqlPort==18306 || mongoPort==27017)throw new IllegalArgumentException("Course ports are forbidden");
        try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+sqlPort+"/sports_mall?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&rewriteBatchedStatements=true","root","");
            var mongo=MongoClients.create("mongodb://127.0.0.1:"+mongoPort)) {
            try(var p=c.prepareStatement("SELECT @@datadir");var r=p.executeQuery()) {
                r.next(); if(!Path.of(r.getString(1)).toRealPath().equals(Path.of(args[1]).toRealPath())) throw new IllegalStateException("Unexpected MySQL directory");
            }
            var db=mongo.getDatabase("sports_mall");
            var opts=mongo.getDatabase("admin").runCommand(new Document("getCmdLineOpts",1));
            String path=opts.get("parsed",Document.class).get("storage",Document.class).getString("dbPath");
            if(!Path.of(path).toRealPath().equals(Path.of(args[3]).toRealPath()))throw new IllegalStateException("Unexpected MongoDB directory");
            fixture(c,db);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("run_at",Instant.now().toString());report.put("java",System.getProperty("java.version"));
            report.put("mysql",c.getMetaData().getDatabaseProductVersion());report.put("mongodb",db.runCommand(new Document("buildInfo",1)).getString("version"));
            report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
            report.put("processors",Runtime.getRuntime().availableProcessors());
            report.put("fixture",Map.of("extra_items",30000,"extra_orders",60000,"extra_comments",60000,"users",6000,"categories",60));
            report.put("method",Map.of("warmup_per_phase",3,"samples_per_phase",REPEATS,"timing","client elapsed, consume all results, no connection establishment","cache","warm local cache; not a cold-cache or load test"));
            List<Map<String,Object>> sql=new ArrayList<>();
            String itemQuery=constant("com.sportsmall.dao.mysql.ItemSearchDao","PAGE");
            sql.add(compareSql(c,"商品按分类和价格分页",itemQuery,p->{
                p.setString(1,null);p.setString(2,null);p.setLong(3,10017);p.setLong(4,10017);
                p.setBigDecimal(5,new BigDecimal("25"));p.setBigDecimal(6,new BigDecimal("25"));
                p.setBigDecimal(7,new BigDecimal("85"));p.setBigDecimal(8,new BigDecimal("85"));
                p.setString(9,"price_asc");p.setString(10,"price_asc");p.setInt(11,20);p.setLong(12,0);
            },"CREATE INDEX idx_items_sale_category_price ON items(status,category_id,price,item_id)"));
            String orderQuery=constant("com.sportsmall.dao.mysql.OrderDao","FIND_MINE");
            sql.add(compareSql(c,"本人订单按状态和日期筛选",orderQuery,p->{
                p.setLong(1,10017);p.setInt(2,1);p.setInt(3,1);
                p.setTimestamp(4,Timestamp.valueOf("2026-09-01 00:00:00"));p.setTimestamp(5,Timestamp.valueOf("2026-09-01 00:00:00"));
                p.setTimestamp(6,Timestamp.valueOf("2026-09-08 00:00:00"));p.setTimestamp(7,Timestamp.valueOf("2026-09-08 00:00:00"));
            },"CREATE INDEX idx_orders_user_status_created ON orders(user_id,status,created_at,order_id)"));
            report.put("mysql_cases",sql);
            var filter=new Document("user_id",10017L);var sort=new Document("created_at",-1).append("_id",-1);
            var collection=db.getCollection("comments");
            var natural=new Document("$natural",1);String index="idx_comments_user_time";
            List<Double> scan=measure(()->mongoRows(collection,filter,sort,natural,null));
            List<Double> indexed=measure(()->mongoRows(collection,filter,sort,null,index));
            var before=mongoRows(collection,filter,sort,natural,null);var after=mongoRows(collection,filter,sort,null,index);
            if(!before.equals(after))throw new IllegalStateException("MongoDB result mismatch");
            Map<String,Object> m=new LinkedHashMap<>(); m.put("case","本人最新评论，最多20条");m.put("filter",filter);m.put("sort",sort);
            m.put("comparison","same existing index versus forced collection scan; not a newly added MongoDB index");
            m.put("scan",summary(scan));m.put("indexed",summary(indexed));m.put("results_equal",true);m.put("returned",before.size());
            m.put("result_sha256",hash(before.toString()));
            Document find=new Document("find","comments").append("filter",filter).append("sort",sort).append("limit",20);
            m.put("scan_explain",db.runCommand(new Document("explain",new Document(find).append("hint",natural)).append("verbosity","executionStats")));
            m.put("indexed_explain",db.runCommand(new Document("explain",new Document(find).append("hint",index)).append("verbosity","executionStats")));
            report.put("mongo_case",m);
            Files.writeString(Path.of(args[4]),new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);
            System.out.println("PERFORMANCE_RESULT="+Path.of(args[4]).toAbsolutePath());
        }
    }
    interface Binder {void bind(PreparedStatement p)throws Exception;}
    interface Work {Object run()throws Exception;}
    static String constant(String type,String name) throws Exception {var f=Class.forName(type).getDeclaredField(name);f.setAccessible(true);return(String)f.get(null);}
    static Map<String,Object> compareSql(Connection c,String name,String sql,Binder bind,String ddl)throws Exception {
        List<String> before=rows(c,sql,bind);var baseline=measure(()->rows(c,sql,bind));String planBefore=explain(c,sql,bind);
        try(var p=c.prepareStatement(ddl)){p.execute();}
        try(var p=c.prepareStatement("ANALYZE TABLE items,orders")){p.execute();}
        List<String> after=rows(c,sql,bind);var optimized=measure(()->rows(c,sql,bind));String planAfter=explain(c,sql,bind);
        if(!before.equals(after))throw new IllegalStateException("SQL result mismatch: "+name);
        Map<String,Object> result=new LinkedHashMap<>();result.put("case",name);result.put("sql",sql);result.put("ddl",ddl);
        result.put("before",summary(baseline));result.put("after",summary(optimized));result.put("results_equal",true);
        result.put("returned",before.size());result.put("result_sha256",hash(before.toString()));
        result.put("explain_before",planBefore);result.put("explain_after",planAfter);return result;
    }
    static List<String> rows(Connection c,String sql,Binder bind)throws Exception {
        try(var p=c.prepareStatement(sql)){bind.bind(p);try(var r=p.executeQuery()){
            List<String> result=new ArrayList<>();int n=r.getMetaData().getColumnCount();while(r.next()){
                List<String> fields=new ArrayList<>();for(int i=1;i<=n;i++)fields.add(r.getString(i));result.add(fields.toString());}return result;
        }}
    }
    static String explain(Connection c,String sql,Binder bind)throws Exception {return String.join("\n",rows(c,"EXPLAIN ANALYZE "+sql,bind));}
    static List<String> mongoRows(MongoCollection<Document> col,Document filter,Document sort,Document natural,String index) {
        var cursor=col.find(filter).sort(sort).limit(20);if(natural!=null)cursor.hint(natural);else cursor.hintString(index);
        List<String> result=new ArrayList<>();try(var rows=cursor.iterator()){while(rows.hasNext())result.add(rows.next().toJson());}return result;
    }
    static List<Double> measure(Work work)throws Exception {
        for(int i=0;i<3;i++)work.run();List<Double> result=new ArrayList<>();
        for(int i=0;i<REPEATS;i++){long t=System.nanoTime();work.run();result.add((System.nanoTime()-t)/1_000_000.0);}return result;
    }
    static Map<String,Object> summary(List<Double> raw) {
        List<Double> sorted=new ArrayList<>(raw);Collections.sort(sorted);
        return Map.of("samples_ms",raw,"median_ms",sorted.get(sorted.size()/2),"p95_ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1));
    }
    static String hash(String s)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    static void fixture(Connection c,MongoDatabase db)throws Exception {
        c.setAutoCommit(false);
        try {
            try(var u=c.prepareStatement("INSERT INTO users(user_id,username,password_hash,email) VALUES(?,?,?,?)");
                var cat=c.prepareStatement("INSERT INTO categories(category_id,name) VALUES(?,?)")) {
                // 每条Mongo评论的user_id也必须能在SQL找到，不用悬空引用凑规模。
                for(int i=0;i<6000;i++){u.setLong(1,10000+i);u.setString(2,"perf_"+i);u.setString(3,"PERFORMANCE_ONLY_NO_LOGIN");u.setString(4,"perf_"+i+"@example.invalid");u.addBatch();if(i%1000==999)u.executeBatch();}
                for(int i=0;i<60;i++){cat.setLong(1,10000+i);cat.setString(2,"测评分类"+i);cat.addBatch();}cat.executeBatch();
            }
            try(var p=c.prepareStatement("INSERT INTO items(item_id,title,category_id,price,status) VALUES(?,?,?,?,1)")) {
                for(int i=0;i<30000;i++){p.setLong(1,10000+i);p.setString(2,"测评装备"+i);p.setLong(3,10000+i%60);p.setBigDecimal(4,BigDecimal.valueOf(2000+i%1000*10L,2));p.addBatch();if(i%1000==999)p.executeBatch();}
            }
            try(var p=c.prepareStatement("INSERT INTO orders(order_id,user_id,item_id,amount,status,created_at) VALUES(?,?,10001,20.10,0,?)")) {
                for(int i=0;i<60000;i++){p.setLong(1,800000+i);p.setLong(2,10000+i%60);p.setTimestamp(3,Timestamp.valueOf(LocalDate.of(2026,9,1).plusDays(i/60%90).atStartOfDay()));p.addBatch();if(i%1000==999)p.executeBatch();}
            }
            try(var p=c.prepareStatement("UPDATE orders SET status=MOD(FLOOR((order_id-800000)/60),3) WHERE order_id>=800000")){p.executeUpdate();}
            c.commit();
        }catch(Exception failure){c.rollback();throw failure;}finally{c.setAutoCommit(true);}
        try(var p=c.prepareStatement("ANALYZE TABLE users,categories,items,orders")){p.execute();}
        List<Document> docs=new ArrayList<>();
        for(int i=0;i<60000;i++) {
            docs.add(new Document("user_id",10000L+i%6000).append("item_id",10000L+i%30000)
                    .append("content","性能测评合成评论").append("rating",i%5+1).append("tags",List.of("性能测评"))
                    .append("created_at",Date.from(Instant.parse("2026-11-08T12:00:00Z").minusSeconds(i*20L))).append("source","PERFORMANCE_ONLY"));
            if(docs.size()==1000){db.getCollection("comments").insertMany(docs);docs.clear();}
        }
        System.out.println("PERFORMANCE_FIXTURE_READY");
    }
}
