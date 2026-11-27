import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AdminUser } from "../auto/api-types";
import { Auth } from "../utils/Auth";
import { Endpoints } from "../utils/navigation/Endpoints";
import { CopyableText } from "./CopyableText";
import { icon } from "./Icons";

/** The users, as the server last said. */
class Users {
  list?: AdminUser[];
  error?: string;

  load() {
    ApiEndpoints.AdminUsers.call({})
      .then((output) => {
        this.list = output.users;
        this.error = undefined;
      })
      .catch((err: Error) => (this.error = err.message));
  }
}

function userPath(username: string): string {
  return Endpoints.ADMIN + "/users/" + encodeURIComponent(username);
}

function formatDate(millis: number | undefined): string {
  return millis === undefined ? "unknown" : new Date(millis).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

/** Recent ones as "5 min ago"; absent: no session left, logged out or expired. */
function formatLastSeen(millis: number | undefined, now = Date.now()): string {
  if (millis === undefined) return "not logged in";
  const minutes = Math.floor((now - millis) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return minutes + " min ago";
  if (minutes < 24 * 60) return Math.floor(minutes / 60) + " h ago";
  return new Date(millis).toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

/** Their second factors: "App + mail", "App", "Mail" or "Off". */
function secondFactor(user: AdminUser): string {
  if (user.totpFactor && user.mailFactor) return "App + mail";
  return user.totpFactor ? "App" : user.mailFactor ? "Mail" : "Off";
}

function badges(user: AdminUser): m.Children {
  return [
    user.admin ? m("span.badge", "admin") : null,
    user.username === Auth.getUsername() ? m("span.badge.you", "you") : null,
  ];
}

/** Every user, each opening their page; and adding one. */
export class UserList implements m.ClassComponent {
  private readonly users = new Users();
  private adding = false;

  oninit() {
    this.users.load();
  }

  view(): m.Children {
    const list = this.users.list;
    return [
      m(".sectionheader", [
        m("h2", "Users"),
        this.adding ? null : m("button.secondary.withicon", { type: "button", onclick: () => (this.adding = true) }, [icon("plus"), "Add user"]),
      ]),
      this.adding ? m(AddUserForm, {
        onAdded: () => this.users.load(),
        onClose: () => (this.adding = false),
      }) : null,
      this.users.error ? m("p.errormessage", { role: "alert" }, this.users.error) : null,
      list === undefined ? (this.users.error ? null : m("p.muted", "Loading…")) : m("ul.userlist", list.map((user) =>
        m("li", { key: user.username }, m(m.route.Link, { href: userPath(user.username) }, [
          m(".userinfo", [
            m(".username", [m("span", user.username), badges(user)]),
            user.email ? m(".usermeta", user.email) : null,
            m(".usermeta", "Created " + formatDate(user.createdAt) + " · Last seen " + formatLastSeen(user.lastSeenAt)
              + " · 2FA " + secondFactor(user).toLowerCase()),
          ]),
          icon("chevron-right"),
        ])))),
    ];
  }
}

interface AddUserFormAttrs {
  onAdded: () => void;
  onClose: () => void;
}

/** Username, email, admin: the server picks the password, shown once added. */
class AddUserForm implements m.ClassComponent<AddUserFormAttrs> {
  private username = "";
  private email = "";
  private admin = false;
  private submitting = false;
  private error?: string;
  private added?: { username: string; password: string };

  view({ attrs }: m.Vnode<AddUserFormAttrs>): m.Children {
    if (this.added) {
      return m(".adduser", [
        m("p", ["User ", m("strong", this.added.username), " added. Their password:"]),
        generatedPassword(this.added.password),
        m("button", { type: "button", onclick: attrs.onClose }, "Done"),
      ]);
    }
    return m("form.stackedform.adduser", { onsubmit: (e: Event) => this.submit(e, attrs) }, [
      m("label.field", [
        m("span.fieldlabel", "Username"),
        m("input", {
          type: "text", value: this.username, disabled: this.submitting, autocomplete: "off", autocapitalize: "none", spellcheck: false,
          oncreate: (vnode: m.VnodeDOM) => (vnode.dom as HTMLInputElement).focus(),
          oninput: (e: Event) => (this.username = (e.target as HTMLInputElement).value.toLowerCase()),
        }),
        m("span.fieldhint", "Lowercase letters, digits, \".\", \"_\" and \"-\". It can't be changed later."),
      ]),
      m("label.field", [
        m("span.fieldlabel", "Email (optional)"),
        m("input", {
          type: "email", value: this.email, disabled: this.submitting, autocomplete: "off",
          oninput: (e: Event) => (this.email = (e.target as HTMLInputElement).value),
        }),
      ]),
      m("label.checkfield", [
        m("input", { type: "checkbox", checked: this.admin, disabled: this.submitting, onchange: (e: Event) => (this.admin = (e.target as HTMLInputElement).checked) }),
        m("span", "Admin: can manage users"),
      ]),
      m(".buttons", [
        m("button", { type: "submit", disabled: this.submitting || !this.username.trim() }, "Add"),
        m("button.secondary", { type: "button", disabled: this.submitting, onclick: attrs.onClose }, "Cancel"),
      ]),
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
    ]);
  }

  private submit(e: Event, attrs: AddUserFormAttrs) {
    e.preventDefault();
    this.submitting = true;
    this.error = undefined;
    const username = this.username.trim();
    ApiEndpoints.AdminAddUser.call({ newUsername: username, email: this.email.trim() || undefined, admin: this.admin })
      .then((result) => {
        if (result.ok && result.password) {
          this.added = { username, password: result.password };
          attrs.onAdded();
        } else {
          this.error = result.error ?? "The user couldn't be added.";
        }
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.submitting = false));
  }
}

export interface UserDetailAttrs {
  username: string;
}

/** A user's page: their admin flag, a new password, their 2FA reset. Read-only for the admin's own account. */
export class UserDetail implements m.ClassComponent<UserDetailAttrs> {
  private readonly users = new Users();
  private busy = false;
  private error?: string;
  private password?: string;

  oninit() {
    this.users.load();
  }

  view({ attrs }: m.Vnode<UserDetailAttrs>): m.Children {
    const back = m(m.route.Link, { href: Endpoints.ADMIN + "/users", class: "backlink" }, [icon("chevron-left"), "All users"]);
    const list = this.users.list;
    if (list === undefined) return [back, this.users.error ? m("p.errormessage", { role: "alert" }, this.users.error) : m("p.muted", "Loading…")];
    const user = list.find((candidate) => candidate.username === attrs.username);
    if (!user) return [back, m("p", "There's no user \"" + attrs.username + "\".")];
    const self = user.username === Auth.getUsername();
    return [
      back,
      m("h2.username", [m("span", user.username), badges(user)]),
      m("dl.userfacts", [
        m("dt", "Email"), m("dd", user.email ?? "none"),
        m("dt", "Created"), m("dd", formatDate(user.createdAt)),
        m("dt", "Last seen online"), m("dd", formatLastSeen(user.lastSeenAt)),
        m("dt", "Two-step verification"), m("dd", secondFactor(user)),
      ]),
      self ? m("p.muted", [
        "This is you. Change your password and two-step verification in ", m(m.route.Link, { href: Endpoints.ACCOUNT + "/security" }, "My Account"),
        ". Only another admin can change your admin rights.",
      ]) : [
        m("h3", "Admin"),
        m("label.checkfield", [
          m("input", { type: "checkbox", checked: user.admin, disabled: this.busy, onchange: (e: Event) => this.setAdmin(user, (e.target as HTMLInputElement).checked) }),
          m("span", "Admin: can manage users"),
        ]),
        m("h3", "Password"),
        this.password ? generatedPassword(this.password) : [
          m("p.muted", "Gives them a new password, which you then pass on to them. They're logged out everywhere."),
          m("button.secondary", { type: "button", disabled: this.busy, onclick: () => this.resetPassword(user) }, "Reset password"),
        ],
        user.totpFactor || user.mailFactor ? [
          m("h3", "Two-step verification"),
          m("p.muted", "For a lost phone or mailbox: turns it off, so that their password alone logs them in. "
            + "They stay logged in, are told by mail if they have an address, and can set it up again in My Account."),
          m("button.secondary", { type: "button", disabled: this.busy, onclick: () => this.resetSecondFactor(user) }, "Reset 2FA"),
        ] : null,
      ],
      this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
    ];
  }

  private setAdmin(user: AdminUser, admin: boolean) {
    this.run(ApiEndpoints.AdminSetAdmin.call({ user: user.username, admin }), () => (user.admin = admin));
  }

  private resetPassword(user: AdminUser) {
    if (!confirm("Give " + user.username + " a new password? Their current one stops working, and they're logged out everywhere.")) return;
    this.run(ApiEndpoints.AdminResetPassword.call({ user: user.username }), (password) => {
      this.password = password;
      this.users.load();
    });
  }

  private resetSecondFactor(user: AdminUser) {
    if (!confirm("Turn off two-step verification for " + user.username + "? Their password alone will log them in.")) return;
    this.run(ApiEndpoints.AdminResetSecondFactor.call({ user: user.username }), () => this.users.load());
  }

  private run(call: Promise<{ ok: boolean; error?: string; password?: string }>, done: (password?: string) => void) {
    this.busy = true;
    this.error = undefined;
    call
      .then((result) => {
        if (result.ok) done(result.password);
        else this.error = result.error ?? "That didn't work.";
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.busy = false));
  }
}

/** A password the server picked, for the admin to pass on. */
function generatedPassword(password: string): m.Children {
  return m(CopyableText, {
    text: password,
    kind: "password",
    hint: "Pass it on to them now: it won't be shown again.",
    selectedHint: "Selected: press Ctrl+C (⌘C on a Mac) to copy it. It won't be shown again.",
  });
}
