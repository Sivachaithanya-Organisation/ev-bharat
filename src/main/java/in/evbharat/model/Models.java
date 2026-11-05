package in.evbharat.model;

import java.util.List;

/** Immutable data shapes served by the JSON API. */
public final class Models {
    private Models() {}

    public record Station(String id, String name, String type, String corridor, String city, String state,
                          double lat, double lng, int powerKw, int chargers,
                          List<String> connectors, List<String> amenities, Double distanceKm) {
        public Station withDistance(double km) {
            return new Station(id, name, type, corridor, city, state, lat, lng, powerKw, chargers,
                    connectors, amenities, km);
        }
    }

    public record Vehicle(String id, String category, String brand, String model, String segment,
                          String battery, int rangeKm, String notes) {}

    public record Policy(String id, String title, String level, String badge, String status,
                         String summary, List<String> benefits) {}
}
