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
import java.util.Map;

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
        List<Station> raw = read("/data/stations.json", new TypeToken<List<Station>>() {}.getType());
        List<Station> cleaned = raw.stream()
                .filter(s -> s.lat() >= 6 && s.lat() <= 37.5 && s.lng() >= 68 && s.lng() <= 98)
                .map(s -> new Station(s.id(), s.name(), s.type(), s.corridor(), s.city(),
                normalizeState(s.state()), s.lat(), s.lng(), s.powerKw(), s.chargers(),
                s.connectors(), s.amenities(), s.distanceKm())).toList();
        List<Station> known = cleaned.stream().filter(s -> VALID_STATES.contains(s.state())).toList();
        stations = cleaned.stream().map(s -> VALID_STATES.contains(s.state()) ? s : withState(s, known)).toList();
        vehicles = read("/data/vehicles.json", new TypeToken<List<Vehicle>>() {}.getType());
        policies = read("/data/policies.json", new TypeToken<List<Policy>>() {}.getType());
        culture = read("/data/culture.json", JsonObject.class);
        market = read("/data/market.json", JsonObject.class);
    }

    private static final java.util.Set<String> VALID_STATES = java.util.Set.of(
            "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
            "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
            "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu",
            "Telangana", "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal", "Delhi", "Chandigarh",
            "Jammu and Kashmir", "Ladakh", "Puducherry", "Andaman and Nicobar Islands", "Lakshadweep",
            "Dadra and Nagar Haveli and Daman and Diu");

    /** Station with a non-state value (a city, "Unknown"...) takes the state of its nearest valid station within 150 km. */
    private static Station withState(Station s, List<Station> known) {
        Station best = null;
        double bestD = 150;
        for (Station k : known) {
            double dLat = (k.lat() - s.lat()) * 111.0;
            double dLng = (k.lng() - s.lng()) * 111.0 * Math.cos(Math.toRadians(s.lat()));
            double d = Math.hypot(dLat, dLng);
            if (d < bestD) { bestD = d; best = k; }
        }
        String state = best == null ? "Unknown" : best.state();
        return new Station(s.id(), s.name(), s.type(), s.corridor(), s.city(), state, s.lat(), s.lng(),
                s.powerKw(), s.chargers(), s.connectors(), s.amenities(), s.distanceKm());
    }

    private static final Map<String, String> STATE_ALIASES = Map.ofEntries(
            Map.entry("gj", "Gujarat"), Map.entry("gujrat", "Gujarat"), Map.entry("gujarat", "Gujarat"),
            Map.entry("mh", "Maharashtra"), Map.entry("maharashtra", "Maharashtra"),
            Map.entry("maharshtra", "Maharashtra"), Map.entry("mahrashtra", "Maharashtra"),
            Map.entry("maharastra", "Maharashtra"),
            Map.entry("mp", "Madhya Pradesh"), Map.entry("madhyapradesh", "Madhya Pradesh"),
            Map.entry("rj", "Rajasthan"), Map.entry("rajasthan", "Rajasthan"),
            Map.entry("ka", "Karnataka"), Map.entry("karnataka", "Karnataka"),
            Map.entry("tn", "Tamil Nadu"), Map.entry("tamilnadu", "Tamil Nadu"),
            Map.entry("up", "Uttar Pradesh"), Map.entry("uttarpradesh", "Uttar Pradesh"),
            Map.entry("uk", "Uttarakhand"), Map.entry("uttarakhand", "Uttarakhand"),
            Map.entry("uttarakhnad", "Uttarakhand"), Map.entry("uttaranchal", "Uttarakhand"),
            Map.entry("dl", "Delhi"), Map.entry("delhi", "Delhi"), Map.entry("newdelhi", "Delhi"),
            Map.entry("hr", "Haryana"), Map.entry("pb", "Punjab"), Map.entry("wb", "West Bengal"),
            Map.entry("westbengal", "West Bengal"), Map.entry("ts", "Telangana"), Map.entry("tg", "Telangana"),
            Map.entry("ap", "Andhra Pradesh"), Map.entry("andhrapradesh", "Andhra Pradesh"),
            Map.entry("kl", "Kerala"), Map.entry("od", "Odisha"), Map.entry("or", "Odisha"),
            Map.entry("orissa", "Odisha"), Map.entry("br", "Bihar"), Map.entry("hp", "Himachal Pradesh"),
            Map.entry("himachalpradesh", "Himachal Pradesh"), Map.entry("jk", "Jammu and Kashmir"),
            Map.entry("cg", "Chhattisgarh"), Map.entry("jh", "Jharkhand"), Map.entry("as", "Assam"),
            Map.entry("ga", "Goa"), Map.entry("ch", "Chandigarh"));

    static String normalizeState(String state) {
        String t = state == null ? "" : state.trim();
        String key = t.toLowerCase().replaceAll("[^a-z]", "");
        if (key.isEmpty() || key.equals("unknown")) {
            return "Unknown";
        }
        String canon = STATE_ALIASES.get(key);
        if (canon != null) {
            return canon;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : t.toLowerCase().split("\\s+")) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
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
