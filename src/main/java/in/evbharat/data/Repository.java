package in.evbharat.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Plain-JDBC access to users and rental charge points. */
public final class Repository {

    public record User(long id, String name, String email, String passwordHash) {}

    public record Point(long id, String title, String city, String state, double lat, double lng,
                        String connector, int powerKw, double pricePerKwh, String phone, String notes,
                        boolean mine) {}

    public record NewPoint(String title, String city, String state, double lat, double lng,
                           String connector, int powerKw, double pricePerKwh, String phone, String notes) {}

    /** Thrown when the e-mail is already registered. */
    public static final class DuplicateEmail extends Exception {}

    public long createUser(String name, String email, String hash) throws SQLException, DuplicateEmail {
        try (Connection c = Db.get().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users(name,email,password_hash) VALUES (?,?,?) RETURNING id")) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new DuplicateEmail();
            }
            throw e;
        }
    }

    public Optional<User> findUserByEmail(String email) throws SQLException {
        return findUser("SELECT id,name,email,password_hash FROM users WHERE email=?", email);
    }

    public Optional<User> findUserById(long id) throws SQLException {
        try (Connection c = Db.get().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id,name,email,password_hash FROM users WHERE id=?")) {
            ps.setLong(1, id);
            return readUser(ps);
        }
    }

    private Optional<User> findUser(String sql, String param) throws SQLException {
        try (Connection c = Db.get().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            return readUser(ps);
        }
    }

    private Optional<User> readUser(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next()
                    ? Optional.of(new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                    : Optional.empty();
        }
    }

    public Point createPoint(long ownerId, NewPoint p) throws SQLException {
        try (Connection c = Db.get().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO charge_points(owner_id,title,city,state,lat,lng,connector,power_kw,price_per_kwh,phone,notes)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id""")) {
            ps.setLong(1, ownerId);
            ps.setString(2, p.title());
            ps.setString(3, p.city());
            ps.setString(4, p.state());
            ps.setDouble(5, p.lat());
            ps.setDouble(6, p.lng());
            ps.setString(7, p.connector());
            ps.setInt(8, p.powerKw());
            ps.setDouble(9, p.pricePerKwh());
            ps.setString(10, p.phone());
            ps.setString(11, p.notes());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Point(rs.getLong(1), p.title(), p.city(), p.state(), p.lat(), p.lng(), p.connector(),
                        p.powerKw(), p.pricePerKwh(), p.phone(), p.notes(), true);
            }
        }
    }

    /** Active points for everybody, or only the owner's points (including inactive) when mineOnly is set. */
    public List<Point> listPoints(Long viewerId, boolean mineOnly, boolean includePhone) throws SQLException {
        String sql = "SELECT id,owner_id,title,city,state,lat,lng,connector,power_kw,price_per_kwh,phone,notes "
                + "FROM charge_points WHERE " + (mineOnly ? "owner_id=?" : "active") + " ORDER BY created_at DESC LIMIT 2000";
        List<Point> out = new ArrayList<>();
        try (Connection c = Db.get().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (mineOnly) {
                ps.setLong(1, viewerId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean mine = viewerId != null && viewerId == rs.getLong(2);
                    out.add(new Point(rs.getLong(1), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getDouble(6), rs.getDouble(7), rs.getString(8), rs.getInt(9), rs.getDouble(10),
                            includePhone || mine ? rs.getString(11) : null, rs.getString(12), mine));
                }
            }
        }
        return out;
    }

    public boolean deletePoint(long id, long ownerId) throws SQLException {
        try (Connection c = Db.get().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM charge_points WHERE id=? AND owner_id=?")) {
            ps.setLong(1, id);
            ps.setLong(2, ownerId);
            return ps.executeUpdate() > 0;
        }
    }
}
