import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Presentation, PresentationFile } from '@oai/artifact-tool';

const repo = process.env.MALL_PROJECT;
const skill = process.env.MALL_PRESENTATION_SKILL;
const python = process.env.MALL_RUNTIME_PYTHON;
if (!repo || !skill || !python) throw new Error('Set MALL_PROJECT, MALL_PRESENTATION_SKILL and MALL_RUNTIME_PYTHON');
const { finalizePresentation, applyPresentationChartFont } = await import(pathToFileURL(path.join(skill, 'container_tools/artifact_tool_utils.mjs')).href);
const build = path.join(repo, 'target/deliverables-build');
const output = path.join(repo, 'output/presentation');
const preview = path.join(build, 'deck-preview');
await fs.mkdir(preview, { recursive: true });
await fs.mkdir(output, { recursive: true });
const facts = JSON.parse(await fs.readFile(path.join(repo, 'docs/测评证据/验证汇总.json'), 'utf8'));
const performance = JSON.parse(await fs.readFile(path.join(repo, 'target/performance/results.json'), 'utf8'));
const main = JSON.parse(await fs.readFile(path.join(repo, 'docs/测评证据/主库数据准备-first.json'), 'utf8'));
const FONT = 'Microsoft YaHei';
const INK = '#17372f', TEAL = '#176f60', PALE = '#e5efea', GRAY = '#60756d', WHITE = '#ffffff';
const presentation = Presentation.create({ slideSize: { width: 1280, height: 720 } });
let counter = 0;

function rect(slide, x, y, width, height, fill=PALE, border='none') {
  return slide.shapes.add({geometry:'rect', name:`surface-${++counter}`, position:{left:x,top:y,width,height}, fill, line:{fill:border,width:border==='none'?0:1,style:'solid'}});
}
function text(slide, value, x, y, width, height, size=26, color=INK, bold=false) {
  const shape = slide.shapes.add({geometry:'textbox',name:`text-${++counter}`,position:{left:x,top:y,width,height},fill:'none',line:{fill:'none',width:0}});
  shape.text=value;
  shape.text.style={typeface:FONT,fontSize:size,color,bold};
  shape.text.insets=0;
  return shape;
}
function line(slide, x1, y1, x2, y2) {
  const vertical = x1 === x2;
  rect(slide,Math.min(x1,x2),Math.min(y1,y2),vertical?2:Math.abs(x2-x1),vertical?Math.abs(y2-y1):2,TEAL);
}
function arrow(slide,x,y,vertical=false) {
  slide.shapes.add({geometry:vertical?'downArrow':'rightArrow',name:`arrow-${++counter}`,position:{left:x,top:y,width:vertical?16:28,height:vertical?28:16},fill:TEAL,line:{fill:'none',width:0}});
}
function node(slide,title,body,x,y,width=270,height=145) {
  rect(slide,x,y,width,height,WHITE,TEAL);
  rect(slide,x,y,width,42,PALE);
  text(slide,title,x+14,y+9,width-28,32,25,TEAL,true);
  text(slide,body,x+14,y+54,width-28,height-60,23);
}
function base(title,subtitle='',dark=false) {
  const slide=presentation.slides.add();
  slide.background.fill=dark?INK:'#fbfcfa';
  text(slide,title,48,43,1180,70,42,dark?WHITE:INK,true);
  if(subtitle)text(slide,subtitle,50,121,1175,58,23,dark?'#d4e9df':GRAY);
  text(slide,`${presentation.slides.items.length}`,1200,670,35,30,16,dark?'#d4e9df':GRAY);
  return slide;
}
function table(slide,values,x,y,width,height,columnWidths) {
  const obj=slide.tables.add({rows:values.length,columns:values[0].length,left:x,top:y,width,height,columnWidths,values});
  obj.borders.assign({fill:'#bfd0c7',width:1,style:'solid'});
  for(let row=0;row<values.length;row++)for(let col=0;col<values[0].length;col++){
    const cell=obj.getCell(row,col);
    cell.fill=row===0?PALE:WHITE;
    cell.text.style={typeface:FONT,fontSize:22,color:INK,bold:row===0};
    cell.text.insets={left:12,right:12,top:8,bottom:8};
  }
  return obj;
}
function notes(slide,body,source) {
  slide.speakerNotes.text=body+'\n\n资料来源\n'+source;
}

