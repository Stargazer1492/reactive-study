package org.stargazer1492.reactivestudy.reactor;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

/**
 * @author weijianglong
 * @since 2025/6/20
 */
@Slf4j
public class ImperativeAndDeclarative {

    public static void imperative() {
        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);

        // 命令式编程需要通过顺序、分支和循环等控制结构来实现需求细节
        int sum = 0;
        for (int num : numbers) {
            sum += num;
        }
        log.info("总和为: {}", sum);
    }

    public static void declarative() {
        Flux.just(1, 2, 3, 4, 5)
            // 声明式编程直接用合适的API“表达”需求
            .reduce(0, Integer::sum)
            .subscribe(result -> log.info("总和为: {}", result));
    }

    public static void main(String[] args) {
        imperative();
        declarative();
    }

}
