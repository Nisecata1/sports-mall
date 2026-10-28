-- 商城五表目标结构，适用于新建数据库；Java 不会自动执行此文件。
-- IF NOT EXISTS 只创建缺失表，不会修改已经存在的表结构。
-- 已有商品表补单价须单独执行 mysql_migrate_001_item_price.sql，本文件不替代迁移。
CREATE DATABASE IF NOT EXISTS sports_mall CHARACTER SET utf8mb4;
USE sports_mall;
-- 用户字段来自项目要求书；必填、自增、自动时间、默认 USER 及状态范围为工程约定。
-- status：1 正常，0 停用。密码哈希由后续 Java 注册流程生成，不在此插入账号。
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NULL,
    role ENUM('ADMIN', 'USER') NOT NULL DEFAULT 'USER',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN (0, 1))
) ENGINE=InnoDB;

-- 一用户至多一份档案为工程约定：user_id 必填、唯一，并引用已存在的商城用户。
-- 暂未填写档案的用户在此表没有对应行；不是插入一行 user_id 为 NULL 的档案。
CREATE TABLE IF NOT EXISTS profiles (
    profile_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    real_name VARCHAR(50) NULL,
    id_card VARCHAR(20) NULL,
    address VARCHAR(500) NULL,
    notes TEXT NULL,
    CONSTRAINT uq_profiles_user UNIQUE (user_id),
    CONSTRAINT fk_profiles_user FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS categories (
    category_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    parent_id BIGINT NULL,
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories(category_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS items (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    category_id BIGINT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_items_category FOREIGN KEY (category_id) REFERENCES categories(category_id),
    CONSTRAINT chk_items_price CHECK (price > 0)
) ENGINE=InnoDB;

-- 每单一件商品；amount 由后续 Java 下单流程读取商品单价后保存，不由外键自动复制。
-- status：0 待处理，1 已完成，2 已取消；允许值和状态转换是不同规则。
CREATE TABLE IF NOT EXISTS orders (
    order_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_item FOREIGN KEY (item_id)
        REFERENCES items(item_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_orders_amount CHECK (amount > 0),
    CONSTRAINT chk_orders_status CHECK (status IN (0, 1, 2))
) ENGINE=InnoDB;
