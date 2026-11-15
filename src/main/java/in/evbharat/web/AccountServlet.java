package in.evbharat.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import in.evbharat.data.Repository;
import in.evbharat.data.Repository.DuplicateEmail;
import in.evbharat.data.Repository.NewPoint;
import in.evbharat.data.Repository.User;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Login and rental charge points.
 * POST /api/auth/register|login|logout, GET /api/auth/me,
 * GET|POST /api/points, GET /api/points/mine, DELETE /api/points/{id}
 */
@WebServlet(name = "account", urlPatterns = {"/api/auth/*", "/api/points", "/api/points/*"})
public class AccountServlet extends HttpServlet {

    private static final Gson GSON = new Gson();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[+0-9 ()-]{7,20}$");
    private static final String SESSION_USER = "uid";
    private final transient Repository repo = new Repository();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        try {
            if (!"GET".equals(req.getMethod()) && !isJson(req)) {
                send(resp, 415, Map.of("error", "Content-Type must be application/json"));
                return;
            }
            route(req, resp);
        } catch (SQLException | RuntimeException e) {
            getServletContext().log("account api error", e);
            e.printStackTrace();
            send(resp, 503, Map.of("error", "Accounts are temporarily unavailable. Please try again later."));
        }
    }

    private void route(HttpServletRequest req, HttpServletResponse resp) throws IOException, SQLException {
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        String m = req.getMethod();
        switch (m + " " + path) {
            case "POST /api/auth/register" -> register(req, resp);
            case "POST /api/auth/login" -> login(req, resp);
            case "POST /api/auth/logout" -> { invalidate(req); send(resp, 200, Map.of("ok", true)); }
            case "GET /api/auth/me" -> me(req, resp);
            case "GET /api/points" -> send(resp, 200, repo.listPoints(userId(req), false, userId(req) != null));
            case "GET /api/points/mine" -> mine(req, resp);
            case "POST /api/points" -> addPoint(req, resp);
            default -> {
                if (m.equals("DELETE") && path.matches("/api/points/\\d+")) {
                    deletePoint(req, resp, Long.parseLong(path.substring(path.lastIndexOf('/') + 1)));
                } else {
                    send(resp, 404, Map.of("error", "Unknown endpoint"));
                }
            }
        }
    }

    private void register(HttpServletRequest req, HttpServletResponse resp) throws IOException, SQLException {
        JsonObject b = body(req);
        if (b == null) { bad(resp, "Invalid request"); return; }
        String name = str(b, "name"), email = str(b, "email").toLowerCase(), pw = str(b, "password");
        if (name.length() < 2 || name.length() > 80) { bad(resp, "Enter your name (2-80 characters)."); return; }
        if (!EMAIL.matcher(email).matches() || email.length() > 120) { bad(resp, "Enter a valid e-mail."); return; }
        if (pw.length() < 8 || pw.length() > 72) { bad(resp, "Password must be 8-72 characters."); return; }
        try {
            long id = repo.createUser(name, email, BCrypt.hashpw(pw, BCrypt.gensalt(11)));
            startSession(req, id);
            send(resp, 201, Map.of("id", id, "name", name, "email", email));
        } catch (DuplicateEmail e) {
            send(resp, 409, Map.of("error", "This e-mail is already registered. Try logging in."));
        }
    }

    private void login(HttpServletRequest req, HttpServletResponse resp) throws IOException, SQLException {
        JsonObject b = body(req);
        if (b == null) { bad(resp, "Invalid request"); return; }
        Optional<User> u = repo.findUserByEmail(str(b, "email").toLowerCase());
        String pw = str(b, "password");
        boolean ok = u.isPresent() && pw.length() <= 72 && BCrypt.checkpw(pw, u.get().passwordHash());
        if (!ok) { send(resp, 401, Map.of("error", "Wrong e-mail or password.")); return; }
        startSession(req, u.get().id());
        send(resp, 200, Map.of("id", u.get().id(), "name", u.get().name(), "email", u.get().email()));
    }

    private void me(HttpServletRequest req, HttpServletResponse resp) throws IOException, SQLException {
        Long id = userId(req);
        Optional<User> u = id == null ? Optional.empty() : repo.findUserById(id);
        if (u.isEmpty()) { send(resp, 401, Map.of("error", "Not logged in")); return; }
        send(resp, 200, Map.of("id", u.get().id(), "name", u.get().name(), "email", u.get().email()));
    }

    private void mine(HttpServletRequest req, HttpServletResponse resp) throws IOException, SQLException {
        Long id = userId(req);
        if (id == null) { send(resp, 401, Map.of("error", "Please log in.")); return; }
        send(resp, 200, repo.listPoints(id, true, true));
    }

    private void addPoint(HttpServletRequest req, HttpServletResponse resp) throws IOException, SQLException {
        Long id = userId(req);
        if (id == null) { send(resp, 401, Map.of("error", "Please log in to add a charger.")); return; }
        JsonObject b = body(req);
        if (b == null) { bad(resp, "Invalid request"); return; }
        try {
            String title = str(b, "title"), city = str(b, "city"), state = str(b, "state");
            String connector = str(b, "connector"), phone = str(b, "phone"), notes = str(b, "notes");
            double lat = b.get("lat").getAsDouble(), lng = b.get("lng").getAsDouble();
            int kw = b.get("powerKw").getAsInt();
            double price = b.get("pricePerKwh").getAsDouble();
            if (title.length() < 3 || title.length() > 100) { bad(resp, "Title must be 3-100 characters."); return; }
            if (city.isEmpty() || city.length() > 60 || state.isEmpty() || state.length() > 60) { bad(resp, "Enter city and state."); return; }
            if (connector.isEmpty() || connector.length() > 40) { bad(resp, "Pick a connector type."); return; }
            if (lat < 6 || lat > 37.5 || lng < 68 || lng > 98) { bad(resp, "Location must be inside India."); return; }
            if (kw < 1 || kw > 400) { bad(resp, "Power must be 1-400 kW."); return; }
            if (price < 0 || price > 1000) { bad(resp, "Price must be between 0 and 1000 per kWh."); return; }
            if (!PHONE.matcher(phone).matches()) { bad(resp, "Enter a valid contact phone number."); return; }
            if (notes.length() > 500) { bad(resp, "Notes can be at most 500 characters."); return; }
            send(resp, 201, repo.createPoint(id, new NewPoint(title, city, state, lat, lng, connector, kw, price, phone, notes)));
        } catch (RuntimeException e) {
            bad(resp, "Please fill in all fields with valid values.");
        }
    }

    private void deletePoint(HttpServletRequest req, HttpServletResponse resp, long pointId) throws IOException, SQLException {
        Long id = userId(req);
        if (id == null) { send(resp, 401, Map.of("error", "Please log in.")); return; }
        if (repo.deletePoint(pointId, id)) send(resp, 200, Map.of("ok", true));
        else send(resp, 404, Map.of("error", "Charger not found."));
    }

    private static boolean isJson(HttpServletRequest req) {
        String ct = req.getContentType();
        return ct != null && ct.toLowerCase().startsWith("application/json");
    }

    private static JsonObject body(HttpServletRequest req) {
        try {
            JsonObject o = GSON.fromJson(req.getReader(), JsonObject.class);
            return o;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : "";
    }

    private static Long userId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s == null ? null : (Long) s.getAttribute(SESSION_USER);
    }

    private static void startSession(HttpServletRequest req, long id) {
        invalidate(req);
        HttpSession s = req.getSession(true); // new session id on login prevents fixation
        s.setMaxInactiveInterval(60 * 60 * 8);
        s.setAttribute(SESSION_USER, id);
    }

    private static void invalidate(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s != null) s.invalidate();
    }

    private static void bad(HttpServletResponse resp, String msg) throws IOException {
        send(resp, 400, Map.of("error", msg));
    }

    private static void send(HttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write(GSON.toJson(body));
    }
}
