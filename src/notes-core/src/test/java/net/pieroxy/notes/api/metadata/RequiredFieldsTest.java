package net.pieroxy.notes.api.metadata;

import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RequiredFieldsTest {
  @TypeScriptType
  static class Item {
    @Required
    String name;
    Integer count;
  }

  @TypeScriptType
  static class Base {
    @Required
    int revision;
    private transient String ignored;
  }

  @TypeScriptType
  static class Input extends Base {
    @Required
    Item item;
    Item optionalItem;
    List<Item> items;
    static int notSerialized;
  }

  @TypeScriptType
  static class Broken {
    int count;
    @Required
    long fine;
    Boolean alsoFine;
    boolean flag;
  }

  private static void check(String json) {
    RequiredFields.check(JsonParser.parseString(json), Input.class);
  }

  private static String failure(String json) {
    return assertThrows(InvalidInputException.class, () -> check(json)).getMessage();
  }

  @Test
  public void acceptsACompleteInput() {
    check("{\"revision\":0,\"item\":{\"name\":\"a\"},\"items\":[{\"name\":\"b\",\"count\":2}]}");
  }

  @Test
  public void aMissingPrimitiveIsDetectedThoughGsonWouldReadItAsZero() {
    assertEquals("Missing required field revision.", failure("{\"item\":{\"name\":\"a\"}}"));
  }

  @Test
  public void nullCountsAsMissing() {
    assertEquals("Missing required field item.", failure("{\"revision\":1,\"item\":null}"));
  }

  @Test
  public void checksNestedObjectsOnlyWhenPresent() {
    assertEquals("Missing required field item.name.", failure("{\"revision\":1,\"item\":{}}"));
    assertEquals("Missing required field optionalItem.name.", failure("{\"revision\":1,\"item\":{\"name\":\"a\"},\"optionalItem\":{}}"));
  }

  @Test
  public void checksEveryListElement() {
    assertEquals("Missing required field items[1].name.",
        failure("{\"revision\":1,\"item\":{\"name\":\"a\"},\"items\":[{\"name\":\"b\"},{\"count\":1}]}"));
    assertEquals("items[0] is null.", failure("{\"revision\":1,\"item\":{\"name\":\"a\"},\"items\":[null]}"));
  }

  @Test
  public void refusesAnythingButAnObject() {
    assertThrows(InvalidInputException.class, () -> RequiredFields.check(JsonParser.parseString("[]"), Input.class));
    assertThrows(InvalidInputException.class, () -> RequiredFields.check(JsonParser.parseString("null"), Input.class));
    assertEquals("item must be an object.", failure("{\"revision\":1,\"item\":\"a\"}"));
  }

  @Test
  public void findsPrimitivesWithoutRequired() {
    assertEquals(List.of(
        Broken.class.getName() + ".count is primitive but not @Required: make it @Required, or use Integer",
        Broken.class.getName() + ".flag is primitive but not @Required: make it @Required, or use Boolean"),
        RequiredFields.findOptionalPrimitives(List.of(Broken.class)));
    assertEquals(List.of(), RequiredFields.findOptionalPrimitives(List.of(Input.class, Item.class)));
  }
}
