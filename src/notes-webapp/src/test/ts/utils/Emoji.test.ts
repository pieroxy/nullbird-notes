import { addRecent, drawnVersions, Emoji, emojiGroups, emojiHelperShown, loadRecent, MAX_RECENT, searchEmojis, systemShortcut } from "../../../ts/utils/Emoji";

const stored = new Map<string, string>();

beforeEach(() => {
  stored.clear();
  Object.assign(globalThis, {
    localStorage: {
      getItem: (key: string) => stored.get(key) ?? null,
      setItem: (key: string, value: string) => stored.set(key, value),
      removeItem: (key: string) => stored.delete(key),
    },
  });
});

const all = () => emojiGroups().flatMap((g) => g.emojis);
const chars = (emojis: Emoji[]) => emojis.map((e) => e.char);

describe("Emoji", () => {
  test("the list: Unicode's groups, without skin-tone variants", () => {
    const groups = emojiGroups();
    expect(groups.map((g) => g.id)).toEqual(["smileys", "people", "animals", "food", "travel", "activities", "objects", "symbols", "flags"]);
    expect(groups[0].emojis[0]).toEqual(expect.objectContaining({ char: "😀", version: 1, name: "grinning face" }));
    expect(all().length).toBeGreaterThan(1800);
    expect(all().some((e) => /[\u{1F3FB}-\u{1F3FF}]/u.test(e.char))).toBe(false);
    expect(new Set(chars(all())).size).toBe(all().length);
  });

  test("searching: by the words' starts, in the name or the keywords, the names first", () => {
    const results = searchEmojis(all(), "thumbs");
    expect(chars(results).slice(0, 2)).toEqual(["👍", "👎"]);
    expect(chars(searchEmojis(all(), "lol"))).toContain("😂");
    expect(chars(searchEmojis(all(), "red heart"))[0]).toBe("❤️");
    expect(chars(searchEmojis(all(), "flag fra"))).toEqual(["🇫🇷"]);
    expect(chars(searchEmojis(all(), "PINATA"))).toContain("🪅");
    expect(searchEmojis(all(), "  ")).toEqual([]);
    expect(searchEmojis(all(), "zzqx")).toEqual([]);
  });

  test("the recent ones: the last first, each once, so many at most", () => {
    addRecent("😀");
    addRecent("🎉");
    addRecent("😀");
    expect(loadRecent()).toEqual(["😀", "🎉"]);
    for (let i = 0; i < MAX_RECENT + 5; i++) addRecent(String(i));
    expect(loadRecent().length).toBe(MAX_RECENT);
    stored.set("notes.emoji.recent", "{garbled");
    expect(loadRecent()).toEqual([]);
  });

  test("the versions drawn: those whose sample is; all when even the oldest isn't", () => {
    const emojis: Emoji[] = [
      { char: "a", version: 0.6, name: "", keywords: "" },
      { char: "b", version: 13, name: "", keywords: "" },
      { char: "c", version: 13, name: "", keywords: "" },
      { char: "d", version: 17, name: "", keywords: "" },
    ];
    expect([...drawnVersions(emojis, (s) => s !== "d")]).toEqual([0.6, 13]);
    expect([...drawnVersions(emojis, () => false)]).toEqual([0.6, 13, 17]);
  });

  test("auto: shown with a mouse, not on a touch screen", () => {
    expect(emojiHelperShown("auto", false)).toBe(true);
    expect(emojiHelperShown("auto", true)).toBe(false);
    expect(emojiHelperShown("shown", true)).toBe(true);
    expect(emojiHelperShown("hidden", false)).toBe(false);
  });

  test("the system's own picker, where there's one", () => {
    expect(systemShortcut("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).toBe("Win+.");
    expect(systemShortcut("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")).toBe("Ctrl+Cmd+Space");
    expect(systemShortcut("Mozilla/5.0 (X11; Linux x86_64)")).toBeUndefined();
  });
});
