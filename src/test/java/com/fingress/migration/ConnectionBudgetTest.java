package com.fingress.migration;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real production ConnectionBudget(4,3) split (DatabaseGateway's CONNECTIONS field),
 * not a test double, to verify the P9 responsiveness guarantee: a foreground catalog/analysis
 * request must still get a connection immediately even when all three background slots are held. */
class ConnectionBudgetTest {
    static ConnectionBudget.Open h2() { return () -> DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"); }

    @Test void foregroundStaysImmediatelyAvailableWithThreeBackgroundSlotsHeld() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(4, 3);
        List<Connection> background = new ArrayList<>();
        for (int i = 0; i < 3; i++) background.add(budget.openBackground(h2()));

        // Background ceiling is 3, independent of the 4-total ceiling: a 4th background request must reject immediately.
        assertThrows(SQLException.class, () -> budget.openBackground(h2()));

        // One slot (4 total - 3 background max) is always reserved for foreground use; it must still be fast.
        long startNanos = System.nanoTime();
        Connection foreground = budget.open(h2());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        assertTrue(elapsedMs < 500, "Foreground connection took " + elapsedMs + "ms with three background slots already held");

        // All four total slots are now in use: a second simultaneous foreground request must reject immediately, not hang.
        assertThrows(SQLException.class, () -> budget.open(h2()));

        foreground.close();
        for (Connection c : background) c.close();
    }

    @Test void releasingABackgroundSlotAllowsAnotherBackgroundConnection() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(4, 3);
        Connection first = budget.openBackground(h2());
        Connection second = budget.openBackground(h2());
        Connection third = budget.openBackground(h2());
        first.close();
        Connection fourth = budget.openBackground(h2());
        fourth.close(); second.close(); third.close();
    }

    @Test void foregroundAndBackgroundShareTheSameTotalCeiling() throws Exception {
        ConnectionBudget budget = new ConnectionBudget(2, 2);
        Connection foreground = budget.open(h2());
        Connection background = budget.openBackground(h2());
        assertThrows(SQLException.class, () -> budget.open(h2()));
        assertThrows(SQLException.class, () -> budget.openBackground(h2()));
        foreground.close(); background.close();
    }
}
