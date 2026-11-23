import { titleAndRest } from "../../../ts/utils/format";

describe("titleAndRest", () => {
  test("the first non-blank line, then what follows it, blank lines before aside", () => {
    expect(titleAndRest("\n  Title  \n\n \nline 1\n  line 2\n")).toEqual({ title: "Title", rest: "line 1\n  line 2\n" });
  });

  test("a title alone, or nothing", () => {
    expect(titleAndRest("Only")).toEqual({ title: "Only", rest: "" });
    expect(titleAndRest(" \n\n")).toEqual({ title: "", rest: "" });
  });
});
