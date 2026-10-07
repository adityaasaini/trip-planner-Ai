package com.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.dto.ResolvedLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SERPAPI CLIENT — Web and Location Search API Integration
 * Replaces legacy search APIs with SerpApi.
 */
@Slf4j
@Component
public class SerpApiClient {

    private final RestClient serpRestClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public SerpApiClient(
            @Value("${api.serpapi.base-url:https://serpapi.com}") String baseUrl,
            @Value("${api.serpapi.api-key:YOUR_API_KEY_HERE}") String apiKey) {
        
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);

        this.serpRestClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Searches locations via SerpApi (Google Maps & Locations engines).
     *
     * @param query Search string (e.g., "Paris", "Jaipur Fort")
     * @return List of ResolvedLocation DTOs
     */
    public List<ResolvedLocation> searchLocations(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        log.info("Executing SerpApi location search for query: '{}'", query);
        List<ResolvedLocation> results = new ArrayList<>();

        // 1. Query SerpApi Google Maps Engine if key is configured
        if (isKeyConfigured()) {
            try {
                String response = serpRestClient.get()
                        .uri("/search.json?engine=google_maps&q={query}&api_key={key}", query, apiKey)
                        .retrieve()
                        .body(String.class);

                if (response != null) {
                    JsonNode root = objectMapper.readTree(response);
                    
                    JsonNode localResults = root.path("local_results");
                    if (localResults.isArray() && !localResults.isEmpty()) {
                        for (JsonNode node : localResults) {
                            ResolvedLocation loc = parseSerpMapNode(node);
                            if (loc != null) {
                                results.add(loc);
                            }
                        }
                    }

                    if (results.isEmpty() && root.has("place_results")) {
                        JsonNode placeNode = root.path("place_results");
                        ResolvedLocation loc = parseSerpMapNode(placeNode);
                        if (loc != null) {
                            results.add(loc);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("SerpApi Google Maps engine search warning: {}", e.getMessage());
            }
        }

        // 2. Query SerpApi Locations API Endpoint
        if (results.isEmpty()) {
            try {
                String uri = isKeyConfigured()
                        ? "/locations.json?q={query}&limit=5&api_key=" + apiKey
                        : "/locations.json?q={query}&limit=5";

                String response = serpRestClient.get()
                        .uri(uri, query)
                        .retrieve()
                        .body(String.class);

                if (response != null) {
                    JsonNode root = objectMapper.readTree(response);
                    if (root.isArray() && !root.isEmpty()) {
                        for (JsonNode node : root) {
                            ResolvedLocation loc = parseSerpLocationNode(node);
                            if (loc != null) {
                                results.add(loc);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("SerpApi Locations API lookup warning: {}", e.getMessage());
            }
        }

        log.info("SerpApi returned {} location results for: '{}'", results.size(), query);
        return results;
    }

    private boolean isKeyConfigured() {
        return apiKey != null 
                && !apiKey.isBlank() 
                && !"YOUR_API_KEY_HERE".equals(apiKey) 
                && !"YOUR_SERPAPI_API_KEY".equals(apiKey);
    }

    private ResolvedLocation parseSerpMapNode(JsonNode node) {
        try {
            String name = node.path("title").asText("");
            if (name.isBlank()) {
                name = node.path("name").asText("");
            }
            if (name.isBlank()) return null;

            String address = node.path("address").asText(name);
            double lat = 0.0;
            double lon = 0.0;

            if (node.has("gps_coordinates")) {
                lat = node.path("gps_coordinates").path("latitude").asDouble(0.0);
                lon = node.path("gps_coordinates").path("longitude").asDouble(0.0);
            } else {
                lat = node.path("latitude").asDouble(0.0);
                lon = node.path("longitude").asDouble(0.0);
            }

            String placeId = "serp-" + node.path("place_id").asText(UUID.randomUUID().toString());

            String[] parts = address.split(",");
            String city = name;
            String country = "";
            if (parts.length > 1) {
                city = parts[0].trim();
                country = parts[parts.length - 1].trim();
            }

            return ResolvedLocation.builder()
                    .name(name)
                    .formattedAddress(address)
                    .latitude(lat)
                    .longitude(lon)
                    .city(city)
                    .state("")
                    .country(country)
                    .countryCode("")
                    .placeId(placeId)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse SerpApi map node: {}", e.getMessage());
            return null;
        }
    }

    private ResolvedLocation parseSerpLocationNode(JsonNode node) {
        try {
            String name = node.path("name").asText("");
            String canonicalName = node.path("canonical_name").asText(name);
            String countryCode = node.path("country_code").asText("").toLowerCase();
            String id = node.path("id").asText(UUID.randomUUID().toString());

            String[] parts = canonicalName.split(",");
            String city = parts.length > 0 ? parts[0].trim() : name;
            String state = parts.length > 2 ? parts[1].trim() : "";
            String country = parts.length > 1 ? parts[parts.length - 1].trim() : countryCode.toUpperCase();

            return ResolvedLocation.builder()
                    .name(city)
                    .formattedAddress(canonicalName)
                    .latitude(0.0)
                    .longitude(0.0)
                    .city(city)
                    .state(state)
                    .country(country)
                    .countryCode(countryCode)
                    .placeId("serp-loc-" + id)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse SerpApi location node: {}", e.getMessage());
            return null;
        }
    }
}
