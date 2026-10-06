package com.fingress.migration;

import java.sql.Statement;
import java.util.concurrent.CancellationException;

final class TransferProgress {
    volatile String phase = "QUEUED", table = "";
    volatile long rowsRead, rowsSent, rowsCommitted, bytes, totalRows, started = System.currentTimeMillis();
    volatile long finished;
    volatile boolean cancel;
    volatile Statement statement;
    void check() { if (cancel || Thread.currentThread().isInterrupted()) throw new CancellationException("Cancelled at a safe boundary"); }
    Model.Progress view() {
        long elapsed = Math.max(0, ((finished==0?System.currentTimeMillis():finished) - started)/1000);
        long processed = phase.equals("EXTRACTING") ? rowsRead : rowsSent;
        double rate = elapsed == 0 ? 0 : (double)processed / elapsed;
        Long eta = rate > 0 && totalRows > processed ? (long)((totalRows-processed)/rate) : null;
        return new Model.Progress(phase,table,rowsRead,rowsSent,rowsCommitted,bytes,totalRows,elapsed,rate,eta,cancel);
    }
}
