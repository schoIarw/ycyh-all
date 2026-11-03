package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.ycyh.oversight.SshRunner;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import org.quartz.CronExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class V9Tasks {
  private final V9Store store;
  private final V9Catalog catalog;
  private final V9Identity identity;
  private final SshRunner policy;
  private final V9SshExecutor ssh;
  private final ThreadPoolExecutor jobs =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
  private final ExecutorService hosts = Executors.newFixedThreadPool(8);

  public V9Tasks(
      V9Store store, V9Catalog catalog, V9Identity identity, SshRunner policy, V9SshExecutor ssh) {
    this.store = store;
    this.catalog = catalog;
    this.identity = identity;
    this.policy = policy;
    this.ssh = ssh;
    for (ObjectNode t : store.all("TASK"))
      if (t.path("_active").asBoolean())
        store.edit(
            "TASK",
            id(t, "id"),
            n -> {
              n.put("_active", false);
              n.put("_armed", false);
              n.put("_status", "UNKNOWN");
              n.put("_instanceState", 30);
              n.put("_end", System.currentTimeMillis());
            });
    for (ObjectNode rt : store.all("RUNTIME"))
      if (rt.path("state").asInt() == 10)
        store.edit(
            "RUNTIME",
            id(rt, "id"),
            n -> {
              n.put("state", 30);
              n.put(
                  "_stderr",
                  text(n, "_stderr")
                      + "\nService restarted; remote process state unknown. No automatic retry.");
            });
  }

  public ObjectNode apply(JsonNode request, String user) {
    ObjectNode req = copy(request);
    req.put("startType", integer(req, "startType", 0));
    ObjectNode script = catalog.script(id(req, "scriptInfoVersionId"));
    JsonNode version = script.path("scriptVersionDto");
    if (version.path("deleted").asInt() != 0 || version.path("useState").asInt() != 1)
      throw new IllegalArgumentException("script.disabled");
    ObjectNode info = copy(req.path("taskInfo"));
    String name = text(info, "taskName");
    if (name.isBlank() || name.length() > 200)
      throw new IllegalArgumentException("taskName.required");
    int drive = integer(info, "driveMode", 3),
        each = integer(info, "eachNum", 100),
        scheduler = integer(info, "taskScheduler", 0);
    if (drive < 1 || drive > 4 || each < 0 || each > 200 || scheduler < 0 || scheduler > 2)
      throw new IllegalArgumentException("invalid.taskInfo");
    if (scheduler == 2 && id(info, "taskTime") < 1)
      throw new IllegalArgumentException("invalid.taskTime");
    if (scheduler == 1) nextCron(text(info, "taskCron"), System.currentTimeMillis());
    String exec = text(req, "execuser");
    if (exec.isBlank()) exec = text(info, "execUser");
    if (exec.isBlank()) exec = text(script, "execuser");
    if (exec.isBlank()) throw new IllegalArgumentException("execuser.required");
    List<ObjectNode> selected = resolveAgents(req);
    if (selected.isEmpty() || selected.size() > 200)
      throw new IllegalArgumentException("invalid.agents");
    for (ObjectNode a : selected) policy.validate(text(a, "agentIp"), exec);
    ArrayNode params = parameters(version, req.path("params"));
    List<JsonNode> attachments = attachments(script, req.path("scriptTempAttachments"));
    int level = version.path("level").asInt();
    long auditor = level == 0 ? 0 : id(req, "auditUserId");
    if (level != 0
        && (auditor != identity.userId(identity.reviewer) || auditor == identity.userId(user)))
      throw new IllegalArgumentException("independent.auditUserId.required");
    if (level != 0 && !text(req, "auditUser").equals(identity.name(auditor)))
      throw new IllegalArgumentException("auditUser.mismatch");
    final String executionUser = exec;
    final long reviewer = auditor;
    ObjectNode result =
        store.transaction(
            () -> {
              long task = store.nextId(), audit = level == 0 ? 0 : store.nextId();
              ArrayNode bindings = arr();
              for (ObjectNode a : selected) bindings.add(object("id", store.nextId(), "agent", a));
              ObjectNode t =
                  object(
                      "id",
                      task,
                      "_key",
                      Long.toString(task),
                      "_request",
                      req,
                      "_script",
                      script,
                      "_params",
                      params,
                      "_attachments",
                      attachments,
                      "_bindings",
                      bindings,
                      "_owner",
                      user,
                      "_reviewer",
                      reviewer,
                      "_auditId",
                      audit == 0 ? null : audit,
                      "_status",
                      level == 0 ? "READY" : "AWAITING_APPROVAL",
                      "_auditState",
                      level == 0 ? 1 : 0,
                      "_instanceState",
                      0,
                      "_active",
                      false,
                      "_armed",
                      false,
                      "_launched",
                      false,
                      "_created",
                      System.currentTimeMillis(),
                      "execUser",
                      executionUser,
                      "taskName",
                      name,
                      "driveMode",
                      drive,
                      "eachNum",
                      each,
                      "taskScheduler",
                      scheduler,
                      "timeout",
                      info.hasNonNull("timeout")
                          ? Long.parseLong(info.path("timeout").asText())
                          : -1L);
              store.put("TASK", t);
              return object("auditId", audit == 0 ? null : audit, "taskId", task);
            });
    if (level == 0)
      start(
          object(
              "scriptTaskId",
              result.get("taskId"),
              "driveMode",
              drive,
              "eachNum",
              each,
              "firstBatch",
              true,
              "taskIps",
              bindingIds(store.get("TASK", id(result, "taskId")))),
          user);
    return result;
  }

  private List<ObjectNode> resolveAgents(JsonNode req) {
    LinkedHashMap<Long, ObjectNode> selected = new LinkedHashMap<>();
    JsonNode explicit = req.path("chosedAgentUsers");
    if (explicit.isArray() && !explicit.isEmpty())
      for (JsonNode supplied : explicit) {
        ObjectNode a = store.get("AGENT", id(supplied, "sysmAgentInfoId"));
        if (!text(supplied, "agentIp").equals(text(a, "agentIp"))
            || supplied.hasNonNull("agentPort")
                && supplied.path("agentPort").asInt() != a.path("agentPort").asInt())
          throw new IllegalArgumentException("agent.identity.mismatch");
        if (selected.put(id(a, "sysmAgentInfoId"), a) != null)
          throw new IllegalArgumentException("duplicate.agent");
      }
    else {
      if (!req.path("resGroupFlag").asBoolean() || !req.path("chosedResGroups").isArray())
        throw new IllegalArgumentException("agents.or.groups.required");
      for (JsonNode g : req.path("chosedResGroups")) {
        ObjectNode group = store.get("GROUP", id(g, "sysmComputerGroupId"));
        for (JsonNode id : group.path("_agentIds")) {
          ObjectNode a = store.get("AGENT", id.asLong());
          selected.put(id.asLong(), a);
        }
      }
    }
    return new ArrayList<>(selected.values());
  }

  private ArrayNode parameters(JsonNode version, JsonNode supplied) {
    if (!supplied.isArray()) throw new IllegalArgumentException("params.array.required");
    Map<Integer, ObjectNode> values = new TreeMap<>();
    for (JsonNode p : version.path("parameterValidationDtoList"))
      values.put(integer(p, "paramOrder", 0), copy(p));
    Set<Integer> seen = new HashSet<>();
    for (JsonNode p : supplied) {
      int order = integer(p, "paramOrder", 0);
      if (order < 1 || order > 200 || !seen.add(order) || !values.containsKey(order))
        throw new IllegalArgumentException("invalid.paramOrder");
      ObjectNode definition = values.get(order);
      if (p.hasNonNull("paramName") && !text(p, "paramName").equals(text(definition, "paramName"))
          || p.hasNonNull("id") && id(p, "id") != id(definition, "id"))
        throw new IllegalArgumentException("parameter.identity.mismatch");
      if (!p.has("paramDefaultValue") || p.get("paramDefaultValue").isNull())
        throw new IllegalArgumentException("paramDefaultValue.required");
      definition.set("paramDefaultValue", p.get("paramDefaultValue"));
    }
    ArrayNode result = arr();
    for (ObjectNode p : values.values()) {
      String value = text(p, "paramDefaultValue"), type = text(p, "paramType");
      if (value.indexOf(0) >= 0) throw new IllegalArgumentException("invalid.parameter");
      try {
        if (Set.of("Int", "Integer", "Long").contains(type)) Long.parseLong(value);
        if (Set.of("Double", "Float", "Number").contains(type)) new java.math.BigDecimal(value);
        if (type.equals("Boolean") && !Set.of("true", "false").contains(value))
          throw new IllegalArgumentException();
        String rule = text(p, "checkRule");
        if (!rule.isEmpty() && !value.matches(rule)) throw new IllegalArgumentException();
      } catch (Exception e) {
        throw new IllegalArgumentException("parameter.validation.failed");
      }
      result.add(p);
    }
    return result;
  }

  private List<JsonNode> attachments(JsonNode script, JsonNode selected) {
    List<JsonNode> result = new ArrayList<>();
    if (selected.isMissingNode()) return result;
    if (!selected.isArray()) throw new IllegalArgumentException("invalid.attachments");
    for (JsonNode requested : selected) {
      long id = id(requested, "id");
      JsonNode match = null;
      for (JsonNode a : script.path("scriptVersionDto").path("attachmentUploadDtoList")) {
        long aid = a.hasNonNull("id") ? id(a, "id") : a.path("response").path("id").asLong();
        if (aid == id) match = a;
      }
      if (match == null) throw new IllegalArgumentException("attachment.not.found");
      V9Catalog.validateAttachment(match);
      if (text(match, "contents").isEmpty())
        throw new IllegalArgumentException("attachment.content.missing");
      result.add(match.deepCopy());
    }
    return result;
  }

  public ObjectNode task(long id, String user) {
    ObjectNode t = store.get("TASK", id);
    authorize(t, user);
    return t;
  }

  private void authorize(JsonNode t, String user) {
    if (!identity.admin(user) && !text(t, "_owner").equals(user))
      throw new IllegalArgumentException("permission.denied");
  }

  public void approve(JsonNode req, String user) {
    long auditId = id(req, "taskId");
    int state = integer(req, "approvalState", 0), source = integer(req, "source", -1);
    String comment = text(req, "approvalComment");
    if (!Set.of(1, 2).contains(state)
        || source < 0
        || source > 2
        || state == 2 && comment.isBlank()) throw new IllegalArgumentException("invalid.approval");
    ObjectNode found =
        store.all("TASK").stream()
            .filter(t -> t.path("_auditId").asLong() == auditId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("audit.not.found"));
    store.edit(
        "TASK",
        id(found, "id"),
        t -> {
          if (!identity.admin(user) || t.path("_reviewer").asLong() != identity.userId(user))
            throw new IllegalArgumentException("reviewer.mismatch");
          if (req.hasNonNull("userId") && id(req, "userId") != identity.userId(user)
              || req.hasNonNull("loginName") && !text(req, "loginName").equals(user))
            throw new IllegalArgumentException("reviewer.mismatch");
          if (!text(t, "_status").equals("AWAITING_APPROVAL"))
            throw new IllegalArgumentException("audit.already.decided");
          t.put("_auditState", state);
          t.put("_status", state == 1 ? "READY" : "REJECTED");
          t.put("_approvalComment", comment);
          t.put("_approvalSource", source);
          t.set("_approval", copy(req));
          t.put("_audited", System.currentTimeMillis());
          t.put("_auditor", user);
        });
  }

  public void start(JsonNode req, String user) {
    long id = id(req, "scriptTaskId");
    ObjectNode task = task(id, user);
    int drive = integer(req, "driveMode", 0), each = integer(req, "eachNum", -1);
    if (drive < 1
        || drive > 4
        || each < 0
        || each > 200
        || !req.path("firstBatch").isBoolean()
        || !req.path("taskIps").isArray()
        || req.path("taskIps").isEmpty())
      throw new IllegalArgumentException("invalid.start.request");
    boolean first = req.path("firstBatch").asBoolean();
    Set<Long> chosen = new LinkedHashSet<>();
    for (JsonNode n : req.path("taskIps")) {
      long value = id(object("value", n), "value");
      if (value <= 0 || !chosen.add(value)) throw new IllegalArgumentException("invalid.taskIps");
    }
    Set<Long> available = new HashSet<>();
    task.path("_bindings").forEach(n -> available.add(n.path("id").asLong()));
    if (!available.containsAll(chosen))
      throw new IllegalArgumentException("taskIps.must.be.binding.ids");
    int scheduler = task.path("taskScheduler").asInt();
    if (first && scheduler != 0) {
      store.edit(
          "TASK",
          id,
          t -> {
            validateReady(t, first);
            t.put("_armed", true);
            t.put("_status", "SCHEDULED");
            t.set("_scheduledBindings", JSON.valueToTree(chosen));
            t.put("_scheduleUser", user);
            t.put("driveMode", drive);
            t.put("eachNum", each);
            JsonNode info = t.path("_request").path("taskInfo");
            t.put(
                "_nextFire",
                scheduler == 2
                    ? id(info, "taskTime")
                    : nextCron(text(info, "taskCron"), System.currentTimeMillis()));
          });
      return;
    }
    launch(id, chosen, drive, each, first, false, user);
  }

  private void validateReady(ObjectNode t, boolean first) {
    if (t.path("_auditState").asInt() != 1) throw new IllegalArgumentException("task.not.approved");
    if (t.path("_active").asBoolean()) throw new IllegalArgumentException("task.already.running");
    if (first && t.path("_launched").asBoolean())
      throw new IllegalArgumentException("firstBatch.already.started");
    if (!first && !t.path("_launched").asBoolean())
      throw new IllegalArgumentException("firstBatch.required");
    if (text(t, "_status").equals("UNKNOWN"))
      throw new IllegalArgumentException("task.state.unknown");
  }

  private void launch(
      long id,
      Set<Long> chosen,
      int drive,
      int each,
      boolean first,
      boolean scheduled,
      String actor) {
    ObjectNode launched =
        store.transaction(
            () -> {
              ObjectNode t = store.lock("TASK", id);
              if (scheduled) t.put("_launched", false);
              validateReady(t, first);
              long instance = first ? store.nextId() : t.path("_instanceId").asLong();
              for (ObjectNode rt : store.all("RUNTIME"))
                if (!rt.path("_notExecuted").asBoolean()
                    && rt.path("taskInstanceId").asLong() == instance
                    && chosen.contains(rt.path("scriptTaskIpsId").asLong()))
                  throw new IllegalArgumentException("binding.already.started");
              t.remove("_end");
              t.put("_instanceId", instance);
              t.put("_instanceState", 10);
              t.put("_active", true);
              t.put("_launched", true);
              t.put("_armed", false);
              t.put("_status", "RUNNING");
              t.put("_start", System.currentTimeMillis());
              t.put("_startUser", actor);
              t.put("driveMode", drive);
              t.put("eachNum", each);
              ArrayNode rows = arr();
              for (JsonNode binding : t.path("_bindings"))
                if (chosen.contains(binding.path("id").asLong())) {
                  JsonNode agent = binding.path("agent");
                  long runtime = store.nextId();
                  ObjectNode row =
                      object(
                          "id",
                          runtime,
                          "_key",
                          Long.toString(instance),
                          "scriptTaskId",
                          id,
                          "taskInstanceId",
                          instance,
                          "srcScriptUuid",
                          t.path("_script").path("scriptVersionDto").path("srcScriptUuid"),
                          "state",
                          10,
                          "scriptName",
                          t.get("taskName"),
                          "agentIp",
                          agent.path("agentIp"),
                          "agentPort",
                          agent.path("agentPort"),
                          "execUser",
                          t.get("execUser"),
                          "scriptTaskIpsId",
                          binding.path("id"),
                          "expectLastline",
                          t.path("_script").path("scriptVersionDto").path("expectLastline"),
                          "expectType",
                          t.path("_script").path("scriptVersionDto").path("expectType"),
                          "timeout",
                          0,
                          "timeoutValue",
                          t.get("timeout"),
                          "startType",
                          t.path("_request").path("startType"),
                          "createTime",
                          time(System.currentTimeMillis()),
                          "_stdout",
                          "",
                          "_stderr",
                          "",
                          "_queued",
                          true,
                          "_start",
                          null,
                          "_end",
                          null,
                          "bizId",
                          "agent-script-start-from-exec-" + runtime);
                  store.put("RUNTIME", row);
                  rows.add(runtime);
                }
              t.set("_runIds", rows);
              store.put("TASK", t);
              store.put(
                  "INSTANCE",
                  object("id", instance, "_key", Long.toString(id), "_taskSnapshot", t.deepCopy()));
              return t;
            });
    try {
      jobs.execute(() -> run(launched));
    } catch (RejectedExecutionException e) {
      for (JsonNode runtime : launched.path("_runIds"))
        store.edit(
            "RUNTIME",
            runtime.asLong(),
            rt -> {
              rt.put("state", 30);
              rt.put("_stderr", "Execution queue full; no remote execution");
              rt.put("_end", System.currentTimeMillis());
            });
      finishTask(id);
    }
  }

  private void run(ObjectNode task) {
    try {
      List<Long> ids = new ArrayList<>();
      task.path("_runIds").forEach(n -> ids.add(n.asLong()));
      int drive = task.path("driveMode").asInt(),
          batch = drive == 4 ? 1 : Math.max(1, task.path("eachNum").asInt());
      for (int from = 0; from < ids.size(); from += batch) {
        List<Future<?>> launched = new ArrayList<>();
        for (Long id : ids.subList(from, Math.min(from + batch, ids.size())))
          launched.add(hosts.submit(() -> runHost(task, id)));
        for (Future<?> job : launched) job.get();
        if (drive == 2
            && ids.subList(from, Math.min(from + batch, ids.size())).stream()
                .anyMatch(id -> store.get("RUNTIME", id).path("state").asInt() != 20)) {
          for (Long id : ids.subList(Math.min(from + batch, ids.size()), ids.size()))
            store.edit(
                "RUNTIME",
                id,
                rt -> {
                  rt.put("_notExecuted", true);
                  rt.put("state", 30);
                  rt.put(
                      "_stderr", "Batch stopped after an earlier failure; host was not executed");
                  rt.put("_end", System.currentTimeMillis());
                });
          break;
        }
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      for (JsonNode id : task.path("_runIds"))
        store.edit(
            "RUNTIME",
            id.asLong(),
            rt -> {
              if (rt.path("state").asInt() == 10) {
                rt.put("state", 30);
                rt.put("_stderr", "Execution interrupted; remote state requires inspection");
                rt.put("_end", System.currentTimeMillis());
              }
            });
    } finally {
      finishTask(id(task, "id"));
    }
  }

  private void runHost(ObjectNode task, long id) {
    ObjectNode rt = store.get("RUNTIME", id);
    if (rt.path("state").asInt() == 60) return;
    store.edit(
        "RUNTIME",
        id,
        n -> {
          n.put("_queued", false);
          n.put("_start", System.currentTimeMillis());
        });
    try {
      List<String> args = new ArrayList<>();
      for (JsonNode p : task.path("_params")) {
        int order = p.path("paramOrder").asInt();
        while (args.size() < order) args.add("");
        args.set(order - 1, text(p, "paramDefaultValue"));
      }
      List<JsonNode> attachments = new ArrayList<>();
      task.path("_attachments").forEach(attachments::add);
      JsonNode script = task.path("_script"), version = script.path("scriptVersionDto");
      V9SshExecutor.Result result =
          ssh.execute(
              id,
              text(version.path("scriptContentDto"), "content"),
              text(script, "scriptType"),
              text(rt, "agentIp"),
              text(rt, "execUser"),
              args,
              attachments,
              task.path("timeout").asLong());
      boolean expected =
          version.path("expectType").asInt() == 1
              ? (!version.hasNonNull("expectLastline")
                  ? result.exitCode == 0
                  : result.lastLine.equals(text(version, "expectLastline")))
              : (!version.hasNonNull("expectLastline")
                  ? result.exitCode == 0
                  : Integer.toString(result.exitCode).equals(text(version, "expectLastline")));
      store.edit(
          "RUNTIME",
          id,
          n -> {
            if (n.path("state").asInt() != 60)
              n.put("state", result.cancelled ? 60 : !result.timedOut && expected ? 20 : 30);
            n.put("_stdout", result.stdout);
            n.put("_stderr", result.stderr + (result.timedOut ? "\nExecution timeout" : ""));
            n.put("_exitCode", result.exitCode);
            n.put("timeout", result.timedOut ? 1 : 0);
            n.put("_end", System.currentTimeMillis());
          });
    } catch (Exception e) {
      store.edit(
          "RUNTIME",
          id,
          n -> {
            if (n.path("state").asInt() != 60) n.put("state", 30);
            n.put("_stderr", "SSH/remote execution failed: " + e.getClass().getSimpleName());
            n.put("_end", System.currentTimeMillis());
          });
    }
  }

  private void finishTask(long id) {
    store.edit(
        "TASK",
        id,
        t -> {
          List<ObjectNode> runtimes = forInstance(t.path("_instanceId").asLong());
          int state =
              runtimes.stream().anyMatch(r -> r.path("state").asInt() == 30)
                  ? 30
                  : runtimes.stream().anyMatch(r -> r.path("state").asInt() == 60)
                      ? 60
                      : runtimes.stream().allMatch(r -> r.path("state").asInt() == 20) ? 20 : 10;
          t.put("_active", false);
          t.put("_instanceState", state);
          t.put("_end", System.currentTimeMillis());
          t.put(
              "_status",
              state == 20
                  ? "COMPLETE"
                  : state == 60 ? "STOPPED" : state == 30 ? "FAILED" : "READY");
          if (t.hasNonNull("_instanceId"))
            store.put(
                "INSTANCE",
                object(
                    "id",
                    t.path("_instanceId").asLong(),
                    "_key",
                    Long.toString(id),
                    "_taskSnapshot",
                    t.deepCopy()));
          if (t.path("taskScheduler").asInt() == 1) {
            try {
              t.put(
                  "_nextFire",
                  nextCron(
                      text(t.path("_request").path("taskInfo"), "taskCron"),
                      System.currentTimeMillis()));
              t.put("_armed", true);
              t.put("_status", "SCHEDULED");
            } catch (IllegalArgumentException exhausted) {
              t.put("_armed", false);
            }
          }
        });
  }

  private List<ObjectNode> forInstance(long id) {
    return store.all("RUNTIME").stream()
        .filter(r -> !r.path("_notExecuted").asBoolean() && r.path("taskInstanceId").asLong() == id)
        .collect(Collectors.toList());
  }

  public ArrayNode bindingIds(JsonNode t) {
    ArrayNode ids = arr();
    t.path("_bindings").forEach(n -> ids.add(n.get("id")));
    return ids;
  }

  public List<ObjectNode> bindings(long id, String user) {
    ObjectNode t = task(id, user);
    List<ObjectNode> rows = new ArrayList<>();
    for (JsonNode b : t.path("_bindings")) {
      ObjectNode a = V9Catalog.agentRow(b.path("agent"));
      a.set("taskIpsId", b.get("id"));
      rows.add(a);
    }
    return rows;
  }

  public List<ObjectNode> runtimes(JsonNode request, String user) {
    JsonNode q = request.path("queryParam");
    long instance = q.hasNonNull("taskInstanceId") ? id(q, "taskInstanceId") : -1;
    List<ObjectNode> rows = new ArrayList<>();
    for (ObjectNode r : store.all("RUNTIME")) {
      if (r.path("_notExecuted").asBoolean()) continue;
      ObjectNode t = store.get("TASK", r.path("scriptTaskId").asLong());
      if (!identity.admin(user) && !text(t, "_owner").equals(user)) continue;
      if (instance > 0 && instance != r.path("taskInstanceId").asLong()) continue;
      if (!text(q, "agentIp").isEmpty() && !text(r, "agentIp").contains(text(q, "agentIp")))
        continue;
      if (q.hasNonNull("state") && q.path("state").asInt() != r.path("state").asInt()) continue;
      if (q.hasNonNull("startTime") && r.path("_start").asLong() < q.path("startTime").asLong()
          || q.hasNonNull("endTime")
              && (!r.hasNonNull("_end") || r.path("_end").asLong() > q.path("endTime").asLong()))
        continue;
      rows.add(runtimeRow(r));
    }
    return rows;
  }

  public ObjectNode runtimeRow(JsonNode r) {
    ObjectNode n =
        fields(
            r,
            "id,scriptTaskId,taskInstanceId,srcScriptUuid,state,scriptName,agentIp,agentPort,execUser,providerIp,providerPort,elapsedTime,expectLastline,expectType,timeout,timeoutValue,startType,createTime,agentTaskId,bizId,scriptTaskIpsId,retry");
    n.set(
        "startTime",
        r.hasNonNull("_start")
            ? JSON.valueToTree(time(r.path("_start").asLong()))
            : NullNode.instance);
    n.set(
        "endTime",
        r.hasNonNull("_end") ? JSON.valueToTree(time(r.path("_end").asLong())) : NullNode.instance);
    n.put(
        "elapsedTime",
        r.hasNonNull("_start")
            ? Math.max(
                0,
                ((r.hasNonNull("_end") ? r.path("_end").asLong() : System.currentTimeMillis())
                        - r.path("_start").asLong())
                    / 1000)
            : 0);
    return n;
  }

  public String output(long runtime, String user) {
    ObjectNode rt = store.get("RUNTIME", runtime);
    task(rt.path("scriptTaskId").asLong(), user);
    V9SshExecutor.Result live = ssh.live(runtime);
    return live != null
        ? live.log()
        : "stdOut:\n" + text(rt, "_stdout") + "\nstdError:\n" + text(rt, "_stderr") + "\n";
  }

  public void stop(JsonNode req, String user) {
    if (!req.hasNonNull("taskId") && !req.hasNonNull("taskInstanceId"))
      throw new IllegalArgumentException("task.or.instance.required");
    if (req.path("runTimeIds").isEmpty() && req.path("agentAddress").isEmpty())
      throw new IllegalArgumentException("runtimes.or.addresses.required");
    final long requestedInstance =
        req.hasNonNull("taskInstanceId") ? id(req, "taskInstanceId") : -1;
    long instance = requestedInstance;
    ObjectNode task;
    if (req.hasNonNull("taskId")) {
      long id = id(req, "taskId");
      try {
        task = task(id, user);
      } catch (IllegalArgumentException e) {
        task =
            store.all("TASK").stream()
                .filter(t -> t.path("_instanceId").asLong() == id)
                .findFirst()
                .orElseThrow(() -> e);
        authorize(task, user);
      }
    } else {
      task =
          store.all("TASK").stream()
              .filter(t -> t.path("_instanceId").asLong() == requestedInstance)
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("instance.not.found"));
      authorize(task, user);
    }
    if (instance > 0 && task.path("_instanceId").asLong() != instance)
      throw new IllegalArgumentException("instance.mismatch");
    if (instance < 0) instance = task.path("_instanceId").asLong();
    Set<Long> ids = new HashSet<>();
    for (JsonNode n : req.path("runTimeIds")) ids.add(id(object("value", n), "value"));
    Set<String> addresses = new HashSet<>();
    if (req.path("agentAddress").isTextual())
      addresses.addAll(Arrays.asList(req.path("agentAddress").asText().split(",")));
    else for (JsonNode n : req.path("agentAddress")) addresses.add(n.asText());
    List<ObjectNode> selected = new ArrayList<>();
    for (ObjectNode rt : forInstance(instance))
      if (ids.contains(id(rt, "id"))
          || addresses.contains(text(rt, "agentIp") + ":" + rt.path("agentPort").asInt()))
        selected.add(rt);
    Set<String> foundAddresses =
        selected.stream()
            .map(rt -> text(rt, "agentIp") + ":" + rt.path("agentPort").asInt())
            .collect(Collectors.toSet());
    if (!foundAddresses.containsAll(addresses))
      throw new IllegalArgumentException("agent.not.in.task");
    if (selected.isEmpty()
        || !selected.stream().map(r -> id(r, "id")).collect(Collectors.toSet()).containsAll(ids))
      throw new IllegalArgumentException("runtime.not.in.task");
    boolean confirmed = true;
    for (ObjectNode rt : selected) {
      if (rt.path("state").asInt() != 10) continue;
      try {
        ssh.stop(id(rt, "id"), text(rt, "agentIp"), text(rt, "execUser"));
        store.edit(
            "RUNTIME",
            id(rt, "id"),
            n -> {
              n.put("state", 60);
              n.put("_end", System.currentTimeMillis());
            });
      } catch (Exception e) {
        confirmed = false;
        store.edit(
            "RUNTIME",
            id(rt, "id"),
            n -> {
              n.put("state", 30);
              n.put("_stderr", "Remote stop not confirmed; inspect host process");
              n.put("_end", System.currentTimeMillis());
            });
      }
    }
    if (!task.path("_active").asBoolean()) finishTask(id(task, "id"));
    if (!confirmed) throw new IllegalArgumentException("stop.not.confirmed");
  }

  public List<ObjectNode> tasks(JsonNode request, String user, String mode) {
    JsonNode q = request.path("queryParam");
    List<ObjectNode> candidates =
        mode.equals("history")
            ? store.all("INSTANCE").stream()
                .map(i -> copy(i.path("_taskSnapshot")))
                .collect(Collectors.toList())
            : store.all("TASK");
    List<ObjectNode> rows = new ArrayList<>();
    for (ObjectNode t : candidates) {
      if (!identity.admin(user) && !text(t, "_owner").equals(user)) continue;
      if (q.hasNonNull("scriptTaskId") && id(q, "scriptTaskId") != id(t, "id")) continue;
      if (mode.equals("history")
          && q.has("allTaskFlag")
          && !q.path("allTaskFlag").asBoolean()
          && !text(t, "_owner").equals(user)) continue;
      if (mode.equals("ready")
          && (t.path("_auditState").asInt() != 1
              || t.path("_active").asBoolean()
              || t.path("_armed").asBoolean()
              || !hasUnstarted(t))) continue;
      if (mode.equals("history") && (!t.hasNonNull("_instanceId") || t.path("_active").asBoolean()))
        continue;
      if (!text(q, "taskName").isEmpty() && !text(t, "taskName").contains(text(q, "taskName")))
        continue;
      if (!text(q, "applyUser").isEmpty() && !text(t, "_owner").contains(text(q, "applyUser")))
        continue;
      JsonNode s = t.path("_script");
      if (!text(q, "scriptName").isEmpty()
          && !text(s, "scriptName").contains(text(q, "scriptName"))) continue;
      if (q.hasNonNull("level")
          && q.path("level").asInt() != s.path("scriptVersionDto").path("level").asInt()) continue;
      if (q.hasNonNull("categoryId") && id(q, "categoryId") != s.path("categoryId").asLong())
        continue;
      if (q.hasNonNull("scriptTaskSource") && q.path("scriptTaskSource").asInt() != 1) continue;
      if (!inRange(t.path("_start").asLong(), q.path("startTimeRange"))
          || !inRange(t.path("_end").asLong(), q.path("endTimeRange"))) continue;
      rows.add(taskRow(t));
    }
    Collections.reverse(rows);
    return rows;
  }

  private boolean inRange(long time, JsonNode range) {
    return !range.isArray()
        || range.size() != 2
        || time >= range.get(0).asLong() && time <= range.get(1).asLong();
  }

  private boolean hasUnstarted(JsonNode task) {
    if (!task.path("_launched").asBoolean()) return true;
    Set<Long> used =
        forInstance(task.path("_instanceId").asLong()).stream()
            .map(r -> r.path("scriptTaskIpsId").asLong())
            .collect(Collectors.toSet());
    for (JsonNode b : task.path("_bindings"))
      if (!used.contains(b.path("id").asLong())) return true;
    return false;
  }

  public ObjectNode taskRow(JsonNode t) {
    ObjectNode n =
        object(
            "scriptTaskId",
            t.get("id"),
            "uniqueUuid",
            t.path("_script").path("uniqueUuid"),
            "srcScriptUuid",
            t.path("_script").path("scriptVersionDto").path("srcScriptUuid"),
            "taskName",
            t.get("taskName"),
            "eachNum",
            t.get("eachNum"),
            "execUser",
            t.get("execUser"),
            "taskScheduler",
            t.get("taskScheduler"),
            "taskTime",
            t.path("_request").path("taskInfo").path("taskTime"),
            "taskCron",
            t.path("_request").path("taskInfo").path("taskCron"),
            "publishDesc",
            t.path("_request").path("taskInfo").path("publishDesc"),
            "startUser",
            t.get("_startUser"),
            "startTime",
            t.hasNonNull("_start") ? time(t.path("_start").asLong()) : null,
            "endTime",
            t.hasNonNull("_end") ? time(t.path("_end").asLong()) : null,
            "timeout",
            t.get("timeout"),
            "taskTimeForDispaly",
            null,
            "type",
            1,
            "driveMode",
            t.get("driveMode"),
            "startType",
            t.path("_request").path("startType"),
            "readyToExecute",
            t.path("_auditState").asInt() == 1 && !t.path("_active").asBoolean() && hasUnstarted(t),
            "applyUser",
            t.get("_owner"),
            "auditUserId",
            t.path("_reviewer").asLong() == 0 ? null : t.get("_reviewer"),
            "applyUserId",
            identity.userId(text(t, "_owner")),
            "scriptCategoryName",
            t.path("_script").path("categoryPath"),
            "scriptTaskInstanceId",
            t.get("_instanceId"),
            "scriptTaskInstanceState",
            t.get("_instanceState"),
            "runAgentCount",
            t.hasNonNull("_instanceId")
                ? forInstance(t.path("_instanceId").asLong()).stream()
                    .filter(r -> r.path("state").asInt() == 10)
                    .count()
                : 0,
            "level",
            t.path("_script").path("scriptVersionDto").path("level"),
            "elapsedTime",
            t.hasNonNull("_start")
                ? Math.max(
                    0,
                    ((t.hasNonNull("_end") ? t.path("_end").asLong() : System.currentTimeMillis())
                            - t.path("_start").asLong())
                        / 1000)
                : null,
            "scriptTaskSource",
            1,
            "version",
            t.path("_script").path("scriptVersionDto").path("version"),
            "scriptNameZh",
            t.path("_script").path("scriptNameZh"),
            "scriptName",
            t.path("_script").path("scriptName"),
            "workOrderNumber",
            t.path("_request").path("workOrderNum"),
            "categoryId",
            t.path("_script").path("categoryId"),
            "auditType",
            t.get("_auditState"),
            "iscriptAuditRelationId",
            t.get("_auditId"),
            "auditTime",
            t.hasNonNull("_audited") ? time(t.path("_audited").asLong()) : null,
            "auditUser",
            t.get("_auditor"));
    for (String k :
        "creatorId,creatorName,updatorId,updatorName,createTime,updateTime,state,applyTime,itsmOrderStartTime,itsmOrderEndTime"
            .split(",")) n.putNull(k);
    return n;
  }

  public ObjectNode templateDetail(JsonNode req) {
    ObjectNode template = store.get("TEMPLATE", id(req, "taskId"));
    ObjectNode script = catalog.script(id(template, "scriptInfoVersionId"));
    if (!text(req, "srcScriptUuid").equals(text(script.path("scriptVersionDto"), "srcScriptUuid")))
      throw new IllegalArgumentException("template.script.mismatch");
    ObjectNode detail = V9Catalog.clean(script);
    detail
        .with("scriptVersionDto")
        .set(
            "parameterValidationDtoList",
            template.has("params")
                ? template.get("params")
                : script.path("scriptVersionDto").path("parameterValidationDtoList"));
    return detail;
  }

  public ObjectNode startTemplate(JsonNode request, String user, boolean simple) {
    long templateId = simple ? id(request, "commonTaskId") : id(request.path("taskInfo"), "id");
    ObjectNode template = store.get("TEMPLATE", templateId), body = template.deepCopy();
    ObjectNode info = body.has("taskInfo") ? copy(body.get("taskInfo")) : obj();
    if (!simple) {
      if (id(request, "scriptInfoVersionId") != id(template, "scriptInfoVersionId"))
        throw new IllegalArgumentException("template.script.mismatch");
      copy(request)
          .fields()
          .forEachRemaining(
              e -> {
                if (!e.getKey().equals("taskInfo")) body.set(e.getKey(), e.getValue());
              });
      request.path("taskInfo").fields().forEachRemaining(e -> info.set(e.getKey(), e.getValue()));
    } else {
      if (request.has("params")) body.set("params", request.get("params"));
      if (request.has("auditUserId")) body.set("auditUserId", request.get("auditUserId"));
      else if (request.has("audituserId")) body.set("auditUserId", request.get("audituserId"));
    }
    if (body.hasNonNull("auditUserId") && text(body, "auditUser").isBlank())
      body.put("auditUser", identity.name(id(body, "auditUserId")));
    if (!info.hasNonNull("taskName"))
      info.put("taskName", text(template, "taskName") + "_" + System.currentTimeMillis());
    body.set("taskInfo", info);
    if (!body.has("params")) body.set("params", arr());
    if (!body.has("scriptTempAttachments")) body.set("scriptTempAttachments", arr());
    if (!body.has("startType")) body.put("startType", 0);
    return apply(body, user);
  }

  public List<ObjectNode> templates(JsonNode request) {
    List<ObjectNode> rows = new ArrayList<>();
    for (ObjectNode t : store.all("TEMPLATE")) {
      ObjectNode s = catalog.script(id(t, "scriptInfoVersionId"));
      if (!catalog.matchesScript(s, request.path("queryParam"))) continue;
      ObjectNode row = V9Catalog.scriptRow(s);
      row.set("iid", t.get("id"));
      row.set("taskName", t.get("taskName"));
      JsonNode info = t.path("taskInfo");
      for (String k :
          List.of("eachNum", "timeout", "driveMode", "taskScheduler", "taskTime", "taskCron"))
        row.set(k, info.path(k));
      row.put("taskType", 0);
      row.set("execDesc", info.path("publishDesc"));
      row.set("maxVersion", s.path("scriptVersionDto").path("version"));
      row.putNull("auditorId");
      rows.add(row);
    }
    return rows;
  }

  public List<ObjectNode> templateAgents(long id) {
    return resolveAgents(store.get("TEMPLATE", id)).stream()
        .map(
            a -> {
              ObjectNode n = V9Catalog.agentRow(a);
              n.set("id", a.get("id"));
              n.putNull("createTime");
              return n;
            })
        .collect(Collectors.toList());
  }

  public List<ObjectNode> templateGroups(long id) {
    List<ObjectNode> rows = new ArrayList<>();
    for (JsonNode g : store.get("TEMPLATE", id).path("chosedResGroups"))
      rows.add(
          fields(
              store.get("GROUP", id(g, "sysmComputerGroupId")),
              "sysmComputerGroupId,cpName,enName,description"));
    return rows;
  }

  @Scheduled(fixedDelayString = "${oversight.v9.scheduler-delay-ms:1000}")
  public void tick() {
    for (ObjectNode task : store.all("TASK"))
      if (task.path("_armed").asBoolean()
          && task.path("_nextFire").asLong() <= System.currentTimeMillis()) {
        try {
          Set<Long> ids = new LinkedHashSet<>();
          task.path("_scheduledBindings").forEach(id -> ids.add(id.asLong()));
          launch(
              id(task, "id"),
              ids,
              task.path("driveMode").asInt(),
              task.path("eachNum").asInt(),
              true,
              true,
              task.path("_scheduleUser").asText(text(task, "_owner")));
        } catch (Exception e) {
          store.edit(
              "TASK",
              id(task, "id"),
              t -> {
                t.put("_armed", false);
                t.put("_status", "UNKNOWN");
              });
        }
      }
  }

  private long nextCron(String value, long after) {
    try {
      CronExpression cron = new CronExpression(value);
      cron.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
      Date next = cron.getNextValidTimeAfter(new Date(after));
      if (next == null) throw new IllegalArgumentException();
      return next.getTime();
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid.taskCron");
    }
  }

  @PreDestroy
  void close() {
    jobs.shutdownNow();
    hosts.shutdownNow();
  }
}
