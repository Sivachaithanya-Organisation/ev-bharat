package in.evbharat.web;

import com.google.gson.Gson;
import in.evbharat.data.DataStore;
import in.evbharat.model.Models.Vehicle;
import in.evbharat.service.StationService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Read-only JSON API (Servlet 4.0 / Tomcat 9).
 * GET /api/stations, /api/stations/meta, /api/stats, /api/vehicles, /api/policies,
 *     /api/culture, /api/market, /api/health
 */
@WebServlet(name = "api", urlPatterns = "/api/*", loadOnStartup = 1)
public class ApiServlet extends HttpServlet {

    private static final Gson GSON = new Gson();
    private transient StationService stationService;

    @Override
    public void init() {
        stationService = new StationService(DataStore.get().stations()); // fail fast on bad data
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "public, max-age=60");

        DataStore ds = DataStore.get();
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();

        Object body = switch (path) {
            case "/stations" -> stationService.search(parseQuery(req));
            case "/stations/meta" -> ds.meta();
            case "/stats" -> ds.stats();
            case "/vehicles" -> vehicles(ds, req.getParameter("category"));
            case "/policies" -> ds.policies();
            case "/culture" -> ds.culture();
            case "/market" -> ds.market();
            case "/health" -> Map.of("status", "ok");
            default -> {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                yield Map.of("error", "Unknown endpoint: " + path);
            }
        };
        resp.getWriter().write(GSON.toJson(body));
    }

    private static List<Vehicle> vehicles(DataStore ds, String category) {
        if (category == null || category.isBlank()) {
            return ds.vehicles();
        }
        return ds.vehicles().stream().filter(v -> v.category().equalsIgnoreCase(category)).toList();
    }

    private static StationService.Query parseQuery(HttpServletRequest r) {
        Double minKw = number(r.getParameter("minKw"));
        return new StationService.Query(
                r.getParameter("type"), r.getParameter("state"), r.getParameter("connector"),
                minKw == null ? 0 : minKw.intValue(), r.getParameter("q"),
                number(r.getParameter("lat")), number(r.getParameter("lng")), number(r.getParameter("radiusKm")));
    }

    private static Double number(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
