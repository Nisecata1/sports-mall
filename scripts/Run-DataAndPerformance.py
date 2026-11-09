"""验证演示数据工具的重跑/保留行为，再在自己创建的临时实例做性能对照。"""
import hashlib, json, os, pathlib, shutil, socket, subprocess, tempfile, time
import xml.etree.ElementTree as ET

repo = pathlib.Path(__file__).resolve().parent.parent
version = ET.parse(repo / "pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
root = pathlib.Path(tempfile.mkdtemp(prefix="sports-mall-performance-")).resolve()
out = repo / "target" / "performance"
out.mkdir(parents=True, exist_ok=True)
mysqlbase = pathlib.Path(r"C:\develop\MySQL\mysql-8.4.13-winx64")
mongobase = pathlib.Path(r"C:\develop\MongoDB")
java = pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
jar = repo / "target" / f"sports-mall-{version}.jar"
sqldata, mongodata = root / "mysql-data", root / "mongo-data"
mongodata.mkdir()
owned, handles = [], []
no_window = getattr(subprocess, "CREATE_NO_WINDOW", 0)

def run(args, *, input=None, timeout=60, name="command.log", env=None, check=True):
    p = subprocess.run([str(x) for x in args], input=input, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, timeout=timeout, creationflags=no_window, env=env)
    text = p.stdout.decode("utf-8", "replace")
    (root / name).write_text(text, encoding="utf-8")
    if check and p.returncode:
        print(text[-4000:], flush=True)
        raise RuntimeError(f"{name} failed ({p.returncode})")
    return text

def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]

def launch(args, name):
    h = (root / name).open("wb")
    handles.append(h)
    p = subprocess.Popen([str(x) for x in args], stdout=h, stderr=subprocess.STDOUT, creationflags=no_window)
    owned.append(p)
    return p

def ready(n, process):
    until = time.monotonic() + 30
    while time.monotonic() < until:
        if process.poll() is not None:
            raise RuntimeError("Owned database exited during startup")
        try:
            with socket.create_connection(("127.0.0.1", n), timeout=.3):
                return
        except OSError:
            time.sleep(.2)
    raise TimeoutError("Database startup timeout")

sqlport, mongoport = port(), port()
while mongoport == sqlport:
    mongoport = port()
