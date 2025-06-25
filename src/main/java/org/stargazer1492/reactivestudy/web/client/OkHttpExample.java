package org.stargazer1492.reactivestudy.web.client;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.sse.RealEventSource;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.stargazer1492.reactivestudy.web.ThreadSafeCostMetric;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author stargazer
 * @since 2024/3/15
 */
@Slf4j
public class OkHttpExample {

    private static final String BASE_URI = "http://localhost:8080";
    private static final int DEFAULT_QPS = 20;
    private static final ThreadPoolExecutor DEFAULT_THREAD_POOL = new ThreadPoolExecutor(
            600, 2000, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger();

                @Override
                public Thread newThread(@NotNull Runnable r) {
                    return new Thread(r, "MyThread-" + counter.incrementAndGet());
                }
            }
    );
    private static OkHttpClient okHttpClient = client();


    public static void testSegment(
            ThreadSafeCostMetric metric, int qps, int batchCounter, int responseDelaySeconds, CountDownLatch latch
    ) {
        for (int i = 0; i < qps; ++i) {
            String requestId = "request-" + batchCounter + "-" + (i + 1);

            Request request = new Request.Builder()
                    .url(BASE_URI + "/segment?requestId=" + requestId + "&mockDuration=" + responseDelaySeconds)
                    .header("content-type", "application/json")
                    .get()
                    .build();

            ThreadSafeCostMetric.EachMetric eachMetric = metric.start(batchCounter);
            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.error("request failed with exception", e);
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    eachMetric.stop();

                    try (ResponseBody responseBody = response.body()) {
                        if (response.isSuccessful() && responseBody != null) {
                            // 处理响应体
                            String responseData = responseBody.string();
                        } else {
                            log.error("request failed with code: {}", response.code());
                        }
                    } catch (IOException e) {
                        log.error("failed to process response", e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
    }

    public static void testStream(
            ThreadSafeCostMetric metric, int qps, int batchCounter, int responseDelaySeconds, CountDownLatch latch
    ) {
        for (int i = 0; i < qps; ++i) {
            String requestId = "request-" + batchCounter + "-" + (i + 1);

            Request request = new Request.Builder()
                    .url(BASE_URI + "/stream?requestId=" + requestId + "&delayInSeconds=" + responseDelaySeconds)
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .get()
                    .build();

            ThreadSafeCostMetric.EachMetric eachMetric = metric.start(batchCounter);
            AtomicBoolean isFirstEvent = new AtomicBoolean(true);
            RealEventSource realEventSource = new RealEventSource(request, new EventSourceListener() {

                @Override
                public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
                }

                @Override
                public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type,
                                    @NotNull String data) {
                    if (isFirstEvent.get()) {
                        isFirstEvent.set(false);
                        eachMetric.stop();
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t,
                                      @Nullable Response response) {
                    log.error("request failed with exception", t);
                    latch.countDown();
                }
            });
            realEventSource.connect(okHttpClient);
        }
    }

    private static void printThreadPoolMetric() {
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            log.info("thread pool info: threads(active/max): {}/{}, waitingTasks: {}, completedTasks: {}",
                    DEFAULT_THREAD_POOL.getActiveCount(),
                    DEFAULT_THREAD_POOL.getMaximumPoolSize(),
                    DEFAULT_THREAD_POOL.getQueue().size(),
                    DEFAULT_THREAD_POOL.getCompletedTaskCount()
            );
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static OkHttpClient client() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        ConnectionPool connectionPool = new ConnectionPool(10, 120, TimeUnit.SECONDS);
        builder.connectionPool(connectionPool);
        builder.connectTimeout(Duration.ofMinutes(5));
        builder.readTimeout(Duration.ofMinutes(5));
        builder.writeTimeout(Duration.ofMinutes(5));

        Dispatcher dispatcher = new Dispatcher(DEFAULT_THREAD_POOL);
        dispatcher.setMaxRequests(1000);
        dispatcher.setMaxRequestsPerHost(1000);
        builder.dispatcher(dispatcher);
        builder.retryOnConnectionFailure(true);
        return builder.build();
    }

    public static void mixedStat(int batchSize) {
        ThreadSafeCostMetric segmentMetric = new ThreadSafeCostMetric("Segment", batchSize * DEFAULT_QPS);
        ThreadSafeCostMetric streamMetric = new ThreadSafeCostMetric("Stream", batchSize * DEFAULT_QPS);
        CountDownLatch latch = new CountDownLatch(batchSize * DEFAULT_QPS * 2);

        // 确保1s发送一批，这样batchSize恰好就是时间
        AtomicInteger batchCounter = new AtomicInteger(1);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        scheduler.scheduleAtFixedRate(() -> {
            int batchCounterVal = batchCounter.getAndIncrement();
            if (batchCounterVal > batchSize) {
                scheduler.shutdownNow();
                return;
            }
            testSegment(segmentMetric, DEFAULT_QPS, batchCounterVal, 5, latch);
            testStream(streamMetric, DEFAULT_QPS, batchCounterVal, 5, latch);
        }, 0, 1, TimeUnit.SECONDS);

        printThreadPoolMetric();
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
//        segmentMetric.printToConsole();
//        streamMetric.printToConsole();
        segmentMetric.printToFile();
        streamMetric.printToFile();
    }

    public static void separateState(int batchSize) {
        ThreadSafeCostMetric segmentMetric = new ThreadSafeCostMetric("Segment", batchSize * DEFAULT_QPS);
        ThreadSafeCostMetric streamMetric = new ThreadSafeCostMetric("Stream", batchSize * DEFAULT_QPS);
        CountDownLatch latch1 = new CountDownLatch(batchSize * DEFAULT_QPS);

        printThreadPoolMetric();

        ScheduledExecutorService scheduler1 = Executors.newScheduledThreadPool(4);
        AtomicInteger batchCounter = new AtomicInteger(1);
        scheduler1.scheduleAtFixedRate(() -> {
            int batchCounterVal = batchCounter.getAndIncrement();
            if (batchCounterVal > batchSize) {
                scheduler1.shutdownNow();
                return;
            }
            testSegment(segmentMetric, DEFAULT_QPS, batchCounterVal, 5, latch1);
        }, 0, 1, TimeUnit.SECONDS);

        try {
            latch1.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        CountDownLatch latch2 = new CountDownLatch(batchSize * DEFAULT_QPS);
        ScheduledExecutorService scheduler2 = Executors.newScheduledThreadPool(4);
        batchCounter.set(1);
        scheduler2.scheduleAtFixedRate(() -> {
            int batchCounterVal = batchCounter.getAndIncrement();
            if (batchCounterVal > batchSize) {
                scheduler2.shutdownNow();
                return;
            }
            testStream(streamMetric, DEFAULT_QPS, batchCounterVal, 5, latch2);
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
//        mixedStat(20);
        separateState(20);
        System.exit(0);
    }

}
