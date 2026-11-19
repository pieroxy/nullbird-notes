package net.pieroxy.notes.api.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Enforces {@link Required}: on the DTO classes at build time, on API inputs at runtime. */
public final class RequiredFields {
  private RequiredFields() {}

  /**
   * @return one message per primitive field of {@code types} (and their superclasses) without
   *     {@link Required}: absent, it would silently read as 0 or false. Enums, serialized as
   *     their names, have no fields to check.
   */
  public static List<String> findOptionalPrimitives(List<Class<?>> types) {
    List<String> errors = new ArrayList<>();
    for (Class<?> type : types) {
      if (type.isEnum()) continue;
      for (Field field : serializedFields(type)) {
        if (field.getType().isPrimitive() && !field.isAnnotationPresent(Required.class)) {
          errors.add(field.getDeclaringClass().getName() + "." + field.getName()
              + " is primitive but not @Required: make it @Required, or use " + boxedName(field.getType()));
        }
      }
    }
    return errors;
  }

  /**
   * Checks the parsed JSON rather than the bound object: once bound, a missing primitive is
   * indistinguishable from 0. Recurses into nested {@link TypeScriptType} fields and collections.
   *
   * @throws InvalidInputException naming the first {@link Required} field absent or null.
   */
  public static void check(JsonElement json, Class<?> type) {
    if (json == null || !json.isJsonObject()) throw new InvalidInputException("Input must be a JSON object.");
    checkObject(json.getAsJsonObject(), type, "");
  }

  private static void checkObject(JsonObject json, Class<?> type, String path) {
    for (Field field : serializedFields(type)) {
      JsonElement value = json.get(jsonName(field));
      String fieldPath = path + jsonName(field);
      if (value == null || value.isJsonNull()) {
        if (field.isAnnotationPresent(Required.class)) throw new InvalidInputException("Missing required field " + fieldPath + ".");
        continue;
      }
      checkValue(value, field.getGenericType(), fieldPath);
    }
  }

  private static void checkValue(JsonElement value, Type type, String path) {
    if (type instanceof Class<?> c && c.isAnnotationPresent(TypeScriptType.class) && !c.isEnum()) {
      if (!value.isJsonObject()) throw new InvalidInputException(path + " must be an object.");
      checkObject(value.getAsJsonObject(), c, path + ".");
    } else if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
        && Collection.class.isAssignableFrom(raw) && value.isJsonArray()) {
      JsonArray array = value.getAsJsonArray();
      for (int i = 0; i < array.size(); i++) {
        if (array.get(i).isJsonNull()) throw new InvalidInputException(path + "[" + i + "] is null.");
        checkValue(array.get(i), p.getActualTypeArguments()[0], path + "[" + i + "]");
      }
    }
  }

  /** The fields Gson (de)serializes: neither static nor transient, superclasses' included. */
  private static List<Field> serializedFields(Class<?> type) {
    List<Field> fields = new ArrayList<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !field.isSynthetic()) fields.add(field);
      }
    }
    return fields;
  }

  private static String jsonName(Field field) {
    SerializedName name = field.getAnnotation(SerializedName.class);
    return name != null ? name.value() : field.getName();
  }

  private static String boxedName(Class<?> primitive) {
    if (primitive == int.class) return "Integer";
    if (primitive == char.class) return "Character";
    String name = primitive.getName();
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}
