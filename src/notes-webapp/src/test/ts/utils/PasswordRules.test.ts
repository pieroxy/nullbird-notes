import { newPasswordProblem } from "../../../ts/utils/PasswordRules";

describe("newPasswordProblem", () => {
  test("12 characters typed twice are fine", () => {
    expect(newPasswordProblem("123456789012", "123456789012")).toBeUndefined();
  });

  test("11 characters are too short", () => {
    expect(newPasswordProblem("12345678901", "12345678901")).toContain("at least 12 characters");
  });

  test("counts characters, not UTF-16 units: 6 emoji are 6, not 12", () => {
    const six = "😀".repeat(6);
    expect(newPasswordProblem(six, six)).toContain("at least 12 characters");
    const twelve = "😀".repeat(12);
    expect(newPasswordProblem(twelve, twelve)).toBeUndefined();
  });

  test("the two must match", () => {
    expect(newPasswordProblem("123456789012", "123456789013")).toBe("The two new passwords differ.");
  });

  test("too short is said first", () => {
    expect(newPasswordProblem("short", "other")).toContain("at least 12 characters");
  });
});
