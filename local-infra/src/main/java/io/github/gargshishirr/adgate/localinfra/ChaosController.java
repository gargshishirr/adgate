package io.github.gargshishirr.adgate.localinfra;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chaos switch for demos: {@code POST /chaos/redis/down}, {@code /slow?delayMs=200} and {@code /normal}.
 */
@RestController
@RequestMapping("/chaos/redis")
public class ChaosController {

    private final ChaosRedis redis;

    public ChaosController(ChaosRedis redis) {
        this.redis = redis;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("mode", redis.mode(), "delayMs", redis.delayMillis(), "port", redis.port());
    }

    @PostMapping("/{mode}")
    public Map<String, Object> setMode(@PathVariable String mode, @RequestParam(defaultValue = "200") long delayMs) {
        try {
            redis.setMode(ChaosRedis.Mode.valueOf(mode.toUpperCase(Locale.ROOT)), delayMs);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be one of normal, down, slow");
        }
        return status();
    }
}
