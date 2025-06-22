package org.stargazer1492.reactivestudy.web;

import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.PostConstruct;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author weijianglong
 * @since 2025/6/5
 */
//@Configuration
public class SpringWebMvcConfigurer implements WebMvcConfigurer {

    private static ExecutorService springAsyncThreadPool = new ThreadPoolExecutor(
            10, 100, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
            r -> new Thread(r, "my-thread-pool-")
    );

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer asyncSupportConfigurer) {
        // 使用自定义的Rhino线程池，替换Spring默认的异步请求处理线程池
        asyncSupportConfigurer.setTaskExecutor(new ConcurrentTaskExecutor(springAsyncThreadPool));
    }

    @PostConstruct
    public void init() {
        startThreadPoolMonitor();
    }

    public void startThreadPoolMonitor() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "thread-pool-monitor");
            thread.setDaemon(true);
            return thread;
        });

        scheduler.scheduleAtFixedRate(() -> {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) springAsyncThreadPool;
            int activeCount = executor.getActiveCount();
            int poolSize = executor.getPoolSize();
            int corePoolSize = executor.getCorePoolSize();
            int maximumPoolSize = executor.getMaximumPoolSize();
            long completedTaskCount = executor.getCompletedTaskCount();
            long taskCount = executor.getTaskCount();
            int queueSize = executor.getQueue().size();

            System.out.println("线程池监控 - " +
                    "活跃线程数: " + activeCount +
                    ", 当前线程数: " + poolSize +
                    ", 核心线程数: " + corePoolSize +
                    ", 最大线程数: " + maximumPoolSize +
                    ", 已完成任务数: " + completedTaskCount +
                    ", 总任务数: " + taskCount +
                    ", 队列中等待任务数: " + queueSize);
        }, 0, 1000, TimeUnit.MILLISECONDS);

    }

}
