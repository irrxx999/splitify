package splitify;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Слой работы с базой. SQLite-файл splitify.db создаётся рядом с jar-ом.
 * Суммы храним как TEXT, чтобы не терять точность BigDecimal.
 */
public class Db {

    private static Connection c;

    public record Trip(long id, String name, String base) {}
    public record ExpenseView(String payer, BigDecimal amount, String currency, String description) {}
    public record Settlement(long id, String from, String to, BigDecimal amount, String currency, String status) {}

    public static synchronized void init() {
        try {
            c = DriverManager.getConnection("jdbc:sqlite:splitify.db");
            try (Statement st = c.createStatement()) {
                // WAL — параллельные чтения не блокируют запись; foreign_keys — включаем контроль ссылок.
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS trips(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        base_currency TEXT NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 1)""");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS participants(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        trip_id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        preferred_currency TEXT NOT NULL)""");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rates(
                        trip_id INTEGER NOT NULL,
                        currency TEXT NOT NULL,
                        rate_to_base TEXT NOT NULL,
                        PRIMARY KEY (trip_id, currency))""");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS expenses(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        trip_id INTEGER NOT NULL,
                        payer_id INTEGER NOT NULL,
                        amount TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        description TEXT,
                        rate_at_time TEXT,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP)""");
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS expense_shares(
                        expense_id INTEGER NOT NULL,
                        participant_id INTEGER NOT NULL)""");
                // Зафиксированный итог поездки. Заполняется один раз при /close.
                // status: pending — долг висит, paid — оплачено, forgiven — прощён.
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settlements(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        trip_id INTEGER NOT NULL,
                        from_name TEXT NOT NULL,
                        to_name TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP)""");
                // Индексы под горячие выборки.
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_expenses_trip ON expenses(trip_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_shares_expense ON expense_shares(expense_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_participants_trip ON participants(trip_id)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trips_chat_active ON trips(chat_id, is_active)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_settlements_trip ON settlements(trip_id)");
            }
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Не удалось открыть базу: " + e.getMessage(), e);
        }
    }

    /**
     * Мягкие миграции для баз, созданных до появления новых колонок.
     * rate_at_time — замороженный курс валюты к базовой на момент /spend.
     */
    private static void migrate() throws SQLException {
        if (!columnExists("expenses", "rate_at_time")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE expenses ADD COLUMN rate_at_time TEXT");
            }
        }
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
            return false;
        }
    }

    /** Аккуратно закрывает соединение: сбрасывает WAL в основной файл. Вызывается из shutdown hook. */
    public static synchronized void close() {
        if (c == null) return;
        try {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            if (!c.isClosed()) c.close();
        } catch (SQLException e) {
            System.err.println("Не удалось закрыть базу: " + e.getMessage());
        }
    }

    // ---------- Поездки ----------

    public static synchronized void newTrip(long chatId, String name, String base) {
        try {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE trips SET is_active = 0 WHERE chat_id = ?")) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO trips(chat_id, name, base_currency) VALUES (?,?,?)")) {
                ps.setLong(1, chatId);
                ps.setString(2, name);
                ps.setString(3, base);
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized Optional<Trip> activeTrip(long chatId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, base_currency FROM trips WHERE chat_id = ? AND is_active = 1")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(new Trip(rs.getLong(1), rs.getString(2), rs.getString(3)));
            return Optional.empty();
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized void closeTrip(long tripId) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE trips SET is_active = 0 WHERE id = ?")) {
            ps.setLong(1, tripId);
            ps.executeUpdate();
        } catch (SQLException e) { throw wrap(e); }
    }

    /** Последняя закрытая поездка чата — чтобы показать зафиксированный итог после /close. */
    public static synchronized Optional<Trip> lastClosedTrip(long chatId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, base_currency FROM trips " +
                "WHERE chat_id = ? AND is_active = 0 ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, chatId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(new Trip(rs.getLong(1), rs.getString(2), rs.getString(3)));
            return Optional.empty();
        } catch (SQLException e) { throw wrap(e); }
    }

    // ---------- Зафиксированный итог ----------

    /** Сохраняет итог поездки один раз при закрытии. Повторный вызов ничего не делает. */
    public static synchronized void saveSettlements(long tripId, List<SettlementService.Transfer> transfers) {
        if (hasSettlements(tripId)) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO settlements(trip_id, from_name, to_name, amount, currency) VALUES (?,?,?,?,?)")) {
            for (SettlementService.Transfer t : transfers) {
                ps.setLong(1, tripId);
                ps.setString(2, t.from());
                ps.setString(3, t.to());
                ps.setString(4, t.amount().toPlainString());
                ps.setString(5, t.currency());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized boolean hasSettlements(long tripId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM settlements WHERE trip_id = ? LIMIT 1")) {
            ps.setLong(1, tripId);
            return ps.executeQuery().next();
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized List<Settlement> settlements(long tripId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, from_name, to_name, amount, currency, status " +
                "FROM settlements WHERE trip_id = ? ORDER BY id")) {
            ps.setLong(1, tripId);
            ResultSet rs = ps.executeQuery();
            List<Settlement> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new Settlement(rs.getLong(1), rs.getString(2), rs.getString(3),
                        new BigDecimal(rs.getString(4)), rs.getString(5), rs.getString(6)));
            }
            return list;
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized void setSettlementStatus(long settlementId, String status) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE settlements SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, settlementId);
            ps.executeUpdate();
        } catch (SQLException e) { throw wrap(e); }
    }

    // ---------- Участники ----------

    public static synchronized void addParticipant(long tripId, String name, String currency) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO participants(trip_id, name, preferred_currency) VALUES (?,?,?)")) {
            ps.setLong(1, tripId);
            ps.setString(2, name);
            ps.setString(3, currency);
            ps.executeUpdate();
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized List<SettlementService.Participant> participants(long tripId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, preferred_currency FROM participants WHERE trip_id = ? ORDER BY id")) {
            ps.setLong(1, tripId);
            ResultSet rs = ps.executeQuery();
            List<SettlementService.Participant> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new SettlementService.Participant(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
            return list;
        } catch (SQLException e) { throw wrap(e); }
    }

    // ---------- Курсы ----------

    /**
     * Записывает актуальный курс валюты и возвращает, сколько уже записанных трат
     * в этой валюте были «доморожены» этим курсом (у них ещё не было зафиксированного
     * курса на момент /spend). Меняя курс позже, эти траты уже не тронешь.
     */
    public static synchronized int setRate(long tripId, String currency, BigDecimal rate) {
        try {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO rates(trip_id, currency, rate_to_base) VALUES (?,?,?)")) {
                ps.setLong(1, tripId);
                ps.setString(2, currency);
                ps.setString(3, rate.toPlainString());
                ps.executeUpdate();
            }
            // Замораживаем курс в тратах, у которых его ещё не было (первое назначение курса).
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE expenses SET rate_at_time = ? " +
                    "WHERE trip_id = ? AND currency = ? AND rate_at_time IS NULL")) {
                ps.setString(1, rate.toPlainString());
                ps.setLong(2, tripId);
                ps.setString(3, currency);
                return ps.executeUpdate();
            }
        } catch (SQLException e) { throw wrap(e); }
    }

    public static synchronized Map<String, BigDecimal> rates(long tripId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT currency, rate_to_base FROM rates WHERE trip_id = ?")) {
            ps.setLong(1, tripId);
            ResultSet rs = ps.executeQuery();
            Map<String, BigDecimal> map = new HashMap<>();
            while (rs.next()) map.put(rs.getString(1), new BigDecimal(rs.getString(2)));
            return map;
        } catch (SQLException e) { throw wrap(e); }
    }

    // ---------- Траты ----------

    /**
     * @param rateAtTime курс валюты траты к базовой, зафиксированный на момент /spend;
     *                   null, если курса ещё нет (тогда он подтянется позже — при /rate или /total).
     */
    public static synchronized void addExpense(long tripId, long payerId, BigDecimal amount, String currency,
                                  String description, BigDecimal rateAtTime, List<Long> sharedBetween) {
        try {
            // Трата и её доли — атомарно: иначе трата без долей навсегда ломает /total.
            c.setAutoCommit(false);
            try {
                long expenseId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO expenses(trip_id, payer_id, amount, currency, description, rate_at_time) " +
                        "VALUES (?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, tripId);
                    ps.setLong(2, payerId);
                    ps.setString(3, amount.toPlainString());
                    ps.setString(4, currency);
                    ps.setString(5, description);
                    ps.setString(6, rateAtTime == null ? null : rateAtTime.toPlainString());
                    ps.executeUpdate();
                    ResultSet keys = ps.getGeneratedKeys();
                    keys.next();
                    expenseId = keys.getLong(1);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO expense_shares(expense_id, participant_id) VALUES (?,?)")) {
                    for (Long pid : sharedBetween) {
                        ps.setLong(1, expenseId);
                        ps.setLong(2, pid);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) { throw wrap(e); }
    }

    /**
     * Один JOIN вместо N+1. INNER JOIN отсекает траты без долей — такие в расчёт
     * не попадают (и не делят на ноль), даже если попали в базу из старых версий.
     */
    public static synchronized List<SettlementService.Expense> expenses(long tripId) {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT e.id, e.payer_id, e.amount, e.currency, e.rate_at_time, s.participant_id
                FROM expenses e JOIN expense_shares s ON s.expense_id = e.id
                WHERE e.trip_id = ? ORDER BY e.id""")) {
            ps.setLong(1, tripId);
            ResultSet rs = ps.executeQuery();
            Map<Long, Acc> byExpense = new LinkedHashMap<>();
            while (rs.next()) {
                long eid = rs.getLong(1);
                Acc a = byExpense.get(eid);
                if (a == null) {
                    String frozen = rs.getString(5);
                    a = new Acc(rs.getLong(2), new BigDecimal(rs.getString(3)), rs.getString(4),
                            frozen == null ? null : new BigDecimal(frozen));
                    byExpense.put(eid, a);
                }
                a.shares.add(rs.getLong(6));
            }
            List<SettlementService.Expense> list = new ArrayList<>();
            for (Acc a : byExpense.values()) {
                list.add(new SettlementService.Expense(a.payerId, a.amount, a.currency, a.rate, a.shares));
            }
            return list;
        } catch (SQLException e) { throw wrap(e); }
    }

    /** Аккумулятор строк JOIN-а для группировки долей по трате. */
    private static final class Acc {
        final long payerId; final BigDecimal amount; final String currency; final BigDecimal rate;
        final List<Long> shares = new ArrayList<>();
        Acc(long payerId, BigDecimal amount, String currency, BigDecimal rate) {
            this.payerId = payerId; this.amount = amount; this.currency = currency; this.rate = rate;
        }
    }

    public static synchronized List<ExpenseView> expenseList(long tripId) {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.name, e.amount, e.currency, e.description
                FROM expenses e JOIN participants p ON p.id = e.payer_id
                WHERE e.trip_id = ? ORDER BY e.id""")) {
            ps.setLong(1, tripId);
            ResultSet rs = ps.executeQuery();
            List<ExpenseView> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new ExpenseView(rs.getString(1), new BigDecimal(rs.getString(2)),
                        rs.getString(3), rs.getString(4)));
            }
            return list;
        } catch (SQLException e) { throw wrap(e); }
    }

    private static RuntimeException wrap(SQLException e) {
        return new RuntimeException("Ошибка базы данных: " + e.getMessage(), e);
    }
}
