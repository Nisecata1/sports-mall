package com.sportsmall.api;

import com.sportsmall.model.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiJsonTest {
    @Test void moneyAndLocalOrderTimestampRoundTripExactly() {
        OrderView input = new OrderView(9007199254740993L, 1, 1, "训练篮球", new BigDecimal("100.00"), 2,
                LocalDateTime.of(2026,11,6,12,30,0));
        OrderView actual = ApiJson.GSON.fromJson(ApiJson.GSON.toJson(input), OrderView.class);
        assertEquals(input, actual);
    }
    @Test void emptyAndPresentDetailsRoundTrip() {
        ItemView item = new ItemView(1, "训练篮球", new BigDecimal("100.00"), 1, "球类");
        for (var detail : List.of(Optional.<ItemDetail>empty(), Optional.of(new ItemDetail(1,"说明",List.of(),"zh-CN")))) {
            var input = new ApiDtos.BrowseTicket("ticket", item, detail);
            assertEquals(input, ApiJson.GSON.fromJson(ApiJson.GSON.toJson(input), ApiDtos.BrowseTicket.class));
        }
    }
    @Test void activityUtcTimestampAndNoActivityNullRoundTrip() {
        Instant time = Instant.parse("2026-11-06T06:00:00Z");
        assertEquals(time, ApiJson.GSON.fromJson(ApiJson.GSON.toJson(time), Instant.class));
        var error = new ApiDtos.ErrorResponse("LOGIN_REQUIRED", "请先登录");
        assertEquals(error, ApiJson.GSON.fromJson(ApiJson.GSON.toJson(error), ApiDtos.ErrorResponse.class));
    }
}
