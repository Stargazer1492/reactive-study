package org.stargazer1492.reactive_study.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

/**
 * @author weijianglong
 * @since 2025/6/16
 */
@RestController
@Slf4j
public class DemoController {

    @GetMapping(value = "/async/reactive", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> asyncReactive() {
        log.info("request start");
        return Flux.just(1, 2, 3)
                   .log()
                   .flatMap(i -> expensiveAction(String.valueOf(i)))
                   .doOnComplete(() -> log.info("request end"))
                   .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/async/callable")
    public Callable<String> asyncCallable() {
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
