package com.sportsmall.model;

public record LogImportResult(String batchId, int total, int synced, int pending) {}
