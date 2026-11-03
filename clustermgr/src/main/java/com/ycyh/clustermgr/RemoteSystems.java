package com.ycyh.clustermgr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Only V9 documented endpoints are used to communicate with the control platform. */
@Component
public class RemoteSystems {
  private final Settings settings;
  private final RestTemplate http;
  private final ObjectMapper json;
  private final String base, username, auditorName, scriptName;
  private final long auditorId;

  @Value("${clustermgr.oversight-rejected-audit-type:2}")
  private int rejectedAuditType;

  private String token;
  private long expires;

  public RemoteSystems(
      Settings settings,
      ObjectMapper json,
      @Value("${clustermgr.oversight-username:operator}") String username,
      @Value("${clustermgr.oversight-auditor-id:2}") long auditorId,
      @Value("${clustermgr.oversight-auditor-name:approver}") String auditorName,
      @Value("${clustermgr.oversight-script-name:ycyh_es_install}") String scriptName) {
    this.settings = settings;
    this.json = json;
    this.username = username;
    this.auditorId = auditorId;
    this.auditorName = auditorName;
    this.scriptName = scriptName;
    this.base = settings.oversight.replaceAll("/api$", "");
    SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(15000);
    f.setReadTimeout(30000);
    http = new RestTemplate(f);
  }

  public static class Software {
    public String name, path, sha256;
    public long size;
  }

  public List<Software> packages() {
    Software[] a = http.getForObject(settings.warehouse + "/_catalog", Software[].class);
    return a == null ? Collections.emptyList() : Arrays.asList(a);
  }

  private ObjectNode object(Object... pairs) {
    ObjectNode node = json.createObjectNode();
    for (int i = 0; i < pairs.length; i += 2)
      node.set(pairs[i].toString(), json.valueToTree(pairs[i + 1]));
    return node;
  }

