package org.stargazer1492.reactivestudy.reactor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.sse.RealEventSource;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * @author weijianglong
 * @since 2025/6/22
 */
@Slf4j
public class ReactorBasic {

    /**
     * 执行本函数之前，先启动ReactiveStudyApplication
     */
    public static Flux<String> requestWithOkhttp() {
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(90)).build();
        Request request = new Request.Builder().url("http://localhost:8080/async/reactive").build();

        // Flux.create会创建一个流，而包含在这个Consumer函数中的内容则是产生整个流的生产者逻辑
        // 每生成一个事件，我们通过sink.next方法将这个事件发送到这个流中。如果发生了异常，我们通过sink.error来向下游发送一个错误
        // 当所有的事件生成之后，我们必须调用sink.complete来向下游发送正常结束信号
        return Flux.create(fluxSink -> {
            log.info("before stream request sent");

            RealEventSource realEventSource = new RealEventSource(request,
                    new EventSourceListener() {

                        @Override
                        public void onEvent(EventSource eventSource, String id, String type, @NotNull String data) {
                            if ("[DONE]".equalsIgnoreCase(data)) {
                                fluxSink.complete();
                            } else {
                                fluxSink.next(data);
                            }
                        }

                        @Override
                        public void onFailure(EventSource eventSource, @Nullable Throwable t,
                                              @Nullable Response response) {
                            fluxSink.error(t);
                        }

                    });

            // 通过Flux.create创建的流如果收到cancel的信号，onCancel会被回调，然后在这里取消掉okhttp的网络请求
            fluxSink.onCancel(realEventSource::cancel);

            realEventSource.connect(client);
        });
    }

    public static void testStreamCreation() {
        requestWithOkhttp()
                // 我们将返回的原始事件转变为自定义的事件，但map是一对一进行转换
                .map(rawResult -> {
                    return new Query(rawResult);
                })
                // 将流经此API的事件，做一些操作之后转变为多个事件，属于一对多转换，多个值我们必须包在Flux中
                // 因为我们将Flux看做是多值流的包装器，将Mono看做单值流包装器
                .flatMap(query -> {
                    return queryCache(query);
                })
                // 触发整个流的执行
                .subscribe(queryResult -> {
                    log.info("query result: {}", queryResult);
                });
    }

    public static void testStreamFailure() {
        Flux.create(fluxSink -> fluxSink.error(new Exception("test")))
            // 副作用：打印个日志
            .doOnError(e -> {
                log.info("error type: {}", e.getClass().getSimpleName());
            })
            // 异常类型转换
            .onErrorMap(RuntimeException::new)
            // 再打印一次，看看类型是否发生了变化
            // 注意：这些API可以多次调用，调用的时机按照他们出现的顺序：我们始终不要忘记面向流编程这个思想，就知道这些API在什么位置出现，会出现什么样的效果了
            .doOnError(e -> {
                log.info("error type: {}", e.getClass().getSimpleName());
            })
            // 发生异常的时候，我们给一段兜底话术
            .onErrorResume(e -> Flux.just("everything is ok"))
            .subscribe(e -> log.info("output: {}", e));
    }

    public static void testStreamTrigger() {
        Flux.defer(() -> {
                // 这里的代码不会立即被执行，当你下了断点到log.info这一行的时候，你会发现程序在执行完subscribe之后才会跳到本断点
                log.info("hello reactive");
                return Flux.empty();
            })
            // 切换一下线程，以便观察subscribe非阻塞的特性
            .subscribeOn(Schedulers.boundedElastic())
            // 触发流的执行，如果已经分配了额外的线程池，那么执行完subscribe的主线程会继续向后执行其他代码，流的执行交给这个线程池
            .subscribe();
        log.info("you may see this message before 'hello reactive'");
    }

    public static void testSubscribe() {
        Flux.defer(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return Flux.error(e);
                }
                // 这条日志会与流外面的日志被同一个线程执行，因为没有设置额外的线程池
                log.info("job done");
                return Flux.empty();
            })
            .subscribe();
        log.info("after subscribe called");
    }

    public static void testConcurrentStream() {
        CountDownLatch latch = new CountDownLatch(1);

        Flux.defer(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return Flux.error(e);
                }
                log.info("job done");
                latch.countDown();
                return Flux.empty();
            })
            // 切换一下线程，以便观察subscribe非阻塞的特性
            .subscribeOn(Schedulers.boundedElastic())
            // 触发流的执行，如果已经分配了额外的线程池，那么执行完subscribe的主线程会继续向后执行其他代码，流的执行交给这个线程池
            .subscribe();
        log.info("after subscribe called");

        // 让主线程等待流执行完毕
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void testMultipleThreads() {
        log.info("---------- subscribeOn ----------");
        Flux.just(1, 2, 3)
            .doOnNext(e -> log.info("first doOnNext: {}", e))
            // 在哪里调用都影响从生成的API（just）到订阅API（subscribe）中所有的API
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(e -> log.info("second doOnNext: {}", e))
            .subscribe();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log.info("---------- publishOn ----------");
        Scheduler scheduler1 = Schedulers.boundedElastic();
        Scheduler scheduler2 = Schedulers.boundedElastic();

        Flux.just(1, 2, 3)
            .publishOn(scheduler1)
            // 我们会在日志中看到输出first doOnNext这行日志的线程名都是scheduler1中线程的名字
            .doOnNext(e -> log.info("first doOnNext: {}", e))
            .publishOn(scheduler2)
            // 我们会在日志中看到输出second doOnNext这行日志的线程名都是scheduler2中线程的名字
            .doOnNext(e -> log.info("second doOnNext: {}", e))
            .subscribe();

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void testMerge() {
        Flux<Integer> fastStream = Flux.range(1, 3).subscribeOn(Schedulers.boundedElastic());
        Flux<Integer> slowStream = Flux.defer(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            log.info("slow numbers: 10 ~ 12");
            return Flux.range(10, 3);
        }).subscribeOn(Schedulers.boundedElastic());
        Flux<Integer> fastStream2 = Flux.range(100, 3).subscribeOn(Schedulers.boundedElastic());

        log.info("---------- merge ----------");
        // slowStream的所有事件因为输出速度慢，必然在fastStream和fastStream2之后
        Flux.merge(fastStream, slowStream, fastStream2).subscribe(data -> log.info("data: {}", data));

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log.info("---------- mergeSequential ----------");
        // 无论slowStream有多慢，它所有产生的事件都一定在fastSteam之后，在fastStream2之前发出
        Flux.mergeSequential(fastStream, slowStream, fastStream2).subscribe(data -> log.info("data: {}", data));

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
//        testStreamCreation();
//        testStreamFailure();
//        testStreamTrigger();
        testSubscribe();
//        testConcurrentStream();
//        testMultipleThreads();
//        testMerge();
    }

    public static Flux<String> queryCache(Query query) {
        if ("1".equals(query.getKey())) {
            return Flux.just("data1", "data2");
        } else {
            return Flux.just("data3", "data4");
        }
    }

    @AllArgsConstructor
    @Getter
    public static final class Query {
        private String key;
    }

}
