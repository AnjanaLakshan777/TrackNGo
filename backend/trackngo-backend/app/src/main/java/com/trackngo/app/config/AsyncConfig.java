package com.trackngo.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Turns on {@code @Async} and makes sure work that runs off the request thread
 * cannot fail silently.
 *
 * <h2>What runs asynchronously, and why</h2>
 *
 * <p>Work that a caller does not need the result of should not be on the thread
 * the caller is waiting on. The clearest case is the driver app publishing a GPS
 * fix: the passenger's map is fed from memory and from the WebSocket broadcast,
 * so the database write that keeps the AI assistant's view current has no reason
 * to sit in front of the driver's HTTP response.
 *
 * <h2>The executor</h2>
 *
 * <p>Deliberately not defined here. Spring Boot's own {@code applicationTaskExecutor}
 * is used instead, configured from {@code spring.task.execution.*} in
 * application.yml, so the pool can be resized by environment variable without a
 * rebuild. Declaring an {@code Executor} bean here would switch Spring Boot's
 * auto-configuration off, taking that with it - and other machinery, including
 * deferred JPA repository bootstrapping, expects that bean to exist.
 *
 * <p>Its queue is left unbounded on purpose: a burst of notifications should be
 * queued and sent late, never rejected and lost.
 *
 * <h2>Exceptions</h2>
 *
 * <p>An {@code @Async} method returning void has nowhere to propagate a failure
 * to - the caller has long since returned. Without the handler below, such a
 * failure would vanish. This logs it with the method and arguments that produced
 * it, so async work is no less diagnosable than the synchronous code it replaced.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("Async {}.{} failed with arguments {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        Arrays.toString(params),
                        ex);
    }
}
