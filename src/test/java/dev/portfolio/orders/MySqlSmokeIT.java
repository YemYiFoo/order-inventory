package dev.portfolio.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;

/** Verifies the actual disposable MySQL version and storage engine. */
class MySqlSmokeIT {

    @Test
    void realDatabaseIsAvailable() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4.6")) {
            mysql.start();
            try (var connection =
                            DriverManager.getConnection(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery("SELECT VERSION(), @@default_storage_engine")) {
                assertTrue(rows.next());
                assertTrue(rows.getString(1).startsWith("8.4.6"));
                assertEquals("InnoDB", rows.getString(2));
            }
        }
    }
}
