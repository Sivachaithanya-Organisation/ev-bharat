package in.evbharat.service;

import in.evbharat.model.Models.Station;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationServiceTest {

    private static Station station(String id, String type, double lat, double lng, int kw) {
        return new Station(id, id, type, "", "City", "State", lat, lng, kw, 2,
                List.of("CCS2"), List.of(), null);
    }

    private final StationService service = new StationService(List.of(
            station("delhi", "city", 28.63, 77.22, 60),
            station("manesar", "highway", 28.35, 76.94, 120),
            station("jaipur", "city", 26.91, 75.79, 30)));

    @Test
    void filtersByTypeAndMinimumPower() {
        var q = new StationService.Query("highway", null, null, 100, null, null, null, null);
        assertEquals(List.of("manesar"), service.search(q).stream().map(Station::id).toList());
    }

    @Test
    void sortsNearestFirstAndAppliesRadius() {
        var q = new StationService.Query(null, null, null, 0, null, 28.6, 77.2, 100.0);
        var ids = service.search(q).stream().map(Station::id).toList();
        assertEquals(List.of("delhi", "manesar"), ids); // Jaipur is ~240 km away
    }

    @Test
    void haversineDelhiToJaipurIsRoughly240Km() {
        double d = StationService.haversineKm(28.6139, 77.2090, 26.9124, 75.7873);
        assertTrue(d > 230 && d < 250, "was " + d);
    }
}
