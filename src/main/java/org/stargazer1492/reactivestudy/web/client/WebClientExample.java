package org.stargazer1492.reactivestudy.web.client;

import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.EventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.client.reactive.ReactorResourceFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.stargazer1492.reactivestudy.web.ThreadSafeCostMetric;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author stargazer
 * @since 2024/3/21
 */
@Slf4j
public class WebClientExample {

    private static final String BASE_URL = "http://localhost:8080";
    private static WebClient webClient = webClient();
    private static final int DEFAULT_QPS = 20;

    public static void testSegment(
            ThreadSafeCostMetric metric, int qps, int batchCounter, int responseDelaySeconds, CountDownLatch latch
    ) {
        for (int i = 0; i < qps; ++i) {
            String requestId = "request-" + batchCounter + "-" + (i + 1);
            ThreadSafeCostMetric.EachMetric eachMetric = metric.start(batchCounter);
            webClient.get()
                     .uri("/segment?requestId=" + requestId + "&mockDuration=" + responseDelaySeconds)
                     .accept(MediaType.APPLICATION_JSON)
                     .retrieve()
                     .bodyToMono(String.class)
                     .doFinally(signalType -> {
                         eachMetric.stop();
                         latch.countDown();
                     })
                     .subscribe();
        }
    }

    public static void testStream(
            ThreadSafeCostMetric metric, int qps, int batchCounter, int responseDelaySeconds, CountDownLatch latch
    ) {
        for (int i = 0; i < qps; ++i) {
            String requestId = "request-" + batchCounter + "-" + (i + 1);
            ThreadSafeCostMetric.EachMetric eachMetric = metric.start(batchCounter);
            AtomicBoolean isFirstEvent = new AtomicBoolean(true);
            webClient.get()
                     .uri("/stream?requestId=" + requestId + "&mockDuration=" + responseDelaySeconds)
                     .accept(MediaType.TEXT_EVENT_STREAM)
                     .retrieve()
                     .bodyToFlux(String.class)
                     .doFinally(signalType -> {
                         latch.countDown();
                     })
                     .subscribe(str -> {
                         if (isFirstEvent.getAndSet(false)) {
                             eachMetric.stop();
                         }
                     });
        }
    }

    public static WebClient webClient() {
        // 使用本机核心数
//        int threadCount = Math.max(1, SystemPropertyUtil.getInt("io.netty.eventLoopThreads", NettyRuntime
//        .availableProcessors() * 2));
        // 模拟目标服务器的核心数
        int threadCount = 4;
        NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(threadCount);

        // ioRatio取一个就行了
        int ioRatio = 0;
        for (EventExecutor eventExecutor : nioEventLoopGroup) {
            ioRatio = ((NioEventLoop) eventExecutor).getIoRatio();
            break;
        }
        log.info("loopResource configuration: thread count: {}, ioRatio: {}", threadCount, ioRatio);

        ReactorResourceFactory resourceFactory = new ReactorResourceFactory();
        resourceFactory.setUseGlobalResources(false);
        resourceFactory.setLoopResources(useNative -> nioEventLoopGroup);
        resourceFactory.setConnectionProvider(
                ConnectionProvider.builder("webclient-demo")
                                  .maxConnections(500)
                                  .maxIdleTime(Duration.ofMinutes(30))
                                  .build()
        );

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(resourceFactory, httpClient -> httpClient
                .responseTimeout(Duration.ofSeconds(90))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10 * 1000)
                .doOnConnected(connection -> {
                    connection.addHandlerLast(new ReadTimeoutHandler(90));
                    connection.addHandlerLast(new WriteTimeoutHandler(90));
                })
        );

        return WebClient.builder()
                        .baseUrl(BASE_URL)
                        .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                        .clientConnector(connector)
                        .build();
    }

    public static void mixedStat(int batchSize) {
        ThreadSafeCostMetric segmentMetric = new ThreadSafeCostMetric("Segment", batchSize * DEFAULT_QPS);
        ThreadSafeCostMetric streamMetric = new ThreadSafeCostMetric("Stream", batchSize * DEFAULT_QPS);
        CountDownLatch latch = new CountDownLatch(batchSize * DEFAULT_QPS * 2);

        // 确保1s发送一批，这样batchSize恰好就是时间
        AtomicInteger batchCounter = new AtomicInteger(1);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(400);
        scheduler.scheduleAtFixedRate(() -> {
            int batchCounterVal = batchCounter.getAndIncrement();
            if (batchCounterVal > batchSize) {
                scheduler.shutdownNow();
                return;
            }
            testSegment(segmentMetric, DEFAULT_QPS, batchCounterVal, 5, latch);
            testStream(streamMetric, DEFAULT_QPS, batchCounterVal, 5, latch);
            log.info("mix batch {} sent", batchCounterVal);
        }, 0, 1, TimeUnit.SECONDS);

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        segmentMetric.printToFile();
        streamMetric.printToFile();
    }

    public static void separateState(int batchSize) {
        ThreadSafeCostMetric segmentMetric = new ThreadSafeCostMetric("Segment", batchSize * DEFAULT_QPS);
        ThreadSafeCostMetric streamMetric = new ThreadSafeCostMetric("Stream", batchSize * DEFAULT_QPS);
        CountDownLatch latch = new CountDownLatch(batchSize * DEFAULT_QPS);

        AtomicInteger batchCounter = new AtomicInteger(1);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(400);
        scheduler.scheduleAtFixedRate(() -> {
            int batchCounterVal = batchCounter.getAndIncrement();
            if (batchCounterVal > batchSize) {
                scheduler.shutdownNow();
                return;
            }
            testSegment(segmentMetric, DEFAULT_QPS, batchCounterVal, 5, latch);
            log.info("segment batch {} sent", batchCounterVal);
        }, 0, 1, TimeUnit.SECONDS);

        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        CountDownLatch latch2 = new CountDownLatch(batchSize * DEFAULT_QPS);
        ScheduledExecutorService scheduler2 = Executors.newScheduledThreadPool(4);
        batchCounter.set(0);
        scheduler2.scheduleAtFixedRate(() -> {
            int batchCounterVal = batchCounter.getAndIncrement();
            if (batchCounterVal > batchSize) {
                scheduler2.shutdownNow();
                return;
            }
            testStream(streamMetric, DEFAULT_QPS, batchCounterVal, 5, latch2);
            log.info("stream batch {} sent", batchCounterVal);
        }, 0, 1, TimeUnit.SECONDS);

        try {
            latch2.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        segmentMetric.printToFile();
        streamMetric.printToFile();
    }

    public static void main(String[] args) {
//        separateState(20);
        mixedStat(20);
        System.exit(0);
    }

}
