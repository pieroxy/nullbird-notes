import { alphabetical } from "../sync/TagOrder";

/**
 * Nested tags: "work/clients/acme" is a tag under "work/clients", itself under "work". A note with
 * it is a note of all three: they're implied, whether or not a note has them. Only names: notes keep
 * their tags as they are. TagPaths.java has the same rules.
 */
export const SEPARATOR = "/";

/** {@code tag} without empty levels: "/work//clients/" is "work/clients". */
export function normalizeTag(tag: string): string {
  return tag.split(SEPARATOR).filter((level) => level !== "").join(SEPARATOR);
}

/** Whether {@code tag} is {@code parent} or a tag under it. */
export function isUnder(tag: string, parent: string): boolean {
  return tag === parent || tag.startsWith(parent + SEPARATOR);
}

/** The tag right above {@code tag}; undefined at the top. */
export function parentOf(tag: string): string | undefined {
  const at = tag.lastIndexOf(SEPARATOR);
  return at > 0 ? tag.slice(0, at) : undefined;
}

/** Its last level: "acme" for "work/clients/acme". */
export function leafOf(tag: string): string {
  return tag.slice(tag.lastIndexOf(SEPARATOR) + 1);
}

/** The tags above {@code tag}, the top one first: "work", "work/clients" for "work/clients/acme". */
export function ancestorsOf(tag: string): string[] {
  const ancestors: string[] = [];
  for (let at = tag.indexOf(SEPARATOR); at > 0; at = tag.indexOf(SEPARATOR, at + 1)) ancestors.push(tag.slice(0, at));
  return ancestors;
}

/** {@code tags} and every tag above them. */
export function withAncestors(tags: Iterable<string>): Set<string> {
  const all = new Set<string>();
  for (const tag of tags) {
    for (const above of ancestorsOf(tag)) all.add(above);
    all.add(tag);
  }
  return all;
}

/** {@code tags} without those above another of them: "work" says nothing "work/clients" doesn't. */
export function withoutRedundant(tags: string[]): string[] {
  return [...new Set(tags)].filter((tag) => !tags.some((other) => other !== tag && isUnder(other, tag)));
}

/**
 * {@code names} and the tags above them, as the tags panel lists them: each one before the tags
 * under it, siblings in the user's order. A tag's place is that of the first of it and the tags under
 * it in {@code order}; without any there (or without an order), after the others, alphabetically.
 */
export function treeOrder(names: Iterable<string>, order: string[] | undefined): string[] {
  const all = withAncestors(names);
  const key = new Map<string, number>();
  (order ?? []).forEach((name, i) => {
    if (!all.has(name)) return;
    for (const above of withAncestors([name])) if (!key.has(above) || key.get(above)! > i) key.set(above, i);
  });
  const children = new Map<string | undefined, string[]>();
  for (const name of all) {
    const parent = parentOf(name);
    children.set(parent, [...(children.get(parent) ?? []), name]);
  }
  const result: string[] = [];
  const add = (parent: string | undefined) => {
    const siblings = children.get(parent) ?? [];
    const sorted = alphabetical(siblings).sort((a, b) => (key.get(a) ?? Infinity) - (key.get(b) ?? Infinity));
    for (const child of sorted) {
      result.push(child);
      add(child);
    }
  };
  add(undefined);
  return result;
}

/** Each tag's place in {@code ordered}. */
export function rankOf(ordered: string[]): Map<string, number> {
  return new Map(ordered.map((tag, i) => [tag, i]));
}

/** {@code ordered} (see treeOrder) with {@code tag} and those under it put at {@code index} among its siblings. */
export function withSiblingMoved(ordered: string[], tag: string, index: number): string[] {
  const children = new Map<string | undefined, string[]>();
  for (const name of ordered) {
    const parent = parentOf(name);
    children.set(parent, [...(children.get(parent) ?? []), name]);
  }
  const parent = parentOf(tag);
  const others = (children.get(parent) ?? []).filter((name) => name !== tag);
  const clamped = Math.max(0, Math.min(index, others.length));
  children.set(parent, [...others.slice(0, clamped), tag, ...others.slice(clamped)]);
  const result: string[] = [];
  const add = (of: string | undefined) => {
    for (const child of children.get(of) ?? []) {
      result.push(child);
      add(child);
    }
  };
  add(undefined);
  return result;
}

/** {@code tag}'s siblings in {@code ordered} (see treeOrder), itself included, in order. */
export function siblingsOf(ordered: string[], tag: string): string[] {
  const parent = parentOf(tag);
  return ordered.filter((name) => parentOf(name) === parent);
}

/** A tag renamed (to), or removed (no to). */
export interface Rename {
  from: string;
  to?: string;
}

/**
 * Renaming {@code from} to {@code to} (undefined: removing it) takes the tags under it along:
 * "work/clients" becomes "job/clients". One rename per tag of {@code names} at or under {@code from},
 * {@code from} itself always, the top one first: replayed in that order, a rename never applies to a
 * tag another one just made. As the server records them (see TagPaths.subtreeRenames).
 */
export function subtreeRenames(names: Iterable<string>, from: string, to: string | undefined): Rename[] {
  const under = new Set([from, ...[...names].filter((name) => isUnder(name, from))]);
  return [...under]
    .sort((a, b) => ancestorsOf(a).length - ancestorsOf(b).length || (a < b ? -1 : a > b ? 1 : 0))
    .map((name) => (to === undefined ? { from: name } : { from: name, to: to + name.slice(from.length) }));
}

/**
 * The nearest of {@code tag} and the tags above it to say something: what {@code pick} gives for its
 * options, and which tag said it. Nested tags follow the tags above them unless they set their own.
 */
export function nearest<T, O>(tag: string, options: { [tag: string]: O }, pick: (options: O) => T | undefined | null): { value?: T; tag?: string } {
  const chain = [...ancestorsOf(tag), tag];
  for (let i = chain.length - 1; i >= 0; i--) {
    const own = options[chain[i]];
    const value = own === undefined ? undefined : pick(own);
    if (value !== undefined && value !== null) return { value, tag: chain[i] };
  }
  return {};
}
