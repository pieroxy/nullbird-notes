import { alphabetical, applyMove, applyMoves, displayOrder, moveTo, orderToSend } from "../../../ts/sync/TagOrder";

describe("TagOrder", () => {
  test("alphabetical, case-insensitive first", () => {
    expect(alphabetical(["work", "Home", "home", "art"])).toEqual(["art", "Home", "home", "work"]);
  });

  test("a move puts the tag right after its anchor, or at the top", () => {
    expect(applyMove(["a", "b", "c", "d"], { tag: "d", after: "a" })).toEqual(["a", "d", "b", "c"]);
    expect(applyMove(["a", "b", "c", "d"], { tag: "c", after: null })).toEqual(["c", "a", "b", "d"]);
    expect(applyMove(["a", "b", "c"], { tag: "a", after: "c" })).toEqual(["b", "c", "a"]);
  });

  test("an anchor gone: at the end; a tag unknown: added", () => {
    expect(applyMove(["a", "b"], { tag: "a", after: "zz" })).toEqual(["b", "a"]);
    expect(applyMove(["a", "b"], { tag: "new", after: "a" })).toEqual(["a", "new", "b"]);
  });

  test("moves made on two devices both survive: replayed on the other's order", () => {
    // The server took the laptop's: b to the top. The phone moved d after a, from the old order.
    const server = { revision: 2, tags: ["b", "a", "c", "d"] };
    expect(orderToSend(server, [{ tag: "d", after: "a" }], [])).toEqual(["b", "a", "d", "c"]);
  });

  test("never ordered: alphabetical, and nothing to show differently", () => {
    expect(displayOrder(undefined, [], ["b", "a"], ["b", "a"])).toBeUndefined();
    expect(orderToSend(undefined, [{ tag: "c", after: null }], ["b", "c", "a"])).toEqual(["c", "a", "b"]);
  });

  test("what's shown: the order, filtered, then tags it doesn't have yet at the end", () => {
    const server = { revision: 1, tags: ["work", "gone", "home"] };
    expect(displayOrder(server, [], ["work", "home", "new"], ["home", "new", "work"])).toEqual(["work", "home", "new"]);
    expect(displayOrder(server, [{ tag: "home", after: null }], ["work", "home"], ["work", "home"])).toEqual(["home", "work"]);
  });

  test("moveTo: the anchor is the tag listed before the spot", () => {
    expect(moveTo(["a", "b", "c"], "c", 0)).toEqual({ tag: "c", after: null });
    expect(moveTo(["a", "b", "c"], "a", 1)).toEqual({ tag: "a", after: "b" });
    expect(moveTo(["a", "b", "c"], "a", 2)).toEqual({ tag: "a", after: "c" });
    expect(moveTo(["a", "b", "c"], "b", 9)).toEqual({ tag: "b", after: "c" });
    expect(applyMoves(["a", "b", "c"], [moveTo(["a", "b", "c"], "a", 1)])).toEqual(["b", "a", "c"]);
  });
});
