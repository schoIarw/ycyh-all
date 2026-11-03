package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/scriptApi", "/scriptApi"})
public class V9Controller {
  private final V9Store store;
  private final V9Catalog catalog;
  private final V9Tasks tasks;

  public V9Controller(V9Store store, V9Catalog catalog, V9Tasks tasks) {
    this.store = store;
    this.catalog = catalog;
    this.tasks = tasks;
  }

  private String user(HttpServletRequest req) {
    return (String) req.getAttribute("v9User");
  }

  @PostMapping("/groups/queryAgentInfoGroupRole")
  public ObjectNode agents(@RequestBody JsonNode req) {
    List<ObjectNode> rows = new ArrayList<>();
    String ip = text(req.path("queryParam"), "agentIp");
    for (ObjectNode agent : store.all("AGENT"))
      if (ip.isEmpty() || text(agent, "agentIp").contains(ip)) rows.add(V9Catalog.agentRow(agent));
    return ok("list.success", page(rows, req));
  }

  @PostMapping("/taskApply/scriptExecAuditing")
  public ObjectNode apply(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("scriptExecAuditing.success", tasks.apply(req, user(http)));
  }

  @PostMapping("/taskApply/listTaskApply")
  public ObjectNode scripts(@RequestBody JsonNode req) {
    List<ObjectNode> rows = new ArrayList<>();
    for (ObjectNode script : store.all("SCRIPT"))
      if (script.path("scriptVersionDto").path("deleted").asInt() == 0
          && script.path("scriptVersionDto").path("useState").asInt() == 1
          && catalog.matchesScript(script, req.path("queryParam")))
        rows.add(V9Catalog.scriptRow(script));
    return ok("查询数据成功！", page(rows, req));
  }

  @PostMapping("/taskTemplate/listCloneTask")
  public ObjectNode templates(@RequestBody JsonNode req) {
    return ok("list.success", page(tasks.templates(req), req));
  }

  @PostMapping("/taskExecute/listCompleteScriptTasks")
  public ObjectNode history(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("查询数据成功！", page(tasks.tasks(req, user(http), "history"), req));
  }

  @PostMapping("/runtime/listTaskRuntime")
  public ObjectNode runtimes(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("list.success", page(tasks.runtimes(req, user(http)), req));
  }

  @GetMapping({"/runtime/getOutPutMessage", "/taskExecute/getRealTimeOutPutMessage"})
  public ObjectNode output(@RequestParam long id, HttpServletRequest http) {
    return ok("get.out.put.message.success", tasks.output(id, user(http)));
  }

  @PostMapping("/taskTemplate/getScriptTemplateDetail")
  public ObjectNode templateDetail(@RequestBody JsonNode req) {
    return ok("查询成功", tasks.templateDetail(req));
  }

  @PostMapping("/taskTemplate/execAuditTemplateTask")
  public ObjectNode execTemplate(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("查询成功", tasks.startTemplate(req, user(http), false).get("taskId"));
  }

  @PostMapping("/taskExecute/listRunningScriptTasks")
  public ObjectNode taskDetails(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("list.success", page(tasks.tasks(req, user(http), "details"), req));
  }

  @PostMapping("/category/selectCategoryListDFS")
  public ObjectNode categories(@RequestBody JsonNode req) {
    return ok("list.success", catalog.categories(req));
  }

  @PostMapping("/myScript/getScriptDetail")
  public ObjectNode detail(@RequestBody JsonNode req) {
    return ok("查询成功", catalog.scriptDetail(req));
  }

  @PostMapping("/taskTemplate/queryTemplateGroupInfo")
  public ObjectNode groups(@RequestBody JsonNode req) {
    return ok("查询成功", tasks.templateGroups(id(req, "scriptTaskId")));
  }

  @PostMapping("/taskTemplate/queryTemplateAgentInfo")
  public ObjectNode templateAgents(@RequestBody JsonNode req) {
    return ok("查询成功", tasks.templateAgents(id(req, "scriptTaskId")));
  }

  @PostMapping("/taskExecute/stopScriptInstanceShell")
  public ObjectNode stop(
      @RequestBody(required = false) JsonNode req,
      @RequestParam org.springframework.util.MultiValueMap<String, String> query,
      HttpServletRequest http) {
    ObjectNode body = req == null ? obj() : copy(req);
    for (String key : List.of("taskId", "taskInstanceId"))
      if (!body.has(key) && query.containsKey(key)) body.put(key, query.getFirst(key));
    if (!body.has("runTimeIds") && query.containsKey("runTimeIds")) {
      ArrayNode ids = arr();
      for (String value : query.get("runTimeIds")) for (String id : value.split(",")) ids.add(id);
      body.set("runTimeIds", ids);
    }
    tasks.stop(body, user(http));
    return ok("script.shell.agent.stop.success", "");
  }

  @PostMapping("/taskExecute/scriptTaskStart")
  public ObjectNode start(@RequestBody JsonNode req, HttpServletRequest http) {
    tasks.start(req, user(http));
    return ok("script.task.start.success", "");
  }

  @PostMapping("/taskExecute/listTaskReadyToExecute")
  public ObjectNode ready(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("list.success", page(tasks.tasks(req, user(http), "ready"), req));
  }

  @PostMapping("/info/getTaskBindAgentInfo")
  public ObjectNode bound(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok(
        "list.success",
        page(tasks.bindings(id(req.path("queryParam"), "scriptTaskId"), user(http)), req));
  }

  @PostMapping("/taskTemplate/startCommonTask")
  public ObjectNode common(@RequestBody JsonNode req, HttpServletRequest http) {
    return ok("提交任务成功!", tasks.startTemplate(req, user(http), true));
  }
}
