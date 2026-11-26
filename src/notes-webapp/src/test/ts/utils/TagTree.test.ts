import {
  ancestorsOf, isUnder, leafOf, nearest, normalizeTag, parentOf, siblingsOf, subtreeRenames, treeOrder, withAncestors, withoutRedundant,
  withSiblingMoved,
} from "../../../ts/utils/TagTree";

describe("TagTree", () => {
  test("empty levels go", () => {
    expect(normalizeTag("/work//clients/")).toBe("work/clients");
    expect(normalizeTag("work")).toBe("work");
    expect(normalizeTag("//")).toBe("");
  });

  test("names: under, parent, leaf, ancestors", () => {
    expect(isUnder("work/clients", "work")).toBe(true);
    expect(isUnder("work", "work")).toBe(true);
    expect(isUnder("workshop", "work")).toBe(false);
    expect(parentOf("work/clients/acme")).toBe("work/clients");
    expect(parentOf("work")).toBeUndefined();
    expect(leafOf("work/clients/acme")).toBe("acme");
    expect(ancestorsOf("work/clients/acme")).toEqual(["work", "work/clients"]);
    expect([...withAncestors(["work/clients", "home"])]).toEqual(["work", "work/clients", "home"]);
    expect(withoutRedundant(["work", "work/clients", "home"])).toEqual(["work/clients", "home"]);
  });

  test("tree order: each tag before those under it, siblings in the user's order, alphabetical without", () => {
    const names = ["home", "work/meetings", "work/clients/acme", "urgent"];
    expect(treeOrder(names, ["urgent", "work/clients/acme", "home", "work/meetings"]))
      .toEqual(["urgent", "work", "work/clients", "work/clients/acme", "work/meetings", "home"]);
    expect(treeOrder(names, undefined)).toEqual(["home", "urgent", "work", "work/clients", "work/clients/acme", "work/meetings"]);
  });

  test("moving a tag among its siblings takes the tags under it along", () => {
    const ordered = ["home", "work", "work/clients", "work/clients/acme", "work/meetings", "urgent"];
    expect(withSiblingMoved(ordered, "work", 0)).toEqual(["work", "work/clients", "work/clients/acme", "work/meetings", "home", "urgent"]);
    expect(withSiblingMoved(ordered, "work/meetings", 0)).toEqual(["home", "work", "work/meetings", "work/clients", "work/clients/acme", "urgent"]);
    expect(siblingsOf(ordered, "work/clients")).toEqual(["work/clients", "work/meetings"]);
  });

  test("a rename takes the tags under it, the top one first", () => {
    expect(subtreeRenames(["work/clients/acme", "workshop", "work/clients"], "work", "job")).toEqual([
      { from: "work", to: "job" }, { from: "work/clients", to: "job/clients" }, { from: "work/clients/acme", to: "job/clients/acme" }]);
    expect(subtreeRenames(["work/x"], "work", undefined)).toEqual([{ from: "work" }, { from: "work/x" }]);
  });

  test("the nearest tag to say: itself, else the nearest above", () => {
    const options: { [tag: string]: { monospace?: boolean; markdown?: boolean } } = { code: { monospace: true }, "code/python": { markdown: true } };
    expect(nearest("code/python/django", options, (o) => o.monospace)).toEqual({ value: true, tag: "code" });
    expect(nearest("code/python", options, (o) => o.markdown)).toEqual({ value: true, tag: "code/python" });
    expect(nearest("other", options, (o) => o.markdown)).toEqual({});
  });
});