  private synchronized String token() {
    if (token != null && System.currentTimeMillis() + 30000 < expires) return token;
    String uri =
        UriComponentsBuilder.fromHttpUrl(base + "/api/system/authority/getSecretToken")
            .queryParam("username", username)
            .build()
            .encode()
            .toUriString();
    JsonNode response = http.getForObject(uri, JsonNode.class);
    JsonNode data = unwrap(response);
    token = data.path("token").asText();
    if (token.isEmpty()) throw new IllegalStateException("Missing V9 token");
    try {
      expires =
          LocalDateTime.parse(
                  data.path("exipreTime").asText(), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
              .atZone(ZoneId.of("Asia/Shanghai"))
              .toInstant()
              .toEpochMilli();
    } catch (Exception e) {
      throw new IllegalStateException("Invalid V9 exipreTime", e);
    }
    return token;
  }

  private JsonNode unwrap(JsonNode response) {
    if (response == null || !response.path("code").asText().equals("10000"))
      throw new IllegalStateException(
          "V9 request failed: "
              + (response == null ? "no response" : response.path("message").asText()));
    return response.path("data");
  }

  private JsonNode post(String path, JsonNode body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("secretToken", token());
    return unwrap(
        http.postForObject(base + "/api/" + path, new HttpEntity<>(body, headers), JsonNode.class));
  }

  private ObjectNode query(long taskId) {
    return object("pageNum", 1, "pageSize", 200, "queryParam", object("scriptTaskId", taskId));
  }

  public Map<String, Object> submit(
      String clusterId, String configBase64, List<Map<String, String>> targets) {
    if (targets.isEmpty() || targets.stream().map(t -> t.get("username")).distinct().count() != 1)
      throw new IllegalArgumentException("V9 requires a common execuser per task");
    JsonNode script = post("scriptApi/myScript/getScriptDetail", object("scriptName", scriptName));
    JsonNode version = script.path("scriptVersionDto");
    if (!version.path("id").canConvertToLong())
      throw new IllegalStateException("Missing script version ID");
    ArrayNode agents = json.createArrayNode();
    for (Map<String, String> target : targets) {
      JsonNode list =
          post(
                  "scriptApi/groups/queryAgentInfoGroupRole",
                  object(
                      "pageNum",
                      1,
                      "pageSize",
                      200,
                      "queryParam",
                      object("agentIp", target.get("hostIp"))))
              .path("list");
      JsonNode agent = null;
      for (JsonNode candidate : list)
        if (candidate.path("agentIp").asText().equals(target.get("hostIp"))) agent = candidate;
      if (agent == null)
        throw new IllegalStateException("Target not registered in V9 agent directory");
      agents.add(agent);
    }
    JsonNode definition = null;
    for (JsonNode p : version.path("parameterValidationDtoList"))
      if (p.path("paramName").asText().equals("configBase64") && p.path("paramOrder").asInt() == 1)
        definition = p;
    if (definition == null)
      throw new IllegalStateException(
          "Registered ES installer requires configBase64 parameter at position 1");
    ObjectNode parameter = ((ObjectNode) definition).deepCopy();
    parameter.put("paramDefaultValue", configBase64);
    ObjectNode request =
        object(
            "auditUser",
            auditorName,
            "auditUserId",
            auditorId,
            "chosedAgentUsers",
            agents,
            "chosedResGroups",
            json.createArrayNode(),
            "execuser",
            targets.get(0).get("username"),
            "params",
            json.createArrayNode().add(parameter),
            "resGroupFlag",
            false,
            "scriptInfoVersionId",
            version.get("id"),
            "workOrderNum",
            "",
            "startType",
            0,
            "scriptTempAttachments",
            json.createArrayNode(),
            "taskInfo",
            object(
                "driveMode",
                3,
                "eachNum",
                targets.size(),
                "execUser",
                targets.get(0).get("username"),
                "publishDesc",
                "YCYH ES deployment " + clusterId,
                "startType",
                10001,
                "taskName",
                "install-es-" + clusterId,
                "taskScheduler",
                0,
                "timeout",
                1200));
    JsonNode applied = post("scriptApi/taskApply/scriptExecAuditing", request);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("applicationId", applied.path("taskId").asText());
    result.put("uuid", version.path("srcScriptUuid").asText());
    result.put("taskId", applied.path("taskId").asText());
    result.put(
        "auditId", applied.path("auditId").isNull() ? null : applied.path("auditId").asText());
    return result;
  }

  public synchronized Map<String, Object> application(String id) {
    long task = Long.parseLong(id);
    JsonNode ready = post("scriptApi/taskExecute/listTaskReadyToExecute", query(task)).path("list");
    if (!ready.isEmpty()) {
      JsonNode bindings = post("scriptApi/info/getTaskBindAgentInfo", query(task)).path("list");
      ArrayNode ids = json.createArrayNode();
      for (JsonNode b : bindings) ids.add(b.path("taskIpsId"));
      if (ids.isEmpty()) throw new IllegalStateException("V9 task has no bound hosts");
      post(
          "scriptApi/taskExecute/scriptTaskStart",
          object(
              "driveMode",
              3,
              "eachNum",
              bindings.size(),
              "firstBatch",
              true,
              "scriptTaskId",
              task,
              "taskIps",
              ids));
    }
    JsonNode details =
        post("scriptApi/taskExecute/listRunningScriptTasks", query(task)).path("list");
    if (details.isEmpty()) return Map.of("status", "PENDING");
    JsonNode row = details.get(0);
    // V9 does not define auditType enums. This optional mapping must be confirmed for a real
    // platform.
    if (rejectedAuditType >= 0
        && row.path("auditType").isIntegralNumber()
        && row.path("auditType").asInt() == rejectedAuditType
        && !row.hasNonNull("scriptTaskInstanceId"))
      return Map.of("status", "REJECTED", "reason", "平台审批驳回");
    int state = row.path("scriptTaskInstanceState").asInt();
    if (!row.hasNonNull("scriptTaskInstanceId")) return Map.of("status", "PENDING");
    JsonNode runtime =
        post(
                "scriptApi/runtime/listTaskRuntime",
                object(
                    "pageNum",
                    1,
                    "pageSize",
                    200,
                    "queryParam",
                    object("taskInstanceId", row.get("scriptTaskInstanceId"))))
            .path("list");
    Map<String, Integer> results = new LinkedHashMap<>();
    boolean running = state == 10;
    for (JsonNode host : runtime) {
      int hostState = host.path("state").asInt();
      if (hostState == 10) running = true;
      else
        results.put(
            host.path("agentIp").asText() + "@" + host.path("execUser").asText(),
            hostState == 20 ? 1 : 0);
    }
    if (running) return Map.of("status", "RUNNING");
    if (state != 20 && state != 30 && state != 60)
      return Map.of("status", "UNKNOWN", "results", results);
    return Map.of("status", state == 20 ? "SUCCEEDED" : "FAILED", "results", results);
  }
}
