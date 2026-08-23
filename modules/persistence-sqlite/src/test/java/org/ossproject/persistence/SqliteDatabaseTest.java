package org.ossproject.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDatabaseTest {
    @TempDir Path temporaryDirectory;

    @Test void createsMissingParentDirectoryAndDatabaseFile() {
        Path databaseFile = temporaryDirectory.resolve("OpenStockAccess").resolve("openstock.db");

        try (SqliteDatabase ignored = SqliteDatabase.open(databaseFile)) {
            assertTrue(Files.isRegularFile(databaseFile));
        }
    }
}
