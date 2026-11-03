package com.ycyh.oversight.v9;

import static com.ycyh.oversight.v9.V9Json.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.ycyh.oversight.SshRunner;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

@Service
public class V9Catalog {
  private final V9Store store;
  private final V9Identity identity;
  private final SshRunner runner;
  private final String allowedHosts, allowedUsers;

  public V9Catalog(
      V9Store store,
      V9Identity identity,
      SshRunner runner,
      @Value("${oversight.allowed-hosts}") String hosts,
      @Value("${oversight.allowed-users}") String users) {
    this.store = store;
    this.identity = identity;
    this.runner = runner;
    allowedHosts = hosts;
    allowedUsers = users;
  }

  @PostConstruct
  void seed() throws Exception {
    if (store.all("CATEGORY").isEmpty())
      store.put(
          "CATEGORY",
          object(
              "id",
              store.nextId(),
              "_key",
              "common",
              "code",
              "common",
              "name",
              "通用",
              "parentId",
              null,
              "sort",
              1,
              "level",
              1,
              "description",
              "模拟平台分类",
              "creatorId",
              identity.userId(identity.reviewer),
              "creatorName",
              identity.reviewer,
              "createTime",
              time(System.currentTimeMillis()),
              "updateTime",
              null));
    String user = allowedUsers.split(",")[0].trim();
    for (String raw : allowedHosts.split(",")) {
      String ip = raw.trim();
      if (store.find("AGENT", ip).isEmpty()) {
        runner.validate(ip, user);
        long id = store.nextId();
        store.put(
            "AGENT",
            object(
                "id",
                id,
                "_key",
                ip,
                "sysmAgentInfoId",
                id,
                "agentIp",
                ip,
                "agentName",
                ip,
                "agentPort",
                15000,
                "agentState",
                0,
                "execUserName",
                user,
                "deviceName",
                ip,
                "osName",
                "Linux",
                "hostName",
                ip,
                "taskIpsId",
                null));
      }
    }
    if (store.all("GROUP").isEmpty()) {
      ArrayNode ids = arr();
      store.all("AGENT").forEach(a -> ids.add(a.path("sysmAgentInfoId")));
      long id = store.nextId();
      store.put(
          "GROUP",
          object(
              "id",
              id,
              "_key",
              "default",
              "sysmComputerGroupId",
              id,
              "cpName",
              "默认资源组",
              "enName",
              "default",
              "description",
              "配置白名单主机",
              "_agentIds",
              ids));
    }
    if (store.find("SCRIPT_NAME", "ycyh_es_install").isEmpty()) {
      String source;
      try (java.io.InputStream in = new ClassPathResource("v9/install-es.sh").getInputStream()) {
        source = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
      }
      ObjectNode script =
          object(
              "scriptName",
              "ycyh_es_install",
              "scriptNameZh",
              "YCYH ES集群安装",
              "scriptType",
              "sh",
              "execuser",
              user,
              "level",
              3,
              "scriptVersionDto",
              object(
                  "level",
                  3,
                  "expectType",
                  2,
                  "expectLastline",
                  "0",
                  "timeout",
                  1200,
                  "scriptContentDto",
                  object("content", source),
                  "parameterValidationDtoList",
                  arr()
                      .add(
                          object(
                              "paramName",
                              "configBase64",
                              "paramOrder",
                              1,
                              "paramType",
                              "String",
                              "paramDefaultValue",
                              "",
                              "paramCheckIid",
                              -1,
                              "scriptParameterManagerId",
                              -1))));
      registerScript(script);
    }
    if (store.all("TEMPLATE").isEmpty()) {
      ObjectNode script = scriptByName("ycyh_es_install");
      ArrayNode agents = arr();
      store.all("AGENT").forEach(agents::add);
      registerTemplate(
          object(
              "taskName",
              "YCYH ES部署模板",
              "scriptInfoVersionId",
              script.path("scriptVersionDto").path("id"),
              "execuser",
              user,
              "chosedAgentUsers",
              agents,
              "params",
              script.path("scriptVersionDto").path("parameterValidationDtoList"),
              "taskInfo",
              object("driveMode", 3, "eachNum", 100, "taskScheduler", 0, "timeout", 1200)));
    }
  }

  public ObjectNode script(long version) {
    return store.get("SCRIPT", version);
  }

  public ObjectNode scriptByName(String name) {
    ObjectNode index =
        store
            .find("SCRIPT_NAME", name)
            .orElseThrow(() -> new IllegalArgumentException("script.not.found"));
    return script(index.path("versionId").asLong());
  }

