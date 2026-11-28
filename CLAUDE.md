# CLAUDE.md

## Releases

To prepare version X.Y.Z, bump the version in exactly these files, and nothing else:

- `pom.xml` (the project's `<version>`)
- `src/notes-core/pom.xml` (the parent's `<version>` and its own)
- `src/notes-webapp/package.json`
- `src/notes-webapp/package-lock.json` (the two `"version"` fields at the top)

The release notes go in the message of the `vX.Y.Z` commit (title `vX.Y.Z`, then the notes). Write them
from the commits since the previous `vX.Y.Z` tag.

### Release notes style

Short. A reader skims them to learn what's new, not how to use it. See the `v1.3.0` commit for an example.

- Sections: `## New features` (with `### <area>` subsections, such as Tags, Notes or Administration),
  `## Security`, `## Bugs`. Leave out the sections with nothing in them.
- One short line per item: what the feature is, and where to find it if that's useful
  (`My Account › Notes: …`). Stop there.
- No how-to, no explanations of behavior, edge cases or caveats, no lists of every option. For example,
  write "Formatting toolbar for Markdown notes", not the list of its buttons and shortcuts.
- Abbreviations and `&` are fine ("md", "fixed-width font & line wrap").
- Bugs: say what was wrong, and where or when if it's specific ("… on Safari").