assert sqlport != 18306 and mongoport != 27017
mysql = [mysqlbase / "bin/mysql.exe", "--no-defaults", "--host=127.0.0.1", f"--port={sqlport}", "--user=root", "--default-character-set=utf8mb4"]
shell = [mongobase / "shell/bin/mongosh.exe", f"mongodb://127.0.0.1:{mongoport}", "--quiet"]
jvm = [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", jar]
env = dict(os.environ)
env.pop("MALL_DB_PASSWORD", None)
print("ISOLATED_PERFORMANCE_ROOT=" + str(root), flush=True)
try:
    run([mysqlbase / "bin/mysqld.exe", "--no-defaults", "--initialize-insecure", "--console", f"--basedir={mysqlbase}", f"--datadir={sqldata}"], name="mysql-init.log")
    mp = launch([mysqlbase / "bin/mysqld.exe", "--no-defaults", "--console", f"--basedir={mysqlbase}", f"--datadir={sqldata}", f"--port={sqlport}", "--bind-address=127.0.0.1", "--mysqlx=OFF"], "mysql-server.log")
    dp = launch([mongobase / "server/bin/mongod.exe", f"--dbpath={mongodata}", f"--port={mongoport}", "--bind_ip=127.0.0.1"], "mongo-server.log")
    ready(sqlport, mp)
    ready(mongoport, dp)
    actual_sql = run(mysql + ["--batch", "--skip-column-names", "--execute", "SELECT @@datadir;"], name="mysql-path-guard.log").strip()
    actual_mongo = run(shell + ["--eval", 'print(db.getSiblingDB("admin").runCommand({getCmdLineOpts:1}).parsed.storage.dbPath);'], name="mongo-path-guard.log").strip()
    if pathlib.Path(actual_sql).resolve() != sqldata.resolve() or pathlib.Path(actual_mongo).resolve() != mongodata.resolve():
        raise RuntimeError("Refusing setup: actual database paths are not the owned directories")
    for name in ("mysql_schema.sql", "mysql_log_import.sql", "mysql_views.sql", "mysql_procedures.sql", "mysql_triggers.sql"):
        run(mysql, input=(repo / "src/main/resources/sql" / name).read_bytes(), name=name + ".log")
    run(mysql, input=b"USE sports_mall; INSERT INTO users(username,password_hash,email,role) VALUES('isolated_operator','ISOLATED_NO_LOGIN','operator@example.invalid','ADMIN');", name="admin-fixture.log")
    for name in ("mongodb_init.js", "mongodb_comment_indexes.js", "mongodb_log_indexes.js"):
        run(shell + ["--file", repo / "src/main/resources/sql" / name], name=name + ".log")
    empty_details = run(shell + ["--eval", 'print(db.getSiblingDB("sports_mall").item_details.countDocuments({}));'], name="fresh-init-check.log")
    assert empty_details.strip() == "0", "Structure initialization must wait for actual SQL item IDs"
    # 在第二份档案写入时失败，证明同一SQL事务内已插入的用户/分类/档案也会撤销。
    run(mysql, input=b"USE sports_mall;\nDELIMITER $$\nCREATE TRIGGER seed_failure BEFORE INSERT ON profiles FOR EACH ROW BEGIN IF NEW.user_id>=3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SEED_INJECTED_PROFILE_FAILURE'; END IF; END$$\nDELIMITER ;\n", name="seed-failure-trigger.log")
    failed = run(jvm + [repo / "scripts/java/CourseDataTool.java", "--isolated", sqlport, mongoport, out / "seed-rejected.json"], timeout=90, name="seed-sql-rejected.log", env=env, check=False)
    assert "SEED_INJECTED_PROFILE_FAILURE" in failed
    rollback = run(mysql + ["--batch", "--skip-column-names", "--execute", "SELECT (SELECT COUNT(*) FROM sports_mall.users),(SELECT COUNT(*) FROM sports_mall.profiles),(SELECT COUNT(*) FROM sports_mall.categories),(SELECT COUNT(*) FROM sports_mall.items),(SELECT COUNT(*) FROM sports_mall.orders);"], name="seed-rollback-counts.log")
    assert rollback.strip() == "1\t0\t0\t0\t0"
    run(mysql, input=b"USE sports_mall; DROP TRIGGER seed_failure;", name="seed-remove-failure.log")
    # Mongo拒绝评论时，SQL已提交、详情已保存。恢复校验规则后重跑应补齐，而不是清空重做。
    run(shell + ["--eval", 'db.getSiblingDB("sports_mall").runCommand({collMod:"comments",validator:{source:"REJECT_DEMO"},validationLevel:"strict"});'], name="seed-mongo-reject-validator.log")
    failed = run(jvm + [repo / "scripts/java/CourseDataTool.java", "--isolated", sqlport, mongoport, out / "seed-mongo-rejected.json"], timeout=90, name="seed-mongo-rejected.log", env=env, check=False)
    assert "validation" in failed.lower()
    partial = run(mysql + ["--batch", "--skip-column-names", "--execute", "SELECT (SELECT COUNT(*) FROM sports_mall.users),(SELECT COUNT(*) FROM sports_mall.orders);"], name="seed-partial-sql-counts.log")
    assert partial.strip() == "11\t20"
    run(shell + ["--eval", 'db.getSiblingDB("sports_mall").runCommand({collMod:"comments",validator:{}});'], name="seed-remove-mongo-validator.log")
    for label in ("first", "repeat"):
        run(jvm + [repo / "scripts/java/CourseDataTool.java", "--isolated", sqlport, mongoport, out / f"seed-{label}.json"], timeout=90, name=f"seed-{label}.log", env=env)
    first = json.loads((out / "seed-first.json").read_text(encoding="utf-8"))
    repeat = json.loads((out / "seed-repeat.json").read_text(encoding="utf-8"))
    assert first["existing_records_unchanged"] and first["quotas_passed"]
    assert repeat["existing_records_unchanged"] and repeat["before"] == repeat["after"] == first["after"]
    print("SEED_CHECKS_PASSED: SQL rollback, cross-store partial write then resume, quotas, existing-record fingerprints, repeat counts", flush=True)
    # 对照必须从没有这两个新索引的临时库开始，绝不删除课程主库索引。
    # InnoDB可能在创建复合索引后移除冗余的user_id单列外键支撑索引。
    # 先在临时实例重建单列支撑，才能移除复合索引；全程保留外键。
    run(mysql, input=b"USE sports_mall; CREATE INDEX perf_baseline_orders_user ON orders(user_id); DROP INDEX idx_items_sale_category_price ON items; DROP INDEX idx_orders_user_status_created ON orders;", name="owned-baseline-indexes.log")
    run(jvm + [repo / "scripts/java/PerformanceProbe.java", sqlport, sqldata, mongoport, mongodata, out / "results.json"], timeout=180, name="performance.log", env=env)
    result = json.loads((out / "results.json").read_text(encoding="utf-8"))
    assert all(c["results_equal"] for c in result["mysql_cases"]) and result["mongo_case"]["results_equal"]
    result["application_version"] = version
    result["backend_jar_sha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
    (out / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PERFORMANCE_CHECKS_PASSED: three result-equivalent query comparisons", flush=True)
finally:
    if owned:
        for args, name in (([mysqlbase / "bin/mysqladmin.exe", "--no-defaults", "--host=127.0.0.1", f"--port={sqlport}", "--user=root", "shutdown"], "mysql-shutdown.log"),
                           (shell + ["--eval", 'db.getSiblingDB("admin").shutdownServer()'], "mongo-shutdown.log")):
            try:
                run(args, timeout=12, name=name, check=False)
            except Exception as e:
                print(name + ": " + type(e).__name__, flush=True)
    for p in owned:
        try:
            p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            p.terminate()
            p.wait(timeout=10)
    for h in handles:
        h.close()
    for data in (sqldata, mongodata):
        resolved = data.resolve()
        if resolved.parent != root or root.parent != pathlib.Path(tempfile.gettempdir()).resolve() or not root.name.startswith("sports-mall-performance-"):
            raise RuntimeError("Cleanup path escaped owned temporary directory")
        if resolved.exists():
            shutil.rmtree(resolved)
    print("OWNED_DATABASES_STOPPED_AND_REMOVED; logs=" + str(root), flush=True)
