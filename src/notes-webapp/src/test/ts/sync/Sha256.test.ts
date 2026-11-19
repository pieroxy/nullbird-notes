import { sha256, sha256Bytes } from "../../../ts/sync/Sha256";

const utf8 = (text: string) => new TextEncoder().encode(text);
const subtleHex = async (text: string) =>
  Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", utf8(text))), (b) => b.toString(16).padStart(2, "0")).join("");

describe("sha256Bytes", () => {
  test("matches the FIPS 180-4 test vectors", () => {
    expect(sha256Bytes(utf8(""))).toBe("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    expect(sha256Bytes(utf8("abc"))).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    expect(sha256Bytes(utf8("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")))
      .toBe("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
  });

  test("matches crypto.subtle across every padding boundary and on non-ASCII text", async () => {
    const pieces = ["a", "é", "\n", String.fromCharCode(0xd83d, 0xde00), "ab"];
    let text = "";
    for (let i = 0; i < 300; i++) {
      expect(sha256Bytes(utf8(text))).toBe(await subtleHex(text));
      text += pieces[i % pieces.length];
    }
  });

  test("handles a large text", async () => {
    const text = "line of a large note\n".repeat(100_000);
    expect(sha256Bytes(utf8(text))).toBe(await subtleHex(text));
  });
});

describe("sha256", () => {
  test("gives the same hash with or without crypto.subtle", async () => {
    const text = "Café ☕ " + String.fromCharCode(0xd83d, 0xde00);
    const withSubtle = await sha256(text);
    const original = Object.getOwnPropertyDescriptor(globalThis, "crypto")!;
    Object.defineProperty(globalThis, "crypto", { value: { getRandomValues: crypto.getRandomValues }, configurable: true });
    try {
      expect(await sha256(text)).toBe(withSubtle);
    } finally {
      Object.defineProperty(globalThis, "crypto", original);
    }
  });
});
