package com.fingress.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AtomicJournalTest {
    @TempDir Path directory;

    @Test void transientLockRetainsOldJournalUntilAtomicReplacementSucceeds() throws Exception {
        Path journal = directory.resolve("job.json"), next = directory.resolve("job.json.tmp");
        Files.writeString(journal, "old"); Files.writeString(next, "new");
        AtomicInteger attempts = new AtomicInteger();
        MigrationService.replaceJournal(() -> {
            assertEquals("old", Files.readString(journal));
            if (attempts.incrementAndGet() <= 2) throw new AccessDeniedException(journal.toString());
            Files.move(next, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        assertEquals(3, attempts.get()); assertEquals("new", Files.readString(journal)); assertFalse(Files.exists(next));
    }

    @Test void persistentDenialStopsAndOtherIoErrorsAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> MigrationService.replaceJournal(() -> {
            attempts.incrementAndGet(); throw new AccessDeniedException("job.json");
        }));
        assertEquals(10, attempts.get()); attempts.set(0);
        assertThrows(IOException.class, () -> MigrationService.replaceJournal(() -> {
            attempts.incrementAndGet(); throw new IOException("Disk failure");
        }));
        assertEquals(1, attempts.get());
    }

    @Test void interruptedRetryPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> MigrationService.replaceJournal(() -> { throw new AccessDeniedException("job.json"); }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
