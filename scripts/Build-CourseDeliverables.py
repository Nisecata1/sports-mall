"""从Markdown源稿和真实验证记录生成课程PDF、结构图与交付索引。"""
from __future__ import annotations
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from collections import Counter
from html import escape

import fitz
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, Preformatted

REPO = pathlib.Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"
BUILD = REPO / "target/deliverables-build"
OUTPUT = REPO / "output/pdf"
VERSION = ET.parse(REPO / "pom.xml").getroot().find("{http://maven.apache.org/POM/4.0.0}version").text
for folder in (BUILD, OUTPUT, DOCS / "测评证据"):
    folder.mkdir(parents=True, exist_ok=True)
pdfmetrics.registerFont(TTFont("CourseSong", r"C:\Windows\Fonts\simsun.ttc", subfontIndex=0))
INK = colors.HexColor("#203832")
TEAL = colors.HexColor("#176f60")
PALE = colors.HexColor("#e6f0eb")
GRAY = colors.HexColor("#61756d")

def label(drawing, x, y, text, size=12, color=INK):
    drawing.setFillColor(color)
    drawing.setFont("CourseSong", size)
    drawing.drawString(x, y, text)

def box(drawing, x, y, width, height, title, lines, size=13):
    drawing.setFillColor(colors.white)
    drawing.setStrokeColor(TEAL)
    drawing.roundRect(x, y, width, height, 8, fill=1, stroke=1)
    drawing.setFillColor(PALE)
    drawing.rect(x + 1, y + height - 36, width - 2, 35, fill=1, stroke=0)
    label(drawing, x + 14, y + height - 24, title, 17)
    for index, line in enumerate(lines):
        label(drawing, x + 14, y + height - 58 - index * 20, line, size)

def arrow(drawing, points, caption=None, caption_at=None):
    drawing.setStrokeColor(TEAL)
    drawing.setLineWidth(1.6)
    path = drawing.beginPath()
    path.moveTo(*points[0])
    for point in points[1:]:
        path.lineTo(*point)
    drawing.drawPath(path)
    previous, last = points[-2:]
    dx, dy = last[0] - previous[0], last[1] - previous[1]
    length = (dx * dx + dy * dy) ** .5
    ux, uy = dx / length, dy / length
    head = drawing.beginPath()
    head.moveTo(*last)
    head.lineTo(last[0] - ux * 8 - uy * 4, last[1] - uy * 8 + ux * 4)
    head.lineTo(last[0] - ux * 8 + uy * 4, last[1] - uy * 8 - ux * 4)
    head.close()
    drawing.setFillColor(TEAL)
    drawing.drawPath(head, fill=1)
    if caption and caption_at:
        label(drawing, *caption_at, caption, 12)

def save_diagram(name, size, draw):
    pdf = OUTPUT / (name + ".pdf")
    page = canvas.Canvas(str(pdf), pagesize=size)
    page.setTitle(name)
    draw(page)
    page.save()
    document = fitz.open(pdf)
    document[0].get_pixmap(matrix=fitz.Matrix(1.5, 1.5)).save(DOCS / (name + ".png"))
    shutil.copyfile(pdf, DOCS / (name + ".pdf"))

def er(page):
    label(page, 42, 808, "运动装备商城 MySQL E-R图", 24)
    label(page, 42, 783, f"软件{VERSION}    关系与字段依据当前DDL    2026-11-09", 12, GRAY)
    box(page, 42, 465, 330, 250, "users", ["user_id BIGINT PK", "username VARCHAR(50) UK", "password_hash VARCHAR(255)", "email VARCHAR(100) UK", "phone VARCHAR(20) NULL", "role ENUM(ADMIN,USER)", "status TINYINT", "created_at DATETIME", "updated_at DATETIME"])
    box(page, 42, 125, 330, 195, "profiles", ["profile_id BIGINT PK", "user_id BIGINT FK / UK", "real_name VARCHAR(50) NULL", "id_card VARCHAR(20) NULL", "address VARCHAR(500)", "notes TEXT"])
    box(page, 430, 600, 330, 130, "categories", ["category_id BIGINT PK", "name VARCHAR(50)", "parent_id BIGINT FK / NULL"])
    box(page, 430, 280, 330, 225, "items", ["item_id BIGINT PK", "title VARCHAR(200)", "category_id BIGINT FK", "price DECIMAL(10,2) > 0", "status TINYINT", "created_at DATETIME", "updated_at DATETIME"])
    box(page, 820, 465, 330, 205, "orders", ["order_id BIGINT PK", "user_id BIGINT FK", "item_id BIGINT FK", "amount DECIMAL(10,2) > 0", "status TINYINT", "created_at DATETIME"])
    box(page, 820, 120, 330, 225, "log_import_rows", ["batch_id CHAR(64) PK", "row_no INT PK", "imported_by BIGINT FK", "payload JSON", "sync_status ENUM(PENDING,SYNCED)", "created_at DATETIME", "synced_at DATETIME"])
    arrow(page, [(180,465),(180,320)], "1 对 0..1", (194,385))
    arrow(page, [(595,600),(595,505)], "1 对 多", (610,550))
    arrow(page, [(210,715),(210,758),(985,758),(985,670)], "用户 1 对 多订单", (470,765))
    arrow(page, [(760,400),(790,400),(790,550),(820,550)], "1 对 多", (775,438))
    arrow(page, [(372,510),(400,510),(400,80),(985,80),(985,120)], "用户 1 对 多导入行", (490,90))
    arrow(page, [(760,675),(786,675),(786,742),(700,742),(700,730)], "上级 0..1", (805,705))
    label(page, 42, 42, "PK 主键    FK 外键    UK 唯一约束    复合主键由batch_id和row_no共同组成", 12, GRAY)
    label(page, 42, 23, "orders.amount保存成交快照。profiles.user_id唯一。管理员角色由后端业务层检查。", 11, GRAY)

