// Tells the user of a browser too old for the app, instead of the blank page it would get: the
// bundle is ES2022, and an older browser refuses all of it with a syntax error nobody sees.
//
// Plain old JavaScript (no let, no arrow functions, no classes), copied as is: it must run on the
// very browsers it turns away. Loaded at the end of the body, so it runs before the bundle (which is
// deferred) and #app already exists. No innerHTML: the CSP asks for Trusted Types for that.
(function () {
  var MESSAGE = "This browser is too old for Notes.";
  var DETAIL = "Use Chrome or Edge 111, Firefox 119, Safari 16.4 (macOS and iOS), or a later version.";

  function supported() {
    try {
      return (
        // Arrived in Chrome 111, Firefox 119 and Safari 16.4, the versions the README lists.
        typeof String.prototype.isWellFormed === "function" &&
        typeof window.fetch === "function" &&
        typeof window.AbortController === "function" &&
        !!window.indexedDB &&
        typeof IDBTransaction.prototype.commit === "function"
      );
    } catch (e) {
      return false;
    }
  }

  var shown = false;
  function show() {
    if (shown) return;
    shown = true;
    var app = document.getElementById("app");
    var box = document.createElement("div");
    box.className = "unsupported";
    var title = document.createElement("p");
    title.className = "unsupportedtitle";
    title.appendChild(document.createTextNode(MESSAGE));
    var detail = document.createElement("p");
    detail.appendChild(document.createTextNode(DETAIL));
    box.appendChild(title);
    box.appendChild(detail);
    // Without #app, the bundle has nowhere to start (see index.ts).
    if (app) app.parentNode.replaceChild(box, app);
    else document.body.appendChild(box);
  }

  if (!supported()) {
    show();
    return;
  }

  // A safety net for what the checks above miss: the bundle doesn't even parse.
  window.addEventListener("error", function (event) {
    var fromBundle = /\/bundle-[0-9a-f]+\.js/.test(event.filename || "");
    var syntax = (event.error && event.error.name === "SyntaxError") || /SyntaxError/.test(event.message || "");
    if (fromBundle && syntax) show();
  });
})();
