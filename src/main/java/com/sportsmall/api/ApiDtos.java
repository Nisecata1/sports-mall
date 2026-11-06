package com.sportsmall.api;

import com.sportsmall.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** HTTP数据契约：只含业务输入输出，不携带数据库连接、口令哈希或服务对象。 */
public final class ApiDtos {
    private ApiDtos() {}
    public record LoginResponse(String token, AuthenticatedUser user) {}
    public record ErrorResponse(String code, String message) {}
    public record PhoneChange(UserAccount account, boolean changed) {}
    public record BrowseTicket(String browseId, ItemView item, Optional<ItemDetail> detail) {}
    public record RankedItem(HotItemStatistics statistics, Optional<ItemView> item) {}
    public record HotReport(Instant fromInclusive, Instant throughInclusive, List<RankedItem> items) {}
    public record UserActivityReport(Instant fromInclusive, Instant throughInclusive, UserActivityStatistics summary) {}
}
