"""从本轮真实测评JSON生成源稿与PDF；不运行测评，不填造缺失结果。"""
import hashlib, json, pathlib, shutil
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak

repo = pathlib.Path(__file__).resolve().parent.parent
results = repo / "target/performance/results.json"
r = json.loads(results.read_text(encoding="utf-8"))
assert all(c["results_equal"] for c in r["mysql_cases"]) and r["mongo_case"]["results_equal"]
evidence = repo / "docs/测评证据"
evidence.mkdir(parents=True, exist_ok=True)
version = ET.parse(repo / "pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
r.setdefault("application_version", version)
r.setdefault("backend_jar_sha256", hashlib.sha256((repo / "target" / f"sports-mall-{version}.jar").read_bytes()).hexdigest())
assert r["application_version"] == "0.3.2", "This report narrative targets 0.3.2; review it before documenting another version."
r["source_sha256"] = {
    p: hashlib.sha256((repo / p).read_bytes()).hexdigest()
    for p in ("scripts/java/CourseDataTool.java", "scripts/java/PerformanceProbe.java", "scripts/Run-DataAndPerformance.py",
              "src/main/java/com/sportsmall/dao/mysql/ItemSearchDao.java", "src/main/java/com/sportsmall/dao/mysql/OrderDao.java")
}
raw_name = "2026-11-08性能原始结果.json"
(evidence / raw_name).write_text(json.dumps(r, ensure_ascii=False, indent=2), encoding="utf-8")
for name in ("seed-first.json", "seed-repeat.json"):
    shutil.copyfile(repo / "target/performance" / name, evidence / ("隔离库-" + name))

cases = []
for c in r["mysql_cases"]:
    cases.append((c["case"], c["before"]["median_ms"], c["after"]["median_ms"], c["before"]["p95_ms"], c["after"]["p95_ms"], c["returned"]))
m = r["mongo_case"]
cases.append((m["case"], m["scan"]["median_ms"], m["indexed"]["median_ms"], m["scan"]["p95_ms"], m["indexed"]["p95_ms"], m["returned"]))
def ms(n): return f"{n:.3f}"
scan_docs = m["scan_explain"]["executionStats"]["totalDocsExamined"]
index_docs = m["indexed_explain"]["executionStats"]["totalDocsExamined"]
table = "\n".join(f"| {name} | {ms(a)} | {ms(b)} | {ms(pa)} | {ms(pb)} | {n} |" for name,a,b,pa,pb,n in cases)
sources = "[MySQL复合索引](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html)、[MongoDB执行计划字段](https://www.mongodb.com/docs/manual/reference/explain-results/)。"
md = f"""# 运动装备商城性能优化报告

软件0.3.2；测量时间UTC {r['run_at']}。本报告对应正式项目要求书的性能优化部分，记录本机临时数据库的合成负载，不表示生产容量或真实用户流量。

## 1. 环境与数据

Windows {r['os']}；Java {r['java']}；MySQL {r['mysql']}；MongoDB {r['mongodb']}；可用逻辑处理器{r['processors']}个。

运行器创建独立目录和动态端口，核对数据库实际物理路径，拒绝18306/27017课程端口。测量后停止自己启动的进程并移除临时数据目录，保留日志。课程主库没有加载这些规模数据。

在小型演示夹具基础上额外生成30000件商品、60000笔订单和60000条评论，以及6000个测评用户和60个分类。评论引用的用户和商品在SQL中存在；订单使用其中60个用户。测评用户没有可登录的真实密码。订单使用现有外键和触发器，先插入待处理，再合法更新状态。评论总数为{scan_docs}，包含前置40条演示评论。

## 2. 方法与正确性

每阶段预热3次、采样21次，计时从已建立连接上发起查询开始，到完整读完返回结果结束；不计数据库启动、数据生成和建索引时间。报告中位数和最近秩法P95，原始21次样本均保存。

两个MySQL测试直接读取应用DAO中的PAGE和FIND_MINE SQL常量并绑定参数，不另写一个更简单的替代查询。新增索引前后逐条比较有序结果并保存SHA-256。所有对照结果一致。EXPLAIN ANALYZE记录一次计划中的实际行数，计划计时与客户端21次计时是不同指标。

商品条件：分类10017、在售、价格25至85、按价格升序、第1页20条；关键词不限。订单条件：用户10017、已完成、2026-09-01至2026-09-08左闭右开。Mongo条件：用户10017、按created_at与_id降序、最多20条。

Mongo对照明确指定已有idx_comments_user_time与自然扫描，两者取同样结果。它验证已有索引的作用，并不声称本轮新建了这个索引。explain会绕过计划缓存；日常查询的计划缓存行为另论。

## 3. 实测结果

| 查询 | 基线中位数ms | 索引后中位数ms | 基线P95ms | 索引后P95ms | 返回条数 |
| --- | ---: | ---: | ---: | ---: | ---: |
{table}

MySQL商品查询从分类单列索引读取500行，再过滤排序；新增(status,category_id,price,item_id)后，对匹配价格区间读取300行。CASE排序仍有Sort，本轮没有消除排序。

本人订单查询从user_id单列索引读取1000行、筛选后排序；新增(user_id,status,created_at,order_id)后，直接范围读取24行，反向索引读取满足时间与编号倒序。索引前后返回同样24条。

Mongo自然扫描检查{scan_docs}份文档；已有复合索引对照只检查{index_docs}份文档，返回同样{m['returned']}条。全计划与样本在`docs/测评证据/{raw_name}`。

## 4. 为什么这些改动有用，代价是什么

业务先按等值条件限定范围，再按价格或日期取一个区间，因此把相应等值列置于复合索引前部。索引可用性取决于条件组合和顺序；不能把本例推广成所有查询都受益。{sources}

本轮只新增两个非唯一MySQL查询索引，不增加业务列、表或HTTP接口，不改变订单金额、归属与状态规则。工具先比对同名索引定义，重跑不会创建第二份。InnoDB可能用复合索引替代冗余的user_id外键支撑单列索引，外键约束保留。

索引额外占用空间，写入要维护索引。本报告没有测索引空间与写入吞吐，因此不声称没有写入代价。商品关键词包含匹配、跨分类排序、深分页、全部聚合等场景没有在本轮优化。

## 5. 边界与后续

单机、单客户端、热缓存、小规模重复查询；基线和索引阶段顺序执行，本机背景负载及阶段顺序都可能影响毫秒值。没有进行随机交叉的多轮实验，不把某一次比例作为稳定承诺。没有测多用户吞吐、P99、远程网络、冷缓存、长期磁盘增长或崩溃恢复，也不把查询测评写成完整压力测试。

本轮复测原有342项JUnit与427项独立HTTP/双库检查，验证业务与权限回归。另验证数据工具的SQL中途失败回滚、跨库部分写入后重跑、已有记录保持、配额达标及重复运行数量不变。故障只在临时实例注入。

复现：先`mvnw.cmd package`，再`python scripts/Run-DataAndPerformance.py`，最后`python scripts/Build-PerformanceReport.py`。当前运行器引用本机C:/develop下的数据库可执行程序，换机器需调整路径。源码散列随原始JSON保存。
"""
(repo / "docs/性能优化报告.md").write_text(md, encoding="utf-8")

pdfmetrics.registerFont(TTFont("Song", r"C:\Windows\Fonts\simsun.ttc", subfontIndex=0))
body = ParagraphStyle("body", fontName="Song", fontSize=10.5, leading=17, textColor=colors.HexColor("#20302e"), wordWrap="CJK", spaceAfter=9)
title = ParagraphStyle("title", parent=body, fontSize=24, leading=32, spaceAfter=18)
h2 = ParagraphStyle("h2", parent=body, fontSize=14, leading=22, spaceBefore=10, spaceAfter=10)
small = ParagraphStyle("small", parent=body, fontSize=9, leading=14)
output = repo / "output/pdf"
output.mkdir(parents=True, exist_ok=True)
pdf = output / "性能优化报告.pdf"
story = []
def para(text, style=body): story.append(Paragraph(escape(text), style))
para("运动装备商城", title)
para("性能优化报告 · 0.3.2", h2)
para("以相同结果为前提，用扫描量与实测时间解释索引的作用。")
para(f"实测UTC：{r['run_at']}\nMySQL {r['mysql']} / MongoDB {r['mongodb']} / Java {r['java']}", small)
para("01  环境、规模与方法", h2)
para("运行器在独立临时实例上测量，核对实际数据路径，拒绝课程主库端口。测后停止自己的进程并清理数据目录。大量数据没有灌入课程数据库。")
para(f"在小型演示夹具上额外生成：30000件商品、60000笔订单、60000条评论、6000名测评用户、60个分类。评论引用对应SQL记录，订单使用其中60个用户。评论总量{scan_docs}条。系统：{r['os']}，{r['processors']}个逻辑处理器。")
para("每阶段预热3次，采样21次；从已建立连接发起查询开始，到完整读取结果结束计时。报告中位数与最近秩法P95。索引创建与数据装载时间不包含在查询计时内。")
para("MySQL直接读取应用DAO的PAGE和FIND_MINE SQL常量。每项对照逐条比较有序结果并保存SHA-256，全部相同。Mongo以同一个查询分别强制自然扫描和使用已有索引。")
para("02  测量结果（毫秒）", h2)
grid=[["查询","基线中位","索引中位","基线P95","索引P95"]]
labels=["商品分类价格分页","本人状态日期订单","本人最新评论"]
for label,(_,a,b,pa,pb,_) in zip(labels,cases): grid.append([label,ms(a),ms(b),ms(pa),ms(pb)])
t=Table([[Paragraph(escape(str(v)),small) for v in row]for row in grid],colWidths=[141,80,80,80,80],hAlign="LEFT",repeatRows=1)
t.setStyle(TableStyle([("BACKGROUND",(0,0),(-1,0),colors.HexColor("#e3efea")),("GRID",(0,0),(-1,-1),.4,colors.HexColor("#c6d5cf")),("VALIGN",(0,0),(-1,-1),"TOP"),("TOPPADDING",(0,0),(-1,-1),9),("BOTTOMPADDING",(0,0),(-1,-1),9)]))
story.append(t);story.append(Spacer(1,12))
para("这些是本机热缓存下的测量值，不是部署后的响应时间承诺。扫描量与计划可帮助判断改动原因，毫秒值还会受后台负载影响。", small)
story.append(PageBreak())
para("03  沿查询计划解释变化", h2)
para("商品：在售 + 分类10017 + 价格25至85，按价格升序取20条。基线通过分类单列索引读取500行，再过滤排序；新索引读取匹配区间300行。CASE排序仍保留Sort，不能声称本轮消除了排序。")
para("新增索引：items(status, category_id, price, item_id)", small)
para("订单：用户10017 + 已完成 + 2026-09-01至09-08左闭右开。基线读取该用户1000笔订单，再筛选排序；新索引直接读取24笔符合条件订单，并反向扫描满足日期、编号倒序。结果同为24条。")
para("新增索引：orders(user_id, status, created_at, order_id)", small)
para(f"Mongo：用户10017的最新评论，按created_at与_id倒序，最多20条。自然扫描检查{scan_docs}份文档；已有idx_comments_user_time只检查{index_docs}份。两者均返回{m['returned']}条相同评论。这是已有索引与强制扫描的对照，不是本轮新增Mongo索引。")
para("前面的等值条件先限定范围，后面的价格或日期继续收窄范围。这两个复合索引来自实际业务查询；不是把每个字段都建一遍索引。")
para("04  成本、验证与适用范围", h2)
para("只新增两个非唯一查询索引，不增加业务列或HTTP接口，不改变订单规则。索引占用额外空间，写入要维护索引。本轮没有测空间增长和写入吞吐，不能声称没有代价。")
para("单机、单客户端、热缓存；基线与索引阶段顺序执行，尚未做多轮随机交叉。没有测并发吞吐、P99、冷缓存、远程网络与长期磁盘增长，不能作为完整压力测试。关键词包含匹配、深分页和全部聚合也不在本轮优化范围。")
para("已复测342项JUnit和427项临时HTTP/双库检查，验证原业务回归。数据工具另外检查SQL故障回滚、跨库部分写入后重跑、已有记录保持、配额与重复运行数量；故障注入不在课程主库执行。")
para("05  证据与复现", h2)
para(f"完整原始样本、执行计划、结果散列与源码散列：docs/测评证据/{raw_name}。报告源稿为docs/性能优化报告.md。", small)
para("执行：mvnw.cmd package → python scripts/Run-DataAndPerformance.py → python scripts/Build-PerformanceReport.py。依赖当前本机C:/develop中的MySQL/MongoDB与JAVA_HOME；换机器需调整运行器路径。", small)
para("参考：MySQL 8.4 Multiple-Column Indexes；MongoDB Explain Results。", small)
for url in ("https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html","https://www.mongodb.com/docs/manual/reference/explain-results/"):
    story.append(Paragraph(f'<link href="{url}" color="#116b5c">{escape(url)}</link>',small))

def footer(canvas, doc):
    canvas.setStrokeColor(colors.HexColor("#c6d5cf"));canvas.line(42,39,A4[0]-42,39)
    canvas.setFont("Song",8);canvas.setFillColor(colors.HexColor("#66736c"));canvas.drawString(42,26,"项目011 · 运动装备商城 | 可复现的本机查询测评")
    canvas.drawRightString(A4[0]-42,26,str(doc.page))
SimpleDocTemplate(str(pdf),pagesize=A4,rightMargin=42,leftMargin=42,topMargin=42,bottomMargin=54,title="运动装备商城性能优化报告",author="Sports Mall course project").build(story,onFirstPage=footer,onLaterPages=footer)
print("PDF_FOR_REVIEW="+str(pdf))
