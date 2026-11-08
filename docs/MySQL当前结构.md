# MySQL当前结构（0.3.1）

原有五张业务表保留，新增log_import_rows作为导入暂存表。工程上级MySQL_E-R图.png仍是五张业务表的历史图，最终交付时依据此结构统一生成图片/PDF。

```mermaid
erDiagram
    users ||--o| profiles : "一位用户至多一份档案"
    users ||--o{ orders : "用户创建订单"
    categories o|--o{ categories : "可选上级分类"
    categories ||--o{ items : "分类包含商品"
    items ||--o{ orders : "订单购买商品"
    users ||--o{ log_import_rows : "管理员执行导入"
    users {
        bigint user_id PK
        varchar username UK
        varchar password_hash
        varchar email UK
        varchar phone
        enum role
        tinyint status
        datetime created_at
        datetime updated_at
    }
    profiles {
        bigint profile_id PK
        bigint user_id FK,UK
        varchar real_name
        varchar id_card
        varchar address
        text notes
    }
    categories {
        bigint category_id PK
        varchar name
        bigint parent_id FK
    }
    items {
        bigint item_id PK
        varchar title
        bigint category_id FK
        decimal price
        tinyint status
        datetime created_at
        datetime updated_at
    }
    orders {
        bigint order_id PK
        bigint user_id FK
        bigint item_id FK
        decimal amount
        tinyint status
        datetime created_at
    }
    log_import_rows {
        char batch_id PK
        int row_no PK
        bigint imported_by FK
        json payload
        enum sync_status
        datetime created_at
        datetime synced_at
    }
```

log_import_rows的主键是batch_id与row_no的组合，不能只用行号识别记录。imported_by引用的是导入操作者；payload.userId是文件里可选的日志主体引用，两者承担不同职责。当前角色为ADMIN由后端在事务中检查，外键本身只保证账号存在。

MySQL记录同步状态，MongoDB system_logs保存查询用的日志。两库通过批次编号、行号对照，没有跨库外键，也没有一个同时提交两库的JDBC事务。已有课程库需手动执行mysql_log_import.sql后才会出现第六张表。
