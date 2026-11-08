package com.sportsmall.service;

import com.sportsmall.exception.MallBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class LogImportParserTest {
    private static final String ROW = "{\"user_id\":42,\"timestamp\":\"2026-11-08T01:02:03Z\",\"level\":\"INFO\",\"message\":\"课程导入演示\",\"operation\":\"DEMO\"}";
    @Test void utf8BomEmptyLinesAndOptionalUserIdAreHandled() {
        var rows = LogImportParser.parse("\uFEFF" + ROW + "\r\n\n" + ROW.replace("42", "null"));
        assertEquals(2, rows.size()); assertEquals(42L, rows.get(0).userId()); assertNull(rows.get(1).userId());
        assertEquals(Instant.parse("2026-11-08T01:02:03Z"), rows.get(0).timestamp());
    }
    @Test void sameActorAndNormalizedContentProduceTheSameBatch() {
        var first = LogImportParser.parse(ROW);
        var spaced = LogImportParser.parse(" \n " + ROW.replace(",", ", ") + "\r\n");
        assertEquals(LogImportParser.batchId(1, first), LogImportParser.batchId(1, spaced));
        assertNotEquals(LogImportParser.batchId(1, first), LogImportParser.batchId(2, first));
        assertNotEquals(LogImportParser.batchId(1, first), LogImportParser.batchId(1, LogImportParser.parse(ROW.replace("DEMO", "OTHER"))));
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "9223372036854775808", "\"42\"", "true"})
    void badUserIdsRejectWholeImport(String bad) {
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse(ROW + "\n" + ROW.replace("42", bad)));
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "{bad}", "", " \n "})
    void incompleteOrMalformedFileCannotBeStaged(String text) {
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse(text));
    }
    @Test void typeAndSourceCannotBeInjectedIntoImportedAudit() {
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse(ROW.replace("{", "{\"log_type\":\"LOGIN\",")));
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse(ROW.replace("INFO", "DEBUG")));
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse(ROW.replace("2026-11-08T01:02:03Z", "2026-11-08")));
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse(ROW.replace("课程导入演示", "x".repeat(501))));
    }
    @Test void sizeAndRowLimitsAreEnforcedBeforeAnyWriter() {
        assertEquals(200, LogImportParser.parse((ROW + "\n").repeat(200)).size());
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse((ROW + "\n").repeat(201)));
        assertThrows(MallBusinessException.class, () -> LogImportParser.parse("中".repeat(22000)));
    }
    @Test void filterDatesUseBeijingDayBoundariesAndRejectReversedRanges() {
        var day = LocalDate.parse("2026-11-08");
        var filter = new com.sportsmall.model.SystemLogFilter(null,"IMPORT","ERROR",day,day);
        assertEquals(Instant.parse("2026-11-07T16:00:00Z"),filter.start());
        assertEquals(Instant.parse("2026-11-08T16:00:00Z"),filter.end());
        assertThrows(MallBusinessException.class, () -> new com.sportsmall.model.SystemLogFilter(0L,null,null,null,null));
        assertThrows(MallBusinessException.class, () -> new com.sportsmall.model.SystemLogFilter(null,"OTHER",null,null,null));
        assertThrows(MallBusinessException.class, () -> new com.sportsmall.model.SystemLogFilter(null,null,null,day,day.minusDays(1)));
    }
}
