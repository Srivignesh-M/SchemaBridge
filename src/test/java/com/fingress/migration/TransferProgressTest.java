package com.fingress.migration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferProgressTest {
    @Test void copyShowsByteRateWithoutInventingRowProgressOrEta() throws Exception {
        TransferProgress progress = new TransferProgress();
        progress.totalRows = 10000; progress.rowsSent = 100;
        progress.beginPhase("COPYING"); progress.bytes += 1_000_000;
        Thread.sleep(1050);
        Model.Progress copy = progress.view();
        assertTrue(copy.bytesPerSecond() > 0); assertEquals(100, copy.rowsSent()); assertNull(copy.remainingSeconds());
        progress.beginPhase("FINGERPRINTING");
        Model.Progress receipt = progress.view();
        assertNull(receipt.bytesPerSecond()); assertNull(receipt.remainingSeconds());
        assertTrue(receipt.phaseSeconds().get("COPYING") >= 1.0);
        assertTrue(receipt.phaseSeconds().containsKey("FINGERPRINTING"));
        progress.beginPhase("COPYING");
        assertEquals(0.0, progress.view().bytesPerSecond(), "New table must not show previous table's byte rate");
    }
}