def architecture(page):
    label(page, 35, 510, "运动装备商城进程与分层", 25)
    box(page, 35, 320, 265, 155, "前端进程", ["MallDesktop  界面与输入", "MallApiClient  HTTP与JSON", "登录凭证保存在客户端内存"], 14)
    box(page, 370, 230, 300, 245, "后端进程", ["MallApiServer  路由与身份", "Service  当前权限与业务规则", "DAO  SQL、文档、聚合", "MallServices  连接池与客户端", "JDK HttpServer  端口18808"], 14)
    box(page, 740, 320, 235, 155, "MySQL 18306", ["五张核心表与导入暂存", "外键、约束、视图", "过程、触发器、索引"], 14)
    box(page, 740, 80, 235, 155, "MongoDB 27017", ["详情、评论、浏览、系统日志", "嵌套对象、数组、索引", "单文档写入与聚合管道"], 13)
    arrow(page, [(300,395),(370,395)], "HTTP", (310,410))
    arrow(page, [(670,395),(740,395)], "JDBC", (680,410))
    arrow(page, [(670,285),(705,285),(705,160),(740,160)])
    label(page, 35, 130, "操作与观察", 17)
    label(page, 35, 101, "DbGate直接连接两库，查看实际记录和对象。", 14)
    label(page, 35, 75, "启动脚本按需启动各进程，构建生成两份JAR。", 14)
    label(page, 35, 30, f"软件{VERSION}    前端请求在后端完成数据库访问后返回。", 12, GRAY)

def relations(page):
    label(page, 30, 365, "MySQL关系概览", 23)
    box(page,30,205,260,125,"users",["user_id PK","账号、角色、状态"],15)
    box(page,30,35,260,125,"profiles",["user_id FK / UK","每用户至多一份档案"],15)
    box(page,360,205,260,125,"categories",["parent_id 自引用","根节点上级可为空"],15)
    box(page,690,205,260,125,"items",["category_id FK","price保存当前单价"],15)
    box(page,360,35,260,125,"log_import_rows",["imported_by FK","批次与行号组成主键"],15)
    box(page,690,35,260,125,"orders",["user_id FK, item_id FK","amount保存成交快照"],15)
    arrow(page,[(160,205),(160,160)])
    arrow(page,[(620,267),(690,267)])
    arrow(page,[(820,205),(820,160)])
    arrow(page,[(290,245),(323,245),(323,95),(360,95)])
    arrow(page,[(290,305),(315,305),(315,345),(975,345),(975,95),(950,95)])

def flow(page, import_flow=False):
    heading = "日志导入与状态确认" if import_flow else "创建订单的事务处理"
    label(page,25,270,heading,24)
    titles = ["前端","后端入口","MySQL事务","MongoDB","MySQL确认"] if import_flow else ["前端","后端入口","DAO事务","MySQL触发器","DAO提交"]
    contents = [["选择JSONL","上传content"],["确认管理员","全文件校验"],["JDBC分组批处理","提交PENDING"],["稳定_id","setOnInsert"],["确认成功行","SYNCED回执"]] if import_flow else [["商品与确认价","POST /api/orders"],["读取会话身份","检查参数"],["锁定用户商品","取价与插入"],["检查初始状态","检查当前金额"],["读回与commit","返回订单结果"]]
    for i,(title,lines) in enumerate(zip(titles,contents)):
        x=25+i*195
        box(page,x,110,170,115,title,lines,13)
        if i<4:arrow(page,[(x+170,166),(x+195,166)])
    label(page, 250, 65, "保留PENDING并以同批次重试，分别确认两个数据库的结果。" if import_flow else "执行失败时回滚本次MySQL事务，提交确认异常时先查实际结果。", 15)
    label(page,25,25,"软件"+VERSION+"    HTTP完整路径与调用方法见技术设计文档。",12,GRAY)

