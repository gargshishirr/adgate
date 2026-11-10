package io.github.gargshishirr.adgate.sim;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Creates a demo portfolio that exercises every serving path: even and ASAP pacing, policy, a paused campaign. */
@Component
class CampaignSeeder {

    private final ObjectMapper mapper;

    CampaignSeeder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    List<String> seed(HttpClient http, URI baseUrl, int campaigns) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>(campaigns);
        for (int i = 1; i <= campaigns; i++) {
            String id = "c-" + i;
            boolean asap = i % 4 == 0;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", i == campaigns ? "PAUSED" : "ACTIVE");
            body.put("dailyBudgetMicros", asap ? 8_000_000L : 20_000_000L);
            body.put("pacing", asap ? "ASAP" : "EVEN");
            body.put("placements", i % 5 == 0 ? List.of("TODAY_TAB") : List.of());
            body.put("negativeKeywords", i % 2 == 1 ? List.of("free", "cheap") : List.of());

            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/admin/campaigns/" + id))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Seeding " + id + " failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            ids.add(id);
        }
        return ids;
    }
}
