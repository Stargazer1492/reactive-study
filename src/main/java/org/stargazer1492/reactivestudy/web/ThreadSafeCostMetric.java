package org.stargazer1492.reactivestudy.web;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 替代StopWatch，实现一个可批量统计，线程安全的计时器
 *
 * @author stargazer
 * @since 2025/6/24
 */
@Slf4j
public class ThreadSafeCostMetric {

    @Setter
    @Getter
    private String metricName;
    @Setter
    @Getter
    private long requestCount;
    private final ConcurrentLinkedDeque<EachMetric> metrics = new ConcurrentLinkedDeque<>();

    public ThreadSafeCostMetric(String metricName, long requestCount) {
        this.metricName = metricName;
        this.requestCount = requestCount;
    }

    public EachMetric start(int batchCounter) {
        return new EachMetric(batchCounter, this);
    }

    public void printToConsole() {
        if (metrics.isEmpty()) {
            log.info("{} 统计结果为空，因为没有任何计时操作", metricName);
        }
        long totalCost = metrics.stream().mapToLong(EachMetric::getCost).sum();
        long maxCost = metrics.stream().mapToLong(EachMetric::getCost).max().orElse(0L);
        double averageCost = totalCost / (double) requestCount;
        long lastCost = metrics.isEmpty() ? -1 : metrics.peekLast().getCost();
        log.info("""
                        ----- {} 统计结果开始 -----
                        总请求数：{}
                        总计时数：{}
                        总耗时：{}ms
                        平均耗时：{}ms
                        最大耗时：{}ms
                        最后一个计时耗时：{}ms
                        ----- {} 统计结果结束 -----
                        """,
                metricName,
                requestCount,
                metrics.size(),
                totalCost,
                averageCost,
                maxCost,
                lastCost,
                metricName
        );
    }

    public void printToFile() {
        try {
            // 创建 report 目录
            Path reportDir = Paths.get("report");
            if (!Files.exists(reportDir)) {
                Files.createDirectories(reportDir);
                log.info("创建目录: {}", reportDir.toAbsolutePath());
            }
            
            // 构建文件路径
            // 获取当前时间，yyyyMMddHHmmss格式
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Path filePath = reportDir.resolve(metricName + "_" + timestamp + ".csv");

            // 将metrics复制到一个新数组，再按照batchCounter从小到大排序
            EachMetric[] sortedMetrics = metrics.toArray(new EachMetric[0]);
            Arrays.sort(sortedMetrics, Comparator.comparingInt(EachMetric::getBatchCounter));
            
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write("category,cost(ms),batch\n");
                for (EachMetric metric : sortedMetrics) {
                    writer.write("%s,%s,%s\n".formatted(metricName, metric.getCost(), metric.getBatchCounter()));
                }
                log.info("统计结果已写入文件: {}", filePath.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("写入文件失败", e);
        }
    }

    private void add(EachMetric eachMetric) {
        metrics.add(eachMetric);
    }

    @Data
    public static final class EachMetric {

        private int batchCounter;
        private long cost;
        private final ThreadSafeCostMetric threadSafeCostMetric;

        public EachMetric(int batchCounter, ThreadSafeCostMetric threadSafeCostMetric) {
            this.batchCounter = batchCounter;
            this.threadSafeCostMetric = threadSafeCostMetric;
            this.cost = System.currentTimeMillis();
        }

        public void stop() {
            this.cost = System.currentTimeMillis() - this.cost;
            threadSafeCostMetric.add(this);
        }

    }

}
