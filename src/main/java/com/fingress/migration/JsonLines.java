package com.fingress.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

final class JsonLines {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonLines() {}
    static String encode(String sql) throws IOException { return MAPPER.writeValueAsString(sql); }
    static String decode(String line) throws IOException { return MAPPER.readValue(line, String.class); }
}