  public ObjectNode scriptDetail(JsonNode query) {
    List<ObjectNode> matches = new ArrayList<>();
    for (ObjectNode s : store.all("SCRIPT")) {
      boolean any = false, match = true;
      for (String k : List.of("scriptName", "scriptNameZh", "srcScriptUuid")) {
        String value = text(query, k);
        if (!value.isEmpty()) {
          any = true;
          String actual =
              k.equals("srcScriptUuid") ? text(s.path("scriptVersionDto"), k) : text(s, k);
          match &= value.equals(actual);
        }
      }
      if (any && match) matches.add(s);
    }
    if (matches.isEmpty()) throw new IllegalArgumentException("script.not.found");
    matches.sort(Comparator.comparingLong(s -> -s.path("scriptVersionDto").path("id").asLong()));
    return clean(matches.get(0));
  }

  public ObjectNode registerScript(JsonNode input) {
    return store.transaction(
        () -> {
          ObjectNode s = copy(input);
          String name = text(s, "scriptName"), nameZh = text(s, "scriptNameZh");
          if (name.isBlank() || name.length() > 100 || nameZh.isBlank())
            throw new IllegalArgumentException("script.name.required");
          ObjectNode v = s.has("scriptVersionDto") ? copy(s.get("scriptVersionDto")) : obj();
          String content = text(v.path("scriptContentDto"), "content");
          if (content.isBlank()
              || content.getBytes(StandardCharsets.UTF_8).length > 1048576
              || content.indexOf(0) >= 0)
            throw new IllegalArgumentException("invalid.script.content");
          String type = text(s, "scriptType");
          if (!Set.of("sh", "py", "ps1", "bat").contains(type))
            throw new IllegalArgumentException("invalid.scriptType");
          long version = v.hasNonNull("id") ? id(v, "id") : store.nextId(),
              info = s.hasNonNull("id") ? id(s, "id") : store.nextId();
          store.reserve(Math.max(version, info));
          String uuid = text(v, "srcScriptUuid");
          if (uuid.isEmpty()) uuid = UUID.randomUUID().toString();
          UUID.fromString(uuid);
          Optional<ObjectNode> old = store.find("SCRIPT", uuid);
          if (old.isPresent() && old.get().path("_storeId").asLong() != version)
            throw new IllegalArgumentException("duplicate.srcScriptUuid");
          int level = integer(v, "level", integer(s, "level", 3));
          if (level < 0 || level > 3) throw new IllegalArgumentException("invalid.level");
          long cat =
              s.hasNonNull("categoryId")
                  ? id(s, "categoryId")
                  : store.all("CATEGORY").get(0).path("id").asLong();
          ObjectNode category = store.get("CATEGORY", cat);
          defaults(
              s,
              object(
                  "execuser",
                  null,
                  "scriptLabel",
                  "",
                  "editState",
                  1,
                  "deleted",
                  0,
                  "checkBeforeExec",
                  0,
                  "share",
                  0,
                  "whiteCommand",
                  0,
                  "visibleType",
                  -1,
                  "platforms",
                  arr().add("Linux"),
                  "femscript",
                  0,
                  "creatorId",
                  identity.userId(identity.reviewer),
                  "creatorName",
                  identity.reviewer,
                  "updatorId",
                  identity.userId(identity.reviewer),
                  "updatorName",
                  identity.reviewer,
                  "createTime",
                  time(System.currentTimeMillis()),
                  "updateTime",
                  null,
                  "platform",
                  "Linux",
                  "scriptSource",
                  0,
                  "orgCode",
                  null,
                  "ignoreTipCmd",
                  null,
                  "hisVersionCount",
                  1,
                  "insertedLabels",
                  null,
                  "removedLabels",
                  null,
                  "customerName",
                  "YCYH"));
          s.put("id", info);
          s.put("_storeId", version);
          s.put("_key", uuid);
          s.put("categoryId", cat);
          s.put("categoryPath", text(category, "name"));
          s.set("categoryDto", categoryTree(category, store.all("CATEGORY")));
          if (!s.hasNonNull("uniqueUuid")) s.put("uniqueUuid", UUID.randomUUID().toString());
          defaults(
              v,
              object(
                  "version",
                  "1.0",
                  "editState",
                  1,
                  "useState",
                  1,
                  "deleted",
                  0,
                  "isDefault",
                  1,
                  "description",
                  "",
                  "timeout",
                  -1,
                  "expectType",
                  2,
                  "expectLastline",
                  "0",
                  "paramflag",
                  0,
                  "creatorId",
                  identity.userId(identity.reviewer),
                  "creatorName",
                  identity.reviewer,
                  "updatorId",
                  identity.userId(identity.reviewer),
                  "updatorName",
                  identity.reviewer,
                  "createTime",
                  time(System.currentTimeMillis()),
                  "updateTime",
                  null,
                  "parameterValidationDtoList",
                  arr(),
                  "variableList",
                  arr(),
                  "functionList",
                  arr(),
                  "dependentList",
                  arr(),
                  "attachmentUploadDtoList",
                  arr()));
          v.put("id", version);
          v.set("infoUniqueUuid", s.get("uniqueUuid"));
          v.put("srcScriptUuid", uuid);
          v.put("level", level);
          Set<Integer> orders = new HashSet<>();
          for (JsonNode p : v.path("parameterValidationDtoList")) {
            ObjectNode parameter = (ObjectNode) p;
            int order = integer(p, "paramOrder", 0);
            if (order < 1 || !orders.add(order))
              throw new IllegalArgumentException("invalid.paramOrder");
            if (!p.hasNonNull("id")) parameter.put("id", store.nextId());
            parameter.put("srcScriptUuid", uuid);
            defaults(
                parameter,
                object(
                    "paramDesc",
                    null,
                    "creatorId",
                    identity.userId(identity.reviewer),
                    "creatorName",
                    identity.reviewer,
                    "createTime",
                    null,
                    "ruleName",
                    null,
                    "checkRule",
                    null,
                    "ruleDes",
                    null,
                    "paramCheckIid",
                    -1,
                    "scriptParameterManagerId",
                    -1));
          }
          for (JsonNode a : v.path("attachmentUploadDtoList")) validateAttachment(a);
          s.set("scriptVersionDto", v);
          store.put("SCRIPT", version, uuid, s);
          long idx =
              store
                  .find("SCRIPT_NAME", name)
                  .map(x -> x.path("id").asLong())
                  .orElseGet(store::nextId);
          store.put("SCRIPT_NAME", object("id", idx, "_key", name, "versionId", version));
          return clean(s);
        });
  }

