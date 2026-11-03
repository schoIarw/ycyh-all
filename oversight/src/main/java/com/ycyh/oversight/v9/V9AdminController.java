package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
@ConditionalOnProperty(
    name = "oversight.legacy-enabled",
    havingValue = "false",
    matchIfMissing = true)
public class V9AdminController {
  private final V9Store store;
  private final V9Catalog catalog;
  private final V9Tasks tasks;
  private final V9Identity identity;

  public V9AdminController(V9Store store, V9Catalog catalog, V9Tasks tasks, V9Identity identity) {
    this.store = store;
    this.catalog = catalog;
    this.tasks = tasks;
    this.identity = identity;
  }

  @GetMapping("/login")
  public String login() {
    return "login";
  }

  @GetMapping("/")
  public String index(Model model) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (ObjectNode t : store.all("TASK")) {
      list.add(
          Map.of(
              "id",
              t.path("id").asText(),
              "name",
              text(t, "taskName"),
              "owner",
              text(t, "_owner"),
              "status",
              text(t, "_status"),
              "level",
              t.path("_script").path("scriptVersionDto").path("level").asInt(),
              "created",
              time(t.path("_created").asLong())));
    }
    Collections.reverse(list);
    model.addAttribute("applications", list);
    return "v9/tasks";
  }

  @GetMapping("/approvals/{id}")
  public String detail(@PathVariable long id, Principal p, Model model) {
    ObjectNode task = tasks.task(id, p.getName());
    List<JsonNode> runtime = new ArrayList<>();
    for (ObjectNode r : store.all("RUNTIME"))
      if (r.path("scriptTaskId").asLong() == id && !r.path("_notExecuted").asBoolean())
        runtime.add(
            object(
                "id",
                r.path("id").asText(),
                "ip",
                text(r, "agentIp"),
                "state",
                r.path("state"),
                "log",
                tasks.output(r.path("id").asLong(), p.getName())));
    model.addAttribute(
        "task",
        Map.of(
            "id",
            task.path("id").asText(),
            "auditId",
            task.path("_auditId").asText(),
            "name",
            text(task, "taskName"),
            "status",
            text(task, "_status"),
            "auditState",
            task.path("_auditState").asInt(),
            "active",
            task.path("_active").asBoolean(),
            "launched",
            task.path("_launched").asBoolean(),
            "comment",
            text(task, "_approvalComment"),
            "snapshot",
            task.toPrettyString()));
    model.addAttribute("runtimes", runtime);
    return "v9/detail";
  }

  @PostMapping("/approvals/{id}/approve")
  public String approve(
      @PathVariable long id, @RequestParam(defaultValue = "") String reason, Principal p) {
    ObjectNode t = tasks.task(id, p.getName());
    tasks.approve(
        object(
            "taskId",
            t.get("_auditId"),
            "approvalState",
            1,
            "source",
            0,
            "approvalComment",
            reason),
        p.getName());
    return "redirect:/approvals/" + id;
  }

  @PostMapping("/approvals/{id}/reject")
  public String reject(@PathVariable long id, @RequestParam String reason, Principal p) {
    ObjectNode t = tasks.task(id, p.getName());
    tasks.approve(
        object(
            "taskId",
            t.get("_auditId"),
            "approvalState",
            2,
            "source",
            0,
            "approvalComment",
            reason),
        p.getName());
    return "redirect:/approvals/" + id;
  }

  @PostMapping("/approvals/{id}/start")
  public String start(@PathVariable long id, Principal p) {
    ObjectNode t = tasks.task(id, p.getName());
    tasks.start(
        object(
            "scriptTaskId",
            id,
            "driveMode",
            t.get("driveMode"),
            "eachNum",
            t.get("eachNum"),
            "firstBatch",
            !t.path("_launched").asBoolean(),
            "taskIps",
            tasks.bindingIds(t)),
        p.getName());
    return "redirect:/approvals/" + id;
  }

  @PostMapping("/approvals/{id}/stop")
  public String stop(@PathVariable long id, Principal p) {
    ObjectNode t = tasks.task(id, p.getName());
    ArrayNode addresses = arr();
    t.path("_bindings")
        .forEach(
            b ->
                addresses.add(
                    text(b.path("agent"), "agentIp")
                        + ":"
                        + b.path("agent").path("agentPort").asInt()));
    tasks.stop(
        object("taskId", id, "taskInstanceId", t.get("_instanceId"), "agentAddress", addresses),
        p.getName());
    return "redirect:/approvals/" + id;
  }

  @GetMapping("/catalog")
  public String catalog(Model model) {
    List<JsonNode> rows = new ArrayList<>();
    for (ObjectNode s : store.all("SCRIPT")) rows.add(V9Catalog.scriptRow(s));
    model.addAttribute("scripts", rows);
    model.addAttribute(
        "catalog",
        object(
                "agents",
                store.all("AGENT"),
                "groups",
                store.all("GROUP"),
                "templates",
                store.all("TEMPLATE"))
            .toPrettyString());
    return "v9/catalog";
  }

  @PostMapping("/catalog/import")
  public String importFile(@RequestParam MultipartFile file) {
    try {
      if (file.getSize() > 1048576) throw new IllegalArgumentException("catalog.too.large");
      catalog.importCatalog(
          parse(new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("invalid.catalog");
    }
    return "redirect:/catalog";
  }
}
