package com.sportsmall.model;

/** 导入暂存行的数据契约，供两个DAO交换；不包含数据库连接或SQL对象。 */
public record LogImportRow(int rowNo, LogImportEntry entry) {}
