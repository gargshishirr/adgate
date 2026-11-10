package io.github.gargshishirr.adgate.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Thread-safe counters for one reporting interval plus running totals. */
final class SimStats {

    final LongAdder sent = new LongAdder();
    final LongAdder failed = new LongAdder();
    final LongAdder spendEvents = new LongAdder();
    final LongAdder duplicateSpendEvents = new LongAdder();

    private final ConcurrentHashMap<String, LongAdder> intervalReasons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> totalReasons = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> intervalLatencyMicros = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> allLatencyMicros = new ConcurrentLinkedQueue<>();

    void recordLatency(long micros) {
        intervalLatencyMicros.add(micros);
        allLatencyMicros.add(micros);
    }

    void recordReason(String reason) {
        intervalReasons.computeIfAbsent(reason, r -> new LongAdder()).increment();
        totalReasons.computeIfAbsent(reason, r -> new LongAdder()).increment();
    }

    /** Formats and resets the interval counters. */
    String drainInterval(long elapsedSeconds) {
        List<Long> latencies = drain(intervalLatencyMicros);
        Map<String, Long> reasons = snapshot(intervalReasons, true);
        return String.format(Locale.ROOT, "[%4ds] sent %,d | failed %,d | client latency %s | spend events %,d (+%,d duplicates)%n       %s",
                elapsedSeconds, sent.sum(), failed.sum(), percentiles(latencies), spendEvents.sum(), duplicateSpendEvents.sum(),
                shares(reasons));
    }

    String summary() {
        List<Long> latencies = new ArrayList<>(allLatencyMicros);
        return String.format(Locale.ROOT, "Totals: sent %,d | failed %,d | client latency %s%n        %s",
                sent.sum(), failed.sum(), percentiles(latencies), shares(snapshot(totalReasons, false)));
    }

    private static List<Long> drain(ConcurrentLinkedQueue<Long> queue) {
        List<Long> values = new ArrayList<>();
        Long value;
        while ((value = queue.poll()) != null) {
            values.add(value);
        }
        return values;
    }

    private static Map<String, Long> snapshot(ConcurrentHashMap<String, LongAdder> counters, boolean reset) {
        Map<String, Long> values = new TreeMap<>();
        counters.forEach((reason, count) -> values.put(reason, reset ? count.sumThenReset() : count.sum()));
        values.values().removeIf(count -> count == 0);
        return values;
    }

    private static String percentiles(List<Long> micros) {
        if (micros.isEmpty()) {
            return "n/a";
        }
        List<Long> sorted = micros.stream().sorted().toList();
        return String.format(Locale.ROOT, "p50 %.1fms p95 %.1fms p99 %.1fms",
                at(sorted, 0.50) / 1000.0, at(sorted, 0.95) / 1000.0, at(sorted, 0.99) / 1000.0);
    }

    private static long at(List<Long> sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String shares(Map<String, Long> reasons) {
        long total = reasons.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return "no decisions";
        }
        return reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> String.format(Locale.ROOT, "%s %.1f%%", e.getKey(), 100.0 * e.getValue() / total))
                .collect(Collectors.joining(" | "));
    }
}
