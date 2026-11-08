-- 0.3.1增量：在已有sports_mall中新建导入暂存表。保留原五表与所有业务数据。
-- JDBC先批量写入本表并提交；之后同步MongoDB，成功后再标记SYNCED。
USE sports_mall;
CREATE TABLE IF NOT EXISTS log_import_rows (
    batch_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_no INT NOT NULL,
    imported_by BIGINT NOT NULL,
    payload JSON NOT NULL,
    sync_status ENUM('PENDING','SYNCED') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    synced_at DATETIME NULL,
    PRIMARY KEY (batch_id,row_no),
    KEY idx_log_import_actor (imported_by,created_at),
    CONSTRAINT fk_log_import_actor FOREIGN KEY (imported_by) REFERENCES users(user_id),
    CONSTRAINT chk_log_import_row CHECK (row_no BETWEEN 1 AND 200)
) ENGINE=InnoDB;
