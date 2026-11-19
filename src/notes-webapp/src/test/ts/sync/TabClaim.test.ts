import { TabClaim } from "../../../ts/sync/TabClaim";

describe("TabClaim", () => {
  const claims: TabClaim[] = [];
  const tab = () => {
    const claim = new TabClaim("alice");
    claims.push(claim);
    return claim;
  };

  afterEach(() => claims.splice(0).forEach((claim) => claim.close()));

  test("the first tab gets the notes after waiting for an answer", async () => {
    const start = Date.now();
    await tab().claim(async () => undefined);
    expect(Date.now() - start).toBeGreaterThanOrEqual(TabClaim.RELEASE_TIMEOUT - 50);
  });

  test("a newer tab takes them over once the older one has stopped", async () => {
    const events: string[] = [];
    await tab().claim(async () => {
      events.push("first stopping");
      await new Promise((resolve) => setTimeout(resolve, 100));
      events.push("first stopped");
    });

    const start = Date.now();
    expect(await tab().claim(async () => undefined)).toBe(true);
    events.push("second active");

    expect(events).toEqual(["first stopping", "first stopped", "second active"]);
    expect(Date.now() - start).toBeLessThan(TabClaim.RELEASE_TIMEOUT);
  });

  test("of two tabs claiming at once, exactly one gets them", async () => {
    const first = tab();
    const second = tab();
    const won = await Promise.all([first.claim(async () => undefined), second.claim(async () => undefined)]);

    expect(won.filter((w) => w)).toHaveLength(1);
  });

  test("another user's tabs are left alone", async () => {
    const lost: string[] = [];
    await tab().claim(async () => void lost.push("alice"));
    const bob = new TabClaim("bob");
    claims.push(bob);
    await bob.claim(async () => undefined);

    expect(lost).toEqual([]);
  });
});
