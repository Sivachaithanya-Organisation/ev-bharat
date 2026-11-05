package in.evbharat.service;

import in.evbharat.model.Models.Station;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** Filtering and nearest-first search over charging stations. */
public final class StationService {

    public record Query(String type, String state, String connector, int minKw, String text,
                        Double lat, Double lng, Double radiusKm) {}

    private static final double EARTH_RADIUS_KM = 6371.0088;
    private final List<Station> stations;

    public StationService(List<Station> stations) {
        this.stations = List.copyOf(stations);
    }

    public List<Station> search(Query q) {
        Stream<Station> stream = stations.stream()
                .filter(s -> blank(q.type()) || s.type().equalsIgnoreCase(q.type()))
                .filter(s -> blank(q.state()) || s.state().equalsIgnoreCase(q.state()))
                .filter(s -> blank(q.connector())
                        || s.connectors().stream().anyMatch(c -> c.equalsIgnoreCase(q.connector())))
                .filter(s -> s.powerKw() >= q.minKw())
                .filter(s -> matchesText(s, q.text()));

        if (q.lat() == null || q.lng() == null) {
            return stream.sorted(Comparator.comparing(Station::state).thenComparing(Station::name)).toList();
        }
        final double radius = q.radiusKm() == null ? Double.MAX_VALUE : q.radiusKm();
        return stream
                .map(s -> s.withDistance(Math.round(haversineKm(q.lat(), q.lng(), s.lat(), s.lng()) * 10) / 10.0))
                .filter(s -> s.distanceKm() <= radius)
                .sorted(Comparator.comparingDouble(Station::distanceKm))
                .toList();
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    private static boolean matchesText(Station s, String text) {
        if (blank(text)) {
            return true;
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return Stream.of(s.name(), s.city(), s.state(), s.corridor())
                .map(v -> Objects.toString(v, "").toLowerCase(Locale.ROOT))
                .anyMatch(v -> v.contains(needle));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
