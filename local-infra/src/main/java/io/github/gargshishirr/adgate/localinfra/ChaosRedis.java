package io.github.gargshishirr.adgate.localinfra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.github.fppt.jedismock.RedisServer;
import com.github.fppt.jedismock.operations.server.MockExecutor;
import com.github.fppt.jedismock.server.ServiceOptions;

/**
 * In-process Redis-compatible server with a chaos switch. Failure modes are injected per command by an
 * interceptor, so data survives the "outage" and recovery can be observed end to end. It listens on 127.0.0.1 only
 * and has no password (it accepts any AUTH), so it must never be reachable from other machines.
 */
@Component
public class ChaosRedis implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChaosRedis.class);

    public enum Mode {
        /** Commands execute normally. */
        NORMAL,
        /** Every command drops the client connection, like a crashed or partitioned Redis. */
        DOWN,
        /** Every command is delayed, like an overloaded Redis. */
        SLOW
    }

    private static final long MAX_DELAY_MILLIS = 60_000;

    private final int port;
    private volatile Mode mode = Mode.NORMAL;
    private volatile long delayMillis = 200;
    private volatile boolean running;
    private RedisServer server;

    public ChaosRedis(LocalInfraProperties properties) {
        this.port = properties.redisPort();
    }

    /** The delay is clamped to 0..60 s. */
    public void setMode(Mode mode, long delayMillis) {
        this.delayMillis = Math.min(Math.max(0, delayMillis), MAX_DELAY_MILLIS);
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public long delayMillis() {
        return delayMillis;
    }

    public int port() {
        return port;
    }

    @Override
    public void start() {
        try {
            server = RedisServer.newRedisServer(port, InetAddress.getLoopbackAddress())
                    .setOptions(ServiceOptions.withInterceptor((state, command, params) -> {
                        Mode current = mode;
                        if (current == Mode.DOWN) {
                            return MockExecutor.breakConnection(state);
                        }
                        if (current == Mode.SLOW) {
                            sleep(delayMillis);
                        }
                        return MockExecutor.proceed(state, command, params);
                    }))
                    .start();
            running = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start Redis on port " + port, e);
        }
        long started = System.nanoTime();
        try {
            RedisPrewarm.run(port, 300);
            log.info("Prewarmed the in-process Redis in {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            log.warn("Redis prewarm failed (first requests may be slow): {}", e.toString());
        }
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (server != null && server.isRunning()) {
                server.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
