package com.tripplanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.client.SerpApiClient;
import com.tripplanner.dto.ResolvedLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Service
public class LocationService {

    private final SerpApiClient serpApiClient;
    private final RestClient nominatimClient;
    private final RestClient owmGeocodingClient;
    private final String owmApiKey;
    private final ObjectMapper objectMapper;

    public LocationService(
            SerpApiClient serpApiClient,
            @Value("${api.openweathermap.api-key:YOUR_OPENWEATHER_API_KEY}") String owmApiKey) {
        
        this.serpApiClient = serpApiClient;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);

        this.nominatimClient = RestClient.builder()
                .baseUrl("https://nominatim.openstreetmap.org")
                .requestFactory(factory)
                .defaultHeader("User-Agent", "TripMindAI/1.0 (contact@tripmind.ai)")
                .build();

        this.owmGeocodingClient = RestClient.builder()
                .baseUrl("https://api.openweathermap.org/geo/1.0")
                .requestFactory(factory)
                .build();

        this.owmApiKey = owmApiKey;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Searches for matching locations.
     * Uses SerpApi as the primary search engine.
     * Falls back to Nominatim / OpenWeatherMap if SerpApi yields no results.
     */
    public List<ResolvedLocation> searchLocations(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        log.info("Searching location autocomplete via SerpApi for: '{}'", query);
        List<ResolvedLocation> results = serpApiClient.searchLocations(query);

        // Fallback: If SerpApi returns empty (e.g. key placeholder unconfigured or empty results), try fallback geocoding
        if (results.isEmpty()) {
            log.info("SerpApi returned no results or key is placeholder. Attempting fallback geocoding for: '{}'", query);
            results = fallbackSearchLocations(query);
        }

        log.info("Found {} autocomplete results for: '{}'", results.size(), query);
        return results;
    }

    private List<ResolvedLocation> fallbackSearchLocations(String query) {
        List<ResolvedLocation> results = new ArrayList<>();

        // 1. Try Nominatim fallback
        try {
            String response = nominatimClient.get()
                    .uri("/search?q={query}&format=json&addressdetails=1&limit=5&accept-language=en", query)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (root.isArray() && !root.isEmpty()) {
                for (JsonNode node : root) {
                    ResolvedLocation loc = parseNominatimNode(node);
                    if (loc != null) {
                        results.add(loc);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Nominatim fallback failed: {}", e.getMessage());
        }

        // 2. Try OpenWeatherMap Geocoding fallback
        if (results.isEmpty()) {
            try {
                String response = owmGeocodingClient.get()
                        .uri("/direct?q={query}&limit=5&appid={key}", query, owmApiKey)
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(response);
                if (root.isArray() && !root.isEmpty()) {
                    for (JsonNode node : root) {
                        ResolvedLocation loc = parseOwmNode(node);
                        if (loc != null) {
                            results.add(loc);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("OpenWeatherMap geocoding fallback failed: {}", e.getMessage());
            }
        }

        return results;
    }

    private ResolvedLocation parseNominatimNode(JsonNode node) {
        try {
            double lat = Double.parseDouble(node.path("lat").asText("0"));
            double lon = Double.parseDouble(node.path("lon").asText("0"));
            if (lat == 0 && lon == 0) return null;

            String formattedAddress = node.path("display_name").asText();
            String placeId = "osm-" + node.path("place_id").asText(UUID.randomUUID().toString());

            JsonNode address = node.path("address");
            String city = address.has("city") ? address.path("city").asText() :
                          address.has("town") ? address.path("town").asText() :
                          address.has("village") ? address.path("village").asText() :
                          address.has("municipality") ? address.path("municipality").asText() :
                          address.has("suburb") ? address.path("suburb").asText() : "";
            
            if (city.isEmpty()) {
                city = address.has("county") ? address.path("county").asText() : "";
            }

            String state = address.path("state").asText("");
            String country = address.path("country").asText("");
            String countryCode = address.path("country_code").asText("").toLowerCase();

            String name = city.isEmpty() ? address.path("name").asText(formattedAddress.split(",")[0]) : city;

            return ResolvedLocation.builder()
                    .name(name)
                    .formattedAddress(formattedAddress)
                    .latitude(lat)
                    .longitude(lon)
                    .city(city)
                    .state(state)
                    .country(country)
                    .countryCode(countryCode)
                    .placeId(placeId)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Nominatim response node: {}", e.getMessage());
            return null;
        }
    }

    private ResolvedLocation parseOwmNode(JsonNode node) {
        try {
            double lat = node.path("lat").asDouble();
            double lon = node.path("lon").asDouble();
            String name = node.path("name").asText();
            String countryCode = node.path("country").asText("").toLowerCase();
            String state = node.path("state").asText("");
            
            String country = "IN".equalsIgnoreCase(countryCode) ? "India" : countryCode.toUpperCase();
            String formattedAddress = name + (state.isEmpty() ? "" : ", " + state) + ", " + country;
            String placeId = "owm-" + lat + "-" + lon;

            return ResolvedLocation.builder()
                    .name(name)
                    .formattedAddress(formattedAddress)
                    .latitude(lat)
                    .longitude(lon)
                    .city(name)
                    .state(state)
                    .country(country)
                    .countryCode(countryCode)
                    .placeId(placeId)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse OpenWeatherMap response node: {}", e.getMessage());
            return null;
        }
    }
}
