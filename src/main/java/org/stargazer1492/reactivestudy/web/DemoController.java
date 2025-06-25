package org.stargazer1492.reactivestudy.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * @author stargazer
 * @since 2025/6/16
 */
@RestController
@Slf4j
public class DemoController {

    private static final String END_FLAT = "[DONE]";
    private static final Integer RESPONSE_DURATION_SECONDS = 5;

    @GetMapping(value = "/segment", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> mockSegment(
            @RequestParam String requestId, @RequestParam(required = false) Integer mockDuration
    ) {
        final int internalDuration = mockDuration == null ? RESPONSE_DURATION_SECONDS : mockDuration;
        log.info("segment request({}) will finish in {} seconds", requestId, internalDuration);
        return Mono.just("段式场景下只会返回这一句").delayElement(Duration.ofSeconds(internalDuration));
    }

    /**
     * @param requestId    每个请求分配一个唯一的id
     * @param mockDuration 期望该流式请求持续输出的耗时，默认RESPONSE_DURATION_SECONDS
     * @return
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> mockStream(
            @RequestParam String requestId, @RequestParam(required = false) Integer mockDuration
    ) {
        final int internalDuration = mockDuration == null ? RESPONSE_DURATION_SECONDS : mockDuration;

        log.info("stream request({}) will finish in {} seconds", requestId, internalDuration);

        return Flux.just("流式场景下这段话可能会重复很多次")
                   .repeatWhen(companion ->
                           companion.delayElements(Duration.ofSeconds(1)).take(internalDuration)
                                    .doOnNext(signal -> {
                                        log.info("signal: {}", signal);
                                    }))
                   .concatWithValues(END_FLAT);
    }

    @GetMapping(value = "/stream/slow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> mockSlowStream() {
        log.info("request start");
        return Flux.just(1, 2, 3)
                   .log()
                   .flatMap(i -> expensiveAction(String.valueOf(i)))
                   .concatWithValues(END_FLAT)
                   .doOnComplete(() -> log.info("request end"))
                   .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/callable")
    public Callable<String> mockCallable() {
        log.info("request start");
        return () -> {
            log.info("start to work");
            Thread.sleep(5000);
            log.info("job done");
            return "Hello, Async!";
        };
    }

    public Flux<String> expensiveAction(String data) {
        try {
            log.info("start to work");
            Thread.sleep(5000);
            log.info("job done");
            return Flux.just(data);
        } catch (InterruptedException e) {
            return Flux.error(e);
        }
    }

}
