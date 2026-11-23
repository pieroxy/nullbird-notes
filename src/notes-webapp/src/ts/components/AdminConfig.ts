import m from "mithril";
import { ApiEndpoints } from "../auto/ApiEndpoints";
import { AdminConfig, AdminConfigApiOutput, AdminForwarder } from "../auto/api-types";
import { configChanged, configWarnings, EditableConfig } from "../utils/ConfigWarnings";

/** The form's fields, as typed. */
interface Fields {
  httpPort: string;
  address: string;
  /** One per line. */
  trustedProxies: string;
  secureCookie: boolean;
  keepLogFiles: string;
  maxNoteSizeMb: string;
}

function fieldsOf(config: AdminConfig): Fields {
  return {
    httpPort: String(config.httpPort),
    address: config.address,
    trustedProxies: config.trustedProxies.join("\n"),
    secureCookie: config.secureCookie,
    keepLogFiles: String(config.keepLogFiles),
    maxNoteSizeMb: String(config.maxNoteSizeMb),
  };
}

/** The trusted proxies field's lines, one address or range each. */
function proxyLines(text: string): string[] {
  return text.split(/[\n,]/).map((line) => line.trim()).filter((line) => line !== "");
}

function editable(config: AdminConfig): EditableConfig {
  return { ...config };
}

/** @return the values, or why they can't be saved. */
function parse(fields: Fields): EditableConfig | string {
  const whole = (value: string) => (/^\s*\d+\s*$/.test(value) ? Number(value) : NaN);
  const httpPort = whole(fields.httpPort);
  const keepLogFiles = whole(fields.keepLogFiles);
  const maxNoteSizeMb = whole(fields.maxNoteSizeMb);
  if (isNaN(httpPort)) return "The HTTP port must be a number.";
  if (isNaN(keepLogFiles)) return "The days of logs to keep must be a number.";
  if (isNaN(maxNoteSizeMb)) return "The largest note must be a number of MB.";
  return {
    httpPort,
    address: fields.address.trim(),
    trustedProxies: proxyLines(fields.trustedProxies),
    secureCookie: fields.secureCookie,
    keepLogFiles,
    maxNoteSizeMb,
  };
}

function show(value: string | number | boolean | string[]): string {
  if (Array.isArray(value)) return value.length ? value.join(", ") : "none";
  if (typeof value === "boolean") return value ? "on" : "off";
  return value === "" ? "all interfaces" : String(value);
}

/**
 * config.json, saved for the next restart: nothing changes before. Each field says what's in effect
 * when that differs; warnings above Save say what a change might break. The data folder is shown,
 * not editable: moving it is a migration.
 */
export class ConfigSection implements m.ClassComponent {
  private config?: AdminConfigApiOutput;
  private fields?: Fields;
  private loadError?: string;
  private saving = false;
  private error?: string;
  private saved = false;

  oninit() {
    this.load();
  }

  private load() {
    ApiEndpoints.AdminConfig.call({})
      .then((config) => {
        this.config = config;
        this.fields = fieldsOf(config.saved);
        this.loadError = undefined;
      })
      .catch((err: Error) => (this.loadError = err.message));
  }

