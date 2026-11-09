# Uses disposable local database instances and synthetic accounts only.
import base64,hashlib,json,os,pathlib,shutil,socket,subprocess,sys,tempfile,time
import xml.etree.ElementTree as ET
repo=pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else pathlib.Path(__file__).resolve().parent.parent
version=ET.parse(repo/"pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
root=pathlib.Path(tempfile.mkdtemp(prefix="sports-mall-admin-")).resolve()
print("ISOLATED_TEST_ROOT="+str(root),flush=True)
mysqlbase=pathlib.Path(r"C:\develop\MySQL\mysql-8.4.13-winx64")
mongobase=pathlib.Path(r"C:\develop\MongoDB")
sqldata=root/"mysql-data"; mongodata=root/"mongo-data"; mongodata.mkdir()
java=pathlib.Path(os.environ["JAVA_HOME"])/"bin/java.exe"
no_window=getattr(subprocess,"CREATE_NO_WINDOW",0)
owned=[]; handles=[]
def run(args,*,input=None,timeout=60,log=None,check=True):
    p=subprocess.run([str(x) for x in args],input=input,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,creationflags=no_window)
    out=p.stdout.decode("utf-8","replace")
    if log: (root/log).write_text(out,encoding="utf-8")
    if p.returncode and check:
        print(out[-5000:],flush=True)
        raise RuntimeError("Command failed: "+str(args[0])+" exit="+str(p.returncode))
    return out
def port():
    with socket.socket() as s:
        s.bind(("127.0.0.1",0))
        return s.getsockname()[1]
def wait_port(n,p):
    until=time.monotonic()+30
    while time.monotonic()<until:
        if p.poll() is not None: raise RuntimeError("Owned database exited during startup")
        try:
            with socket.create_connection(("127.0.0.1",n),timeout=.3): return
        except OSError: time.sleep(.2)
    raise TimeoutError("Database startup timed out")
def launch(args,log):
    handle=(root/log).open("wb"); handles.append(handle)
    p=subprocess.Popen([str(x) for x in args],stdout=handle,stderr=subprocess.STDOUT,creationflags=no_window)
    owned.append(p); return p
sqlport=port(); mongoport=port()
while mongoport==sqlport: mongoport=port()
mysql=[mysqlbase/"bin/mysql.exe","--no-defaults","--host=127.0.0.1","--port="+str(sqlport),"--user=root","--default-character-set=utf8mb4"]
shell=[mongobase/"shell/bin/mongosh.exe","mongodb://127.0.0.1:"+str(mongoport),"--quiet"]
try:
    run([mysqlbase/"bin/mysqld.exe","--no-defaults","--initialize-insecure","--console","--basedir="+str(mysqlbase),"--datadir="+str(sqldata)],log="mysql-init.log")
    mysqlp=launch([mysqlbase/"bin/mysqld.exe","--no-defaults","--console","--basedir="+str(mysqlbase),"--datadir="+str(sqldata),"--port="+str(sqlport),"--bind-address=127.0.0.1","--mysqlx=OFF"],"mysql-server.log")
    mongop=launch([mongobase/"server/bin/mongod.exe","--dbpath="+str(mongodata),"--port="+str(mongoport),"--bind_ip=127.0.0.1"],"mongo-server.log")
    wait_port(sqlport,mysqlp); wait_port(mongoport,mongop)
    for f in ["mysql_schema.sql","mysql_log_import.sql","mysql_views.sql","mysql_procedures.sql","mysql_triggers.sql","mysql_query_indexes.sql"]:
        run(mysql,input=(repo/"src/main/resources/sql"/f).read_bytes(),log=f+".log")
    salt=b"0123456789ABCDEF"
    digest=hashlib.pbkdf2_hmac("sha256",b"synthetic-integration-password",salt,600000)
    encoded="pbkdf2_sha256$600000$"+base64.b64encode(salt).decode()+"$"+base64.b64encode(digest).decode()
    fixture=f"""USE sports_mall;
INSERT INTO users(username,password_hash,email,status) VALUES('integration_owner','{encoded}','owner@example.test',1),('integration_other','{encoded}','other@example.test',1),('integration_disabled','{encoded}','disabled@example.test',0);
INSERT INTO categories(name) VALUES('球类');
INSERT INTO items(title,category_id,price,status) VALUES('训练篮球',1,100.00,1),('下架商品',1,100.00,0);
"""
    run(mysql,input=fixture.encode("utf-8"),log="mysql-fixtures.log")
    run(shell+["--file",repo/"src/main/resources/sql/mongodb_init.js"],log="mongo-fixtures.log")
    run(shell+["--file",repo/"src/main/resources/sql/mongodb_comment_indexes.js"],log="mongo-comment-indexes.log")
    run(shell+["--file",repo/"src/main/resources/sql/mongodb_log_indexes.js"],log="mongo-log-indexes.log")
    # SQL夹具已创建商品1，测试扩展详情显式放在夹具中。
    run(shell+["--eval",'db.getSiblingDB("sports_mall").item_details.insertOne({item_id:Long("1"),description:"训练用篮球，适合日常运球与投篮练习。",images:[],metadata:{language:"zh-CN"}})'],log="mongo-detail-fixture.log")
    classpath=str(repo/"target/test-classes")+os.pathsep+str(repo/"target"/f"sports-mall-{version}.jar")
    output=run([java,"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8",
        "-Dmall.desktop.jar="+str(repo/"target"/f"sports-mall-{version}-desktop.jar"),
        "-cp",classpath,"com.sportsmall.integration.HttpIntegrationProbe",str(sqlport),sqldata,
        "mongodb://127.0.0.1:"+str(mongoport),mongodata],timeout=150,log="integration.log")
    print("\n".join(line for line in output.splitlines() if line.startswith(("PASS","INTEGRATION_CHECKS_PASSED"))),flush=True)
finally:
    if owned:
        try: run([mysqlbase/"bin/mysqladmin.exe","--no-defaults","--host=127.0.0.1","--port="+str(sqlport),"--user=root","shutdown"],timeout=12,log="mysql-shutdown.log")
        except Exception as e: print("MYSQL_SHUTDOWN="+type(e).__name__,flush=True)
        # 服务端退出会主动断开mongosh连接；保存输出，随后以自己启动的进程退出为准。
        try: run(shell+["--eval",'db.getSiblingDB("admin").shutdownServer()'],timeout=12,log="mongo-shutdown.log",check=False)
        except Exception as e: print("MONGO_SHUTDOWN="+type(e).__name__,flush=True)
    for p in owned:
        try: p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            p.terminate(); p.wait(timeout=10)
    for h in handles: h.close()
    for data in (sqldata,mongodata):
        resolved=data.resolve()
        if resolved.parent!=root or root.parent!=pathlib.Path(tempfile.gettempdir()).resolve() or not root.name.startswith("sports-mall-admin-"):
            raise RuntimeError("Cleanup path outside the owned test directory")
        if resolved.exists(): shutil.rmtree(resolved)
    print("OWNED_TEST_DATABASES_STOPPED_AND_REMOVED; logs="+str(root),flush=True)