{
  const s=base('运动装备商城','项目011  数据库系统开发集训',true);
  text(s,'Java JDBC',54,238,800,70,52,WHITE,true);
  text(s,'MySQL关系型核心与MongoDB文档扩展',56,322,1100,62,35,'#d4e9df');
  rect(s,56,422,1110,2,'#6ba58a');
  text(s,'桌面前端   HTTP后端   双库数据访问',56,463,1120,58,29,WHITE);
  text(s,`软件 ${facts.version}    交付日期 2026-11-09`,56,562,1120,45,24,'#c1d9cc');
  notes(s,'介绍商城的业务入口和两种数据库的分工。项目通过需求确认、AI辅助实现、运行和GUI/数据库核对逐步推进，操作证据与自动检查分别记录。','项目011_运动装备商城.docx；docs/项目总结报告.md');
}
{
  const s=base('业务范围与主库数据','账号、分类、商品、订单、档案，以及评论、浏览和系统日志');
  s.images.add({blob:new Uint8Array(await fs.readFile(path.join(repo,'docs/images/主库数量截图.png'))),contentType:'image/png',fit:'contain',alt:'用户提供的DbGate六表数量截图',position:{left:42,top:195,width:625,height:430}});
  const values=[['MySQL表','记录数'],...['users','profiles','categories','items','orders','log_import_rows'].map(n=>[n,String(main.after['mysql.'+n])])];
  table(s,values,710,195,510,350,[330,180]);
  text(s,'MongoDB  详情22   评论42\n浏览103   系统日志152',718,574,500,72,25);
  notes(s,'左侧为用户在2026-11-09提供的实际查询截图。右侧数量来自主库准备回执。演示账号和商品有专门命名与内容标记，统计会包含符合条件的模拟行为。','docs/images/主库数量截图.png；docs/测评证据/主库数据准备-first.json');
}
{
  const s=base('前端、后端与数据库','沿一次请求区分界面输入、业务检查与实际保存');
  node(s,'前端进程','MallDesktop\nMallApiClient\n表单、请求、结果',50,250,290,205);
  node(s,'后端进程','MallApiServer\nService 与 DAO\n身份、规则、数据访问',420,250,350,205);
  node(s,'MySQL','账号、订单、约束\nJDBC与连接池',900,205,310,150);
  node(s,'MongoDB','详情、评论、日志\n文档与聚合',900,420,310,150);
  arrow(s,367,341);text(s,'HTTP',349,298,80,35,20,TEAL);
  line(s,770,310,850,310);arrow(s,854,303);
  line(s,808,310,808,495);line(s,808,495,855,495);arrow(s,855,488);
  text(s,'数据库连接配置由后端持有，DbGate供操作者直接查询数据库。',52,592,1140,52,25,GRAY);
  notes(s,'从窗口点击创建订单开始说明。前端收集商品与确认价，HTTP入口确定登录身份，Service校验业务，DAO管理连接和SQL。数据库执行约束后，结果按调用链返回窗口。','src/main/java/com/sportsmall/server/MallServices.java；docs/技术设计文档.md');
}
{
  const s=base('MySQL六表关系','主键识别记录，外键与唯一约束表达关联和数量限制');
  line(s,195,325,195,412);arrow(s,188,412,true);
  line(s,740,262,805,262);arrow(s,805,255);
  line(s,1000,325,1000,412);arrow(s,993,412,true);
  line(s,335,300,380,300);line(s,380,300,380,500);line(s,380,500,408,500);arrow(s,408,493);
  line(s,195,350,800,350);line(s,800,350,800,500);line(s,800,500,805,500);arrow(s,805,493);
  node(s,'users','user_id PK\n角色、状态、密码哈希',50,200,285,125);
  node(s,'profiles','user_id FK 与 UK\n每用户至多一份档案',50,440,285,125);
  node(s,'categories','category_id PK\nparent_id自引用',438,200,302,125);
  node(s,'items','item_id PK, category_id FK\nprice当前单价',840,200,340,125);
  node(s,'log_import_rows','imported_by FK\n主键 batch_id + row_no',438,440,302,125);
  node(s,'orders','user_id FK, item_id FK\namount成交快照',840,440,340,125);
  text(s,'1 对 0..1',215,363,150,35,22,TEAL);
  text(s,'1 对 多',1007,367,140,35,22,TEAL);
  text(s,'分类上级可为空，订单历史字段由触发器保护。',52,602,1130,45,25,GRAY);
  notes(s,'说明档案唯一关系和商品分类引用，再讲订单保存金额快照。导入暂存表的两列共同构成主键，imported_by关联操作者，当前ADMIN资格由后端在事务里校验。','src/main/resources/sql/mysql_schema.sql；mysql_log_import.sql；docs/MySQL_E-R图.pdf');
}
{
  const s=base('MongoDB文档模型','数组与嵌套对象围绕读取和增长方式组织');
  const columns=[['item_details','item_id\ndescription\nimages [ ]\nmetadata { language }'],['comments','user_id, item_id\ncontent, rating\ntags [ ]\ncreated_at'],['action_logs','user_id, item_id\naction_type\nduration_seconds\nclient_info { }\ncreated_at'],['system_logs','user_id, log_type\nlog_level, message\naction_detail { }\ntimestamp']];
  columns.forEach(([name,body],i)=>{text(s,name,48+i*308,218,292,50,29,TEAL,true);rect(s,48+i*308,276,280,2,TEAL);text(s,body,48+i*308,307,280,230,25);});
  text(s,'引用编号使用BSON Long，业务时间使用Date。详情按item_id唯一。',50,589,1160,60,26,GRAY);
  notes(s,'商品说明和图片一起读取，嵌入一份详情。评论和浏览持续增加，组织为独立文档。跨库查询先取得有效SQL编号，再读Mongo。导入系统日志的_id为稳定字符串，普通日志可使用ObjectId。','docs/MongoDB集合设计.md；src/main/java/com/sportsmall/dao/mongo');
}
{
  const s=base('订单创建与事务','以一笔订单的身份、价格与状态规则组织操作');
  const steps=[['前端','商品编号\n用户确认价'],['后端入口','会话身份\n请求校验'],['DAO事务','锁定用户商品\n取价与插入'],['MySQL','插入触发器\n状态与金额'],['DAO提交','读回新订单\ncommit返回']];
  steps.forEach(([title,body],i)=>{node(s,title,body,44+i*245,260,210,155);if(i<4)arrow(s,258+i*245,330);});
  text(s,'执行失败时回滚本次MySQL事务，提交确认异常时先查实际订单。',50,473,1170,68,29);
  text(s,'商品改价后，已保存订单继续使用成交金额。',50,578,1170,47,28,TEAL);
  notes(s,'请求发送itemId和quotedPrice。事务在当前连接里校验账号、商品状态与价格，插入触发器再检查初始状态和金额。数据库成功提交后，窗口显示订单。成功请求的审计另行写Mongo，技术文档记录相应故障边界。','OrderService、OrderDao、JdbcTransactions；mysql_triggers.sql');
}
{
  const s=base('JDBC日志导入与跨库重试','一批日志通过暂存状态和稳定编号继续确认');
  const steps=[['前端上传','UTF-8 JSONL\n选择与确认'],['后端校验','当前管理员\n整文件规范化'],['MySQL暂存','JDBC分组批处理\n提交PENDING'],['Mongo同步','稳定_id\nsetOnInsert'],['MySQL确认','确认成功行\n标记SYNCED']];
  steps.forEach(([title,body],i)=>{node(s,title,body,44+i*245,235,210,170);if(i<4)arrow(s,258+i*245,315);});
  text(s,'每50行执行批处理，一批最多200行。',50,464,1150,48,29);
  text(s,'中断后保留PENDING，同一管理员提交原文件继续补齐与确认。',50,540,1150,80,29,TEAL);
  notes(s,'先说明老师要求JDBC日志批处理以及Mongo日志存储。工程增加暂存表衔接两项要求。每批各执行组属于同一个MySQL事务，之后独立同步Mongo，再更新确认状态。部分成功与确认失败均有临时实例验证。','AdminLogService、LogImportDao、SystemLogDao；docs/日志管理与JDBC批量导入实操.md');
}
{
  const s=base('查询与聚合','SQL组织关系查询，MongoDB管道汇总文档');
  const rows=[['商品组合查询','名称、直接分类、价格共同筛选，排序后分页'],['评论统计','按商品匹配，再分组计算条数、均分与评分分布'],['近7天热门','按时间匹配VIEW，按商品分组并排序取前若干项'],['本人活动','按用户和时间分组，计算次数、时长与去重集合'],['月度订单','存储过程按创建月份统计查询时的订单状态']];
  table(s,[['业务','查询口径'],...rows],48,205,1170,370,[280,890]);
  text(s,'热门结果产生商品编号后，后端再读取MySQL中的名称。',52,607,1160,45,26,GRAY);
  notes(s,'选择评论统计或热门排行说明match和group的输入输出。热门排序优先比较次数，再比较累计时长和商品编号。月报计算当月创建、查询时已完成的金额，金额来自订单快照。','CommentDao、ActionLogDao；mysql_procedures.sql；docs/技术设计文档.md');
}
{
  const s=base('查询索引实测','预热3次、采样21次，比较相同有序结果的客户端耗时');
  const cases=performance.mysql_cases.map(c=>({name:c.case,values:[c.before.median_ms,c.after.median_ms]}));
  cases.push({name:'Mongo本人评论',values:[performance.mongo_case.scan.median_ms,performance.mongo_case.indexed.median_ms]});
  cases.forEach((entry,i)=>{
    const chart=s.charts.add('bar',{position:{left:45+i*408,top:215,width:377,height:340},title:entry.name,titleTextStyle:{fontSize:23},categories:['基线','索引'],series:[{name:'中位数ms',values:entry.values.map(v=>Number(v.toFixed(3))),fill:TEAL}],hasLegend:false,
      barOptions:{direction:'column',grouping:'clustered',gapWidth:120},xAxis:{textStyle:{fontSize:20}},yAxis:{min:0,numberFormatCode:'0.0',textStyle:{fontSize:16},majorGridlines:{fill:'#d7e2db',width:1}},dataLabels:{showValue:true,position:'outEnd',numberFormatCode:'0.000',textStyle:{fontSize:19}}});
    applyPresentationChartFont(chart,{fontFamily:FONT});
  });
  text(s,'各图独立刻度，单位ms。订单候选行1000到24，Mongo文档60040到10。',48,590,1180,56,25,TEAL);
  text(s,'单机热缓存样本，空间成本、写入吞吐与更大并发规模留作后续测评。',48,641,1180,37,21,GRAY);
  notes(s,'指出所有对照返回相同结果后，再讨论扫描量和时间。商品查询仍有Sort。Mongo复用已有索引对照强制扫描。样本包括30000商品、60000订单和60000评论，位于独立临时实例。','docs/性能优化报告.pdf；docs/测评证据/2026-11-08性能原始结果.json');
}
{
  const s=base('测试与故障恢复','342项JUnit通过，427项临时HTTP和真实双库检查通过');
  table(s,[['场景','检查结果'],['并发订单处理','一次成功转换，另一个请求收到状态冲突'],['JDBC批处理中途失败','整批暂存行回滚'],['Mongo部分成功','保留成功行，同批次重试补齐'],['SQL确认标记失败','回执保持PENDING，重试确认已有Mongo行'],['演示工具重复执行','既有记录指纹保持，第二次数量保持']],50,210,1165,350,[365,800]);
  text(s,'故障触发器与校验规则安装在独立临时数据库内。',52,600,1150,50,27,TEAL);
  notes(s,'区分单元测试、真实HTTP/数据库检查和用户主库操作。这里列出特定故障和竞态的实际结果。测试脚本记录端口与物理数据目录，结束后清理自己的实例数据，日志保留。','docs/测试报告.pdf；docs/测评证据/验证汇总.json');
}
{
  const s=base('系统演示顺序','围绕一件商品观察界面、请求、记录和统计');
  const steps=[['1','查看分类与商品','演示分类、价格筛选和分页'],['2','浏览详情并评论','观察Mongo详情、评论与VIEW记录'],['3','创建并处理订单','查看成交金额、状态和月报'],['4','查看日志与导入','核对IMPORT回执和两库状态']];
  steps.forEach(([num,title,body],i)=>{const y=211+i*104;text(s,num,54,y,60,68,42,TEAL,true);text(s,title,145,y,420,50,31,INK,true);text(s,body,600,y+5,620,60,27,GRAY);if(i<3)rect(s,146,y+78,1040,1,'#d7e2db');});
  notes(s,'使用本人已有商城账号登录，再使用管理员账号展示管理入口。演示数据有课程标记。每个动作可对应用户手册中的操作、技术设计中的路径和DbGate中的实际记录。','docs/用户手册.pdf；docs/交付索引.md');
}
{
  const s=base('交付与验收记录','源码、设计、测试与运行材料按正式清单组织');
  text(s,'已整理的交付',52,220,520,58,34,TEAL,true);
  text(s,'六表关系图与四集合设计\n需求、技术文档与用户手册\n性能、测试与项目总结\n答辩稿与仓库复现步骤',52,302,570,255,29);
  text(s,'当前验收事项',715,220,500,58,34,TEAL,true);
  text(s,'日志暂存方案为工程实现选择\n首11个自然日缺10月29日与11月5日提交\nGit基线34次，保留真实日期\nPowerPoint放映与教师验收待记录',715,302,510,270,27);
  text(s,'Gitee与GitHub同步保存工程和材料。',52,615,1160,48,27,GRAY);
  notes(s,'结合项目总结说明学习过程中的需求确认、AI辅助实现、运行和核对。Git频率以首次提交2026-10-27为起点、按北京时间核对，差距如实记录。日志暂存是衔接两项原文要求的工程选择。','docs/项目总结报告.pdf；docs/需求规格说明书.pdf；docs/测评证据/验证汇总.json');
}

