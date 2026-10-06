package com.fingress.migration;

/** Immutable, validated settings shared by desktop requests and CLI configuration. */
public record MigrationOptions(Long maxRows, Long maxTableBytes, Long diskReserveBytes,
        Integer fetchSize, Integer batchRows, Long batchBytes, Integer queryTimeoutSeconds,
        Integer readTimeoutSeconds, Boolean validateData, Boolean useCopy, Integer settingsVersion) {
    public static final int CURRENT_VERSION = 1;
    public MigrationOptions(Long maxRows, Long maxTableBytes, Long diskReserveBytes, Integer fetchSize,
            Integer batchRows, Long batchBytes, Integer queryTimeoutSeconds, Integer readTimeoutSeconds,
            Boolean validateData, Boolean useCopy) {
        this(maxRows,maxTableBytes,diskReserveBytes,fetchSize,batchRows,batchBytes,queryTimeoutSeconds,readTimeoutSeconds,validateData,useCopy,null);
    }
    public MigrationOptions {
        settingsVersion = settingsVersion == null ? CURRENT_VERSION : settingsVersion;
        if(settingsVersion != CURRENT_VERSION)throw new IllegalArgumentException("Unsupported migration settings version: "+settingsVersion);
        maxRows = maxRows == null ? 2_000_000L : maxRows;
        maxTableBytes = maxTableBytes == null ? 10_000_000_000L : maxTableBytes;
        diskReserveBytes = diskReserveBytes == null ? 256_000_000L : diskReserveBytes;
        fetchSize = fetchSize == null ? 1000 : fetchSize;
        batchRows = batchRows == null ? 1000 : batchRows;
        batchBytes = batchBytes == null ? 4_000_000L : batchBytes;
        queryTimeoutSeconds = queryTimeoutSeconds == null ? 1800 : queryTimeoutSeconds;
        readTimeoutSeconds = readTimeoutSeconds == null ? 1800 : readTimeoutSeconds;
        validateData = validateData == null || validateData;
        useCopy = useCopy != null && useCopy;
        if (maxRows < 1 || maxRows > 1_000_000_000L || maxTableBytes < 1 || maxTableBytes > 1_000_000_000_000L
                || diskReserveBytes < 0 || fetchSize < 1 || fetchSize > 10000 || batchRows < 1 || batchRows > 10000
                || batchBytes < 1024 || batchBytes > 64_000_000L || queryTimeoutSeconds < 1 || queryTimeoutSeconds > 86400
                || readTimeoutSeconds < 1 || readTimeoutSeconds > 86400)
            throw new IllegalArgumentException("Migration settings are outside the supported range");
    }
    public static MigrationOptions defaults() { return new MigrationOptions(null,null,null,null,null,null,null,null,null,null); }
    public static MigrationOptions defaults(long rows) { return new MigrationOptions(rows,null,null,null,null,null,null,null,null,null); }
}
