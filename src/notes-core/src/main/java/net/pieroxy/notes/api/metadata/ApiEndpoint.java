package net.pieroxy.notes.api.metadata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface ApiEndpoint {
  void process(HttpServletRequest req, HttpServletResponse res);
}