  public ObjectNode registerTemplate(JsonNode input) {
    ObjectNode t = copy(input);
    long version = id(t, "scriptInfoVersionId");
    script(version);
    long id = t.hasNonNull("id") ? id(t, "id") : store.nextId();
    store.reserve(id);
    t.put("id", id);
    t.put("iid", id);
    t.put("_key", Long.toString(id));
    if (text(t, "taskName").isBlank()) throw new IllegalArgumentException("taskName.required");
    if (!t.has("taskInfo")) t.set("taskInfo", obj());
    store.put("TEMPLATE", t);
    return t;
  }

  public static void validateAttachment(JsonNode a) {
    String n = text(a, "name");
    if (n.isBlank()
        || n.contains("/")
        || n.contains("\\")
        || n.equals(".")
        || n.equals("..")
        || n.equals("main.sh")
        || n.equals("pid")
        || n.indexOf(0) >= 0) throw new IllegalArgumentException("invalid.attachment.name");
    String encoded = text(a, "contents");
    if (!encoded.isEmpty()) {
      byte[] bytes = Base64.getDecoder().decode(encoded);
      if (bytes.length > 16777216
          || a.hasNonNull("size") && a.path("size").asLong() != bytes.length)
        throw new IllegalArgumentException("invalid.attachment.size");
    }
  }

  public ObjectNode importCatalog(JsonNode input) {
    return store.transaction(
        () -> {
          int count = 0;
          for (JsonNode c : input.path("categories")) {
            ObjectNode n = copy(c);
            if (!n.hasNonNull("id")) n.put("id", store.nextId());
            store.reserve(id(n, "id"));
            n.put("_key", text(n, "code"));
            n.remove("children");
            store.put("CATEGORY", n);
            count++;
          }
          // Verify parent links, including cycles, before committing.
          for (ObjectNode c : store.all("CATEGORY")) {
            Set<Long> visited = new HashSet<>();
            ObjectNode current = c;
            while (current.hasNonNull("parentId")) {
              long p = id(current, "parentId");
              if (!visited.add(p) || p == id(c, "id"))
                throw new IllegalArgumentException("category.cycle");
              current = store.get("CATEGORY", p);
            }
          }
          for (JsonNode a : input.path("agents")) {
            ObjectNode n = copy(a);
            String ip = text(n, "agentIp"), user = text(n, "execUserName");
            if (user.isBlank()) user = allowedUsers.split(",")[0].trim();
            runner.validate(ip, user);
            long aid = n.hasNonNull("sysmAgentInfoId") ? id(n, "sysmAgentInfoId") : store.nextId();
            store.reserve(aid);
            n.put("id", aid);
            n.put("sysmAgentInfoId", aid);
            n.put("_key", ip);
            defaults(
                n,
                object(
                    "agentPort",
                    15000,
                    "agentState",
                    0,
                    "agentName",
                    ip,
                    "execUserName",
                    user,
                    "osName",
                    "Linux"));
            store.put("AGENT", n);
            count++;
          }
          for (JsonNode g : input.path("groups")) {
            ObjectNode n = copy(g);
            long gid =
                n.hasNonNull("sysmComputerGroupId") ? id(n, "sysmComputerGroupId") : store.nextId();
            store.reserve(gid);
            n.put("id", gid);
            n.put("sysmComputerGroupId", gid);
            n.put("_key", Long.toString(gid));
            ArrayNode members = arr();
            for (JsonNode id : n.path("agentIds")) {
              store.get("AGENT", id.asLong());
              members.add(id);
            }
            n.set("_agentIds", members);
            store.put("GROUP", n);
            count++;
          }
          for (JsonNode s : input.path("scripts")) {
            registerScript(s);
            count++;
          }
          for (JsonNode t : input.path("templates")) {
            registerTemplate(t);
            count++;
          }
          return object("imported", count);
        });
  }

