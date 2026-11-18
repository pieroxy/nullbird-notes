package net.pieroxy.notes.api.metadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link TypeScriptType}'s field: never absent. Unannotated fields are optional in TypeScript
 * ({@code field?: T}), and must be of a reference type so that absent reads as null, never as 0 or
 * false: see {@link RequiredFields}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Required {
}
