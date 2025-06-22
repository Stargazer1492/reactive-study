package org.stargazer1492.reactivestudy.reactor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author weijianglong
 * @since 2025/6/20
 */
@Slf4j
public class AsyncRequestUsingReactive {

    public static Flux<String> sendRequest() {
        // 使用响应式编程，不需要将结果处理的对象（如Callback）传入，而是将返回的结果看作是一个“多值流”，然后直接返回
        // 这样做的好处显而易见：不需要将一个callback传来传去，代码执行的顺序与编写顺序一致
        Flux<String> stream = WebClient.builder()
                                       .baseUrl("http://localhost:8080/async/reactive")
                                       .build()
                                       .get()
                                       .retrieve()
                                       .bodyToFlux(String.class);

        // 当前线程立即返回，此时请求还没有发起
        log.info("请求尚未发起，但sendRequest已执行完毕");
        return stream;
    }

    public static void processResult() {
        AtomicBoolean completed = new AtomicBoolean(false);

        // 调用subscribe的时候，请求才会被真正发起
        sendRequest().subscribe(
                eachData -> {
                    // 在这里消费返回的每个结果
                    log.info("响应体: {}", eachData);
                },
                error -> {
                    log.error("请求失败", error);
                },
                () -> {
                    log.info("请求完成");
                    completed.set(true);
                }
        );

        // 即便subscribe方法也不会阻塞的，我们在这里等待直到接到complete信号
        while (!completed.get()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void main(String[] args) {
        processResult();
    }

}