  view(): m.Children {
    const config = this.config;
    const fields = this.fields;
    if (!config || !fields) return [m("h2", "Config"), this.loadError ? m("p.errormessage", { role: "alert" }, this.loadError) : m("p.muted", "Loading…")];
    const saved = editable(config.saved);
    const running = editable(config.running);
    const parsed = parse(fields);
    const wanted = typeof parsed === "string" ? undefined : parsed;
    const changed = wanted !== undefined && configChanged(saved, wanted);
    const pendingRestart = configChanged(running, saved);
    const warnings = wanted ? configWarnings(saved, wanted, { configFile: config.configFile, largeNotes: config.largeNotes, overHttps: location.protocol === "https:" }) : [];

    /** What's in effect, when the saved value differs: it takes over at the next restart. */
    const inEffect = (key: keyof EditableConfig) => {
      const now = running[key];
      const next = saved[key];
      const same = Array.isArray(now) && Array.isArray(next) ? now.join() === next.join() : now === next;
      return same ? null : m("span.ineffect", "In effect until the restart: " + show(now));
    };
    const text = (key: "httpPort" | "address" | "keepLogFiles" | "maxNoteSizeMb", label: string, hint: string, attrs: Record<string, unknown> = {}) =>
      m("label.field", [
        m("span.fieldlabel", label),
        m("input", {
          ...attrs, value: fields[key], disabled: this.saving,
          oninput: (e: Event) => {
            fields[key] = (e.target as HTMLInputElement).value;
            this.saved = false;
          },
        }),
        m("span.fieldhint", hint),
        inEffect(key),
      ]);

    return [
      m("h2", "Config"),
      m("p.muted", ["Saved to ", m("code", config.configFile), ". Changes take effect when the service restarts."]),
      pendingRestart ? m("p.notice", { role: "status" }, "Saved changes are waiting for the service to restart.") : null,
      m("form.stackedform.configform", { onsubmit: (e: Event) => this.submit(e, wanted) }, [
        m("h3", "Network"),
        text("httpPort", "HTTP port", "The port the service listens on.", { type: "text", inputmode: "numeric", autocomplete: "off" }),
        text("address", "Address", "The interface to listen on, e.g. 127.0.0.1 behind a reverse proxy. Blank: all interfaces.", { type: "text", autocomplete: "off", spellcheck: false }),
        m(".field", [
          m("label.checkfield", [
            m("input", {
              type: "checkbox", checked: fields.secureCookie, disabled: this.saving,
              onchange: (e: Event) => {
                fields.secureCookie = (e.target as HTMLInputElement).checked;
                this.saved = false;
              },
            }),
            m("span", "Secure cookie"),
          ]),
          m("span.fieldhint", "The login cookie then never travels in clear. Turn it off only if users reach the service over plain HTTP: "
            + "with it on, they can't log in."),
          inEffect("secureCookie"),
        ]),
        m("label.field", [
          m("span.fieldlabel", "Trusted proxies"),
          m("textarea", {
            rows: 3, value: fields.trustedProxies, disabled: this.saving, spellcheck: false,
            oninput: (e: Event) => {
              fields.trustedProxies = (e.target as HTMLTextAreaElement).value;
              this.saved = false;
            },
          }),
          m("span.fieldhint", "IP addresses or ranges (10.0.0.0/8) of your reverse proxies, one per line: their X-Forwarded-For header is believed."),
          inEffect("trustedProxies"),
        ]),
        this.forwarders(config.forwarders, fields),
        m("h3", "Notes"),
        text("maxNoteSizeMb", "Largest note (MB)", "Larger notes are refused.", { type: "text", inputmode: "numeric", autocomplete: "off" }),
        m(".field", [
          m("span.fieldlabel", "Data folder"),
          m("code.readonly", config.dataFolderPath),
          m("span.fieldhint", ["Moving it is a migration: stop the service, copy the folder to its new place, set ", m("code", "dataFolder"),
            " in ", m("code", config.configFile), ", then start the service."]),
        ]),
        m("h3", "Logs"),
        text("keepLogFiles", "Days of logs to keep", "0 keeps them forever.", { type: "text", inputmode: "numeric", autocomplete: "off" }),
        warnings.length > 0 ? m("ul.warnings", { role: "alert" }, warnings.map((warning) => m("li", warning))) : null,
        typeof parsed === "string" ? m("p.errormessage", { role: "alert" }, parsed) : null,
        m(".buttons", [
          m("button", { type: "submit", disabled: this.saving || !changed }, "Save"),
          m("button.secondary", {
            type: "button", disabled: this.saving || !changed,
            onclick: () => {
              this.fields = fieldsOf(config.saved);
              this.error = undefined;
            },
          }, "Undo changes"),
        ]),
        this.error ? m("p.errormessage", { role: "alert" }, this.error) : null,
        this.saved ? m("p.successmessage", { role: "status" }, "Saved. Restart the service to apply it.") : null,
      ]),
    ];
  }

  /**
   * The addresses that forwarded requests without being trusted: likely the proxy in front, but a
   * client can fake the header, hence the evidence. Adding one only fills the field.
   */
  private forwarders(forwarders: AdminForwarder[], fields: Fields): m.Children {
    if (forwarders.length === 0) return null;
    const listed = proxyLines(fields.trustedProxies);
    return m(".field.forwarders", [
      m("span.fieldlabel", "Seen forwarding requests"),
      m("span.fieldhint", "Since the service started, these addresses sent requests with an X-Forwarded-For header without being trusted. "
        + "A reverse proxy adds it to nearly all of its requests, for many clients."),
      m("ul", forwarders.map((forwarder) => {
        const added = listed.includes(forwarder.address);
        const share = Math.round(100 * forwarder.forwarded / Math.max(1, forwarder.requests));
        const clients = forwarder.clients + (forwarder.moreClients ? "+" : "") + (forwarder.clients > 1 || forwarder.moreClients ? " clients" : " client");
        return m("li", { key: forwarder.address }, [
          m(".forwarderinfo", [
            m("code", forwarder.address),
            forwarder.yours ? m("span.forwardernote", "Your own requests come from it.") : null,
            forwarder.privateAddress ? null : m("span.forwardernote.risky", "A public address: trust it only if it's your own proxy."),
            m("span.fieldhint", share + "% of " + forwarder.requests.toLocaleString() + " requests forwarded, for " + clients
              + ". Last seen " + new Date(forwarder.lastSeenAt).toLocaleString() + "."),
          ]),
          m("button.secondary", {
            type: "button", disabled: this.saving || added,
            onclick: () => {
              fields.trustedProxies = [...listed, forwarder.address].join("\n");
              this.saved = false;
            },
          }, added ? "Added" : "Add"),
        ]);
      })),
      m("span.fieldhint", "A proxy in a Docker container can change address when it restarts: its network's range, e.g. 172.18.0.0/16, keeps working."),
    ]);
  }

  private submit(e: Event, wanted: EditableConfig | undefined) {
    e.preventDefault();
    if (!wanted) return;
    this.saving = true;
    this.error = undefined;
    this.saved = false;
    ApiEndpoints.AdminSaveConfig.call(wanted)
      .then((result) => {
        if (result.ok) {
          this.saved = true;
          this.load();
        } else {
          this.error = result.error ?? "The config couldn't be saved.";
        }
      })
      .catch((err: Error) => (this.error = err.message))
      .finally(() => (this.saving = false));
  }
}
