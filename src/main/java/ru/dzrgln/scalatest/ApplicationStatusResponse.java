package ru.dzrgln.scalatest;

import java.time.Duration;

public sealed interface ApplicationStatusResponse {
    record Failure(Duration lastRequestTime, int retriesCount) implements ApplicationStatusResponse {}
    record Success(String id, String status) implements ApplicationStatusResponse {}
}