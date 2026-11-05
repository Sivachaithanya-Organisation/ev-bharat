package in.evbharat.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import in.evbharat.model.Models.Policy;
import in.evbharat.model.Models.Station;
import in.evbharat.model.Models.Vehicle;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Loads the JSON datasets from the classpath once and keeps them in memory. */
public final class DataStore {

    public record Stats(int stations, int highway, int city, int states, int chargers, int fastDc) {}
    public record Meta(List<String> states, List<String> connectors, List<String> corridors) {}

    private static DataStore instance;

    private final Gson gson = new Gson();
    private final List<Station> stations;
    private final List<Vehicle> vehicles;
    private final List<Policy> policies;
    private final JsonObject culture;
    private final JsonObject market;

    private DataStore() {
        stations = read("/data/stations.json", new TypeToken<List<Station>>() {}.getType());
        vehicles = read("/data/vehicles.json", new TypeToken<List<Vehicle>>() {}.getType());
        policies = read("/data/policies.json", new TypeToken<List<Policy>>() {}.getType());
        culture = read("/data/culture.json", JsonObject.class);
        market = read("/data/market.json", JsonObject.class);
    }

    public static synchronized DataStore get() {
        if (instance == null) {
            instance = new DataStore();
        }
        return instance;
    }

    private <T> T read(String path, Type type) {
        try (var in = DataStore.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Station> stations() { return stations; }
    public List<Vehicle> vehicles() { return vehicles; }
    public List<Policy> policies() { return policies; }
    public JsonObject culture() { return culture; }
    public JsonObject market() { return market; }

    public Stats stats() {
        return new Stats(
                stations.size(),
                (int) stations.stream().filter(s -> "highway".equals(s.type())).count(),
                (int) stations.stream().filter(s -> "city".equals(s.type())).count(),
                (int) stations.stream().map(Station::state).distinct().count(),
                stations.stream().mapToInt(Station::chargers).sum(),
                (int) stations.stream().filter(s -> s.powerKw() >= 50).count());
    }

    public Meta meta() {
        return new Meta(
                stations.stream().map(Station::state).distinct().sorted().toList(),
                stations.stream().flatMap(s -> s.connectors().stream()).distinct().sorted().toList(),
                stations.stream().map(Station::corridor).filter(c -> c != null && !c.isBlank())
                        .distinct().sorted().toList());
    }
}
