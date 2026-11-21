package net.pieroxy.notes.api.implementations.admin;

import net.pieroxy.notes.api.metadata.Required;
import net.pieroxy.notes.api.metadata.TypeScriptType;

/**
 * A user as admins see it. Times in epoch milliseconds.
 *
 * @param email      absent if none.
 * @param createdAt  absent for users written by hand in credentials.json.
 * @param lastSeenAt the last use of any of their sessions; absent if they have none left.
 */
@TypeScriptType
public record AdminUser(@Required String username, String email, @Required boolean admin, Long createdAt, Long lastSeenAt) {
}
