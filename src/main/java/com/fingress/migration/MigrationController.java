package com.fingress.migration;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import static com.fingress.migration.Model.*;

@RestController
@RequestMapping("/api")
public class MigrationController {
    private final MigrationService service; private final DatabaseGateway db;
    private record DownloadTicket(String planId,Map<String,Action> actions,long expires) {}
    private final java.util.concurrent.ConcurrentHashMap<String,DownloadTicket> tickets=new java.util.concurrent.ConcurrentHashMap<>();
    public MigrationController(MigrationService service, DatabaseGateway db) { this.service = service; this.db = db; }
    public record TablesRequest(ConnectionSpec connection, String schema) {}
    @GetMapping("/capabilities") public Map<String, Object> capabilities() { return Map.of("dialects", List.of("ORACLE", "POSTGRESQL"), "executionPolicy", "INSERT_ONLY", "version","1.1.0", "defaults",MigrationOptions.defaults()); }
    public record ColumnsRequest(ConnectionSpec connection,String schema,String table) {}
    @PostMapping("/columns") public List<Column> columns(@RequestBody ColumnsRequest request)throws Exception {
        try(var connection=db.connect(request.connection())){Table table=db.inspect(connection,request.schema(),request.table(),request.connection().dialect());if(table==null)throw new IllegalArgumentException("Table not found");return table.columns();}
    }
    @PostMapping("/preparations") public PreparationView prepare(@RequestBody PlanRequest request){return service.prepare(request);}
    @GetMapping("/preparations/{id}") public PreparationView preparation(@PathVariable String id){return service.preparation(id);}
    @PostMapping("/preparations/{id}/cancel") public PreparationView cancelPreparation(@PathVariable String id){return service.cancelPreparation(id);}
    @PostMapping(value = "/schemas", consumes = MediaType.APPLICATION_JSON_VALUE) public List<String> schemas(@RequestBody ConnectionSpec request) throws Exception { return db.schemas(request); }
    @PostMapping(value = "/tables", consumes = MediaType.APPLICATION_JSON_VALUE) public List<String> tables(@RequestBody TablesRequest request) throws Exception { return db.tables(request.connection(), request.schema()); }
    @PostMapping(value = "/plans", consumes = MediaType.APPLICATION_JSON_VALUE) public PlanView plan(@RequestBody PlanRequest request) throws Exception { return service.create(request); }
    @GetMapping("/plans/{id}") public PlanView plan(@PathVariable String id) { return service.view(id); }
    @GetMapping(value = "/plans/{id}/preview", produces = MediaType.TEXT_PLAIN_VALUE) public String preview(@PathVariable String id) throws Exception { return service.preview(id); }
    @DeleteMapping("/plans/{id}") public void delete(@PathVariable String id) throws Exception { service.delete(id); }
    @PostMapping(value = "/plans/{id}/download", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void download(@PathVariable String id, @RequestBody Map<String, Action> actions, HttpServletResponse response) throws Exception {
        String name = service.downloadName(id);
        response.setContentType("application/zip"); response.setHeader("Content-Disposition", "attachment; filename=\"" + name + ".zip\""); service.download(id, actions, response.getOutputStream(), name);
    }
    @PostMapping(value = "/plans/{id}/execute", consumes = MediaType.APPLICATION_JSON_VALUE) public JobView execute(@PathVariable String id, @RequestBody ExecuteRequest request) { return service.execute(id, request); }
    @GetMapping("/jobs/{id}") public JobView job(@PathVariable String id) { return service.job(id); }
    @GetMapping("/jobs") public List<JobView> history(){return service.history();}
    @PostMapping("/jobs/{id}/cancel") public JobView cancel(@PathVariable String id){return service.cancel(id);}
    @PostMapping("/jobs/{id}/resume") public JobView resume(@PathVariable String id,@RequestBody ConnectionSpec target){return service.resume(id,target);}
    @PostMapping("/plans/{id}/download-ticket") public Map<String,String> ticket(@PathVariable String id,@RequestBody Map<String,Action> actions){
        service.validateDownload(id,actions);long now=System.currentTimeMillis();tickets.entrySet().removeIf(e->e.getValue().expires()<now);
        if(tickets.size()>=64)throw new IllegalArgumentException("Too many pending downloads");String token=UUID.randomUUID().toString();
        tickets.put(token,new DownloadTicket(id,Map.copyOf(actions),now+120000));return Map.of("url","/api/downloads/"+token);
    }
    @GetMapping("/downloads/{token}") public void stream(@PathVariable String token,HttpServletResponse response)throws Exception{
        DownloadTicket ticket=tickets.remove(token);if(ticket==null||ticket.expires()<System.currentTimeMillis())throw new IllegalArgumentException("Download link expired. Request a new download.");
        download(ticket.planId(),ticket.actions(),response);
    }
}
