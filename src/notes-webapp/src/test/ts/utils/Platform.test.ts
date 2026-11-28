import type { Platform as PlatformType } from "../../../ts/utils/Platform";

/** The module reads what the native app said once, when loaded: each case loads it afresh. */
function load(said: unknown): typeof PlatformType {
  // Node has neither: just what Platform uses of them.
  Object.assign(globalThis, { window: { NullbirdNative: said }, location: { origin: "https://page.example.com" } });
  let loaded: typeof PlatformType | undefined;
  jest.isolateModules(() => {
    loaded = jest.requireActual<{ Platform: typeof PlatformType }>("../../../ts/utils/Platform").Platform;
  });
  return loaded!;
}

describe("Platform", () => {
  test("in a browser: everything, and the page's own server", () => {
    const platform = load(undefined);
    expect(platform.isNative()).toBe(false);
    expect(platform.serverOrigin()).toBe("https://page.example.com");
    expect([platform.canExport(), platform.canImport(), platform.canUploadFonts(), platform.usesServiceWorker()]).toEqual([true, true, true, true]);
  });

  test("in a native app: its server, and no downloads, file pickers or service worker", () => {
    const platform = load({ platform: "ios", serverUrl: "https://notes.example.com" });
    expect(platform.isNative()).toBe(true);
    expect(platform.serverOrigin()).toBe("https://notes.example.com");
    expect(platform.overHttps()).toBe(true);
    expect([platform.canExport(), platform.canImport(), platform.canUploadFonts(), platform.usesServiceWorker()]).toEqual([false, false, false, false]);
  });

  test("anything invalid is taken for a browser", () => {
    for (const said of ["ios", { platform: "windows", serverUrl: "https://a.b" }, { platform: "android", serverUrl: "http://a.b" },
      { platform: "android", serverUrl: "https://a.b/" }, { platform: "android" }]) {
      expect(load(said).isNative()).toBe(false);
    }
  });
});