save_diagram("MySQL_E-R图", (1190,842), er)
save_diagram("架构图", (1000,550), architecture)
save_diagram("关系概览", (1000,390), relations)
save_diagram("订单事务流程", (1000,300), lambda page: flow(page))
save_diagram("日志导入流程", (1000,300), lambda page: flow(page,True))

BODY = ParagraphStyle("body",fontName="CourseSong",fontSize=10.2,leading=16.3,textColor=INK,wordWrap="CJK",spaceAfter=7)
H1 = ParagraphStyle("h1",parent=BODY,fontSize=24,leading=32,spaceAfter=18)
H2 = ParagraphStyle("h2",parent=BODY,fontSize=15,leading=23,spaceBefore=12,spaceAfter=8,keepWithNext=True)
H3 = ParagraphStyle("h3",parent=BODY,fontSize=12,leading=19,spaceBefore=8,spaceAfter=6,keepWithNext=True)
SMALL = ParagraphStyle("small",parent=BODY,fontSize=8.5,leading=13,spaceAfter=0)
CODE = ParagraphStyle("code",parent=BODY,fontSize=8,leading=11,leftIndent=7,backColor=PALE,borderPadding=7)

def inline(text):
    text = escape(text)
    text = re.sub(r"\*\*(.*?)\*\*",r"<b>\1</b>",text)
    text = re.sub(r"`([^`]+)`",r"\1",text)
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)",r"\1",text)

def footer(page, document):
    page.setStrokeColor(colors.HexColor("#c4d6ce"))
    page.line(43,39,A4[0]-43,39)
    label(page,43,25,f"项目011  运动装备商城    软件{VERSION}",8,GRAY)
    page.drawRightString(A4[0]-43,25,str(document.page))

