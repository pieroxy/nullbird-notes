import m from "mithril";
import { ApiEndpoints } from "../../auto/ApiEndpoints";
import { session } from "../../sync/Session";
import { Auth } from "../../utils/Auth";
import { Endpoints } from "../../utils/navigation/Endpoints";
import { Routing } from "../../utils/navigation/Routing";
import { NO_FILTER, saveFilter } from "../../utils/TagFilter";
import { EMOJI_HELPER, loadChoice, saveChoice, Setting, SPACING, TEXT_PIXELS, TEXT_SIZE } from "../../utils/Appearance";
import { ChangePasswordForm } from "../ChangePasswordForm";
import { FontChoices } from "../FontChoices";
import { ImportNotes } from "../ImportNotes";
import { MailFactorSection } from "../MailFactorSection";
import { PageSection, sectionedPage } from "../SectionedPage";
import { AbstractPage } from "./AbstractPage";

export interface AccountPageAttrs {
  /** From the route: /account/:section. Absent at /account. */
  section?: string;
}

/** Fetched once: moving between sections recreates the page. */
let version: string | undefined;

/**
 * The user's account: who's logged in and logging out, then its sections, each at
 * /account/:section (see sectionedPage). Opens the user's notes (see NotesSession) if they aren't
 * yet: the sync goes on, and logging out knows what isn't synced.
 */
export class AccountPage extends AbstractPage<AccountPageAttrs> {
  private loggingOut = false;
  private readonly sections: PageSection[] = [
    { id: "appearance", title: "Appearance", render: () => this.appearance() },
    { id: "data", title: "Your notes", render: () => this.data() },
    { id: "security", title: "Security", render: () => this.security() },
  ];

  oninit() {
    const username = Auth.getUsername();
    if (username) void session.open(username);
    if (version === undefined) {
      ApiEndpoints.Version.call({})
        .then((output) => (version = output.version + " (" + output.revision + ")"))
        .catch(() => undefined);
    }
  }

  getPageTitle(): string {
    return "My Account - Notes";
  }

  render({ attrs }: m.Vnode<AccountPageAttrs>): m.Children {
    return sectionedPage({
      route: Endpoints.ACCOUNT,
      title: "My Account",
      sections: this.sections,
      section: attrs.section,
      className: "accountpage",
      navHeader: [
        m("p", ["Logged in as ", m("strong", Auth.getUsername())]),
        m("p.muted", "Logging out removes your notes from this device. They stay on the server."),
        m("button.secondary", { type: "button", disabled: this.loggingOut, onclick: () => void this.logout() }, "Log out"),
      ],
      navFooter: version ? m("p.accountversion", "Version " + version) : null,
    });
  }

  private appearance(): m.Children {
    return [
      m("h2", "Text size"),
      // Each sample at its own size, whatever the one chosen; enlarged alike, to be told apart.
      choices(TEXT_SIZE, "textsize", "Text size", (id) => m("span.sample", { style: { fontSize: TEXT_PIXELS[id] * 1.5 + "px" } }, "Aa")),
      m("h2", "Spacing"),
      m("p.muted", "How close together the notes, tags and menu items are."),
      // Three rows, as far apart as rows are at that spacing.
      choices(SPACING, "spacing", "Spacing", (id) => m("span.sample.rows." + id, [m("i"), m("i"), m("i")])),
      m(FontChoices),
      m("h2", "Emoji button"),
      m("p.muted", "A button by the note's, to insert emojis. Phones and tablets have them on their keyboard already."),
      choices(EMOJI_HELPER, "emojihelper", "Emoji button", (id) => m("span.sample.words", EMOJI_SAMPLES[id])),
      m("p.muted.devicenote", "All of these are for this device only: each of yours keeps its own."),
    ];
  }

  private security(): m.Children {
    return [
      m("h2", "Password"),
      m(ChangePasswordForm, { username: Auth.getUsername() ?? "" }),
      m(MailFactorSection, { username: Auth.getUsername() ?? "" }),
    ];
  }

  private data(): m.Children {
    const pending = session.engine?.status().pending ?? 0;
    return [
      m("h2", "Export"),
      m("p", "Downloads all your notes as a .zip file, laid out as Simplenote's export: a .txt file per note, "
        + "plus source/notes.json with every note, its tags and dates, deleted notes included."),
      pending > 0 ? m("p.warning", pending + (pending > 1 ? " notes have" : " note has")
        + " changes not synced yet: the export has " + (pending > 1 ? "them" : "it") + " as last synced.") : null,
      // A plain link: the server answers with a download, the page stays.
      m("a.button", { href: "/export", download: "" }, "Export my notes"),
      m("h2", "Import"),
      m("p", "Adds the notes of an export to yours: this app's or Simplenote's, the .zip file or the notes.json in it. "
        + "You see what it would do before running it."),
      m("p.muted", "Notes you already have are skipped: the same note (by its ID), or the same text. "
        + "Nothing of yours is changed or deleted."),
      m(ImportNotes),
    ];
  }

  /** Logging out wipes this device's copy of the notes: a shared computer mustn't keep them. */
  private async logout() {
    const pending = session.engine?.status().pending ?? 0;
    if (pending > 0 && !confirm(pending + (pending > 1 ? " notes have" : " note has")
      + " changes not synced yet, which will be lost. Log out anyway?")) return;
    this.loggingOut = true;
    const username = Auth.getUsername();
    if (username) {
      await session.destroy(username);
      saveFilter(username, NO_FILTER);
    }
    await Auth.logout();
    Routing.goToScreen(Endpoints.LOGIN);
  }
}

const EMOJI_SAMPLES: Record<string, string> = { auto: "On computers", shown: "Everywhere", hidden: "Nowhere" };

/** Radio buttons drawn as tiles, each with a sample of what it does. */
function choices(setting: Setting, name: string, label: string, sample: (id: string) => m.Children): m.Children {
  const chosen = loadChoice(setting);
  return m(".choices", { role: "radiogroup", "aria-label": label }, setting.choices.map((c) =>
    m("label.choice", { key: c.id, class: c.id === chosen ? "chosen" : "" }, [
      m("input", { type: "radio", name, value: c.id, checked: c.id === chosen, onchange: () => saveChoice(setting, c.id) }),
      sample(c.id),
      m("span", c.label),
    ])));
}
