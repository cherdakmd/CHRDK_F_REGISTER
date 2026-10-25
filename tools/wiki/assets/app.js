/* Вики CHRDK: меню, поиск, копирование команд и подсветка содержания. */
(function () {
  "use strict";

  var toastTimer = null;
  function toast(message) {
    var node = document.getElementById("toast");
    if (!node) return;
    node.textContent = message;
    node.classList.add("is-visible");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      node.classList.remove("is-visible");
    }, 1800);
  }

  /* ------------------------------------------------------------- тема */

  var THEME_KEY = "chrdk-wiki-theme";
  var root = document.documentElement;
  try {
    var saved = localStorage.getItem(THEME_KEY);
    if (saved === "light" || saved === "dark") {
      root.setAttribute("data-theme", saved);
    }
  } catch (error) {
    /* приватный режим — просто тёмная тема */
  }

  var themeBtn = document.getElementById("themeBtn");
  function paintThemeButton() {
    if (!themeBtn) return;
    var dark = root.getAttribute("data-theme") !== "light";
    themeBtn.textContent = dark ? "☾" : "☀";
    themeBtn.setAttribute("aria-label", dark ? "Включить светлую тему" : "Включить тёмную тему");
    themeBtn.title = dark ? "Светлая тема" : "Тёмная тема";
  }
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var next = root.getAttribute("data-theme") === "light" ? "dark" : "light";
      root.setAttribute("data-theme", next);
      try {
        localStorage.setItem(THEME_KEY, next);
      } catch (error) {
        /* без сохранения */
      }
      paintThemeButton();
    });
  }
  paintThemeButton();

  /* ------------------------------------------------------------- меню */

  var menuBtn = document.getElementById("menuBtn");
  function closeNav() {
    document.body.classList.remove("nav-open");
    if (menuBtn) menuBtn.setAttribute("aria-expanded", "false");
  }
  if (menuBtn) {
    menuBtn.addEventListener("click", function () {
      var open = document.body.classList.toggle("nav-open");
      menuBtn.setAttribute("aria-expanded", open ? "true" : "false");
    });
    document.addEventListener("click", function (event) {
      if (!document.body.classList.contains("nav-open")) return;
      var sidebar = document.getElementById("sidebar");
      if (sidebar && sidebar.contains(event.target)) return;
      if (event.target === menuBtn || (menuBtn.contains && menuBtn.contains(event.target))) return;
      closeNav();
    });
  }

  /* ------------------------------------------------------------- копирование */

  var copyable = document.querySelectorAll(".page code");
  Array.prototype.forEach.call(copyable, function (node) {
    var text = (node.textContent || "").trim();
    if (text.charAt(0) !== "/" && text.charAt(0) !== "!") return;
    node.classList.add("cmd");
    node.title = "Нажмите, чтобы скопировать";
    node.addEventListener("click", function () {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(
          function () {
            toast("Скопировано: " + text);
          },
          function () {
            toast(text);
          }
        );
      } else {
        toast(text);
      }
    });
  });

  /* ------------------------------------------------------------- поиск */

  var modal = document.getElementById("searchModal");
  var input = document.getElementById("searchInput");
  var results = document.getElementById("searchResults");
  var trigger = document.getElementById("searchTrigger");
  var closeBtn = document.getElementById("searchClose");
  var indexPromise = null;
  var index = [];
  var selected = 0;
  var hits = [];

  function loadIndex() {
    if (!indexPromise) {
      indexPromise = fetch("search-index.json", { credentials: "same-origin" })
        .then(function (response) {
          if (!response.ok) throw new Error("Нет индекса поиска");
          return response.json();
        })
        .catch(function () {
          return [];
        });
    }
    return indexPromise;
  }

  function escapeHtml(value) {
    return value.replace(/[&<>"]/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch];
    });
  }

  function highlight(text, query) {
    var safe = escapeHtml(text);
    if (!query) return safe;
    var escaped = query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    try {
      return safe.replace(new RegExp("(" + escaped + ")", "gi"), "<mark>$1</mark>");
    } catch (error) {
      return safe;
    }
  }

  function snippet(text, query) {
    var lower = text.toLowerCase();
    var at = lower.indexOf(query.toLowerCase());
    if (at < 0) return text.slice(0, 130);
    var start = Math.max(0, at - 50);
    var piece = text.slice(start, start + 160);
    return (start > 0 ? "…" : "") + piece + (start + 160 < text.length ? "…" : "");
  }

  function render() {
    var query = (input.value || "").trim().toLowerCase();
    if (query.length < 2) {
      hits = [];
      results.innerHTML = '<p class="search-empty">Введите хотя бы два символа: «клады», «ранг», «приват»…</p>';
      return;
    }
    var scored = [];
    index.forEach(function (page) {
      var score = 0;
      var title = page.title.toLowerCase();
      if (title.indexOf(query) === 0) score += 120;
      else if (title.indexOf(query) >= 0) score += 90;
      var headings = (page.headings || []).join(" ").toLowerCase();
      if (headings.indexOf(query) >= 0) score += 40;
      var text = page.text.toLowerCase();
      var first = text.indexOf(query);
      if (first >= 0) score += 25 + Math.min(20, Math.floor(first / 200));
      if (score > 0) {
        scored.push({ page: page, score: score, at: first });
      }
    });
    scored.sort(function (a, b) {
      return b.score - a.score || a.page.title.localeCompare(b.page.title);
    });
    hits = scored.slice(0, 12).map(function (entry) {
      return entry.page;
    });
    selected = 0;
    if (!hits.length) {
      results.innerHTML = '<p class="search-empty">Ничего не нашлось. Попробуйте другое слово.</p>';
      return;
    }
    results.innerHTML = hits
      .map(function (page, position) {
        var excerpt = snippet(page.text, query);
        return (
          '<a class="search-hit' +
          (position === 0 ? " is-selected" : "") +
          '" href="' +
          page.url +
          '">' +
          "<small>" +
          escapeHtml(page.group) +
          "</small><strong>" +
          highlight(page.title, query) +
          "</strong><p>" +
          highlight(excerpt, query) +
          "</p></a>"
        );
      })
      .join("");
  }

  function move(delta) {
    if (!hits.length) return;
    selected = (selected + delta + hits.length) % hits.length;
    var nodes = results.querySelectorAll(".search-hit");
    Array.prototype.forEach.call(nodes, function (node, position) {
      node.classList.toggle("is-selected", position === selected);
    });
    if (nodes[selected] && nodes[selected].scrollIntoView) {
      nodes[selected].scrollIntoView({ block: "nearest" });
    }
  }

  function openSearch() {
    if (!modal) return;
    modal.hidden = false;
    document.body.style.overflow = "hidden";
    input.value = "";
    input.focus();
    loadIndex().then(function (data) {
      index = data || [];
      if (input.value.trim().length >= 2) render();
    });
    render();
  }

  function closeSearch() {
    if (!modal) return;
    modal.hidden = true;
    document.body.style.overflow = "";
  }

  if (trigger) trigger.addEventListener("click", openSearch);
  if (closeBtn) closeBtn.addEventListener("click", closeSearch);
  if (modal) {
    modal.addEventListener("click", function (event) {
      if (event.target === modal) closeSearch();
    });
  }
  if (input) {
    input.addEventListener("input", render);
    input.addEventListener("keydown", function (event) {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        move(1);
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        move(-1);
      } else if (event.key === "Enter" && hits[selected]) {
        window.location.href = hits[selected].url;
      }
    });
  }

  document.addEventListener("keydown", function (event) {
    var userTyping = /^(INPUT|TEXTAREA|SELECT)$/.test((event.target.tagName || "").toUpperCase());
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      if (modal && !modal.hidden) closeSearch();
      else openSearch();
    } else if (event.key === "Escape") {
      if (modal && !modal.hidden) closeSearch();
      closeNav();
    } else if (event.key === "/" && !userTyping && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      openSearch();
    }
  });

  /* ------------------------------------------------------------- содержание */

  var headings = document.querySelectorAll(".page h2[id]");
  var tocLinks = document.querySelectorAll(".toc a");
  if (headings.length && tocLinks.length && "IntersectionObserver" in window) {
    var byId = {};
    Array.prototype.forEach.call(tocLinks, function (link) {
      byId[link.getAttribute("href").slice(1)] = link;
    });
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          Array.prototype.forEach.call(tocLinks, function (link) {
            link.style.color = "";
          });
          var link = byId[entry.target.id];
          if (link) link.style.color = "var(--gold)";
        });
      },
      { rootMargin: "-20% 0px -70% 0px", threshold: 0 }
    );
    Array.prototype.forEach.call(headings, function (heading) {
      observer.observe(heading);
    });
  }

  /* ------------------------------------------------------- прогресс чтения */

  var progress = document.getElementById("readProgress");
  if (progress) {
    var updateProgress = function () {
      var doc = document.documentElement;
      var max = doc.scrollHeight - window.innerHeight;
      var value = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
      progress.style.transform = "scaleX(" + value.toFixed(4) + ")";
    };
    updateProgress();
    window.addEventListener("scroll", updateProgress, { passive: true });
    window.addEventListener("resize", updateProgress);
  }
})();
