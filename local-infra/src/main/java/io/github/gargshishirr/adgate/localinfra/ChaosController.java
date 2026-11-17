package io.github.gargshishirr.adgate.localinfra;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only chaos switch, POST only: {@code POST /chaos/redis/down}, {@code /slow?delayMs=200} and {@code /normal}.
 * Each call answers with the current mode. It listens on 127.0.0.1 only (see {@link LocalOnlyWebConfig}).
 */
@RestController
@RequestMapping("/chaos/redis")
public class ChaosController {

    private final ChaosRedis redis;

    public ChaosController(ChaosRedis redis) {
        this.redis = redis;
    }

    @PostMapping("/{mode}")
    public ResponseEntity<Map<String, Object>> setMode(@PathVariable String mode, @RequestParam(defaultValue = "200") long delayMs) {
        ChaosRedis.Mode parsed;
        try {
            parsed = ChaosRedis.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be one of normal, down, slow"));
        }
        redis.setMode(parsed, delayMs);
        return ResponseEntity.ok(Map.of("mode", redis.mode(), "delayMs", redis.delayMillis(), "port", redis.port()));
    }
}
