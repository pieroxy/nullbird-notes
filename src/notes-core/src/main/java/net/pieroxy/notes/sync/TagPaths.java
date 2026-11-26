package net.pieroxy.notes.sync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Nested tags: {@code work/clients/acme} is a tag under {@code work/clients}, itself under
 * {@code work}. A note with it is a note of all three: they're implied, whether or not a note has
 * them. Only names: notes keep their tags as they are. TagTree.ts has the same rules.
 */
public final class TagPaths {
  public static final String SEPARATOR = "/";

  private TagPaths() {}

  /** {@code tag} without empty levels: "/work//clients/" is "work/clients". */
  public static String normalize(String tag) {
    if (tag == null) return null;
    List<String> levels = new ArrayList<>();
    for (String level : tag.split(SEPARATOR, -1)) if (!level.isEmpty()) levels.add(level);
    return String.join(SEPARATOR, levels);
  }

  /** @return whether {@code tag} is {@code parent} or a tag under it. */
  public static boolean isUnder(String tag, String parent) {
    return tag.equals(parent) || tag.startsWith(parent + SEPARATOR);
  }

  /** The tags above {@code tag}, the top one first: "work", "work/clients" for "work/clients/acme". */
  public static List<String> ancestors(String tag) {
    List<String> ancestors = new ArrayList<>();
    for (int at = tag.indexOf(SEPARATOR); at > 0; at = tag.indexOf(SEPARATOR, at + 1)) ancestors.add(tag.substring(0, at));
    return ancestors;
  }

  /** {@code tags} and every tag above them. */
  public static Set<String> withAncestors(Collection<String> tags) {
    Set<String> all = new LinkedHashSet<>();
    for (String tag : tags) {
      all.addAll(ancestors(tag));
      all.add(tag);
    }
    return all;
  }

  /** {@code tags} without those above another of them: "work" says nothing "work/clients" doesn't. */
  public static List<String> withoutRedundant(Collection<String> tags) {
    return tags.stream().filter(tag -> tags.stream().noneMatch(other -> !other.equals(tag) && isUnder(other, tag))).distinct().toList();
  }

  /**
   * {@code names} and the tags above them, as the tags panel lists them: each one before the tags
   * under it, siblings in the user's order. A tag's place is that of the first of it and the tags
   * under it in {@code order}; without any there (or without an order), after the others,
   * alphabetically.
   */
  public static List<String> treeOrder(Collection<String> names, List<String> order, Comparator<String> alphabetical) {
    Set<String> all = withAncestors(names);
    Map<String, Integer> key = new HashMap<>();
    if (order != null) {
      for (int i = 0; i < order.size(); i++) {
        String name = order.get(i);
        if (!all.contains(name)) continue;
        for (String above : withAncestors(List.of(name))) key.merge(above, i, Math::min);
      }
    }
    Map<String, List<String>> children = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
    for (String name : all) children.computeIfAbsent(parent(name), p -> new ArrayList<>()).add(name);
    Comparator<String> siblings = Comparator.<String>comparingInt(name -> key.getOrDefault(name, Integer.MAX_VALUE)).thenComparing(alphabetical);
    for (List<String> list : children.values()) list.sort(siblings);
    List<String> result = new ArrayList<>();
    addInOrder(null, children, result);
    return result;
  }

  private static void addInOrder(String parent, Map<String, List<String>> children, List<String> result) {
    for (String child : children.getOrDefault(parent, List.of())) {
      result.add(child);
      addInOrder(child, children, result);
    }
  }

  /** @return the tag right above {@code tag}, null at the top. */
  public static String parent(String tag) {
    int at = tag.lastIndexOf(SEPARATOR);
    return at > 0 ? tag.substring(0, at) : null;
  }

  /** {@code from} becomes {@code to}; null: it's removed. */
  public record Rename(String from, String to) {}

  /**
   * Renaming {@code from} to {@code to} (null: removing it) takes the tags under it along:
   * "work/clients" becomes "job/clients". One rename per tag of {@code names} at or under
   * {@code from}, {@code from} itself always, the top one first: replayed in that order, a rename
   * never applies to a tag another one just made (moving "work/clients" up to "work" makes
   * "work/clients/clients" into "work/clients", once "work/clients" itself moved).
   */
  public static List<Rename> subtreeRenames(Collection<String> names, String from, String to) {
    Set<String> under = new LinkedHashSet<>();
    under.add(from);
    for (String name : names) if (isUnder(name, from)) under.add(name);
    return under.stream()
        .sorted(Comparator.comparingInt((String name) -> ancestors(name).size()).thenComparing(Comparator.naturalOrder()))
        .map(name -> new Rename(name, to == null ? null : to + name.substring(from.length())))
        .toList();
  }
}
