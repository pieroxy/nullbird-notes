/** config.json's values the admin can change, as the server gives them and the form holds them. */
export interface EditableConfig {
  httpPort: number;
  /** "" for all interfaces. */
  address: string;
  trustedProxies: string[];
  secureCookie: boolean;
  /** Days; 0 keeps them forever. */
  keepLogFiles: number;
  maxNoteSizeMb: number;
}

export interface LargeNote {
  username: string;
  size: number;
}

export interface WarningContext {
  /** config.json's absolute path. */
  configFile: string;
  /** Every note over 1 MB. */
  largeNotes: LargeNote[];
  /** Whether this page is served over HTTPS: a secure cookie only travels over it. */
  overHttps: boolean;
}

const MB = 1024 * 1024;

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, i) => value === b[i]);
}

/** 0 (forever) keeps the most. */
function keepsFewerLogs(before: number, after: number): boolean {
  return after !== 0 && (before === 0 || after < before);
}

/**
 * What saving {@code wanted} over {@code saved} might break, once the service restarts with it:
 * shown above the Save button. Empty when nothing risky changed.
 */
export function configWarnings(saved: EditableConfig, wanted: EditableConfig, context: WarningContext): string[] {
  const warnings: string[] = [];
  const network = wanted.httpPort !== saved.httpPort || wanted.address !== saved.address || wanted.secureCookie !== saved.secureCookie;
  if (network) {
    warnings.push("The port, the address and the secure cookie can make the service unreachable once it restarts. "
      + "To get out of trouble, edit " + context.configFile + " by hand and restart the service.");
  }
  if (wanted.secureCookie && !saved.secureCookie && !context.overHttps) {
    warnings.push("This page is served over plain HTTP: with a secure cookie, nobody, you included, will be able to log in "
      + "unless the service is reached over HTTPS.");
  }
  if (wanted.httpPort !== saved.httpPort && wanted.httpPort < 1024) {
    warnings.push("Ports below 1024 usually need the service to run as root.");
  }
  if (wanted.maxNoteSizeMb < saved.maxNoteSizeMb) {
    const over = context.largeNotes.filter((note) => note.size > wanted.maxNoteSizeMb * MB);
    if (over.length > 0) {
      const users = new Set(over.map((note) => note.username)).size;
      warnings.push((over.length > 1 ? over.length + " notes" : "1 note") + " (" + (users > 1 ? users + " users" : "1 user") + ") "
        + (over.length > 1 ? "are" : "is") + " larger than " + wanted.maxNoteSizeMb + " MB: "
        + (over.length > 1 ? "they" : "it") + " won't be editable any more.");
    }
  }
  if (keepsFewerLogs(saved.keepLogFiles, wanted.keepLogFiles)) {
    warnings.push("Logs older than " + wanted.keepLogFiles + (wanted.keepLogFiles > 1 ? " days" : " day") + " will be deleted at the next daily rotation.");
  }
  if (!sameList(saved.trustedProxies, wanted.trustedProxies)) {
    warnings.push("Wrong trusted proxies: if the proxy in front of the service isn't listed, every visitor seems to come from it, and "
      + "a few failed logins from anyone block logins for everyone for a while. If an address is listed that isn't a proxy of yours, "
      + "anyone can fake where they come from.");
  }
  return warnings;
}

/** Whether {@code wanted} differs from {@code saved} at all. */
export function configChanged(saved: EditableConfig, wanted: EditableConfig): boolean {
  return wanted.httpPort !== saved.httpPort || wanted.address !== saved.address || !sameList(wanted.trustedProxies, saved.trustedProxies)
    || wanted.secureCookie !== saved.secureCookie || wanted.keepLogFiles !== saved.keepLogFiles || wanted.maxNoteSizeMb !== saved.maxNoteSizeMb;
}
