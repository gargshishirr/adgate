package io.github.gargshishirr.adgate.sim;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.common.SpendEvent;
import io.github.gargshishirr.adgate.common.Topics;

/**
 * Closed-loop simulation of an ad server: sends ad requests at a fixed rate, runs a toy auction over the eligible
 * candidates, and publishes every win as a spend event. Spend therefore feeds back into pacing and budgets, the
 * same way it does in production.
 */
@Component
class TrafficSimulator implements ApplicationRunner {

    private static final String[] QUERIES = {
            "photo editor", "budget planner", "fitness tracker", "puzzle games", "free games",
            "music player", "cheap flights", "meditation", "recipe app", "language learning"};
    private static final String[] PLACEMENTS = {
            "SEARCH_RESULTS", "SEARCH_RESULTS", "SEARCH_RESULTS", "SEARCH_TAB", "TODAY_TAB", "PRODUCT_PAGES"};
    private static final String HUMAN_USER_AGENT = "AppStore/3.0 iOS/19.1";
    private static final String BOT_USER_AGENT = "Mozilla/5.0 (compatible; ExampleBot/2.1)";
    private static final String HOT_DEVICE = "device-hot";

    private final SimProperties properties;
    private final CampaignSeeder seeder;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final SimStats stats = new SimStats();

    TrafficSimulator(SimProperties properties, CampaignSeeder seeder, KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.properties = properties;
        this.seeder = seeder;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        URI base = properties.eligibilityUrl();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        awaitReady(http, base, Duration.ofSeconds(60));

        List<String> campaigns = properties.seedCampaigns()
                ? seeder.seed(http, base, properties.campaigns())
                : IntStream.rangeClosed(1, properties.campaigns()).mapToObj(i -> "c-" + i).toList();
        System.out.printf("Campaigns: %s%nSending %d requests/s for %ds to %s%n", campaigns,
                properties.requestsPerSecond(), properties.duration().toSeconds(), base);

        long startNanos = System.nanoTime();
        long reportMillis = properties.reportInterval().toMillis();
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(
                () -> System.out.println(stats.drainInterval(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos))),
                reportMillis, reportMillis, TimeUnit.MILLISECONDS);

        long periodNanos = 1_000_000_000L / Math.max(1, properties.requestsPerSecond());
        long endNanos = startNanos + properties.duration().toNanos();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            long next = startNanos;
            while (System.nanoTime() < endNanos) {
                workers.submit(() -> sendRequest(http, base, campaigns));
                next += periodNanos;
                long wait = next - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
            }
        }
        reporter.shutdownNow();
        kafka.flush();

        System.out.println();
        System.out.println(stats.summary());
        printCampaigns(http, base);
    }

    /** Waits until the service reports readiness, i.e. it has finished warming up. */
    private static void awaitReady(HttpClient http, URI base, Duration timeout) throws InterruptedException {
        HttpRequest probe = HttpRequest.newBuilder(base.resolve("/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (http.send(probe, BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Eligibility service at " + base + " was not ready within " + timeout.toSeconds() + "s");
    }

    private void sendRequest(HttpClient http, URI base, List<String> campaigns) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String deviceId = "device-" + random.nextInt(properties.devices());
        String userAgent = HUMAN_USER_AGENT;
        if (random.nextDouble() < properties.botShare()) {
            if (random.nextBoolean()) {
                deviceId = HOT_DEVICE;
            } else {
                userAgent = BOT_USER_AGENT;
            }
        }
        List<String> shuffled = new ArrayList<>(campaigns);
        Collections.shuffle(shuffled, random);
        List<String> chosen = new ArrayList<>(shuffled.subList(0, Math.min(properties.candidatesPerRequest(), shuffled.size())));
        if (random.nextDouble() < properties.unknownCampaignShare()) {
            chosen.add("c-unknown");
        }
        Map<String, Object> body = Map.of(
                "requestId", UUID.randomUUID().toString(),
                "placement", PLACEMENTS[random.nextInt(PLACEMENTS.length)],
                "query", QUERIES[random.nextInt(QUERIES.length)],
                "device", Map.of("id", deviceId, "userAgent", userAgent),
                "candidates", chosen.stream().map(id -> Map.of("adId", "ad-" + id, "campaignId", id)).toList());

        stats.sent.increment();
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/v1/eligibility"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
            stats.recordLatency((System.nanoTime() - start) / 1_000);
            if (response.statusCode() != 200) {
                stats.failed.increment();
                return;
            }
            List<String> eligible = new ArrayList<>();
            for (JsonNode decision : mapper.readTree(response.body()).path("decisions")) {
                stats.recordReason(decision.path("reason").asText());
                if (decision.path("eligible").asBoolean()) {
                    eligible.add(decision.path("campaignId").asText());
                }
            }
            if (!eligible.isEmpty() && random.nextDouble() < properties.winRate()) {
                publishSpend(eligible.get(random.nextInt(eligible.size())), random);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            stats.failed.increment();
        }
    }

    private void publishSpend(String campaignId, ThreadLocalRandom random) throws JsonProcessingException {
        long cost = random.nextLong(properties.minCostMicros(), properties.maxCostMicros() + 1);
        String json = mapper.writeValueAsString(new SpendEvent(UUID.randomUUID().toString(), campaignId, cost, System.currentTimeMillis()));
        kafka.send(Topics.SPEND_EVENTS, campaignId, json);
        stats.spendEvents.increment();
        if (random.nextDouble() < properties.duplicateSpendShare()) {
            kafka.send(Topics.SPEND_EVENTS, campaignId, json);
            stats.duplicateSpendEvents.increment();
        }
    }

    private void printCampaigns(HttpClient http, URI base) {
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/admin/campaigns")).timeout(Duration.ofSeconds(5)).GET().build();
            JsonNode views = mapper.readTree(http.send(request, BodyHandlers.ofString()).body());
            System.out.printf("%n%-7s %-7s %-5s %9s %8s %9s %10s%n", "id", "status", "pace", "budget", "spent", "allowed", "spend lag");
            for (JsonNode view : views) {
                JsonNode campaign = view.path("campaign");
                System.out.printf(Locale.ROOT, "%-7s %-7s %-5s %9.2f %7.1f%% %9s %10s%n",
                        campaign.path("id").asText(), campaign.path("status").asText(), campaign.path("pacing").asText(),
                        campaign.path("dailyBudgetMicros").asLong() / 1e6, view.path("spentPercent").asDouble(),
                        view.has("pacingAllowancePercent") ? view.path("pacingAllowancePercent").asText() + "%" : "-",
                        view.has("spendLagMillis") ? view.path("spendLagMillis").asText() + " ms" : "-");
            }
        } catch (Exception e) {
            System.out.println("Could not fetch the campaign summary: " + e);
        }
    }
}
