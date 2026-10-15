"use strict";

/*
 * epub.html: an EPUB, the format most books that are not PDFs come in. Issue #50.
 *
 * A book is a zip of web pages. Each chapter is an XHTML file with its stylesheets, pictures
 * and fonts beside it, and a package file lists the chapters in reading order. So this is a
 * reader of Gander's own over two libraries the app already carried, JSZip to open the zip
 * and DOMPurify to clean a chapter. The libraries written for the job were looked at first:
 * epub.js last had a release in 2022 and is 224 KB before the JSZip it needs, and foliate-js
 * supports only the newest engines and has no stable release.
 *
 * A chapter is somebody else's markup, and three things stand between it and the page.
 * DOMPurify takes out script, handlers, forms and frames. Nothing in a chapter keeps an
 * address: a picture, font or stylesheet is read out of the zip here and handed over as an
 * object URL, and anything pointing outside the book is dropped. And the page's policy
 * (ViewerPolicy.kt) runs no script that is not Gander's own, whatever got past the first two.
 *
 * One chapter is on the page at a time, in a shadow tree, so the book's stylesheets cannot
 * reach the page around it and the page's cannot reach the book. Inside it the chapter gets
 * an html and a body element of its own, which is what its stylesheets expect to find.
 *
 * A book has no pages, since its text is laid out afresh on every screen and at every size
 * of type. How far through it the reader is, which the app shows as a percentage, is counted
 * in equal stretches of its files: see VW_EPUB_PAGE_BYTES. The place it reopens at is finer
 * than that, the first letter that was on the screen: see firstShowing().
 */

/*
 * How much of a chapter's file counts as one stretch of the book, which the code below calls
 * a page: about what a printed page of a novel comes to. Counted from the files, since their
 * sizes are known without reading them, and so the same on every screen.
 */
var VW_EPUB_PAGE_BYTES = 2048;

