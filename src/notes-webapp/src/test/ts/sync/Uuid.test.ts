import { uuid } from "../../../ts/sync/Uuid";

describe("uuid", () => {
  test("is a lowercase version 4 UUID, as the server's note IDs require", () => {
    for (let i = 0; i < 1000; i++) {
      expect(uuid()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    }
  });

  test("doesn't repeat", () => {
    const seen = new Set<string>();
    for (let i = 0; i < 10_000; i++) seen.add(uuid());
    expect(seen.size).toBe(10_000);
  });
});
