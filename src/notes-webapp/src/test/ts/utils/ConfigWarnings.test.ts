import { configChanged, configWarnings, EditableConfig, WarningContext } from "../../../ts/utils/ConfigWarnings";

const MB = 1024 * 1024;
const SAVED: EditableConfig = { httpPort: 8080, address: "", trustedProxies: [], secureCookie: false, keepLogFiles: 30, maxNoteSizeMb: 10 };
const CONTEXT: WarningContext = { configFile: "/etc/notes/config.json", largeNotes: [], overHttps: true };

function warnings(change: Partial<EditableConfig>, context: Partial<WarningContext> = {}): string[] {
  return configWarnings(SAVED, { ...SAVED, ...change }, { ...CONTEXT, ...context });
}

describe("configWarnings", () => {
  it("nothing changed, nothing to say", () => {
    expect(warnings({})).toEqual([]);
    expect(configChanged(SAVED, { ...SAVED })).toBe(false);
  });

  it("the port, the address or the secure cookie: how to get out of trouble", () => {
    for (const change of [{ httpPort: 9000 }, { address: "127.0.0.1" }, { secureCookie: true }]) {
      const said = warnings(change);
      expect(said).toHaveLength(1);
      expect(said[0]).toContain("edit /etc/notes/config.json by hand and restart the service");
      expect(configChanged(SAVED, { ...SAVED, ...change })).toBe(true);
    }
  });

  it("a secure cookie over plain HTTP locks everyone out", () => {
    expect(warnings({ secureCookie: true }, { overHttps: false }).join()).toContain("nobody, you included");
    expect(warnings({ secureCookie: true }, { overHttps: true }).join()).not.toContain("nobody");
  });

  it("a privileged port", () => {
    expect(warnings({ httpPort: 80 }).join()).toContain("below 1024");
  });

  it("a lower note size: which notes it leaves uneditable", () => {
    const largeNotes = [{ username: "alice", size: 6 * MB }, { username: "alice", size: 3 * MB }, { username: "bob", size: 8 * MB }];
    expect(warnings({ maxNoteSizeMb: 5 }, { largeNotes })).toEqual(["2 notes (2 users) are larger than 5 MB: they won't be editable any more."]);
    expect(warnings({ maxNoteSizeMb: 7 }, { largeNotes })).toEqual(["1 note (1 user) is larger than 7 MB: it won't be editable any more."]);
    expect(warnings({ maxNoteSizeMb: 9 }, { largeNotes: [] })).toEqual([]);
    expect(warnings({ maxNoteSizeMb: 20 }, { largeNotes })).toEqual([]);
  });

  it("fewer days of logs, forever being the most", () => {
    expect(warnings({ keepLogFiles: 7 })).toEqual(["Logs older than 7 days will be deleted at the next daily rotation."]);
    expect(warnings({ keepLogFiles: 60 })).toEqual([]);
    expect(warnings({ keepLogFiles: 0 })).toEqual([]);
    expect(configWarnings({ ...SAVED, keepLogFiles: 0 }, { ...SAVED, keepLogFiles: 365 }, CONTEXT)).toHaveLength(1);
  });

  it("trusted proxies", () => {
    expect(warnings({ trustedProxies: ["10.0.0.1"] }).join()).toContain("trusted proxies");
  });
});
