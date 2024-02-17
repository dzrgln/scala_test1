package ru.dzrgln.scalatest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class HandlerImpl implements Handler {
    private final Client client;
    private final AtomicInteger attemptsNumber = new AtomicInteger();
    private final AtomicBoolean registeredMistake = new AtomicBoolean();
    private static final long TIMEOUT = 15000L;

    public HandlerImpl(Client client) {
        this.client = client;
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        long startTime = System.currentTimeMillis();
        var executorService = Executors.newFixedThreadPool(2);

        Callable<ApplicationStatusResponse> callable1 = () -> getResponse(startTime, () -> client.getApplicationStatus1(id));
        Callable<ApplicationStatusResponse> callable2 = () -> getResponse(startTime, () -> client.getApplicationStatus2(id));
        try {
            return executorService.invokeAny(List.of(callable1, callable2));
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return new ApplicationStatusResponse.Failure(null, attemptsNumber.get());
        } finally {
            executorService.shutdown();
        }
    }

    private ApplicationStatusResponse getResponse(long startTime, Supplier<Response> responseConsumer) throws InterruptedException {
        long timeRequest = 0L;
        var endTime = 0L;
        var failRegistered = false;

        while (System.currentTimeMillis() - startTime < TIMEOUT) {
            Response response = new Response.Failure(new Throwable());
            if (!failRegistered) {
                response = responseConsumer.get();
                attemptsNumber.incrementAndGet();
                timeRequest = System.currentTimeMillis();
            }
            if (response instanceof Response.Success successResponse) {
                return new ApplicationStatusResponse.Success(successResponse.applicationId(), successResponse.applicationStatus());
            } else if (response instanceof Response.RetryAfter retryAfterResponse) {
                var delay = retryAfterResponse.delay();
                Thread.sleep(delay.toMillis());
            } else {
                if (!failRegistered) {
                    endTime = System.currentTimeMillis();
                }
                failRegistered = true;
                var mistakeInAnotherService = registeredMistake.getAndSet(true);
                //Если ошибка уже есть в другом сервисе, то возвращаем ответ, иначе надеемся на успешное завершение работы второго сервиса
                if (mistakeInAnotherService) {
                    var between = Duration.between(Instant.ofEpochMilli(timeRequest), Instant.ofEpochMilli(endTime));
                    return new ApplicationStatusResponse.Failure(between, attemptsNumber.get());
                }
            }
        }
        var between = Duration.between(Instant.ofEpochMilli(timeRequest), Instant.ofEpochMilli(endTime));
        return new ApplicationStatusResponse.Failure(between, attemptsNumber.get());
    }
}
