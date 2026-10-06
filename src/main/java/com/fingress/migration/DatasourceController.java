package com.fingress.migration;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {
    private final DatasourceCatalog catalog;
    public DatasourceController(DatasourceCatalog catalog) { this.catalog=catalog; }
    @GetMapping public Object list(HttpServletResponse response) throws Exception {
        response.setHeader("Cache-Control","no-store");
        return Map.of("configured",catalog.configured(),"datasources",catalog.configured()?catalog.list():java.util.List.of());
    }
    // Selection may contain the saved password: only fetched for the explicitly selected row.
    @PostMapping(value="/{id}/select",consumes="application/json") public Object select(@PathVariable long id, HttpServletResponse response) throws Exception {
        response.setHeader("Cache-Control","no-store"); return catalog.get(id);
    }
}