  public List<ObjectNode> categories(JsonNode query) {
    List<ObjectNode> all = store.all("CATEGORY"), roots = new ArrayList<>();
    String code = text(query, "code");
    for (ObjectNode c : all)
      if (code.isEmpty() ? !c.hasNonNull("parentId") : code.equals(text(c, "code")))
        roots.add(categoryTree(c, all));
    return roots;
  }

  private ObjectNode categoryTree(ObjectNode c, List<ObjectNode> all) {
    ObjectNode n =
        fields(
            c,
            "id,parentId,code,name,sort,level,description,creatorId,creatorName,updatorId,updatorName,createTime,updateTime");
    ArrayNode kids = arr();
    for (ObjectNode k : all)
      if (k.hasNonNull("parentId") && k.path("parentId").asLong() == c.path("id").asLong())
        kids.add(categoryTree(k, all));
    n.set("children", kids);
    return n;
  }

  public static ObjectNode clean(ObjectNode n) {
    ObjectNode copy = n.deepCopy();
    List<String> keys = new ArrayList<>();
    copy.fieldNames()
        .forEachRemaining(
            k -> {
              if (k.startsWith("_")) keys.add(k);
            });
    copy.remove(keys);
    return copy;
  }

  public static void defaults(ObjectNode n, ObjectNode values) {
    values
        .fields()
        .forEachRemaining(
            e -> {
              if (!n.has(e.getKey())) n.set(e.getKey(), e.getValue());
            });
  }

  public static ObjectNode scriptRow(JsonNode s) {
    JsonNode v = s.path("scriptVersionDto");
    ObjectNode row =
        fields(
            s,
            "uniqueUuid,scriptNameZh,scriptName,scriptType,execuser,scriptLabel,categoryId,platform,creatorId,creatorName,createTime");
    row.set("scriptInfoId", s.get("id"));
    row.set("scriptInfoVersionId", v.get("id"));
    row.set("srcScriptUuid", v.get("srcScriptUuid"));
    row.set("version", v.get("version"));
    row.set("level", v.get("level"));
    row.set("updateTime", v.get("updateTime"));
    row.set("scriptCategoryName", s.get("categoryPath"));
    return row;
  }

  public static ObjectNode agentRow(JsonNode a) {
    ObjectNode n =
        fields(
            a,
            "sysmAgentInfoId,agentIp,agentName,agentPort,agentState,execUserName,deviceName,osName,taskIpsId,centerId,centerName,businessSystemNameList,businessSystemCodeList,machineRoom,ipVfourNumber,hostName,tagNames");
    for (String key : List.of("businessSystemNameList", "businessSystemCodeList"))
      if (n.path(key).isNull()) n.set(key, arr());
    return n;
  }

  public boolean matchesScript(JsonNode s, JsonNode q) {
    ObjectNode row = scriptRow(s);
    for (String k :
        List.of("categoryId", "level", "platform", "scriptLabel", "scriptName", "scriptNameZh")) {
      String value = text(q, k);
      if (!value.isEmpty() && !text(row, k).contains(value)) return false;
    }
    String keyword = text(q, "keyword");
    if (!keyword.isEmpty()
        && !(row.toString() + s.path("scriptVersionDto").path("scriptContentDto"))
            .contains(keyword)) return false;
    for (JsonNode def : q.path("getDefault"))
      if (def.asInt() == 1 && s.path("scriptVersionDto").path("isDefault").asInt() != 1)
        return false;
    return true;
  }
}