var vwEpub = (function () {
  var XLINK = "http://www.w3.org/1999/xlink";
  var SVGNS = "http://www.w3.org/2000/svg";
  var OPS = "http://www.idpf.org/2007/ops";
  var MATHML = "http://www.w3.org/1998/Math/MathML";

  /*
   * The two ways a book's fonts are scrambled, each with how much of the file's start it
   * covers. Neither is a lock on the book. Both are published, and are there so that a font
   * does not come out of the zip whole when someone unzips it: see unscrambled().
   */
  var SCRAMBLES = Object.assign(bag(), {
    "http://www.idpf.org/2008/embedding": { key: "idpf", length: 1040 },
    "http://ns.adobe.com/pdf/enc#RC": { key: "adobe", length: 1024 }
  });

  /* How a font file begins: TrueType, OpenType, a collection, and the two web wrappings */
  var FONT_START = /^(\x00\x01\x00\x00|OTTO|true|typ1|ttcf|wOFF|wOF2)$/;

  var UUID = /[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}/i;

  /* Elements with no end tag in HTML, which is what tells them from one written as <p/> */
  var VOID = /^(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)$/i;

  /*
   * What DOMPurify takes out beyond its own list. A chapter's stylesheets are read and
   * rewritten on their own, see sheet(). Forms and frames have no place in a book. The
   * attributes are the ones that fetch something or act by themselves.
   *
   * SANITIZE_DOM is the one thing switched off. It removes an id that is also the name of
   * something the document has, and "title" is both: a book that marks its heading that way
   * would lose the rule its stylesheet has for it and the place its contents point to. Such
   * an id is a danger where it can stand in for document.title, which takes an element in
   * the document itself. A chapter is only ever in the shadow tree, where no id does that,
   * and must stay there for this to hold.
   */
  var PURIFY = {
    RETURN_DOM_FRAGMENT: true,
    SANITIZE_DOM: false,
    FORBID_TAGS: ["style", "link", "meta", "base", "form", "input", "button", "select",
      "option", "textarea", "iframe", "frame", "object", "embed", "dialog"],
    FORBID_ATTR: ["srcset", "sizes", "background", "autoplay", "ping", "action", "formaction"],
    ADD_ATTR: ["epub:type", "xml:lang"]
  };

  /* By extension, for a file the package gives no type or a wrong one */
  var TYPES = Object.assign(bag(), {
    png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif",
    webp: "image/webp", svg: "image/svg+xml", avif: "image/avif", bmp: "image/bmp",
    mp3: "audio/mpeg", m4a: "audio/mp4", ogg: "audio/ogg", wav: "audio/wav",
    mp4: "video/mp4", webm: "video/webm",
    ttf: "font/ttf", otf: "font/otf", woff: "font/woff", woff2: "font/woff2"
  });

  /*
   * What a chapter starts from inside the shadow tree. These come before the book's
   * stylesheets, which may overrule any of them.
   */
  var BOOK_CSS = [
    ":host { display: block; }",
    ".vw-page { contain: layout; box-sizing: border-box; max-width: 42em; margin: 0 auto;",
    "  font-family: serif; font-size: 17px; line-height: 1.55;",
    "  overflow-wrap: break-word; word-wrap: break-word; }",
    "img, video { height: auto; }",
    "svg { max-height: 100vh; }",
    "pre { white-space: pre-wrap; }",
    "table { max-width: 100%; }",
    ".vw-fixed > html { background-color: #ffffff; color: #000000; }",
    "::highlight(vw-find) { background-color: rgba(255, 213, 79, 0.45); }",
    "::highlight(vw-find-active) { background-color: rgba(255, 145, 0, 0.62); }"
  ].join("\n");

  /*
   * What a book's stylesheets may not overrule, so these come after them and are all marked
   * important. They keep a chapter inside the screen and readable on a dark one.
   *
   * A book's margins are set for a sheet of paper or a desktop window: six ems on one side and
   * sixteen on the other leave a phone no room for a single letter. So the chapter's html and
   * body are a plain block the width of the page, with this page's margins as the body's
   * padding, which also takes a paper colour the book gives its body out to the edges.
   *
   * Text set in columns running down the page, as Japanese and Chinese books may be, is laid
   * out in rows: a page that scrolls downward has no way to turn to the next column.
   *
   * A dark phone gets the book's words in light ink on the page's dark ground, whatever
   * colours the book set, and its pictures as they are. A picture keeps the ground its book
   * gave it, which is often there for this very page. One drawn in dark ink on a ground left
   * clear was drawn for white paper and would be lost on this one: see weigh() for the two
   * that are told apart, and for what each is given. A rule drawn in black is lost the same
   * way, and is drawn in the ink of the words beside it: see inkRules().
   *
   * None of that is for a page of a fixed size (.vw-fixed, see scale()), which is a drawing
   * more than it is text: its html is the sheet it was drawn on, white unless the book says
   * otherwise, and everything on it stays where and what colour the book put it.
   */
  var GUARD_CSS = [
    ".vw-flow > html, .vw-flow > html > body { display: block !important; float: none !important;",
    "  position: static !important; margin: 0 !important; columns: auto !important;",
    "  width: auto !important; min-width: 0 !important; max-width: none !important;",
    "  height: auto !important; min-height: 0 !important; max-height: none !important;",
    "  overflow: visible !important; }",
    ".vw-flow > html { padding: 0 !important; }",
    ".vw-flow > html > body { padding: 8px 20px 24px !important; }",
    ".vw-flow html, .vw-flow body, .vw-flow body * { writing-mode: horizontal-tb !important; }",
    ".vw-flow img, .vw-flow svg, .vw-flow video { max-width: 100% !important; }",
    ".vw-fixed > html { display: block !important; position: relative !important;",
    "  margin: 0 !important; padding: 0 !important; overflow: hidden !important;",
    "  transform-origin: 0 0 !important; }",
    "@media (prefers-color-scheme: dark) {",
    "  .vw-flow html, .vw-flow body, .vw-flow body * { color: #E7E2D9 !important; }",
    "  .vw-flow html, .vw-flow body, .vw-flow body *:not(img):not(svg):not(video) {",
    "    background-color: transparent !important; }",
    "  .vw-flow a, .vw-flow a * { color: #FFB39E !important; }",
    "  .vw-flow svg text, .vw-flow svg tspan { fill: #E7E2D9 !important; }",
    "  .vw-flow img[data-vw-ground=\"ink\"] { filter: invert(0.9) !important; }",
    "  .vw-flow img[data-vw-ground=\"paper\"] { background-color: #E7E2D9 !important; }",
    "}"
  ].join("\n");

  /*
   * What a place in a chapter is counted in besides the letters of its text: each of these
   * is one. See firstShowing().
   */
  var COUNTED = /^(img|svg|video)$/;

  /* The kinds of picture file that can leave their ground clear, which a photograph's cannot */
  var CLEAR_KINDS = /^image\/(png|gif|webp|avif|svg\+xml)$/;

  /* How many pixels across and down a picture is weighed at: see weigh() */
  var WEIGH_PX = 32;

  /* Below this much light, of 255, ink does not show on the dark page */
  var TOO_DARK = 70;

  var EDGES = ["border-top-color", "border-right-color", "border-bottom-color", "border-left-color"];

  /* What a tap may be a link through: a link, and a part of a picture that is one */
  var LINK = /^(a|area)$/;

  /* What the engine takes for a control, a tap on which a summary leaves alone */
  var CONTROLS = /^(button|fieldset|input|output|select|textarea)$/;

  /* The addresses outside a book that a reader is shown, the ones vwDisarmLinks() leaves a
     Word document: a web page, somebody to write to and a number to ring */
  var OUTSIDE = /^(https?|mailto|tel):/i;

  /* The least room, in ems of the chapter's text, that a picture set beside the text may leave
     it: about three short words to a line. With less, the picture is set on its own. */
  var FLOAT_ROOM_EMS = 8;

  /*
   * A map with nothing in it to begin with, not even what every object has. The keys are a
   * book's own names, or are looked up by them, and a link, a scrambling or a file named
   * "constructor" must find nothing.
   */
  function bag() {
    return Object.create(null);
  }

  var zip = null;
  /* Every file in the zip by its name in lower case, for a book that spells one differently */
  var names = bag();
  /* The type the package gives each file, by path */
  var types = bag();
  /* The file the package names to show where another cannot be, by that other's path */
  var stead = bag();
  /* Files under a real lock, which is what DRM is and nothing here can read */
  var locked = bag();
  /* Fonts the book scrambled, by path, each with the entry of SCRAMBLES for how */
  var scrambled = bag();
  /* The name the book goes by, which is what its fonts are scrambled with, and its UUID */
  var bookId = "";
  var bookUuid = "";
  /* The key for each kind of scrambling, once it has been asked for */
  var keys = bag();
  /* The chapters in reading order: { path, pages, start, fixed, wait, ready } */
  var spine = [];
  /* A chapter's place in the spine, by path */
  var index = bag();
  /* The contents list: { label, depth, at, frag }, at being -1 for a heading that goes nowhere */
  var contents = [];
  var bookTitle = "";
  var total = 0;

  var shown = -1;
  /* The body of the chapter on the page */
  var onPage = null;
  /* The size that chapter was drawn at, where it is a page of a fixed-layout book */
  var drawn = null;
  /* Counts chapters asked for, so that work for one the reader has left is dropped */
  var turn = 0;
  /* The page last told to the app */
  var sent = 0;
  /* How many letters and pictures of the chapter are above the screen: see firstShowing() */
  var above = 0;
  /* The place last told to the app, and whether the chapter is one that takes its text size */
  var told = "";
  var toldFlow = "";
  /* Set while the page is still moving, and the place it stops at has yet to be worked out */
  var settling = 0;
  /* How far down the page relaid() left the screen, or -1 once the reader has moved it */
  var heldAt = -1;
  /* Whether the app has been told that Back has something to do here before it leaves */
  var toldOver = false;
  /*
   * Where the reader was when they last followed a link to another place in the book, which
   * Back brings them back to: { i, past } as a place is, with how far down the page the link
   * left them and whether they have touched the page since. A note is read and come back
   * from, and many books give their notes no link back. One place and no trail of them, and
   * forgotten once the reader has read on from where the link put them: see readOn().
   */
  var before = null;
  /* What fit() and unfloat() have set on the chapter, each with what was there before */
  var setToFit = [];
  /* The width the chapter was last fitted to, and the height of the letter that tells the
     size of the type: see relaid() */
  var across = window.innerWidth;
  var gauge = null;
  var gaugeTall = 0;
  /* The zoom the phone itself asks of the page's text, as a percentage, where the app said */
  var phoneType = Number(vwParams.get("type")) || 0;
  var opened = false;
  /* Whether the reader has moved the page since the chapter went on it */
  var moved = false;
  /* Set by a search: every chapter is read and kept, so the whole book can be searched */
  var wholeBook = false;
  /* While that reading is going on */
  var reading = false;
  /* Looks out for the chapter's pictures coming near the screen on a dark phone: see watch() */
  var watching = null;
  /* A small canvas, which pictures are weighed on and colours are read with */
  var scales = null;
  /* Whether each colour a book has given a rule is too dark to see, by the colour as written */
  var darkness = bag();
  var darkPhone = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  /* Object URLs made for the chapter on the page, by path, given back when it leaves */
  var held = bag();
  /* The same for what stylesheets name, kept as long as the book, since chapters share them */
  var shared = bag();
  /* Each stylesheet as the page can use it, by path */
  var sheets = bag();
  /* Each stylesheet in the zip in parts, by its name there: see partsOf() */
  var readSheets = bag();

  var host = document.getElementById("book");
  var shadow = host.attachShadow({ mode: "open" });
  var bookSheets = document.createElement("div");
  var page = document.createElement("div");
  /* A chapter's @font-face rules, which do nothing inside a shadow tree */
  var faces = document.createElement("style");
  var EMPTY = document.createElement("div");

  var bar = document.getElementById("epub-bar");
  var where = document.getElementById("epub-where");
  var turnBar = document.getElementById("epub-turn");
  var prev = document.getElementById("epub-prev");
  var next = document.getElementById("epub-next");
  var panel = document.getElementById("epub-contents");
  var list = document.getElementById("epub-contents-list");
  var openContents = document.getElementById("epub-contents-open");
  var closeContents = document.getElementById("epub-contents-close");
  var linkBox = document.getElementById("epub-link");
  var linkAddress = document.getElementById("epub-link-address");
  var linkCopy = document.getElementById("epub-link-copy");
  var linkClose = document.getElementById("epub-link-close");

  /*
   * Whether what runs past a box widens the page around it on this engine, layout
   * containment or not. WebView 66 lets it, for what is in the flow and for what a book has
   * positioned alike, and a page wider than the screen moves sideways under the finger.
   */
  function widens() {
    var outer = document.createElement("div");
    var box = document.createElement("div");
    var inFlow = document.createElement("div");
    var placed = document.createElement("div");
    outer.style.cssText = "position:absolute;top:0;left:0;width:10px;height:10px;" +
      "overflow:hidden;visibility:hidden";
    box.style.cssText = "contain:layout;width:10px;height:10px";
    inFlow.style.cssText = "width:50px;height:1px";
    placed.style.cssText = "position:absolute;left:50px;width:10px;height:1px";
    box.appendChild(inFlow);
    box.appendChild(placed);
    outer.appendChild(box);
    document.body.appendChild(outer);
    var is = outer.scrollWidth > outer.clientWidth;
    document.body.removeChild(outer);
    return is;
  }

  (function () {
    var base = document.createElement("style");
    var guard = document.createElement("style");
    base.textContent = BOOK_CSS;
    guard.textContent = GUARD_CSS;
    page.className = "vw-page vw-flow";
    // On such an engine the book's box cuts off what runs past it, and the page is what a
    // positioned thing is placed against, as containment makes it everywhere else. Only
    // there, since a box that cuts off is also what position: sticky in a book sticks to.
    if (widens()) {
      host.style.overflowX = "hidden";
      page.style.position = "relative";
    }
    shadow.appendChild(base);
    shadow.appendChild(bookSheets);
    shadow.appendChild(guard);
    shadow.appendChild(page);
    document.head.appendChild(faces);
  })();

  /* An error the reader is shown under a title of its own */
  function problem(title, detail) {
    var e = new Error(detail);
    e.vwTitle = title;
    return e;
  }

  function local(el) {
    return (el.localName || "").toLowerCase();
  }

  function clean(s) {
    return String(s || "").replace(/\s+/g, " ").trim();
  }

  // ---------------------------------------------------------------------------------------
  // The zip
  // ---------------------------------------------------------------------------------------

  function entry(path) {
    var file = zip.file(path);
    if (file) return file;
    var real = names[path.toLowerCase()];
    return real ? zip.file(real) : null;
  }

  function bytes(path) {
    var file = entry(path);
    return file ? file.async("uint8array") : Promise.reject(new Error("The book has no " + path));
  }

  /*
   * Text out of a file's bytes. UTF-8 unless the file says otherwise, in a byte order mark or
   * in the encoding its first line declares, which is where XML and CSS both say it.
   */
  function decode(b) {
    var as = "utf-8";
    if (b.length >= 2 && b[0] === 0xFF && b[1] === 0xFE) as = "utf-16le";
    else if (b.length >= 2 && b[0] === 0xFE && b[1] === 0xFF) as = "utf-16be";
    else {
      var head = "";
      for (var i = 0; i < b.length && i < 200; i++) head += String.fromCharCode(b[i]);
      var said = /^\s*<\?xml[^>]*encoding\s*=\s*["']([\w.-]+)["']/i.exec(head) ||
        /^\s*@charset\s+["']([\w.-]+)["']/i.exec(head);
      if (said) as = said[1];
    }
    try {
      return new TextDecoder(as).decode(b);
    } catch (e) {
      return new TextDecoder("utf-8").decode(b);
    }
  }

  function text(path) {
    return bytes(path).then(decode);
  }

  /*
   * How long a file is once unpacked, without unpacking it. JSZip keeps the figure from the
   * zip's directory but offers no call for it, so this reads where it keeps it; VendoredLibsTest
   * pins the file, so the field cannot move unnoticed. Nought where it is not there, and a
   * chapter of unknown length counts as one page.
   */
  function sizeOf(path) {
    var file = entry(path);
    var data = file && file._data;
    return data && data.uncompressedSize > 0 ? data.uncompressedSize : 0;
  }

  /*
   * The file a reference written in the file at [base] points to, and the place in it, or null
   * for one that leaves the book: anything with a scheme, which is every web and mail address.
   */
  function resolve(base, href) {
    href = String(href == null ? "" : href).trim();
    if (!href || /^[a-z][a-z0-9+.-]*:/i.test(href) || href.indexOf("//") === 0) return null;
    var hash = href.indexOf("#");
    var frag = hash < 0 ? "" : href.slice(hash + 1);
    var path = hash < 0 ? href : href.slice(0, hash);
    var query = path.indexOf("?");
    if (query >= 0) path = path.slice(0, query);
    try { path = decodeURIComponent(path); } catch (e) { /* as written, then */ }
    try { frag = decodeURIComponent(frag); } catch (e) { /* as written, then */ }
    if (!path) return { path: base, frag: frag };
    var from = path.charAt(0) === "/" ? "" : base.slice(0, base.lastIndexOf("/") + 1);
    var steps = (from + path).split("/");
    var out = [];
    for (var i = 0; i < steps.length; i++) {
      if (steps[i] === "" || steps[i] === ".") continue;
      // Nothing is above the top of the zip, so a step up from there stays there
      if (steps[i] === "..") out.pop();
      else out.push(steps[i]);
    }
    return { path: out.join("/"), frag: frag };
  }

  // ---------------------------------------------------------------------------------------
  // The package: what the book is made of, and in what order
  // ---------------------------------------------------------------------------------------

  function xml(source) {
    var doc = new DOMParser().parseFromString(source, "application/xml");
    if (!doc.documentElement || doc.getElementsByTagName("parsererror").length) {
      throw new Error("One of the book's own files is not sound XML.");
    }
    return doc;
  }

  function all(node, name) {
    return node.getElementsByTagNameNS("*", name);
  }

  /* Which files are encrypted, from the list a book carries when any are */
  function readLocks() {
    if (!entry("META-INF/encryption.xml")) return Promise.resolve();
    return text("META-INF/encryption.xml").then(function (source) {
      var data = all(xml(source), "EncryptedData");
      for (var i = 0; i < data.length; i++) {
        var method = all(data[i], "EncryptionMethod")[0];
        var ref = all(data[i], "CipherReference")[0];
        var at = ref && resolve("", ref.getAttribute("URI"));
        if (!at) continue;
        var how = SCRAMBLES[method ? method.getAttribute("Algorithm") || "" : ""];
        if (how) scrambled[at.path] = how;
        else locked[at.path] = true;
      }
    }).catch(function () { /* a list that cannot be read locks nothing */ });
  }

  /*
   * What a file in the reading order is shown as: "page", "picture", or "other" for what is
   * neither. A chapter the package gives the wrong type, or none, is still a chapter, so
   * anything that is text or XML by its type is read as a page, and only a type that says
   * the file is something else is believed.
   */
  function kindOf(path) {
    var type = (types[path] || "").split(";")[0].trim();
    if (isPage(path) || !type || /^text\/|[/+]xml$/.test(type)) return "page";
    return isPicture(path) ? "picture" : "other";
  }

  /* Whether the package calls a file a picture of a kind the engine draws */
  function isPicture(path) {
    return /^image\/(png|jpeg|gif|webp|avif|bmp)$/.test((types[path] || "").split(";")[0].trim());
  }

  /*
   * The file a picture in a chapter is drawn from: the one the chapter names, or, where the
   * package says that is something the engine does not draw, the first it does among those
   * the package names to show in its stead.
   */
  function drawnFrom(path) {
    if (!stead[path] || !/^image\//.test(types[path] || "") || isPicture(path) || isPage(path)) return path;
    var seen = bag();
    for (var at = path; at && !seen[at]; at = stead[at]) {
      seen[at] = true;
      if ((isPicture(at) || isPage(at)) && entry(at)) return at;
    }
    return path;
  }

  /* Whether the package calls a file a page: a chapter in XHTML, or a drawing */
  function isPage(path) {
    var type = (types[path] || "").split(";")[0].trim();
    return type === "application/xhtml+xml" || type === "image/svg+xml";
  }

  function readPackage(doc, path) {
    var byId = bag();
    var insteadOf = bag();
    var nav = null;
    var ncx = null;
    var items = all(doc, "item");
    for (var i = 0; i < items.length; i++) {
      var at = resolve(path, items[i].getAttribute("href"));
      if (!at) continue;
      var type = (items[i].getAttribute("media-type") || "").toLowerCase();
      types[at.path] = type;
      byId[items[i].getAttribute("id")] = at.path;
      insteadOf[items[i].getAttribute("id")] = items[i].getAttribute("fallback");
      if (/(^|\s)nav(\s|$)/.test(items[i].getAttribute("properties") || "")) nav = at.path;
      if (type === "application/x-dtbncx+xml" && !ncx) ncx = at.path;
    }
    for (var id in insteadOf) {
      if (byId[insteadOf[id]]) stead[byId[id]] = byId[insteadOf[id]];
    }

    // Whether the book's pages are of a fixed size, which one chapter may say otherwise about
    var fixedBook = false;
    var metas = all(doc, "meta");
    for (var m = 0; m < metas.length; m++) {
      if (metas[m].getAttribute("property") === "rendition:layout") {
        fixedBook = clean(metas[m].textContent) === "pre-paginated";
      }
    }

    /*
     * The file a place in the reading order is read from. A book may put a picture there, or
     * a file of any other kind, and name what to show where that cannot be shown: the first
     * page or picture among those is taken, and the file itself where there is none.
     */
    function readFrom(id) {
      var seen = bag();
      for (var at = id; at && byId[at] && !seen[at]; at = insteadOf[at]) {
        seen[at] = true;
        if ((isPage(byId[at]) || isPicture(byId[at])) && entry(byId[at])) return byId[at];
      }
      return byId[id];
    }

    var refs = all(doc, "itemref");
    for (var r = 0; r < refs.length; r++) {
      var file = readFrom(refs[r].getAttribute("idref"));
      if (!file || !entry(file) || index[file] !== undefined) continue;
      if (locked[file]) {
        throw problem("This book is locked",
          "It is protected against copying (DRM), so only the app of the shop or library " +
          "it came from can open it.");
      }
      var kind = kindOf(file);
      // A picture is one page however large its file is
      var pages = kind === "page" ? Math.max(1, Math.ceil(sizeOf(file) / VW_EPUB_PAGE_BYTES)) : 1;
      index[file] = spine.length;
      var says = " " + (refs[r].getAttribute("properties") || "") + " ";
      var fixed = says.indexOf(" rendition:layout-pre-paginated ") >= 0 ||
        (fixedBook && says.indexOf(" rendition:layout-reflowable ") < 0);
      spine.push({ path: file, kind: kind, pages: pages, start: total, fixed: fixed, wait: null, ready: null });
      total += pages;
    }
    if (!spine.length) throw new Error("The book lists no chapters.");

    var named = all(doc, "title")[0];
    bookTitle = caption(named);

    // The identifier the package calls its own, or failing that its first
    var own = doc.documentElement.getAttribute("unique-identifier");
    var ids = all(doc, "identifier");
    for (var n = ids.length - 1; n >= 0; n--) {
      if (n === 0 || ids[n].getAttribute("id") === own) {
        bookId = ids[n].textContent;
        break;
      }
    }
    for (var u = -1; u < ids.length && !bookUuid; u++) {
      bookUuid = (UUID.exec(u < 0 ? bookId : ids[u].textContent) || [""])[0];
    }
    var tongue = all(doc, "language")[0];
    if (tongue && clean(tongue.textContent)) host.lang = clean(tongue.textContent);

    var spineEl = all(doc, "spine")[0];
    turnBar.dir = spineEl && spineEl.getAttribute("page-progression-direction") === "rtl" ? "rtl" : "ltr";
    var declared = spineEl && byId[spineEl.getAttribute("toc")];
    return { nav: nav, ncx: declared || ncx };
  }

  // ---------------------------------------------------------------------------------------
  // The contents list, from the newer kind of book's nav file or the older kind's NCX
  // ---------------------------------------------------------------------------------------

  /* Copy only text and inherited language/direction, never a publisher's markup or styles. */
  function caption(el) {
    var out = { label: el ? clean(el.textContent) : "", dir: "", lang: null };
    for (; el; el = el.parentElement) {
      var dir = (el.getAttribute("dir") || "").trim().toLowerCase();
      if (!out.dir && /^(ltr|rtl|auto)$/.test(dir)) out.dir = el.vwDir || dir;
      if (out.dir === "auto") {
        var source = el.cloneNode(true);
        var skip = source.querySelectorAll("bdi, script, style, textarea, [dir=ltr i], [dir=rtl i], [dir=auto i]");
        for (var i = 0; i < skip.length; i++) skip[i].remove();
        var probe = document.createElement("bdi");
        probe.hidden = true;
        probe.textContent = source.textContent;
        document.body.appendChild(probe);
        el.vwDir = out.dir = getComputedStyle(probe).direction;
        probe.remove();
      }
      if (out.lang === null) out.lang = el.hasAttribute("lang") ? el.getAttribute("lang") : el.getAttribute("xml:lang");
    }
    return { textContent: out.label, dir: out.dir || "auto", lang: out.lang || "" };
  }

  function listed(el, depth, base, href, out) {
    var label = caption(el);
    if (!label.textContent && /^(a|span)$/.test(local(el))) label.textContent = calledBy(el);
    if (!label.textContent) return;
    var at = href ? resolve(base, href) : null;
    var place = at && index[at.path] !== undefined ? index[at.path] : -1;
    out.push({ label: label, depth: depth, at: place, frag: at && place >= 0 ? at.frag : "" });
  }

  function navList(ol, depth, base, out) {
    for (var li = ol.firstElementChild; li; li = li.nextElementSibling) {
      if (local(li) !== "li") continue;
      var named = null;
      var under = null;
      for (var c = li.firstElementChild; c; c = c.nextElementSibling) {
        var name = local(c);
        if (name === "ol") under = c;
        else if (!named && (name === "a" || name === "span")) named = c;
      }
      if (named) listed(named, depth, base, named.getAttribute("href"), out);
      if (under) navList(under, depth + 1, base, out);
    }
  }

  /* What an entry with no words goes by: its own title, or what the picture in it is described as */
  function calledBy(el) {
    var img = el.querySelector("img");
    return clean(el.getAttribute("title") || (img && (img.getAttribute("alt") || img.getAttribute("title"))) || "");
  }

  function readNav(path) {
    return text(path).then(function (source) {
      var navs = all(markup(source), "nav");
      var toc = null;
      for (var i = 0; i < navs.length && !toc; i++) {
        if (/(^|\s)toc(\s|$)/.test(navs[i].getAttribute("epub:type") || "")) toc = navs[i];
      }
      toc = toc || navs[0];
      var out = [];
      for (var c = toc ? toc.firstElementChild : null; c; c = c.nextElementSibling) {
        if (local(c) === "ol") navList(c, 0, path, out);
      }
      return out;
    });
  }

  function ncxPoints(parent, depth, base, out) {
    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
      if (local(c) !== "navpoint") continue;
      var label = null;
      var src = null;
      for (var k = c.firstElementChild; k; k = k.nextElementSibling) {
        if (local(k) === "navlabel") label = all(k, "text")[0] || k;
        else if (local(k) === "content") src = k.getAttribute("src");
      }
      if (label) listed(label, depth, base, src, out);
      ncxPoints(c, depth + 1, base, out);
    }
  }

  function readNcx(path) {
    return text(path).then(function (source) {
      var map = all(xml(source), "navMap")[0];
      var out = [];
      if (map) ncxPoints(map, 0, path, out);
      return out;
    });
  }

  /* The list a book gives, or failing both kinds, its chapters by number */
  function readContents(from) {
    function attempt(read, path) {
      return path ? read(path).catch(function () { return []; }) : Promise.resolve([]);
    }
    return attempt(readNav, from.nav).then(function (found) {
      return found.length ? found : attempt(readNcx, from.ncx);
    }).then(function (found) {
      contents = found;
      if (contents.length) return;
      for (var i = 0; i < spine.length; i++) {
        contents.push({ label: { textContent: "Part " + (i + 1), dir: "auto", lang: "" }, depth: 0, at: i, frag: "" });
      }
    });
  }

  function open(buffer) {
    return JSZip.loadAsync(buffer).then(function (z) {
      zip = z;
      for (var name in z.files) names[name.toLowerCase()] = name;
      if (!entry("META-INF/container.xml")) throw new Error("not a book");
      return readLocks();
    }, function () {
      throw new Error("not a book");
    }).catch(function () {
      throw problem("Could not open this book", "It is not an EPUB, or the file is damaged.");
    }).then(function () {
      return text("META-INF/container.xml");
    }).then(function (source) {
      var roots = all(xml(source), "rootfile");
      var path = null;
      for (var i = 0; i < roots.length && !path; i++) path = roots[i].getAttribute("full-path");
      if (!path || !entry(path)) throw new Error("The book does not say where its chapters are listed.");
      return text(path).then(function (pkg) { return readPackage(xml(pkg), path); });
    }).then(readContents);
  }

  // ---------------------------------------------------------------------------------------
  // A chapter, from its file to something safe to put on the page
  // ---------------------------------------------------------------------------------------

  /*
   * A chapter's file as a document. XHTML is XML, and read as XML an empty <a id="x"/> is
   * the empty element it says it is. Half the books in circulation are not sound XML though,
   * most often for an &nbsp; that XML does not know, so one that fails is read again as
   * HTML, which takes anything. Its empty elements are spelled out first, because HTML reads
   * <title/> as a title that never ends and takes the whole chapter for its text.
   */
  function markup(source) {
    var doc = new DOMParser().parseFromString(source, "application/xhtml+xml");
    if (doc.documentElement && !doc.getElementsByTagName("parsererror").length) return doc;
    // The gap before the stroke is taken with the attributes: looked for apart from them, a
    // tag with a hundred thousand spaces in it is gone over once for every one of them
    var spelled = source.replace(/<([A-Za-z][\w:.-]*)((?:\s[^<>]*?)?)\/>/g, function (m, tag, attrs) {
      return VOID.test(tag) ? m : "<" + tag + attrs + "></" + tag + ">";
    });
    return new DOMParser().parseFromString(spelled, "text/html");
  }

  /* Whether the style of one element names a file. Every way of naming one has a bracket. */
  function addressed(style) {
    return !!style && style.indexOf("(") >= 0 && inStyle(style, "", false).found.all > 0;
  }

  function setAside(el, name) {
    var value = el.getAttribute(name);
    if (value === null) return;
    el.removeAttribute(name);
    el.setAttribute("data-vw-" + name, value);
  }

  /*
   * Takes every address off a chapter before any of it reaches this page, and keeps each in
   * a data attribute for dress() to answer from the zip.
   *
   * It has to happen here, in the document the parser made, which fetches nothing. An img
   * that arrived on this page with its src would be fetched from Gander's own host the moment
   * it was copied in, as a path that is not there.
   */
  function disarm(doc) {
    // Read as XML, SRC is not src and nothing here would find it, but DOMPurify writes it
    // back in lower case, as src
    var every = doc.getElementsByTagName("*");
    for (var n = 0; n < every.length; n++) {
      var named = every[n].attributes;
      for (var k = named.length - 1; k >= 0; k--) {
        var lower = named[k].name.toLowerCase();
        if (lower === named[k].name || !/^(src|poster|style)$/.test(lower)) continue;
        var was = named[k].value;
        every[n].removeAttribute(named[k].name);
        every[n].setAttribute(lower, was);
      }
    }

    var els = doc.querySelectorAll("[src], [poster], [style], image, use, feImage, feimage");
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      setAside(el, "src");
      setAside(el, "poster");
      if (addressed(el.getAttribute("style"))) setAside(el, "style");
      var name = local(el);
      if (name !== "image" && name !== "use" && name !== "feimage") continue;
      var href = el.getAttributeNS(XLINK, "href") || el.getAttribute("href") || "";
      // A use that points into its own drawing is left alone
      if (name === "use" && href.charAt(0) === "#") continue;
      el.removeAttributeNS(XLINK, "href");
      el.removeAttribute("href");
      if (name === "image" && href) el.setAttribute("data-vw-href", href);
    }

    /*
     * A drawing fills, masks, clips and filters a shape by the address of something drawn
     * for the purpose, written as in a stylesheet but in an attribute of the shape. One that
     * points into the chapter's own drawings stays. Any other would be asked of a host, so
     * the attribute goes.
     */
    var shapes = doc.getElementsByTagNameNS(SVGNS, "*");
    for (var s = 0; s < shapes.length; s++) {
      var attrs = shapes[s].attributes;
      for (var a = attrs.length - 1; a >= 0; a--) {
        // Its style is answered from the zip like any other: see fetched()
        if (/^(data-vw-)?style$/.test(attrs[a].name) || attrs[a].value.indexOf("(") < 0) continue;
        if (inStyle(attrs[a].value, "", false).found.far) shapes[s].removeAttribute(attrs[a].name);
      }
    }
  }

  /*
   * A switch offers one piece of markup to a reader that knows it and a default to every
   * other, and its default is what is kept here. Left alone the whole of it would go, default
   * and all, since DOMPurify drops an element that is neither HTML, a drawing nor mathematics
   * together with everything in it. Older books wrap each formula in one.
   */
  function unswitch(doc) {
    var found = [].slice.call(doc.getElementsByTagNameNS(OPS, "switch"))
      .concat([].slice.call(doc.getElementsByTagName("epub:switch")));
    found.forEach(function (sw) {
      // In both lists when the chapter was read as XML, and gone by the second time
      if (!sw.parentNode) return;
      var keep = null;
      for (var c = sw.firstElementChild; c && !keep; c = c.nextElementSibling) {
        if (/(^|:)default$/.test(local(c))) keep = c;
      }
      while (keep && keep.firstChild) sw.parentNode.insertBefore(keep.firstChild, sw);
      sw.parentNode.removeChild(sw);
    });
  }

  /* The few attributes of a book's html or body that its stylesheets go by */
  function kept(el) {
    var out = {};
    if (!el) return out;
    ["class", "id", "dir", "lang"].forEach(function (name) {
      var value = el.getAttribute(name);
      if (value) out[name] = value;
    });
    if (!out.lang && el.getAttribute("xml:lang")) out.lang = el.getAttribute("xml:lang");
    // One that names a file was set aside by disarm(), and the chapter goes without it
    var style = el.getAttribute("style");
    if (style) {
      var read = inStyle(style, "", false);
      if (!read.found.all) out.style = read.parts.join("");
    }
    return out;
  }

  /*
   * The size a page of a fixed-layout book was drawn at, { w, h }, or null. Such a page says
   * it in the line a web page uses to tell a phone how wide it is, and the rule for these
   * books is that it must. A width in words, such as device-width, is not a size.
   */
  function drawnAt(doc) {
    var meta = doc.querySelector('meta[name="viewport"]');
    var said = meta ? meta.getAttribute("content") || "" : "";
    var w = /(?:^|[\s,;])width\s*=\s*([\d.]+)/i.exec(said);
    var h = /(?:^|[\s,;])height\s*=\s*([\d.]+)/i.exec(said);
    return w && h && w[1] > 0 && h[1] > 0 ? { w: Number(w[1]), h: Number(h[1]) } : null;
  }

  function chapter(source, path, fixed) {
    var doc = markup(source);
    var body = doc.body;
    if (!body) {
      // A chapter that is a drawing, or anything else with no body: given one
      var around = document.implementation.createHTMLDocument("");
      around.body.appendChild(around.importNode(doc.documentElement, true));
      doc = around;
      body = around.body;
    }

    /*
     * A book set in columns that run down the page may bring a second stylesheet for rows,
     * each marked by "vertical" or "horizontal" in its class and the second as an alternative
     * to the first. Rows are how this page lays text out, so where a chapter offers the pair
     * the one for rows is taken and the one for columns left, whose margins are on the sides
     * that suit columns.
     */
    var rows = doc.querySelector('link[rel~="stylesheet"][class~="horizontal"]') !== null;
    var styles = [];
    var marks = doc.querySelectorAll("link, style");
    for (var i = 0; i < marks.length; i++) {
      if (local(marks[i]) === "style") {
        styles.push({ css: marks[i].textContent });
        continue;
      }
      var rel = marks[i].getAttribute("rel") || "";
      var made = " " + (marks[i].getAttribute("class") || "") + " ";
      if (!/(^|\s)stylesheet(\s|$)/i.test(rel)) continue;
      if (rows && made.indexOf(" vertical ") >= 0) continue;
      if (/(^|\s)alternate(\s|$)/i.test(rel) && !(rows && made.indexOf(" horizontal ") >= 0)) continue;
      var at = resolve(path, marks[i].getAttribute("href"));
      if (at && entry(at.path)) styles.push({ path: at.path });
    }

    unswitch(doc);
    disarm(doc);
    var holder = document.createElement("body");
    holder.appendChild(DOMPurify.sanitize(body, PURIFY));
    return {
      body: holder, html: kept(doc.documentElement), own: kept(body), styles: styles,
      drawn: fixed ? drawnAt(doc) : null
    };
  }

  /* A page with nothing on it but the picture at [path], for a picture in the reading order */
  function pictured(path) {
    var name = encodeURIComponent(path.slice(path.lastIndexOf("/") + 1));
    return '<html xmlns="http://www.w3.org/1999/xhtml"><head><title></title></head>' +
      '<body><img src="' + name + '" alt=""/></body></html>';
  }

  function load(i) {
    var ch = spine[i];
    if (ch.wait) return ch.wait;
    var read = ch.kind === "picture" ? Promise.resolve(pictured(ch.path))
      // A file that is neither a page nor a picture has nothing in it to read as one
      : ch.kind === "other" ? Promise.reject(new Error("Not a page."))
        : text(ch.path);
    ch.wait = read.then(function (source) {
      return chapter(source, ch.path, ch.fixed);
    }).catch(function () {
      // One chapter that cannot be read leaves the rest of the book readable
      var holder = document.createElement("body");
      var said = document.createElement("p");
      said.textContent = "This part of the book could not be read.";
      holder.appendChild(said);
      return { body: holder, html: {}, own: {}, styles: [], drawn: null };
    }).then(function (made) {
      ch.ready = made;
      return made;
    });
    return ch.wait;
  }

  /* Without a search asking for the whole book, only the chapter being read and its two
     neighbours are kept, so a long book does not stay in memory as it is read through. */
  function trim() {
    if (wholeBook) return;
    for (var i = 0; i < spine.length; i++) {
      if (Math.abs(i - shown) > 1) {
        spine[i].wait = null;
        spine[i].ready = null;
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // What a chapter needs from the zip: pictures, fonts and stylesheets
  // ---------------------------------------------------------------------------------------

  /*
   * The type a file is handed to the page as, or null for one that is not handed over. Only
   * pictures, sound, film and fonts, whatever the package calls the file: an object URL for a
   * web page or a script is one that nothing here has any use for.
   */
  function typeOf(path) {
    var said = types[path] || "";
    if (/^(image|audio|video|font)\//.test(said)) return said;
    if (/font|opentype|truetype/.test(said)) return "application/octet-stream";
    return TYPES[path.slice(path.lastIndexOf(".") + 1).toLowerCase()] || null;
  }

  /*
   * The key a kind of scrambling uses, as bytes, or null where it cannot be had. One kind
   * takes the SHA-1 of the book's identifier with its spaces and line ends out, and the
   * other the sixteen bytes of the book's UUID.
   */
  function key(kind) {
    if (keys[kind]) return keys[kind];
    if (kind === "adobe") {
      var hex = bookUuid.replace(/-/g, "");
      var made = hex ? new Uint8Array(16) : null;
      for (var i = 0; made && i < 16; i++) made[i] = parseInt(hex.substr(i * 2, 2), 16);
      keys[kind] = Promise.resolve(made);
    } else if (window.crypto && crypto.subtle && window.TextEncoder) {
      var name = new TextEncoder().encode(bookId.replace(/[\u0020\u0009\u000D\u000A]/g, ""));
      keys[kind] = crypto.subtle.digest("SHA-1", name).then(function (digest) {
        return new Uint8Array(digest);
      }, function () {
        return null;
      });
    } else {
      keys[kind] = Promise.resolve(null);
    }
    return keys[kind];
  }

  /*
   * A font the book scrambled, put right, or null. The start of the file was combined with
   * the key a byte at a time, and combining it again undoes that. What comes out is a font
   * or the key was not the one, so a file that does not begin as a font does is left out,
   * and the engine is not handed noise to complain of.
   */
  function unscrambled(b, how) {
    return key(how.key).then(function (k) {
      if (!k) return null;
      // A copy, since the bytes may be the zip's own
      var out = new Uint8Array(b);
      var n = Math.min(out.length, how.length);
      for (var i = 0; i < n; i++) out[i] ^= k[i % k.length];
      return FONT_START.test(String.fromCharCode(out[0], out[1], out[2], out[3])) ? out : null;
    });
  }

  /* An object URL for a file in the book, kept in [store], or null */
  function objectUrl(path, store) {
    if (store[path]) return store[path];
    var type = typeOf(path);
    if (!type || locked[path] || !entry(path)) return Promise.resolve(null);
    store[path] = bytes(path).then(function (b) {
      return scrambled[path] ? unscrambled(b, scrambled[path]) : b;
    }).then(function (b) {
      return b ? URL.createObjectURL(new Blob([b], { type: type })) : null;
    }, function () {
      return null;
    });
    return store[path];
  }

  function release() {
    for (var path in held) {
      held[path].then(function (url) { if (url) URL.revokeObjectURL(url); });
    }
    held = bag();
  }

  // ---------------------------------------------------------------------------------------
  // A book's stylesheets, read the way the engine reads them
  // ---------------------------------------------------------------------------------------

  /*
   * A stylesheet as CSS Syntax 3 has the engine read it: one kind of line end and no NULs.
   * A backslash at the very end is written as the letter it stands for there, so that
   * nothing put after the sheet can become part of its last escape.
   */
  function prepared(css) {
    css = String(css).replace(/\r\n?|\f/g, "\n").replace(/\u0000/g, "\uFFFD");
    var end = css.length;
    while (end > 0 && css.charCodeAt(end - 1) === 92) end--;
    return (css.length - end) % 2 ? css + "\uFFFD" : css;
  }

  function codeAt(css, i) {
    return i < css.length ? css.charCodeAt(i) : -1;
  }

  function isDigit(c) {
    return c >= 48 && c <= 57;
  }

  function isHex(c) {
    return isDigit(c) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102);
  }

  function isNameStart(c) {
    return (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c === 95 || c >= 128;
  }

  function isName(c) {
    return isNameStart(c) || isDigit(c) || c === 45;
  }

  function isSpace(c) {
    return c === 10 || c === 9 || c === 32;
  }

  function isUnprintable(c) {
    return (c >= 0 && c <= 8) || c === 11 || (c >= 14 && c <= 31) || c === 127;
  }

  /* Whether the backslash at [i] begins an escape, which anything but a line end may follow */
  function escapes(css, i) {
    return codeAt(css, i) === 92 && codeAt(css, i + 1) !== 10;
  }

  /* The letter an escape stands for, its backslash being at [i - 1], and where it ends */
  function escaped(css, i) {
    var c = codeAt(css, i);
    if (c < 0) return { ch: "\uFFFD", to: i };
    if (!isHex(c)) return { ch: css.charAt(i), to: i + 1 };
    var j = i;
    while (j < i + 6 && isHex(codeAt(css, j))) j++;
    var n = parseInt(css.slice(i, j), 16);
    // One space after the number is part of the escape
    if (isSpace(codeAt(css, j))) j++;
    if (n === 0 || (n >= 0xD800 && n <= 0xDFFF) || n > 0x10FFFF) return { ch: "\uFFFD", to: j };
    if (n < 0x10000) return { ch: String.fromCharCode(n), to: j };
    n -= 0x10000;
    return { ch: String.fromCharCode(0xD800 + (n >> 10), 0xDC00 + (n & 0x3FF)), to: j };
  }

  /* Whether a name begins at [i]: an identifier's, a function's or an @rule's */
  function startsName(css, i) {
    var c = codeAt(css, i);
    if (c === 45) {
      var d = codeAt(css, i + 1);
      return isNameStart(d) || d === 45 || escapes(css, i + 1);
    }
    return isNameStart(c) || escapes(css, i);
  }

  function startsNumber(css, i) {
    var c = codeAt(css, i);
    if (c === 43 || c === 45) {
      c = codeAt(css, i + 1);
      return isDigit(c) || (c === 46 && isDigit(codeAt(css, i + 2)));
    }
    return c === 46 ? isDigit(codeAt(css, i + 1)) : isDigit(c);
  }

  /* The name that begins at [i], with its escapes read, and where it ends */
  function nameAt(css, i) {
    var value = "";
    var run = i;
    for (;;) {
      if (isName(codeAt(css, i))) {
        i++;
      } else if (escapes(css, i)) {
        var e = escaped(css, i + 1);
        value += css.slice(run, i) + e.ch;
        i = run = e.to;
      } else {
        return { value: value + css.slice(run, i), to: i };
      }
    }
  }

  /* Where the number that begins at [i] ends, with its unit or its percent sign */
  function numberEnd(css, i) {
    if (codeAt(css, i) === 43 || codeAt(css, i) === 45) i++;
    while (isDigit(codeAt(css, i))) i++;
    if (codeAt(css, i) === 46 && isDigit(codeAt(css, i + 1))) {
      i += 2;
      while (isDigit(codeAt(css, i))) i++;
    }
    var c = codeAt(css, i);
    var d = codeAt(css, i + 1);
    if ((c === 69 || c === 101) && (isDigit(d) || ((d === 43 || d === 45) && isDigit(codeAt(css, i + 2))))) {
      i += isDigit(d) ? 2 : 3;
      while (isDigit(codeAt(css, i))) i++;
    }
    if (startsName(css, i)) return nameAt(css, i).to;
    return codeAt(css, i) === 37 ? i + 1 : i;
  }

  /*
   * The quoted text whose opening quote is at [i - 1]. A line end ends it too, as a bad
   * string, before the line end; the end of the sheet closes it.
   */
  function stringAt(css, i, quote) {
    var value = "";
    var run = i;
    for (;;) {
      var c = codeAt(css, i);
      if (c === quote || c < 0) {
        return { type: "string", value: value + css.slice(run, i), to: c < 0 ? i : i + 1, closed: c >= 0 };
      }
      if (c === 10) return { type: "bad-string", to: i };
      if (c !== 92) {
        i++;
        continue;
      }
      value += css.slice(run, i);
      var d = codeAt(css, i + 1);
      if (d < 0 || d === 10) {
        // Nothing at the very end, and a line carried on to the next
        i = run = d < 0 ? i + 1 : i + 2;
      } else {
        var e = escaped(css, i + 1);
        value += e.ch;
        i = run = e.to;
      }
    }
  }

  /* An address written bare after url(, from [i] */
  function urlAt(css, i) {
    var value = "";
    var run = i;
    for (;;) {
      var c = codeAt(css, i);
      if (c === 41 || c < 0) return { type: "url", value: value + css.slice(run, i), to: c < 0 ? i : i + 1 };
      if (isSpace(c)) {
        var end = i;
        while (isSpace(codeAt(css, i))) i++;
        c = codeAt(css, i);
        if (c === 41 || c < 0) return { type: "url", value: value + css.slice(run, end), to: c < 0 ? i : i + 1 };
        return badUrlAt(css, i);
      }
      if (c === 34 || c === 39 || c === 40 || isUnprintable(c) || (c === 92 && !escapes(css, i))) {
        return badUrlAt(css, i);
      }
      if (c === 92) {
        var e = escaped(css, i + 1);
        value += css.slice(run, i) + e.ch;
        i = run = e.to;
      } else {
        i++;
      }
    }
  }

  /* The rest of an address the engine gives up on, which runs to its bracket, escapes and all */
  function badUrlAt(css, i) {
    for (;;) {
      var c = codeAt(css, i);
      if (c === 41 || c < 0) return { type: "bad-url", to: c < 0 ? i : i + 1 };
      i = escapes(css, i) ? escaped(css, i + 1).to : i + 1;
    }
  }

  /* An identifier, a function, or url( and the bare address after it, from [i] */
  function nameLike(css, i) {
    var name = nameAt(css, i);
    if (codeAt(css, name.to) !== 40) return { type: "ident", value: name.value, to: name.to };
    if (/^url$/i.test(name.value)) {
      var k = name.to + 1;
      while (isSpace(codeAt(css, k))) k++;
      // A quoted address is a function and its text, as the engine reads it
      if (codeAt(css, k) !== 34 && codeAt(css, k) !== 39) return urlAt(css, k);
    }
    return { type: "function", value: name.value, to: name.to + 1 };
  }

  /*
   * A stylesheet in the pieces the engine reads it in, CSS Syntax 3's tokens: each is
   * { type, from, to }, and a value where it has one. A character that is a token of its
   * own is its own type.
   *
   * Read any other way, a sheet can be spelled so that the engine finds an address where
   * the reading found none: u\72l(, a quote a line end closes, a comment inside an address.
   * So nothing below goes by patterns, and whatever the engine will take for an address is
   * one here too.
   */
  function tokens(css) {
    var out = [];
    var i = 0;
    while (i < css.length) {
      var c = css.charCodeAt(i);
      var t;
      if (c === 47 && codeAt(css, i + 1) === 42) {
        var end = css.indexOf("*/", i + 2);
        t = { type: "comment", to: end < 0 ? css.length : end + 2 };
      } else if (isSpace(c)) {
        var j = i;
        while (isSpace(codeAt(css, j))) j++;
        t = { type: "space", to: j };
      } else if (c === 34 || c === 39) {
        t = stringAt(css, i + 1, c);
      } else if (c === 35 && (isName(codeAt(css, i + 1)) || escapes(css, i + 1))) {
        t = { type: "hash", to: nameAt(css, i + 1).to };
      } else if (c === 64 && startsName(css, i + 1)) {
        var at = nameAt(css, i + 1);
        t = { type: "at", value: at.value, to: at.to };
      } else if (startsNumber(css, i)) {
        t = { type: "number", to: numberEnd(css, i) };
      } else if (c === 45 && codeAt(css, i + 1) === 45 && codeAt(css, i + 2) === 62) {
        t = { type: "cdc", to: i + 3 };
      } else if (c === 60 && css.substr(i + 1, 3) === "!--") {
        t = { type: "cdo", to: i + 4 };
      } else if (startsName(css, i)) {
        t = nameLike(css, i);
      } else {
        t = { type: css.charAt(i), to: i + 1 };
      }
      t.from = i;
      out.push(t);
      i = t.to;
    }
    return out;
  }

  /* An address as the URL parser takes it, which trims spaces and controls from either end */
  function urlTrim(s) {
    var a = 0;
    var b = s.length;
    while (a < b && s.charCodeAt(a) <= 32) a++;
    while (b > a && s.charCodeAt(b - 1) <= 32) b--;
    return s.slice(a, b);
  }

  /* Text written to go between double quotes in a stylesheet */
  function cssString(s) {
    return s.replace(/[\\"]/g, "\\$&").replace(/\n/g, "\\a ");
  }

  function closerOf(type) {
    return type === "{" ? "}" : type === "[" ? "]" : type === "(" || type === "function" ? ")" : "";
  }

  /* Past where the bracket token [i] opens is closed, or [to] */
  function closedAt(list, i, to) {
    var open = [closerOf(list[i].type)];
    for (var j = i + 1; j < to; j++) {
      var close = closerOf(list[j].type);
      if (close) {
        open.push(close);
      } else if (list[j].type === open[open.length - 1]) {
        open.pop();
        if (!open.length) return j + 1;
      }
    }
    return to;
  }

  /*
   * Past where the rule that begins at token [i] ends: its semicolon or its block. Inside
   * another rule's block, the end of that block ends it too.
   */
  function ruleEnd(list, i, to, nested) {
    for (var j = i + 1; j < to; j++) {
      var type = list[j].type;
      if (type === ";") return j + 1;
      if (type === "}" && nested) return j;
      if (type === "{") return closedAt(list, j, to);
      if (closerOf(type)) j = closedAt(list, j, to) - 1;
    }
    return to;
  }

  /* Whether the rule from token [i] to [end] has a block of its own */
  function blocked(list, i, end) {
    for (var j = i + 1; j < end; j++) {
      if (list[j].type === "{") return true;
      if (closerOf(list[j].type)) j = closedAt(list, j, end) - 1;
    }
    return false;
  }

  /* The next token after [i] that is not a space or a comment */
  function after(list, i, to) {
    for (i++; i < to && (list[i].type === "space" || list[i].type === "comment"); i++);
    return i;
  }

  /* The address the @import at token [i] names, or null */
  function importing(list, i, to) {
    var j = after(list, i, to);
    var t = j < to ? list[j] : null;
    if (t && (t.type === "string" || t.type === "url")) return t.value;
    if (t && t.type === "function" && /^url$/i.test(t.value)) {
      var k = after(list, j, to);
      if (k < to && list[k].type === "string") return list[k].value;
    }
    return null;
  }

  /* Rules that hold rules, inside which a font rule may begin as at the top of a sheet */
  var HOLDERS = /^(media|supports|document|-moz-document|layer|container|scope|starting-style)$/i;

  /* What a picture offered at several sizes may be written with besides an address: a picture, and a size */
  var PICTURED = /^((-webkit-|-moz-)?(image-set|image|cross-fade|element|gradient|(repeating-)?(linear|radial|conic)-gradient)|type|calc|min|max|clamp)$/;

  /*
   * The properties EPUB once had names of its own for, which the engine still answers to
   * some of, -epub-writing-mode among them. These are the ones it does not, so a book that
   * hyphenates by -epub-hyphens would otherwise go unhyphenated.
   */
  var EPUB_ONLY = /^-epub-(hyphens|line-break|text-align-last|text-underline-position)$/i;

  /*
   * Writes tokens [from, to) of [css] into [into], as text and, for each address to be
   * answered from the zip, { path }. In a sheet, [how.sheet], an @import at the top is
   * { imp: its name in the zip } and a font rule { face: its parts }, and :root, :host and
   * the EPUB properties are written for the shadow tree.
   *
   * Everything else is written as it was, but for what could take in whatever is written
   * after it, which is written closed: an address as url("..."), one the engine would not
   * take as url() with a control letter in it, which it gives up on, a comment as an empty
   * one, and a quote or a bracket the text leaves open by closing it.
   *
   * Answers how many addresses there were, and how many of them were not a place in the
   * chapter itself: { all, far }.
   */
  function rewrite(css, list, from, to, into, how) {
    var text = "";
    var found = { all: 0, far: 0 };
    var open = [];
    var top = { rules: !!how.sheet, start: true, at: false, holds: false };
    var levels = [top];

    function flush() {
      if (text) into.push(text);
      text = "";
    }

    function address(value) {
      var trimmed = urlTrim(value);
      var here = trimmed.charAt(0) === "#";
      found.all++;
      if (!here) found.far++;
      if (here || /^(data|blob):/i.test(trimmed) || !how.answer) {
        text += 'url("' + cssString(value) + '")';
        return;
      }
      var at = resolve(how.base, trimmed);
      if (!at) {
        text += 'url("data:,")';
        return;
      }
      flush();
      into.push({ path: at.path });
    }

    function unread() {
      found.all++;
      found.far++;
      text += "url(\u0001)";
    }

    for (var i = from; i < to; i++) {
      var t = list[i];
      var type = t.type;
      var inner = open.length ? open[open.length - 1] : null;
      var level = levels[levels.length - 1];
      // Not inside a bracket or a function, where a rule may begin or end
      var bare = !inner || !!inner.level;

      if (type === "comment") {
        text += "/**/";
        continue;
      }
      if (type === "space") {
        text += css.slice(t.from, t.to);
        continue;
      }

      if (bare && level.rules && level.start && type !== "cdo" && type !== "cdc") {
        level.start = false;
        level.at = type === "at";
        level.holds = false;
        var name = type === "at" ? t.value.toLowerCase() : "";
        var first = levels.length === 1;
        var own = (first && (name === "import" || name === "namespace")) || name === "font-face";
        var end = own ? ruleEnd(list, i, to, !first) : i;
        // A namespace with a block is no namespace, and is read as any other rule is
        if (name === "namespace" && list[end - 1].type === "}") own = false;
        if (own) {
          flush();
          if (name === "import") {
            var href = importing(list, i, end);
            var target = href === null ? null : resolve(how.base, urlTrim(href));
            var file = target && entry(target.path);
            if (file) into.push({ imp: file.name });
          } else if (name === "namespace" || blocked(list, i, end)) {
            // A namespace's address is a name and nothing to fetch, so it is not answered
            var part = [];
            rewrite(css, list, i, end, part, { base: how.base, answer: name === "font-face" });
            // Ended, so that the rules an import puts after one a sheet ends in stay out of it
            if (name === "namespace" && list[end - 1].type !== ";") part.push(";");
            into.push(name === "font-face" ? { face: part } : part.join(""));
          }
          // A font rule with no block of its own is none, and is left out as the engine leaves
          // it out: written as it was, it would take the next font rule for its block
          i = end - 1;
          level.start = true;
          continue;
        }
        level.holds = HOLDERS.test(name);
      }

      if (type === "{") {
        text += "{";
        var block = bare ? { rules: level.rules && level.at && level.holds, start: true, at: false, holds: false } : null;
        open.push({ close: "}", level: block });
        if (block) levels.push(block);
        continue;
      }
      if (type === "}" || type === ")" || type === "]") {
        text += type;
        if (inner && inner.close === type) {
          open.pop();
          if (inner.level) {
            levels.pop();
            levels[levels.length - 1].start = true;
          }
        }
        continue;
      }
      if (type === ";") {
        text += ";";
        if (bare && level.at) level.start = true;
        continue;
      }
      if (type === "url") {
        address(t.value);
        continue;
      }
      if (type === "bad-url") {
        unread();
        continue;
      }
      if (type === "string") {
        // A picture offered at several sizes may be named by its address in quotes alone
        if (inner && inner.sets) address(t.value);
        else text += t.closed ? css.slice(t.from, t.to) : '"' + cssString(t.value) + '"';
        continue;
      }
      if (type === "function") {
        var fn = t.value.toLowerCase();
        if (fn === "url" || fn === "src") {
          var arg = after(list, i, to);
          var shut = closedAt(list, i, to);
          if (arg < shut && list[arg].type === "string") address(list[arg].value);
          else unread();
          i = shut - 1;
          continue;
        }
        // A size of a picture named by anything but a picture or a size, as var(), if() and a
        // book's own functions are, is only known when the page is drawn
        if (inner && inner.sets && !PICTURED.test(fn)) {
          unread();
          i = closedAt(list, i, to) - 1;
          continue;
        }
        text += css.slice(t.from, t.to);
        open.push({ close: ")", sets: fn === "image-set" || fn === "-webkit-image-set" || fn === "image" });
        continue;
      }
      if (type === "(" || type === "[") {
        text += type;
        open.push({ close: closerOf(type) });
        continue;
      }
      if (how.sheet && type === ":" && i + 1 < to) {
        var next = list[i + 1];
        var called = next.type === "ident" || next.type === "function" ? next.value.toLowerCase() : "";
        // :root is the page's root and not the book's, so it is the html element the chapter is given
        if (next.type === "ident" && called === "root") {
          text += "html";
          i++;
          continue;
        }
        // The element the shadow tree hangs on is Gander's, and a book has no business with it
        if (called === "host" || (next.type === "function" && called === "host-context")) {
          text += ":not(*)";
          i = next.type === "function" ? closedAt(list, i + 1, to) - 1 : i + 1;
          continue;
        }
      }
      if (how.sheet && type === "ident" && EPUB_ONLY.test(t.value)) {
        var k = after(list, i, to);
        if (k < to && list[k].type === ":") {
          text += t.value.slice(6);
          continue;
        }
      }
      text += css.slice(t.from, t.to);
    }

    // A rule the text leaves begun, given an empty block so it ends where the text does
    var unfinished = how.sheet && !top.start && !(open.length && open[0].level);
    for (var o = open.length - 1; o >= 0; o--) text += open[o].close;
    if (unfinished) text += "{}";
    flush();
    return found;
  }

  /* The style of one element, read as any stylesheet is: { parts, found }, as rewrite() has them */
  function inStyle(style, base, answer) {
    var css = prepared(style);
    var list = tokens(css);
    var parts = [];
    var found = rewrite(css, list, 0, list.length, parts, { base: base, answer: answer });
    return { parts: parts, found: found };
  }

  /* A stylesheet's text in parts, as rewrite() writes them */
  function parsed(source, base) {
    var css = prepared(source);
    var list = tokens(css);
    var parts = [];
    rewrite(css, list, 0, list.length, parts, { base: base, sheet: true, answer: true });
    return parts;
  }

  /* A stylesheet in the zip in parts, by its name there, read once for the whole book */
  function partsOf(name) {
    if (!readSheets[name]) {
      readSheets[name] = text(name).then(function (css) {
        return parsed(css, name);
      }, function () {
        return [];
      });
    }
    return readSheets[name];
  }

  /*
   * Parts with each address answered from the zip, from [store], as { css, faces }: the
   * text, and the font rules among the parts, each of which is kept only where a font is
   * behind it. One the book does not hold, or one that did not unscramble, would only be a
   * rule the engine complains it cannot decode.
   */
  function answered(parts, store) {
    var wanted = [];
    (function want(list) {
      list.forEach(function (part) {
        if (part.face) want(part.face);
        else if (part.path !== undefined) wanted.push(part.path);
      });
    })(parts);
    return Promise.all(wanted.map(function (path) { return objectUrl(path, store); })).then(function (made) {
      var urlOf = bag();
      for (var i = 0; i < wanted.length; i++) urlOf[wanted[i]] = made[i];
      function written(part) {
        return typeof part === "string" ? part : 'url("' + (urlOf[part.path] || "data:,") + '")';
      }
      var css = "";
      var fonts = "";
      parts.forEach(function (part) {
        if (!part.face) {
          css += written(part);
          return;
        }
        var rule = part.face.map(written).join("");
        if (/url\("(blob:|data:[^,])/i.test(rule)) fonts += rule + "\n";
      });
      return { css: css, faces: fonts };
    });
  }

  /*
   * A stylesheet as the shadow tree can use it, { css, faces }, with the sheets it imports
   * written into it: [source] read against [base], or the sheet called [name] in the zip.
   * Its @font-face rules are taken out, since a shadow tree ignores them, and put in the
   * page's own head by show().
   *
   * A sheet imported from more than one place goes in once, at the last of them, which is
   * where the engine's cascade has its rules stand: a rule later on beats the same rule
   * earlier. So each is read once however often it is imported, and eight that each import
   * the next ten times come to eight readings, not ten million. One that imports a sheet it
   * was itself imported by is not followed back, as the engine does not follow it.
   */
  function fitted(source, base, name) {
    var known = bag();

    function reach(found, deep) {
      var wanted = [];
      found.forEach(function (parts) {
        parts.forEach(function (part) {
          if (part.imp !== undefined && !(part.imp in known) && deep < 8) {
            known[part.imp] = null;
            wanted.push(part.imp);
          }
        });
      });
      if (!wanted.length) return null;
      return Promise.all(wanted.map(partsOf)).then(function (got) {
        for (var i = 0; i < wanted.length; i++) known[wanted[i]] = got[i];
        return reach(got, deep + 1);
      });
    }

    var root;
    return new Promise(function (done) {
      done(name ? partsOf(name) : parsed(source, base));
    }).then(function (parts) {
      root = parts;
      if (name) known[name] = root;
      return reach([root], 0);
    }).then(function () {
      // Gone through from the end, so that each sheet is met at the last place it stands. A
      // sheet is placed as it is entered, so one that imports its own importer stops there
      var placed = bag();
      var out = [];
      (function visit(parts, at) {
        if (at) placed[at] = true;
        for (var i = parts.length - 1; i >= 0; i--) {
          var part = parts[i];
          var to = part.imp;
          if (to === undefined) out.push(part);
          else if (known[to] && !placed[to]) visit(known[to], to);
        }
      })(root, name);
      return answered(out.reverse(), shared);
    }).catch(function () {
      return { css: "", faces: "" };
    });
  }

  function sheet(path) {
    if (!sheets[path]) {
      var file = entry(path);
      sheets[path] = file ? fitted(null, null, file.name) : Promise.resolve({ css: "", faces: "" });
    }
    return sheets[path];
  }

  /*
   * Everything the chapter in [body] set aside, read from the zip. Answers a function that
   * puts it all in place at once, so the chapter is dressed in one step and not picture by
   * picture.
   */
  function fetched(body, base) {
    var jobs = [];
    var clear = [];

    function one(el, name, put) {
      var value = el.getAttribute("data-vw-" + name);
      if (value === null) return;
      el.removeAttribute("data-vw-" + name);
      if (/^data:(image|audio|video)\//i.test(value)) {
        jobs.push(Promise.resolve(function () { put(el, value); }));
        return;
      }
      var at = resolve(base, value);
      var from = at ? drawnFrom(at.path) : "";
      if (from && name === "src" && local(el) === "img" && CLEAR_KINDS.test(typeOf(from))) clear.push(el);
      jobs.push((from ? objectUrl(from, held) : Promise.resolve(null)).then(function (url) {
        return function () { if (url) put(el, url); };
      }));
    }

    var els = body.querySelectorAll("[data-vw-src], [data-vw-poster], [data-vw-href], [data-vw-style]");
    for (var i = 0; i < els.length; i++) {
      one(els[i], "src", function (el, url) { el.setAttribute("src", url); });
      one(els[i], "poster", function (el, url) { el.setAttribute("poster", url); });
      one(els[i], "href", function (el, url) { el.setAttributeNS(XLINK, "xlink:href", url); });
      (function (el) {
        var style = el.getAttribute("data-vw-style");
        if (style === null) return;
        el.removeAttribute("data-vw-style");
        jobs.push(answered(inStyle(style, base, true).parts, held).then(function (got) {
          return function () { el.setAttribute("style", got.css); };
        }));
      })(els[i]);
    }

    return Promise.all(jobs).then(function (puts) {
      var dress = function () { puts.forEach(function (put) { put(); }); };
      // The pictures among them whose kind of file can have a clear ground: see watch()
      dress.clear = clear;
      return dress;
    });
  }

  /*
   * On a dark phone, has each of [pictures] weighed as it comes within a screen of the one
   * showing. A book of formulas has a hundred pictures to a chapter and drawing each one
   * small takes the engine a moment, so they are not all weighed as the chapter goes up.
   * Nothing is weighed on a light phone, where every picture is on the paper it was drawn
   * for: show() hands over none.
   */
  function watch(pictures) {
    if (watching) watching.disconnect();
    watching = null;
    if (!pictures.length || !window.IntersectionObserver) return;
    watching = new IntersectionObserver(function (seen) {
      for (var i = 0; i < seen.length; i++) {
        if (!seen[i].isIntersecting) continue;
        watching.unobserve(seen[i].target);
        weigh(seen[i].target);
      }
    }, { rootMargin: "100% 0px" });
    pictures.forEach(function (img) { watching.observe(img); });
  }

  /*
   * Tells a picture drawn in dark ink on a clear ground, and marks it for the dark page's
   * rules in GUARD_CSS. Standard Ebooks does the same by hand for its own books, in the same
   * two ways. Lines in plain black, a formula or an ornament or a diagram, are drawn in the
   * page's light ink instead, as the words around them are. Anything else dark, which has
   * greys or colours that would come out as a negative, is given paper to stand on.
   *
   * Told by drawing the picture small and counting: a tenth of it clear, and of the rest
   * 85 in 100 black for lines, or 60 in 100 dark for paper. A picture the book has already
   * given a ground or a filter is the book's to show, and one the engine will not let be
   * read is left as it is.
   */
  function weigh(img) {
    if (!img.complete) {
      img.addEventListener("load", function () { weigh(img); });
      return;
    }
    if (!img.naturalWidth || img.hasAttribute("data-vw-ground")) return;
    var style = getComputedStyle(img);
    if (style.filter !== "none" || style.backgroundImage !== "none" ||
        !/^(transparent|rgba\(0, 0, 0, 0\))$/.test(style.backgroundColor)) return;
    var data;
    try {
      scales2d().clearRect(0, 0, WEIGH_PX, WEIGH_PX);
      scales.drawImage(img, 0, 0, WEIGH_PX, WEIGH_PX);
      data = scales.getImageData(0, 0, WEIGH_PX, WEIGH_PX).data;
    } catch (e) {
      return;
    }
    var clear = 0;
    var inked = 0;
    var dark = 0;
    var black = 0;
    for (var k = 0; k < data.length; k += 4) {
      if (data[k + 3] < 64) clear++;
      if (data[k + 3] < 128) continue;
      inked++;
      var light = 0.2126 * data[k] + 0.7152 * data[k + 1] + 0.0722 * data[k + 2];
      var hue = Math.max(data[k], data[k + 1], data[k + 2]) - Math.min(data[k], data[k + 1], data[k + 2]);
      if (light < 90) dark++;
      if (light < TOO_DARK && hue < 32) black++;
    }
    if (!inked || clear * 10 < WEIGH_PX * WEIGH_PX) return;
    if (black * 100 >= inked * 85) img.setAttribute("data-vw-ground", "ink");
    else if (dark * 100 >= inked * 60) img.setAttribute("data-vw-ground", "paper");
  }

  function scales2d() {
    if (!scales) {
      var canvas = document.createElement("canvas");
      canvas.width = canvas.height = WEIGH_PX;
      scales = canvas.getContext("2d");
    }
    return scales;
  }

  /*
   * Whether [colour], as a stylesheet writes it, is too dark to see on the dark page. The
   * canvas reads every way of writing one and answers in two, and leaves what it is given
   * alone for a word that is not a colour of its own, as inherit is not.
   */
  function tooDark(colour) {
    if (darkness[colour] !== undefined) return darkness[colour];
    var is = false;
    try {
      scales2d().fillStyle = "#ffffff";
      scales.fillStyle = colour;
      var read = String(scales.fillStyle);
      var hex = /^#(..)(..)(..)$/.exec(read);
      var parts = hex ? [parseInt(hex[1], 16), parseInt(hex[2], 16), parseInt(hex[3], 16)]
        : (read.match(/[\d.]+/g) || []).map(Number);
      is = parts.length >= 3 && !(parts.length > 3 && parts[3] < 0.5) &&
        0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2] < TOO_DARK;
    } catch (e) { /* not too dark, then */ }
    darkness[colour] = is;
    return is;
  }

  /* Gives each edge of [style] that is too dark to see the ink of the words it is among */
  function inkEdges(style) {
    for (var k = 0; k < EDGES.length; k++) {
      var colour = style.getPropertyValue(EDGES[k]);
      if (colour && tooDark(colour)) {
        style.setProperty(EDGES[k], "currentColor", style.getPropertyPriority(EDGES[k]));
      }
    }
  }

  /*
   * On a dark phone, draws what a book rules in black in the ink of the words beside it: the
   * lines of a table, the box round a note, the line under a heading. Only a colour too dark
   * to see is changed, in the book's rules as they stand on the page for this chapter. A
   * grey rule or a coloured one stays the book's, and so does one left clear to hold a space
   * open, which the usual way of doing this, every border in the ink, would draw.
   */
  function inkRules(rules) {
    for (var i = 0; i < rules.length; i++) {
      if (rules[i].cssRules) inkRules(rules[i].cssRules);
      if (rules[i].selectorText && rules[i].style) inkEdges(rules[i].style);
    }
  }

  /* Once the chapter's pictures have their sizes, or after a second and a half without them */
  function settled(body) {
    return new Promise(function (resolve) {
      var imgs = body.querySelectorAll("img");
      var waiting = 0;
      var done = false;
      function finish() {
        if (done) return;
        done = true;
        resolve();
      }
      function one() {
        if (--waiting <= 0) finish();
      }
      for (var i = 0; i < imgs.length; i++) {
        if (imgs[i].complete) continue;
        waiting++;
        imgs[i].addEventListener("load", one);
        imgs[i].addEventListener("error", one);
      }
      if (!waiting) finish();
      else setTimeout(finish, 1500);
    });
  }

  /*
   * Brings a page of a fixed size down, or up, to the width of the screen. It is laid out at
   * the size its book drew it for, since everything on it is placed by measurements from its
   * corner, and then shown smaller as a picture of itself would be. Scaling takes up no room
   * on the page, so the room is given by hand.
   */
  function scale() {
    var by = page.clientWidth / drawn.w;
    onPage.parentNode.style.transform = "scale(" + by + ")";
    page.style.height = Math.ceil(drawn.h * by) + "px";
  }

  /* Sets one thing on an element of the chapter over whatever its book said, and keeps what
     was there: see refit() */
  function setFor(el, name, value) {
    setToFit.push({
      el: el, name: name, value: el.style.getPropertyValue(name), priority: el.style.getPropertyPriority(name)
    });
    el.style.setProperty(name, value, "important");
  }

  /* The selectors of every rule in [rules] that sets something beside the text, added to [into] */
  function floaters(rules, into) {
    for (var i = 0; i < rules.length; i++) {
      var rule = rules[i];
      if (rule.cssRules) floaters(rule.cssRules, into);
      if (rule.selectorText && rule.style && rule.style.cssFloat && rule.style.cssFloat !== "none") {
        into.push(rule.selectorText);
      }
    }
  }

  /*
   * Sets a picture or a box in the middle of the page, with the text above and below it,
   * where the book set it beside the text and a phone leaves the text no room there: a plate
   * 320 pixels wide has a column one letter wide down its side. A drop capital, or anything
   * else that leaves the text room, stays where the book put it.
   *
   * What a book sets beside its text is found from its stylesheets, since asking every
   * element of a long chapter would cost more than the few there are.
   */
  function unfloat() {
    var found = ["[style*=float]", "img[align]", "table[align]"];
    for (var s = bookSheets.firstChild; s; s = s.nextSibling) {
      // A sheet the engine will not show the rules of sets nothing aside that can be found
      try { floaters(s.sheet.cssRules, found); } catch (e) { /* as above */ }
    }
    var narrow = FLOAT_ROOM_EMS * (parseFloat(getComputedStyle(onPage).fontSize) || 17);
    var loose = [];
    found.forEach(function (selector) {
      var els;
      // One written for an engine newer than this one finds nothing here
      try { els = onPage.querySelectorAll(selector); } catch (e) { return; }
      for (var i = 0; i < els.length; i++) {
        var style = getComputedStyle(els[i]);
        if (style.cssFloat === "none") continue;
        // The block its text is set in, which is what it takes its room from
        var within = els[i].parentNode;
        var around = getComputedStyle(within);
        while (within !== onPage && /^(inline|contents)$/.test(around.display)) {
          within = within.parentNode;
          around = getComputedStyle(within);
        }
        var room = within.clientWidth - (parseFloat(around.paddingLeft) || 0) - (parseFloat(around.paddingRight) || 0);
        var taken = els[i].getBoundingClientRect().width +
          Math.max(0, parseFloat(style.marginLeft) || 0) + Math.max(0, parseFloat(style.marginRight) || 0);
        var left = room - taken;
        // Something small in a narrow box is left alone: the box is what is narrow there. So is
        // what fills its box from side to side, as a heading may to sit on the box's edge: no
        // text can stand beside that
        if (taken * 2 > room && left >= 1 && left < narrow) loose.push({ el: els[i], block: style.display === "block" });
      }
    });
    loose.forEach(function (b) {
      setFor(b.el, "float", "none");
      // A picture is one of the words of its line again unless it is told otherwise
      if (!b.block) return;
      setFor(b.el, "display", "block");
      setFor(b.el, "margin-left", "auto");
      setFor(b.el, "margin-right", "auto");
    });
  }

  /*
   * Gives whatever is wider than the screen a way to be seen whole: a box the book gave a
   * width in pixels is held to the page, which brings the picture in it down with it, and a
   * table or a line of code that still does not fit scrolls sideways where it stands. The
   * page itself never moves sideways, so the rest of such a thing has no other way into view.
   *
   * Measured rather than ruled for every table and box in every book, so that the ones that
   * fit, which is nearly all of them, are laid out exactly as the book says. Only what runs
   * over is looked into, from the chapter down, so a chapter where nothing does costs one
   * comparison. Everything is measured first and changed afterwards, since a change between
   * two measurements would lay the chapter out again each time.
   */
  function fit() {
    if (drawn) return scale();
    if (!onPage) return;
    unfloat();
    if (onPage.scrollWidth <= onPage.clientWidth + 1) return;
    if (getComputedStyle(onPage).overflowX !== "visible") return;
    var edge = document.documentElement.clientWidth;
    var wide = [];

    function hold(box, style) {
      // Something set in a line of words cannot scroll, nor can any part of a formula, so
      // the block that the line or the formula is in does
      while (box !== onPage && (box.namespaceURI === MATHML || /^(inline|contents)/.test(style.display))) {
        box = box.parentNode;
        style = getComputedStyle(box);
      }
      if (wide.length && wide[wide.length - 1].el === box) return;
      wide.push({
        el: box,
        table: /^table/.test(style.display),
        scrolls: style.overflowX !== "visible",
        // Its margins are outside the width it is held to, so they come off that width
        margins: Math.max(0, parseFloat(style.marginLeft) || 0) + Math.max(0, parseFloat(style.marginRight) || 0)
      });
    }

    /* Looks through what is in [parent], which runs over, for what in it does. Says whether
       anything was found, since where nothing is, it is the parent's own words that run over. */
    function seek(parent) {
      // A chapter read right to left runs over to the left, and nowhere else
      var rtl = getComputedStyle(parent).direction === "rtl";
      var found = false;
      for (var el = parent.firstElementChild; el; el = el.nextElementSibling) {
        var rect = el.getBoundingClientRect();
        if (!rect.width) continue;
        var over = el.scrollWidth - el.clientWidth;
        var out = rtl ? rect.left < -1 : rect.right > edge + 1;
        // Within the screen itself, with something in it that is not
        var spills = !out && over > 1 &&
          (rtl ? rect.left + el.clientLeft - over < -1 : rect.left + el.clientLeft + el.scrollWidth > edge + 1);
        if (!out && !spills) continue;
        var style = getComputedStyle(el);
        // Put where it is by the book, off the screen as often as not, on purpose
        if (style.position === "absolute" || style.position === "fixed") continue;
        found = true;
        if (spills) {
          // Scrolls sideways already, or cuts off what runs over, by the book's own word
          if (style.overflowX !== "visible") continue;
          if (seek(el)) continue;
        }
        hold(el, style);
      }
      return found;
    }

    if (!seek(onPage)) hold(onPage, getComputedStyle(onPage));
    wide.forEach(function (b) {
      // A table scrolls only as a block, with its rows and columns as they were inside it
      if (b.table) setFor(b.el, "display", "block");
      setFor(b.el, "box-sizing", "border-box");
      setFor(b.el, "max-width", "calc(100% - " + b.margins + "px)");
      if (!b.scrolls) setFor(b.el, "overflow-x", "auto");
    });
  }

  /*
   * Fits the chapter again from the layout its book gave it, for a screen that has turned or
   * text that has changed size. What was set for the old width is taken back first: a picture
   * set alone because large type left no room beside it has that room again at a smaller size.
   */
  function refit() {
    for (var i = setToFit.length - 1; i >= 0; i--) {
      var was = setToFit[i];
      if (was.value) was.el.style.setProperty(was.name, was.value, was.priority);
      else was.el.style.removeProperty(was.name);
    }
    setToFit = [];
    fit();
  }

  // ---------------------------------------------------------------------------------------
  // Where the reader is
  // ---------------------------------------------------------------------------------------

  /* How far down the chapter the top of the screen is, from 0 to just under 1 */
  function fraction() {
    var box = page.getBoundingClientRect();
    var root = document.documentElement;
    // The foot of a chapter cannot be brought to the top of the screen, so being as far down
    // as the page goes is what counts as its last page
    if (window.pageYOffset > 0 && window.pageYOffset + window.innerHeight >= root.scrollHeight - 2) {
      return 0.999999;
    }
    return Math.max(0, Math.min(0.999999, (vwTopPx - box.top) / (box.height || 1)));
  }

  function pageNow() {
    var ch = spine[shown];
    return ch.start + Math.min(ch.pages - 1, Math.floor(fraction() * ch.pages + 1e-6)) + 1;
  }

  /* Tells the app how far through the book the screen is, in the words pdf.html uses for its
     pages: see PortMessage.kt */
  function report() {
    if (shown < 0 || !onPage) return;
    var n = pageNow();
    if (n !== sent && vwFind.post("page " + n + " " + total)) sent = n;
  }

  /* Tells the app whether the chapter is one whose text takes the size the reader chose. A
     page of a fixed size is not: it was drawn around its type at the size the book gave it. */
  function flow() {
    var say = drawn ? "reflow 0" : "reflow 1";
    if (say !== toldFlow && vwFind.post(say)) toldFlow = say;
  }

  /* Where on the screen a chapter comes out from under the title bar, or from under as much
     of the bar as is still down */
  function topEdge() {
    return Math.max(0, vwTopPx - vwBarGonePx);
  }

  /*
   * A place in a chapter is a count of what comes before it: every letter of its text, and
   * one for each picture. Unlike a distance down the page, that is the same place at any
   * size of type and whichever way the phone is held.
   */
  function counted() {
    return document.createTreeWalker(onPage, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
      acceptNode: function (n) {
        return n.nodeType === 3 || COUNTED.test(local(n))
          ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      }
    });
  }

  /* Whether enough of a box is below [top] for it to be what the reader is looking at: half
     of a line of words, or two dozen pixels of anything taller */
  function showing(box, top) {
    return !!box && box.height > 0 && box.bottom - top >= Math.min(box.height / 2, 24);
  }

  /* The box of the line that letter [k] of a text is on, or of its last line for [last]. A
     space a line was broken at has no box, so it is the line of the next letter that has
     one, and null where none does. */
  function lineOf(text, k, last) {
    var range = document.createRange();
    range.setStart(text, k);
    range.setEnd(text, text.data.length);
    var boxes = range.getClientRects();
    for (var i = 0; i < boxes.length; i++) {
      var box = boxes[last ? boxes.length - 1 - i : i];
      if (box.height > 0) return box;
    }
    return null;
  }

  /*
   * The first letter or picture in [el], in reading order, that is showing below [top]:
   * { node, at }, at being the letter where the node is a text. A box wholly above the edge
   * is passed over with all that is in it, so a long chapter costs a look at each of its
   * paragraphs and at the letters of one.
   */
  function firstShowing(el, top) {
    for (var n = el.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3) {
        if (!/\S/.test(n.data)) continue;
        var end = lineOf(n, 0, true);
        if (!showing(end, top)) continue;
        // Its lines run down the page, so the first of them showing is found by halving
        var lo = 0;
        var hi = n.data.length - 1;
        while (lo < hi) {
          var mid = (lo + hi) >> 1;
          if (showing(lineOf(n, mid) || end, top)) hi = mid;
          else lo = mid + 1;
        }
        return { node: n, at: lo };
      }
      if (n.nodeType !== 1) continue;
      var box = n.getBoundingClientRect();
      if (COUNTED.test(local(n))) {
        if (showing(box, top)) return { node: n, at: 0 };
        continue;
      }
      // Something with no height is not a box at all, and what is in it may be anywhere
      if (box.height > 0 && box.bottom <= top) continue;
      var found = firstShowing(n, top);
      if (found) return found;
    }
    return null;
  }

  /* How many letters and pictures of the chapter come before letter [at] of [node] */
  function countBefore(node, at) {
    var walk = counted();
    var sum = 0;
    for (var n = walk.nextNode(); n; n = walk.nextNode()) {
      if (n === node) return sum + at;
      sum += n.nodeType === 3 ? n.data.length : 1;
    }
    return 0;
  }

  /* The box of what comes after [past] letters and pictures of the chapter, or of the first
     thing after that which has one, since words a book hides have none */
  function boxAt(past) {
    var walk = counted();
    var left = past;
    for (var n = walk.nextNode(); n; n = walk.nextNode()) {
      var size = n.nodeType === 3 ? n.data.length : 1;
      if (left >= size) {
        left -= size;
        continue;
      }
      var box = n.nodeType === 3 ? lineOf(n, left) : n.getBoundingClientRect();
      if (box && box.height > 0) return box;
      left = 0;
    }
    return null;
  }

  /*
   * Works out the place the screen has come to rest at and tells the app, which keeps it for
   * the next time the book is opened: see Positions.kt. Done once the page has stopped
   * moving, since finding it costs more than there is to spend on each frame of a fling.
   */
  function rest() {
    settling = 0;
    if (shown < 0 || !onPage || page.style.opacity === "0") return;
    readOn();
    // Where a change of type size left it, the place is still the letter that was kept on
    // top. Worked out again it would be the start of that letter's line, a little earlier
    // at each change, and the reader would be walked back up the chapter.
    if (heldAt >= 0 && Math.abs(window.pageYOffset - heldAt) < 1) return;
    heldAt = -1;
    above = here();
    tell();
  }

  /* How many letters and pictures of the chapter are above the screen as it stands */
  function here() {
    var first = window.pageYOffset > 0 ? firstShowing(onPage, topEdge()) : null;
    return first ? countBefore(first.node, first.at) : 0;
  }

  function tell() {
    var say = "at " + shown + " " + above;
    if (say !== told && vwFind.post(say)) told = say;
  }

  function named(frag) {
    var el = shadow.getElementById(frag);
    if (el || !window.CSS || !CSS.escape) return el;
    return onPage.querySelector('[name="' + CSS.escape(frag) + '"]');
  }

  /* Puts the screen at [to]: { frag } a place the book names, { past } that many letters and
     pictures into the chapter, or anything else its top, with the strip above it in view. */
  function place(to) {
    var mark = to && to.frag ? named(to.frag) : null;
    var box = !mark && to && to.past > 0 ? boxAt(to.past) : null;
    if (mark) {
      mark.scrollIntoView();
    } else if (box) {
      window.scrollTo(0, window.pageYOffset + box.top - topEdge());
    } else {
      window.scrollTo(0, 0);
    }
  }

  /* The element in the middle of the screen, which a search has just brought there */
  function middle() {
    var el = shadow.elementFromPoint
      ? shadow.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2) : null;
    return el && onPage && onPage !== el && onPage.contains(el) ? el : null;
  }

  // ---------------------------------------------------------------------------------------
  // Putting a chapter on the page
  // ---------------------------------------------------------------------------------------

  /* The name the contents give the chapter on the page, or the nearest one before it */
  function nameOf(i) {
    var best = "";
    var bestAt = -1;
    for (var k = 0; k < contents.length; k++) {
      var at = contents[k].at;
      if (at < 0 || at > i || at < bestAt) continue;
      if (at > bestAt || !best) {
        best = contents[k].label;
        bestAt = at;
      }
    }
    return best;
  }

  function dressLike(el, attrs) {
    for (var name in attrs) el.setAttribute(name, attrs[name]);
  }

  /*
   * Puts chapter [i] on the page, which must have been read already.
   *
   * In two steps, because find.js needs the first to be done by the time this returns: the
   * chapter's words go in at once, unseen, and its stylesheets and pictures follow when the
   * zip has given them up. [quiet] is a search bringing the chapter up for a match in it,
   * which places the screen itself, so what it brought to the middle is kept there while
   * the chapter is dressed and nothing is said to find.js about a change it made.
   */
  function show(i, to, quiet) {
    var made = spine[i].ready;
    var mine = ++turn;
    var base = spine[i].path;
    release();
    shown = i;
    moved = false;
    setToFit = [];
    heldAt = -1;
    above = to && to.past > 0 ? to.past : 0;
    // Said at once, and the line they come to rest on when the chapter is dressed: the app
    // hears how far through the book this chapter is as soon as it is up, and kept with the
    // place it had, a book left in that moment would reopen in the chapter before
    tell();

    var html = document.createElement("html");
    var body = made.body.cloneNode(true);
    dressLike(html, made.html);
    dressLike(body, made.own);
    html.appendChild(body);
    drawn = made.drawn;
    flow();
    page.className = drawn ? "vw-page vw-fixed" : "vw-page vw-flow";
    page.style.height = "";
    if (drawn) {
      html.style.width = drawn.w + "px";
      html.style.height = drawn.h + "px";
    }
    bookSheets.textContent = "";
    faces.textContent = "";
    page.textContent = "";
    // Unseen rather than hidden, so the middle of the screen can still be asked what is in it
    page.style.opacity = "0";
    page.appendChild(html);
    onPage = body;

    Object.assign(where, caption(null), nameOf(i));
    prev.disabled = i <= 0;
    next.disabled = i >= spine.length - 1;
    if (!quiet) place(to);

    var styled = Promise.all(made.styles.map(function (s) {
      return s.path ? sheet(s.path) : fitted(s.css, base);
    }));
    Promise.all([styled, fetched(body, base)]).then(function (got) {
      if (mine !== turn) return null;
      var keep = quiet ? middle() : null;
      var fonts = "";
      var night = !drawn && !!darkPhone && darkPhone.matches;
      got[0].forEach(function (s) {
        var el = document.createElement("style");
        el.textContent = s.css;
        bookSheets.appendChild(el);
        fonts += s.faces;
        try { if (night && el.sheet) inkRules(el.sheet.cssRules); } catch (e) { /* as the book ruled it */ }
      });
      faces.textContent = fonts;
      got[1]();
      if (night) {
        var ruled = body.querySelectorAll('[style*="border"]');
        for (var r = 0; r < ruled.length; r++) inkEdges(ruled[r].style);
      }
      watch(night ? got[1].clear : []);
      page.style.opacity = "";
      fit();
      if (keep) keep.scrollIntoView({ block: "center" });
      else if (!quiet) place(to);
      return settled(body).then(function () { return keep; });
    }).then(function (keep) {
      if (mine !== turn) return;
      // Again now that the pictures have their sizes
      fit();
      if (!moved) {
        if (keep) keep.scrollIntoView({ block: "center" });
        else if (!quiet) place(to);
      }
      if (!opened) {
        opened = true;
        bar.hidden = false;
        turnBar.hidden = false;
        vwStatusDone();
      }
      report();
      if (!quiet) vwFind.changed();
      trim();
      // Last, so that nothing above waits on it
      rest();
    }).catch(function (e) {
      page.style.opacity = "";
      if (!opened) fail(e);
    });
  }

  function go(i, to) {
    if (i < 0 || i >= spine.length) return Promise.resolve();
    if (i === shown && onPage) {
      place(to);
      report();
      return Promise.resolve();
    }
    var mine = ++turn;
    return load(i).then(function () {
      if (mine === turn) show(i, to, false);
    });
  }

  // ---------------------------------------------------------------------------------------
  // Find in page, across the whole book: see find.js for what it asks of a page in parts
  // ---------------------------------------------------------------------------------------

  /* Reads every chapter, one after another, then has the search run again over all of them */
  function readWholeBook() {
    if (wholeBook) return;
    wholeBook = true;
    reading = true;
    var chain = Promise.resolve();
    spine.forEach(function (ch, i) {
      chain = chain.then(function () { return load(i); });
    });
    chain.then(function () {
      reading = false;
      vwFind.reread();
    });
  }

  window.vwFindParts = {
    count: function () { return spine.length; },
    root: function (i) {
      if (i === shown && onPage) return onPage;
      if (spine[i].ready) return spine[i].ready.body;
      // Asked for a chapter that is not in memory, which only a search does
      readWholeBook();
      return EMPTY;
    },
    show: function (i) {
      if (spine[i].ready) {
        forget();
        show(i, null, true);
      }
      return onPage;
    },
    shown: function () { return shown; },
    busy: function () { return reading; },
    top: topEdge
  };

  /* What the app sends besides search: "o" for the contents, from its menu, and "x" for
     Back while something of the page's own is open over the book */
  window.vwPageCommand = function (msg) {
    var verb = msg.charAt(0);
    if (verb === "o" && opened) showContents(true);
    else if (verb === "x") closeOver();
  };

  /* Everything the app is told is said again to a port that has only now opened */
  window.vwPortReady = function () {
    sent = 0;
    told = "";
    toldFlow = "";
    toldOver = false;
    if (shown < 0 || !onPage) return;
    flow();
    report();
    rest();
    over();
  };

  // ---------------------------------------------------------------------------------------
  // The page's own parts: the two buttons, the contents, and links in a chapter
  // ---------------------------------------------------------------------------------------

  function buildContents() {
    Object.assign(document.getElementById("epub-title"), bookTitle);
    // An epic in two thousand short chapters lists every one, so the limit is well past that
    for (var i = 0; i < contents.length && i < 20000; i++) {
      var li = document.createElement("li");
      var el = document.createElement(contents[i].at < 0 ? "span" : "button");
      if (contents[i].at >= 0) {
        el.type = "button";
        el.setAttribute("data-n", i);
      }
      Object.assign(el, contents[i].label);
      el.style.webkitPaddingStart = (12 + 16 * Math.min(contents[i].depth, 4)) + "px";
      li.appendChild(el);
      list.appendChild(li);
    }
  }

  function showContents(on) {
    if (on) {
      var here = nameOf(shown);
      var buttons = list.querySelectorAll("button");
      var current = null;
      for (var i = 0; i < buttons.length; i++) {
        var e = contents[Number(buttons[i].getAttribute("data-n"))];
        var is = !current && e.at === shown && e.label === here;
        if (is) {
          buttons[i].setAttribute("aria-current", "true");
          current = buttons[i];
        } else {
          buttons[i].removeAttribute("aria-current");
        }
      }
      showLink("");
      panel.hidden = false;
      if (current) current.scrollIntoView({ block: "center" });
      closeContents.focus({ preventScroll: true });
    } else {
      panel.hidden = true;
      openContents.focus({ preventScroll: true });
    }
    over();
  }

  /* Tells the app whether Back has something to do on this page before it leaves the book:
     the contents or a link's address to close, or a place to go back to. See PortMessage.kt */
  function over() {
    var is = !panel.hidden || !linkBox.hidden || !!before;
    if (is !== toldOver && vwFind.post(is ? "cover 1" : "cover 0")) toldOver = is;
  }

  /* Back, from the app: one thing at a time, what is open over the book before the place */
  function closeOver() {
    if (!linkBox.hidden) {
      showLink("");
    } else if (!panel.hidden) {
      showContents(false);
    } else if (before) {
      var to = before;
      before = null;
      go(to.i, { past: to.past });
    }
    // The app stops listening for Back as it sends this, so that a page which never answers
    // cannot hold on to it. Where there is still something for Back to do, it is told again.
    toldOver = false;
    over();
  }

  /* Keeps [from] as the place Back goes to, the reader having just left it by a link */
  function remember(from) {
    before = { i: from.i, past: from.past, y: window.pageYOffset, touched: false };
    over();
  }

  function forget() {
    if (!before) return;
    before = null;
    over();
  }

  /*
   * Called when the page comes to rest. Until the reader touches it, where it rests is where
   * the link put them, which is settled only once the chapter's pictures have their sizes.
   * After that, more than two screens from there is a reader who has gone on reading, as one
   * does from a link in a book's own list of chapters. Back is then theirs to leave by, and
   * the place the book is left at is the one they are reading.
   */
  function readOn() {
    if (!before) return;
    if (!before.touched) before.y = window.pageYOffset;
    else if (Math.abs(window.pageYOffset - before.y) > 2 * window.innerHeight) forget();
  }

  /* Whether [past], a place in the chapter on the page, is within a screen of its top */
  function near(past) {
    if (past <= 0) return window.pageYOffset < window.innerHeight;
    var box = boxAt(past);
    return !!box && Math.abs(box.top - topEdge()) < window.innerHeight;
  }

  /* An address outside the book that a reader is shown, or nothing for any other */
  function outside(href) {
    href = String(href || "").trim();
    if (href.indexOf("//") === 0) return "https:" + href;
    return OUTSIDE.test(href) ? href : "";
  }

  /* Shows [address] with a way to copy it, or puts that away for no address */
  function showLink(address) {
    if (address) {
      linkAddress.textContent = address;
      linkCopy.textContent = "Copy address";
      linkBox.hidden = false;
      linkCopy.focus({ preventScroll: true });
    } else {
      linkBox.hidden = true;
    }
    over();
  }

  /*
   * Puts the address on the clipboard. The older way goes first, which copies what is
   * selected on the page, because an old engine allows it only while the tap is still being
   * handled: WebView 66 refuses the newer way a moment later, and by then refuses the older
   * one too. Where the older way will not, the newer is asked, and where neither will, the
   * address is left selected for the reader's own Copy.
   */
  function copyLink() {
    var range = document.createRange();
    range.selectNodeContents(linkAddress);
    var chosen = window.getSelection();
    chosen.removeAllRanges();
    chosen.addRange(range);
    function done() {
      chosen.removeAllRanges();
      linkCopy.textContent = "Copied";
    }
    var did = false;
    try { did = document.execCommand("copy"); } catch (e) { /* the newer way, then */ }
    if (did) {
      done();
    } else if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(linkAddress.textContent).then(done, function () { /* left selected */ });
    }
  }

  openContents.addEventListener("click", function () { showContents(true); });
  closeContents.addEventListener("click", function () { showContents(false); });
  // Turning the chapter or choosing one is going on with the book, and Back is for leaving
  prev.addEventListener("click", function () { forget(); go(shown - 1, null); });
  next.addEventListener("click", function () { forget(); go(shown + 1, null); });
  linkCopy.addEventListener("click", copyLink);
  linkClose.addEventListener("click", function () { showLink(""); });
  // A tap beside the box, on the book behind it, puts it away as well
  linkBox.addEventListener("click", function (e) { if (e.target === linkBox) showLink(""); });

  list.addEventListener("click", function (e) {
    var el = e.target;
    if (!el || !el.getAttribute || el.getAttribute("data-n") === null) return;
    var to = contents[Number(el.getAttribute("data-n"))];
    forget();
    showContents(false);
    go(to.at, to.frag ? { frag: to.frag } : null);
  });

  /*
   * A link in a chapter. One to somewhere else in the book goes there. One to a web address
   * shows the address, for the reader to copy: Gander opens no web address, here or in any
   * other viewer, and nothing a document says is handed on to a browser.
   */
  /*
   * The link a tap on [el] is inside: the nearest a or area above it with an address. An a
   * with none is a name for a place and not a link.
   */
  function linkAt(el) {
    for (var a = el; a && a !== shadow; a = a.parentNode) {
      if (a.nodeType === 1 && LINK.test(local(a)) && (a.hasAttribute("href") || a.hasAttributeNS(XLINK, "href"))) return a;
    }
    return null;
  }

  /*
   * The details a tap on [el] inside [link] opens or closes, as the engine has it, or null
   * where the tap is the link's: one whose own summary, its first, is nearer than the link,
   * unless what was tapped is a control. A summary in no details, or not the first of its
   * own, does nothing, and the engine follows the link around it.
   */
  function detailsAt(el, link) {
    if (CONTROLS.test(local(el))) return null;
    for (var s = el; s && s !== link; s = s.parentNode) {
      if (s.nodeType !== 1 || local(s) !== "summary" || !s.parentNode || local(s.parentNode) !== "details") continue;
      for (var first = s.parentNode.firstElementChild; first && local(first) !== "summary"; first = first.nextElementSibling);
      if (first === s) return s.parentNode;
    }
    return null;
  }

  shadow.addEventListener("click", function (e) {
    var a = linkAt(e.target);
    if (!a) return;
    // First, and whatever the link says of itself, so that no tap in a book is ever the
    // engine's to follow: it would follow one to Gander's own pages, which the app allows
    e.preventDefault();
    // Which also keeps the engine from opening a details the tap was on, so that is done here
    var details = detailsAt(e.target, a);
    if (details) {
      details.open = !details.open;
      return;
    }
    var href = a.getAttribute("href") || a.getAttributeNS(XLINK, "href");
    if (!href) return;
    var at = resolve(spine[shown].path, href);
    if (!at) {
      var address = outside(href);
      if (address) showLink(address);
      return;
    }
    if (index[at.path] === undefined) return;
    var from = { i: shown, past: here() };
    var back = before;
    go(index[at.path], at.frag ? { frag: at.frag } : null).then(function () {
      // A note's own link back is a link like any other. Arriving where they set out from is
      // the return itself, and leaves nothing for Back to return to.
      if (back && shown === back.i && near(back.past)) forget();
      else remember(from);
    });
  });

  // A middle click is not a click, and would open the link in a page of its own
  shadow.addEventListener("auxclick", function (e) {
    if (linkAt(e.target)) e.preventDefault();
  });

  var reporting = false;
  window.addEventListener("scroll", function () {
    // Where relaid() put the screen is the page's doing and not the reader's, and so is
    // where the engine lets it fall as the type changes size, which is heard of here before
    // relaid() has run. Counted as theirs, a second change of size close on the first would
    // find the page on the move and leave the line there.
    var own = relaying() || (heldAt >= 0 && Math.abs(window.pageYOffset - heldAt) < 1);
    if (!own) {
      if (settling) clearTimeout(settling);
      // Nothing thrown in a timer may reach the page, which would put a card over the book
      settling = setTimeout(function () {
        try { rest(); } catch (e) { settling = 0; }
      }, 150);
    }
    if (reporting) return;
    reporting = true;
    requestAnimationFrame(function () {
      reporting = false;
      report();
    });
  }, { passive: true });

  /*
   * The type has changed size, or the phone has turned. The chapter is fitted to that
   * afresh, and the line the reader was on is brought back to the top of the screen, unless
   * the page is on the move and the place that was kept is no longer where they are.
   */
  function relaid() {
    across = window.innerWidth;
    gaugeTall = typeTall();
    ownSize();
    if (shown < 0 || !onPage) return;
    refit();
    if (!settling) {
      place(above > 0 ? { past: above } : null);
      heldAt = window.pageYOffset;
    }
    // Every line below the first is somewhere else now, and so is where the link left them
    if (before) before.y = window.pageYOffset;
    report();
  }

  /*
   * Keeps the page's own words the size the phone asks for: see --epub-own in epub.css. The
   * letter that tells the size of the type is 100 px tall before any zoom, so its height is
   * the whole zoom as a percentage, the phone's own and the reader's text size together.
   */
  function ownSize() {
    var tall = typeTall();
    if (!phoneType || !tall) return;
    document.documentElement.style.setProperty("--epub-own", String(phoneType / tall));
  }

  // The keyboard coming up changes the height alone, which moves nothing in a chapter
  window.addEventListener("resize", function () {
    if (window.innerWidth !== across) relaid();
  });

  function typeTall() {
    return gauge ? gauge.getBoundingClientRect().height : 0;
  }

  /* Whether the type has changed size, or the screen its width, and relaid() has yet to run */
  function relaying() {
    return typeTall() !== gaugeTall || window.innerWidth !== across;
  }

  /*
   * A letter off the page, at a size no book can reach. The size of the type here is the
   * app's to set, through the WebView, and the engine then lays the page out again without
   * a word to anything on it. This letter's box changing is how the page hears of it.
   */
  (function () {
    if (!window.ResizeObserver) return;
    var first = true;
    gauge = document.createElement("span");
    gauge.id = "epub-gauge";
    gauge.textContent = "M";
    gauge.setAttribute("aria-hidden", "true");
    gauge.style.cssText = "position:absolute;top:0;left:0;visibility:hidden;" +
      "pointer-events:none;white-space:nowrap;font:100px/1 serif";
    document.body.appendChild(gauge);
    gaugeTall = typeTall();
    ownSize();
    new ResizeObserver(function () {
      // The first word is of the size the letter starts at
      if (first) {
        first = false;
        gaugeTall = typeTall();
        return;
      }
      try { relaid(); } catch (e) { /* the chapter is still there, as it was */ }
    }).observe(gauge);
  })();

  ["touchstart", "wheel", "keydown"].forEach(function (name) {
    window.addEventListener(name, function () {
      moved = true;
      if (before) before.touched = true;
    }, { passive: true });
  });

  function fail(e) {
    vwError((e && e.vwTitle) || "Could not open this book", String((e && e.message) || e));
  }

  vwFetchDoc("buffer").then(open).then(function () {
    buildContents();
    // The place the book was left at, which the app keeps and sends back: the chapter by
    // its number in the reading order, and how much of it was above the screen
    var left = /^(\d+)\.(\d+)$/.exec(vwParams.get("at") || "");
    if (left && Number(left[1]) < spine.length) {
      return go(Number(left[1]), { past: Number(left[2]) });
    }
    return go(0, null);
  }).catch(fail);

  return { go: go };
})();
