package dev.primetech.ads.common;

public enum RejectReason {
    MISSING_FIELD,
    UNKNOWN_EVENT_TYPE,
    FIELD_TOO_LONG,
    INVALID_COUNTRY,
    EVENT_TIME_IN_FUTURE,
    EVENT_TIME_TOO_OLD,
    PRICE_ON_NON_BILLABLE,
    MISSING_PRICE,
    NEGATIVE_PRICE
}
