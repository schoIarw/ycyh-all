package com.ycyh.oversight.v9;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class V9Json {
  public static final ObjectMapper JSON = new ObjectMapper();

  private V9Json() {}

  public static ObjectNode obj() {
    return JSON.createObjectNode();
  }

  public static ArrayNode arr() {
    return JSON.createArrayNode();
  }

  public static ObjectNode object(Object... pairs) {
    ObjectNode n = obj();
    for (int i = 0; i < pairs.length; i += 2)
      n.set(pairs[i].toString(), JSON.valueToTree(pairs[i + 1]));
    return n;
  }

  public static ObjectNode ok(String message, Object data) {
    return object("code", "10000", "message", message, "data", data);
  }

  public static ObjectNode fail(String message) {
    return object("code", "10602", "message", message, "data", null);
  }

  public static String text(JsonNode n, String key) {
    return n.path(key).isNull() ? "" : n.path(key).asText("");
  }

  public static long id(JsonNode n, String key) {
    try {
      JsonNode v = n.path(key);
      long value;
      if (v.isIntegralNumber()) {
        if (!v.canConvertToLong()) throw new IllegalArgumentException();
        value = v.longValue();
      } else value = Long.parseLong(text(n, key));
      if (value <= 0 || value == Long.MAX_VALUE) throw new IllegalArgumentException();
      return value;
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid." + key);
    }
  }

  public static int integer(JsonNode n, String key, int fallback) {
    JsonNode v = n.path(key);
    if (v.isMissingNode() || v.isNull()) return fallback;
    try {
      return Integer.parseInt(v.asText());
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid." + key);
    }
  }

  public static ObjectNode copy(JsonNode n) {
    if (!n.isObject()) throw new IllegalArgumentException("object.required");
    return ((ObjectNode) n).deepCopy();
  }

  public static String time(long millis) {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")));
  }

  public static JsonNode parse(String text) {
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid.json");
    }
  }

  public static ObjectNode page(List<? extends JsonNode> all, JsonNode request) {
    int page = integer(request, "pageNum", 1), size = integer(request, "pageSize", 10);
    if (page < 1 || size < 1 || size > 1000)
      throw new IllegalArgumentException("invalid.pagination");
    int total = all.size(), pages = (total + size - 1) / size;
    long from = (long) (page - 1) * size;
    int start = (int) Math.min(from, total), end = Math.min(start + size, total);
    ArrayNode rows = arr();
    all.subList(start, end).forEach(rows::add);
    ArrayNode nav = arr();
    int begin = Math.max(1, Math.min(page - 3, pages - 7));
    for (int i = begin; i <= Math.min(begin + 7, pages); i++) nav.add(i);
    return object(
        "total",
        total,
        "list",
        rows,
        "pageNum",
        page,
        "pageSize",
        size,
        "size",
        rows.size(),
        "startRow",
        rows.isEmpty() ? 0 : start + 1,
        "endRow",
        end,
        "pages",
        pages,
        "prePage",
        page > 1 ? page - 1 : 0,
        "nextPage",
        page < pages ? page + 1 : 0,
        "isFirstPage",
        page == 1,
        "isLastPage",
        page >= pages,
        "hasPreviousPage",
        page > 1,
        "hasNextPage",
        page < pages,
        "navigatePages",
        8,
        "navigatepageNums",
        nav,
        "navigateFirstPage",
        nav.isEmpty() ? 0 : nav.get(0).asInt(),
        "navigateLastPage",
        nav.isEmpty() ? 0 : nav.get(nav.size() - 1).asInt());
  }

  public static ObjectNode fields(JsonNode source, String names) {
    ObjectNode result = obj();
    for (String key : names.split(","))
      result.set(key, source.has(key) ? source.get(key).deepCopy() : NullNode.instance);
    return result;
  }
}