const candidate = path.join(build,'candidate.pptx');
await (await PresentationFile.exportPptx(presentation)).save(candidate);
const revision = Date.now();
const finalPath = path.join(output,`项目答辩-${revision}.pptx`);
await finalizePresentation({workspaceDir:repo,candidatePath:candidate,finalPath,pythonExecutable:python,
  integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
  layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit',
    '--require-native-table-slide','2','--require-native-table-slide','8','--require-native-table-slide','10'],
  explicitTotalSlideCount:12,requiredNativeTableOwnerSlides:[2,8,10],requiredNativeChartOwnerSlides:[9],
  materializeLiteralChartWorkbooks:true,fontPolicy:{basis:'design',families:[FONT]},verifyArtifactToolImport:true,
  receiptPath:path.join(build,`presentation-${revision}.validation.json`)});
for (const [index,slide] of presentation.slides.items.entries()) {
  const blob=await presentation.export({slide,format:'png',scale:1});
  await fs.writeFile(path.join(preview,`slide-${String(index+1).padStart(2,'0')}.png`),new Uint8Array(await blob.arrayBuffer()));
}
await fs.copyFile(finalPath,path.join(repo,'docs/项目答辩.pptx'));
console.log(JSON.stringify({slides:12,output:finalPath,preview}));
