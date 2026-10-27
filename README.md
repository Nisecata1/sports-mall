# 项目011 · 运动装备商城

这是老师指定的运动装备商城正式工程。后续仅在此 sports-mall 目录持续开发，已有数据库和源码继续使用。

本会话以《项目011_运动装备商城.docx》为主要验收依据。另一份总说明中的对比论文由用户安排的其他会话负责；项目要求书自身列出的需求、设计、性能、测试、用户手册、总结和答辩材料仍按本项目清单推进。

Gitee 仓库：[Nisegitee/sports-mall](https://gitee.com/Nisegitee/sports-mall)。2026-10-27 已上传首个版本 `1eed789`，默认开发分支为 `main`。

需求基线：[需求规格说明书 PDF](docs/需求规格说明书.pdf)。本地同名 Markdown 为编辑源稿；PDF 是老师规定的交付格式。需求文档描述目标功能，不表示这些功能均已实现。

## 本轮目标

已完成 Java 从现有 `sports_mall` 数据库读取商品与分类，并保存、上传首版 Git 版本。当前进入需求规格确认和完整数据设计阶段。现有程序仍仅查询实际数据，不创建表、不插入示例记录、不重置数据库。

这是正式工程的首版入口，不是最终提交版本。完整要求与当前进度见 [需求对照与进度](docs/需求对照与进度.md)。商品录入、页面、MongoDB 详情等均须继续实现；仅能读取一件商品不能算完整商品模块完成。

## 1. 当前正式工程目录

你已在课程文件夹中解压并成功运行。当前目录结构为：

```text
sports-mall
  pom.xml
  mvnw.cmd
  .mvn\wrapper\maven-wrapper.properties
  src\main\java\com\sportsmall\...
```

此 README 与 `pom.xml` 所在位置就是工程根目录。目录位置可自定义，先前的 C:\develop 只是示例；当前直接使用你已经解压的 sports-mall，不必搬迁或重新解压。

旧“运动装备商城”目录是此工程的原始副本；旧“边做边学_运动装备商城”中的独有网页草稿已保存在 `docs/历史素材/商品页面草稿.html`。草稿尚未接入后端，不计作已完成的业务界面，不影响当前程序；最终提交时只按要求选取成品。

老师的环境表列出 IntelliJ IDEA / Eclipse。现在先通过 PowerShell 确认工程能运行，随后在 IDE 中打开同一个 `pom.xml`、使用相同 JDK 与 Wrapper，不另建一份重复项目。

## 2. 检查工程自己的 Maven

保留 MySQL 服务窗口，在新的 PowerShell 7 窗口执行：

```powershell
# 在当前 sports-mall 工程根目录执行
.\mvnw.cmd -v
```

首次运行会获取工程固定的 Maven 3.9.16，以后复用缓存，不需要另行安装全局 `mvn`。

**原理：**Wrapper 固定构建工具版本；`pom.xml` 声明工程依赖；JDK 执行编译和运行。这一步不连接数据库。

**预期：**输出 Maven 3.9.16、Java 21 和 JDK 路径。

## 3. 编译、打包正式工程

```powershell
.\mvnw.cmd package
```

第一次会获取数据库驱动、HikariCP、日志组件等依赖。成功时显示 `BUILD SUCCESS`，并生成：

```text
target\sports-mall-0.1.0.jar
```

**原理：**将 Java 源码编译，并将运行所需的库打进可执行 JAR。构建成功说明程序已准备好，实际数据库连接在下一步验证。

## 4. 输入你自己的 MySQL 密码，读取真实商品

确认 MySQL 仍使用你已初始化的数据目录，并监听本机 18306。默认账号是你已经验证过的 `root`；密码不放入源码。

在刚才同一 PowerShell 中执行：

```powershell
$env:MALL_DB_PASSWORD = Read-Host "输入你自己设置的 MySQL 密码" -MaskInput
java -jar .\target\sports-mall-0.1.0.jar
```

输入时显示星号。密码只传给这个终端运行的程序，不写入项目文件或永久环境变量。关闭终端后，下次需重新输入。

**原理：**Java 从连接池取得 JDBC 连接，由 DAO 执行 JOIN 查询，显示数据库实际返回的商品与分类。

若数据库仍保留你前面录入的数据，预期类似：

```text
连接成功：MySQL 8.4.13 / sports_mall
商品编号 | 商品名称 | 分类编号 | 分类名称
1 | 训练篮球 | 1 | 球类
共读取 1 条商品。
```

这是预期示例，实际输出以你的数据库为准。2026-10-27 用户已成功实连 MySQL 并读到 1 条商品；在当前 PowerShell 对齐 UTF-8 编码后，连接提示、表头、商品及日志中文均已显示正常。

如果中文显示成“杩炴帴鎴愬姛”等乱码，先在同一个 PowerShell 中对齐终端编码再运行：

```powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
java -jar .\target\sports-mall-0.1.0.jar
```

这只改变当前终端的文本编码设置，不修改数据库或重新编译；如果换了终端，需要按前文重新输入密码。

```mermaid
flowchart LR
    A["你启动 Java 工程"] --> B["HikariCP 提供 JDBC 连接"]
    B --> C["ItemDao 执行查询"]
    C --> D["已有 sports_mall 数据库"]
    D --> E["实际记录返回终端"]
```

## 当前关键文件

| 文件 | 项目作用 |
|---|---|
| `pom.xml` | 固定依赖和构建设置 |
| `.mvn/wrapper/maven-wrapper.properties` | 固定 Maven 版本 |
| `src/main/resources/application.properties` | 数据库地址、账号和连接池配置 |
| `src/main/java/com/sportsmall/Main.java` | 当前入口 |
| `src/main/java/com/sportsmall/dao/ItemDao.java` | 从 MySQL 读取商品与分类 |
| `src/main/java/com/sportsmall/model/ItemView.java` | 承载一条查询结果 |
| `src/main/resources/sql/mysql_schema.sql` | 记录已完成表结构，后续逐步补齐；程序不会自动执行它 |
| `docs/需求规格说明书.pdf` | 老师要求的需求规格交付物，V0.1需求基线 |
| `docs/需求对照与进度.md` | 本地内部核对笔记，不替代正式需求文档 |

## 哪些属于课程工程，哪些只是辅助文件

“老师没有逐个指定文件名”不等于“文件多余”。课程要求的功能、配置、日志、异常处理和构建，需要由具体源码与配置文件实现。

| 当前文件／目录 | 与要求的关系 | 保留与提交方式 |
|---|---|---|
| `src/`、`pom.xml`、`README.md` | 要求书第六节工程结构中的源码、构建配置和说明；当前只有首版内容 | 保留，随最终源码提交；继续补齐功能和脚本 |
| `.gitignore` | 要求书第五节要求正确配置忽略规则 | 保留，随 Git 仓库提交 |
| `mvnw`、`mvnw.cmd`、`.mvn/wrapper/` | 本工程选用的 Maven Wrapper；老师要求 Maven，没有强制 Wrapper | 保留，随源码提供，便于别人用同一版本构建 |
| `docs/需求对照与进度.md` | 本会话添加的核对笔记，不是老师要求的“需求规格说明书” | 开发时保留，最终提交默认不含此笔记 |
| `docs/需求规格说明书.pdf` | 老师明确要求的需求规格说明书 | 随源码提交至 Gitee，最终按课程要求交付 |
| `docs/需求规格说明书.md` | PDF 的本地可编辑源稿 | 本轮提交只选 PDF；源稿在本地保留，不自动进入最终提交包 |
| `docs/历史素材/商品页面草稿.html` | 旧页面草稿，不是已完成界面，也不是规定交付物 | 当前仅归档，最终提交排除未采用草稿 |
| `target/` | Maven 自动生成的编译文件及 JAR | 可重新构建，已由 `.gitignore` 排除，不随源码提交；当前运行仍使用其中的可执行 JAR |

课程目录旁的旧“运动装备商城”“边做边学_运动装备商城”不属于当前活动工程。最终从唯一的 sports-mall 工程整理交付物，不把整个课程文件夹或旧目录一并压缩。

## 接下来按要求推进

1. 对应 Day1：Git/Gitee 首版已完成；审阅并提交 PDF 需求规格说明书。当前对照笔记不替代这份交付物。
2. 对应 Day2：补齐五张 MySQL 表的设计与 E-R 图，设计四个 MongoDB 集合；已有两张表保留，不重新初始化数据库。
3. 对应 Day3：补齐 MySQL DDL 与 MongoDB 初始化脚本，在设计明确后按步骤执行。
4. 继续真实业务功能：完善商品的录入、修改、删除和查询，再接入 MongoDB 详情、多条件查询与分页。此后按要求补其他模块、视图、存储过程、触发器、测试与项目文档。

要求书的 Day 是阶段标记，不在此推断实际截止日期；开发期间持续保留真实 Git 提交。IDE 使用 IntelliJ IDEA / Eclipse 打开当前工程，不另建项目。

## 实现约定与后续事项

- 商品必须有分类，分类可以没有上级；因此两处关联字段的可空性不同。
- 编号采用自增，状态暂约定 1 为在售；这些是本工程的实现选择。
- 首次只读连接沿用你已验证的本机账号；进入完整写入功能时完善应用专用账号与权限。
- 当前连接参数仅面向本机开发：关闭 TLS 并允许认证公钥获取。部署时需按部署环境另行配置。
- 用户已确认下一步增加商品单价 price，由程序读取单价并写入订单金额，旧订单金额不随商品改价变化。当前表结构和 Java 查询尚未实现该扩展，将在完整数据设计后手操迁移；不由此增加库存等新范围。
- 当前只声明了 JUnit 5 依赖，尚未完成正式测试用例与测试报告，不能以构建成功替代测试。
- Git/Gitee 已初始化并推送首版 `1eed789`。当前只有一个提交日期；后续持续保留真实提交，不伪造前 11 天记录。
- 代码使用 AI 辅助编写；用户亲手配置和运行。最终按老师要求标注大模型使用，保存真实开发、测试证据。

## 连接错误的处理

- `PASSWORD_NOT_SET`：在当前 PowerShell 输入密码。
- `CONNECTION_FAILED`：确认 MySQL 服务窗口仍运行、端口为 18306。
- `LOGIN_FAILED`：输入你后来设置的新密码，不是初始化时的旧临时密码。
- `DATABASE_NOT_FOUND` / `TABLE_NOT_FOUND`：核对是否连接到已有商城数据的实例，先检查，不重复初始化数据目录。

## 官方参考

- [Maven Wrapper](https://maven.apache.org/wrapper/)
- [MySQL Connector/J 的 Maven 配置](https://dev.mysql.com/doc/connector-j/en/connector-j-installing-maven.html)
- [HikariCP](https://github.com/brettwooldridge/HikariCP)

## 首版检查记录（2026-10-27）

- 在可访问的工具环境中，Wrapper 成功运行 Maven 3.9.16，使用 Temurin JDK 21.0.12.1。
- `mvnw.cmd package` 已成功，5 个 Java 源文件编译通过；可执行 JAR 的入口、JDBC 驱动、连接池、服务发现文件及配置已检查。
- 未设置数据库密码时，程序输出可读中文提示并以非零状态结束，没有尝试访问数据库。
- 工具环境未使用用户密码连接数据库。用户已在自己的终端完成构建、设置密码环境变量并运行，实际连接 MySQL 8.4.13 / sports_mall，读取 1 条商品；2026-10-27 14:32 的运行结果确认中文显示正常。
- 当前没有测试源文件，构建显示 `No tests to run`；不把构建成功表述为正式单元测试通过。
- 用户已完成首次提交并成功推送 Gitee 的 main 分支，仓库地址与上文一致；尚未进行从远程重新检出后的完整部署验收。