def build_pdf(name):
    lines = (DOCS/(name+".md")).read_text(encoding="utf-8").splitlines()
    story=[]
    i=0
    while i<len(lines):
        line=lines[i].strip()
        if not line:
            i+=1
            continue
        if line.startswith("```"):
            block=[]
            i+=1
            while i<len(lines) and not lines[i].startswith("```"):
                block.append(lines[i]);i+=1
            story.append(Preformatted("\n".join(block),CODE,maxLineLength=85))
            i+=1
            continue
        if line.startswith("|"):
            rows=[]
            while i<len(lines) and lines[i].strip().startswith("|"):
                parts=[p.strip() for p in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", p) for p in parts):
                    rows.append(parts)
                i+=1
            n=len(rows[0]); width=A4[0]-86
            fractions={2:[.29,.71],3:[.22,.28,.50]}.get(n,[1/n]*n)
            table=Table([[Paragraph(inline(cell),SMALL)for cell in row]for row in rows],colWidths=[width*f for f in fractions],repeatRows=1,hAlign="LEFT")
            table.setStyle(TableStyle([("BACKGROUND",(0,0),(-1,0),PALE),("GRID",(0,0),(-1,-1),.35,colors.HexColor("#c8d7d0")),("VALIGN",(0,0),(-1,-1),"TOP"),("TOPPADDING",(0,0),(-1,-1),5),("BOTTOMPADDING",(0,0),(-1,-1),5)]))
            story.extend([table,Spacer(1,9)])
            continue
        image=re.fullmatch(r"!\[([^\]]*)\]\(([^)]+)\)",line)
        if image:
            path=DOCS/image[2]
            pix=fitz.Pixmap(str(path))
            width=A4[0]-86
            story.append(Image(str(path),width=width,height=width*pix.height/pix.width))
            story.append(Paragraph(inline(image[1]),SMALL))
            i+=1
            continue
        if line.startswith("# "): style,text=H1,line[2:]
        elif line.startswith("## "): style,text=H2,line[3:]
        elif line.startswith("### "): style,text=H3,line[4:]
        else:
            style,text=BODY,line
            while i+1<len(lines) and lines[i+1].strip() and not lines[i+1].startswith(("#","|","```","![")) and not re.match(r"\d+\.",lines[i+1]):
                i+=1;text+=" "+lines[i].strip()
        story.append(Paragraph(inline(text),style))
        i+=1
    result=OUTPUT/(name+".pdf")
    SimpleDocTemplate(str(result),pagesize=A4,leftMargin=43,rightMargin=43,topMargin=43,bottomMargin=55,title=name,author="Sports Mall course project").build(story,onFirstPage=footer,onLaterPages=footer)
    shutil.copyfile(result,DOCS/(name+".pdf"))

sources=("需求规格说明书","技术设计文档","用户手册","MongoDB集合设计","测试报告","项目总结报告")
for source in sources:
    build_pdf(source)

reports=[]
for path in (REPO/"target/surefire-reports").glob("TEST-*.xml"):
    suite=ET.parse(path).getroot()
    reports.append({"suite":suite.attrib["name"],"tests":int(suite.attrib["tests"]),"failures":int(suite.attrib["failures"]),"errors":int(suite.attrib["errors"])})
log=(REPO/f"target/integration-{VERSION}.log").read_text(encoding="utf-8")
matches=re.findall(r"INTEGRATION_CHECKS_PASSED=(\d+)",log)
assert matches and int(matches[-1])==427
assert sum(s["tests"]for s in reports)==342 and sum(s["failures"]+s["errors"]for s in reports)==0
history=subprocess.check_output(["git","log","--format=%h%x09%aI%x09%s"],cwd=REPO).decode("utf-8").splitlines()
zone=datetime.timezone(datetime.timedelta(hours=8))
dates=Counter(datetime.datetime.fromisoformat(row.split("\t")[1]).astimezone(zone).date().isoformat()for row in history)
expected=[str(datetime.date(2026,10,27)+datetime.timedelta(days=i))for i in range(11)]
facts={"version":VERSION,"recorded_at":datetime.datetime.now(zone).isoformat(),"base_commit":subprocess.check_output(["git","rev-parse","HEAD"],cwd=REPO).decode().strip(),"junit":reports,"junit_total":342,"http_database_checks":427,
       "integration_log_sha256":hashlib.sha256(log.encode()).hexdigest(),"selected_passes":[line for line in log.splitlines()if line.startswith("PASS ")and any(word in line.lower()for word in ("concurrent","batch","partial","failure","rollback","audit"))],
       "git_commits_before_this_delivery":len(history),"author_dates_shanghai":dict(sorted(dates.items())),"first_day_assumed":"2026-10-27","missing_first_eleven_calendar_days":[d for d in expected if d not in dates],
       "user_mysql_screenshot":"images/主库数量截图.png","main_database_receipts":["主库数据准备-first.json","主库数据准备-repeat.json"]}
native_check=BUILD/"native-ppt-check.json"
if native_check.exists():
    presentation_check=json.loads(native_check.read_text(encoding="utf-8-sig"))
    actual=hashlib.sha256((DOCS/"项目答辩.pptx").read_bytes()).hexdigest().upper()
    if presentation_check.get("PptxSha256")!=actual:
        raise RuntimeError("PowerPoint check belongs to a different presentation revision")
    facts["presentation_check"]=presentation_check
(DOCS/"测评证据/验证汇总.json").write_text(json.dumps(facts,ensure_ascii=False,indent=2),encoding="utf-8")

preview=BUILD/"pdf-preview"
preview.mkdir(exist_ok=True)
index=[]
for name in (*sources,"MySQL_E-R图","架构图","关系概览","订单事务流程","日志导入流程","性能优化报告"):
    path=DOCS/(name+".pdf")
    document=fitz.open(path)
    for number,page in enumerate(document):
        png=preview/f"{name}-{number+1}.png"
        page.get_pixmap(matrix=fitz.Matrix(1.2,1.2)).save(png)
        index.append({"file":path.name,"page":number+1,"preview":str(png.relative_to(REPO)),"characters":len(page.get_text())})
(BUILD/"pdf-pages.json").write_text(json.dumps(index,ensure_ascii=False,indent=2),encoding="utf-8")
binary_files=sorted(path for path in DOCS.rglob("*") if path.is_file() and path.suffix.lower() in {".pdf",".png",".pptx"} and "历史素材" not in path.parts)
manifest={"version":VERSION,"scope":"正式PDF、图片和PPTX的实际文件字节。源码与文字源稿由Git记录。",
          "files":[{"path":str(path.relative_to(REPO)).replace("\\","/"),"bytes":path.stat().st_size,"sha256":hashlib.sha256(path.read_bytes()).hexdigest()} for path in binary_files]}
(DOCS/"测评证据/交付文件校验.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"version":VERSION,"pdfs":len(sources)+6,"pages":len(index),"git_gaps":facts["missing_first_eleven_calendar_days"]},ensure_ascii=False))
