package com.google.daq.mqtt.validator;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.daq.mqtt.validator.SequenceValidator.OBJECT_MAPPER;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.daq.mqtt.validator.semantic.SemanticValue;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import udmi.schema.Config;

/**
 * Utility class to help with detecting differences in config values.
 */
public class ConfigDiffEngine {

  private Map<String, Object> previous;

  public ConfigDiffEngine() {
  }

  static <T> T convertTo(Class<T> targetClass, String messageString) {
    try {
      return OBJECT_MAPPER.readValue(messageString, checkNotNull(targetClass, "target class"));
    } catch (Exception e) {
      throw new RuntimeException("While converting message to " + targetClass.getName(), e);
    }
  }

  static <T> T convertTo(Class<T> targetClass, Object message) {
    return message == null ? null : convertTo(targetClass, toJsonString(message));
  }

  static String toJsonString(Object message) {
    try {
      return OBJECT_MAPPER.writeValueAsString(message);
    } catch (Exception e) {
      throw new RuntimeException("While stringifying message", e);
    }
  }

  @SuppressWarnings("unchecked")
  List<String> computeChanges(Config deviceConfig) {
    Map<String, Object> updated = convertSemantics(deviceConfig);
    List<String> configUpdates = new ArrayList<>();
    accumulateDifference("", previous, updated, configUpdates);
    previous = updated;
    return configUpdates;
  }

  private Map<String, Object> convertSemantics(Object thing) {
    return thing == null ? ImmutableMap.of() :
        Arrays.stream(thing.getClass().getFields())
            .filter(field -> isNotNull(thing, field)).collect(
                Collectors.toMap(Field::getName, field -> convertSemantics(thing, field)));
  }

  private Object convertSemantics(Object thing, Field field) {
    try {
      if (isBaseType(field)) {
        return field.get(thing);
      } else {
        return convertSemantics(field.get(thing));
      }
    } catch (Exception e) {
      throw new RuntimeException("While converting field " + field.getName(), e);
    }
  }

  private boolean isNotNull(Object thing, Field field) {
    try {
      return field.get(thing) != null;
    } catch (Exception e) {
      throw new RuntimeException("While checking for null " + field.getName(), e);
    }
  }

  private boolean isBaseType(Object value) {
    Class<?> type = value instanceof Field ? ((Field) value).getType() : value.getClass();
    return Integer.class.isAssignableFrom(type)
        || String.class.isAssignableFrom(type)
        || Date.class.isAssignableFrom(type);
  }

  @SuppressWarnings("unchecked")
  void accumulateDifference(String prefix, Map<String, Object> left, Map<String, Object> right,
      List<String> configUpdates) {
    right.forEach((key, value) -> {
      if (left != null && left.containsKey(key)) {
        Object leftValue = left.get(key);
        if (SemanticValue.equals(value, leftValue)) {
          return;
        }
        if (isBaseType(value)) {
          configUpdates.add(String.format("Set `%s%s` = %s", prefix, key, semanticValue(value)));
        } else {
          String newPrefix = prefix + key + ".";
          accumulateDifference(newPrefix, (Map<String, Object>) leftValue,
              (Map<String, Object>) value, configUpdates);
        }
      } else {
        configUpdates.add(String.format("Add `%s%s` = %s", prefix, key, semanticValue(value)));
      }
    });
    if (left != null) {
      left.forEach((key, value) -> {
        if (!right.containsKey(key)) {
          configUpdates.add(String.format("Remove `%s%s`", prefix, key));
        }
      });
    }
  }

  private String singleLine(String toJsonString) {
    return Joiner.on(' ').join(
        Arrays.stream(toJsonString.split("\n")).map(String::trim).collect(Collectors.toList()));
  }

  @SuppressWarnings("unchecked")
  private String semanticValue(Object value) {
    if (value instanceof Map) {
      return semanticMapValue((Map<String, Object>) value);
    }
    boolean isSemantic = SemanticValue.isSemanticValue(value);
    if (value instanceof Date && !isSemantic) {
      throw new IllegalArgumentException(
          "Unexpected non-semantic Date in semantic value calculation");
    }
    String wrapper = isSemantic ? "_" : "`";
    return wrapper + (isSemantic ? SemanticValue.getDescription(value) : value) + wrapper;
  }

  private String semanticMapValue(Map<String, Object> map) {
    String contents = map.keySet().stream()
        .map(key -> String.format("\"%s\": %s", key, semanticMapValue(map, key))).collect(
            Collectors.joining(", "));
    return String.format("{ %s }", contents);
  }

  @SuppressWarnings("unchecked")
  private String semanticMapValue(Map<String, Object> map, String key) {
    return semanticValue(map.get(key));
  }

  boolean equals(Config deviceConfig, Object receivedConfig) {
    Map<String, Object> left = convertSemantics(deviceConfig);
    Map<String, Object> right = convertSemantics(receivedConfig);
    List<String> configUpdates = new ArrayList<>();
    accumulateDifference("", left, right, configUpdates);
    return configUpdates.isEmpty();
  }
}