package net.pieroxy.notes.api.metadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Endpoint {
  ApiMethod method();

  /** Whether the webapp redraws once a call answers, as Mithril's m.request does. */
  boolean redraw() default true;
}
