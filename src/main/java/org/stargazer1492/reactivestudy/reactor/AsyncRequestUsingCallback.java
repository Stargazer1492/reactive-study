package org.stargazer1492.reactivestudy.reactor;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;

/**
 * @author weijianglong
 * @since 2025/6/20
 */
@Slf4j
public class AsyncRequestUsingCallback {

    public static void sendRequest(Callback callback) {
        Request request = new Request.Builder()
                .url("http://localhost:8080/async/reactive")
                .build();

        new OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .newCall(request)
                // 主线程发起请求后继续往后执行，结果的接收与处理交给okhttp的内置线程池，即callback中的逻辑预期会在另一个线程中执行
                // 可通过观察控制台日志中的线程名来进行判断
                .enqueue(callback);

        log.info("请求已发送，主线程返回");
    }

    public static void processResult() {
        // 定义响应逻辑，在真正的业务系统中，回调的逻辑可能需要从该方法的入口中传入
        Callback callback = new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // 请求失败时的处理逻辑
                log.error("请求失败", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // 请求成功时的处理逻辑
                if (response.isSuccessful()) {
                    log.info("响应体: {}", response.body().string());
                } else {
                    log.error("响应失败，状态码: {}", response.code());
                }
            }
        };

        sendRequest(callback);
    }

    public static void main(String[] args) {
        processResult();
    }

}
