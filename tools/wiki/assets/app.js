/* CHRDK: поиск, навигация и копирование. Никакой разметки из поискового запроса. */
(function () {
  "use strict";
  function normalize(text) {
    return String(text).toLocaleLowerCase("ru").replace(/ё/g, "е");
  }
  function safePageUrl(url) {
    return /^[a-z0-9-]+\.html(?:#[a-z0-9_-]+)?$/.test(url);
  }
  function rankPages(pages, query) {
    const words = normalize(query).trim().split(/\s+/).filter(Boolean);
    if (!words.length) return [];
    return pages
      .filter((p) => safePageUrl(p.url))
      .map((p) => {
        const title = normalize(p.title),
          heads = normalize((p.headings || []).join(" "));
        const all = normalize(
          [p.title, p.group, (p.headings || []).join(" "), p.text].join(" "),
        );
        if (!words.every((w) => all.includes(w))) return null;
        const score = words.reduce(
          (sum, w) =>
            sum +
            (title.includes(w) ? 20 : 0) +
            (heads.includes(w) ? 7 : 0) +
            1,
          0,
        );
        return { page: p, score };
      })
      .filter(Boolean)
      .sort(
        (a, b) =>
          b.score - a.score || a.page.title.localeCompare(b.page.title, "ru"),
      );
  }
  if (typeof module !== "undefined" && module.exports)
    module.exports = { normalize, safePageUrl, rankPages };
  if (typeof document === "undefined") return;

  const $ = (id) => document.getElementById(id);
  const theme = $("themeBtn");
  function paintTheme() {
    theme.setAttribute(
      "aria-label",
      document.documentElement.dataset.theme === "light"
        ? "Включить тёмную тему"
        : "Включить светлую тему",
    );
  }
  theme.addEventListener("click", () => {
    const next =
      document.documentElement.dataset.theme === "light" ? "dark" : "light";
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem("chrdk-wiki-theme", next);
    } catch (_) {
      /* Без сохранения в приватном режиме. */
    }
    paintTheme();
  });
  paintTheme();

  const menu = $("menuBtn"),
    sidebar = $("sidebar"),
    mobile = matchMedia("(max-width:850px)");
  function closeNav() {
    document.body.classList.remove("nav-open");
    menu.setAttribute("aria-expanded", "false");
    if (mobile.matches && sidebar.contains(document.activeElement))
      menu.focus();
    sidebar.inert = mobile.matches;
  }
  menu.addEventListener("click", () => {
    const open = document.body.classList.toggle("nav-open");
    menu.setAttribute("aria-expanded", String(open));
    sidebar.inert = !open && mobile.matches;
  });
  mobile.addEventListener("change", closeNav);
  closeNav();
  sidebar.addEventListener("click", (event) => {
    if (event.target.closest("a")) closeNav();
  });
  document.addEventListener("click", (event) => {
    if (!sidebar.contains(event.target) && !menu.contains(event.target))
      closeNav();
  });

  let toastTimer;
  function toast(text) {
    const node = $("toast");
    node.textContent = text;
    node.classList.add("is-visible");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => node.classList.remove("is-visible"), 2200);
  }
  document.querySelectorAll(".page code").forEach((code) => {
    const text = code.textContent.trim();
    if (!text.startsWith("/")) return;
    code.classList.add("cmd");
    code.tabIndex = 0;
    code.setAttribute("role", "button");
    code.setAttribute("aria-label", "Скопировать команду " + text);
    async function copy() {
      try {
        await navigator.clipboard.writeText(text);
        toast("Команда скопирована. Замените параметры перед вводом.");
      } catch (_) {
        toast("Выделите и скопируйте команду вручную.");
      }
    }
    code.addEventListener("click", copy);
    code.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        copy();
      }
    });
  });

  const modal = $("searchModal"),
    input = $("searchInput"),
    results = $("searchResults"),
    trigger = $("searchTrigger");
  let promise,
    index = [],
    failed = false,
    selection = 0,
    hits = [],
    previousFocus;
  async function load() {
    if (!promise)
      promise = fetch(
        "search-index.json?v=" +
          encodeURIComponent(document.body.dataset.revision),
        { cache: "no-cache" },
      )
        .then((response) => {
          if (!response.ok) throw new Error("search");
          return response.json();
        })
        .then((data) => {
          if (
            !Array.isArray(data) ||
            !data.every(
              (p) =>
                p &&
                safePageUrl(p.url) &&
                typeof p.title === "string" &&
                typeof p.text === "string" &&
                Array.isArray(p.headings) &&
                p.headings.every((h) => typeof h === "string"),
            )
          )
            throw new Error("index");
          index = data;
          failed = false;
        })
        .catch(() => {
          failed = true;
          promise = null;
        });
    await promise;
  }
  function message(text) {
    const p = document.createElement("p");
    p.className = "search-empty";
    p.textContent = text;
    results.replaceChildren(p);
  }
  function highlight(node, text, words) {
    const escaped = words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    if (!escaped.length) {
      node.textContent = text;
      return;
    }
    const pattern = new RegExp(escaped.join("|"), "gi");
    let start = 0;
    for (const match of text.matchAll(pattern)) {
      node.append(document.createTextNode(text.slice(start, match.index)));
      const mark = document.createElement("mark");
      mark.textContent = match[0];
      node.append(mark);
      start = match.index + match[0].length;
    }
    node.append(document.createTextNode(text.slice(start)));
  }
  function choose(index) {
    selection = Math.max(0, Math.min(index, hits.length - 1));
    results
      .querySelectorAll(".search-hit")
      .forEach((node, i) =>
        node.classList.toggle("is-selected", i === selection),
      );
    if (hits.length)
      results.children[selection].scrollIntoView({ block: "nearest" });
  }
  function render() {
    const query = input.value.trim();
    selection = 0;
    if (failed) {
      hits = [];
      message(
        "Поиск не загрузился. Откройте нужный раздел слева или попробуйте поиск снова.",
      );
      return;
    }
    if (query.length < 2) {
      hits = [];
      message(
        "Введите хотя бы два символа. Ищем по всем текстам, командам и материалам.",
      );
      return;
    }
    hits = rankPages(index, query)
      .slice(0, 24)
      .map((hit) => hit.page);
    results.replaceChildren();
    if (!hits.length) {
      message(
        "Ничего не найдено. Попробуйте другое слово или английский ID материала.",
      );
      return;
    }
    const words = query.split(/\s+/).filter(Boolean);
    hits.forEach((page) => {
      const link = document.createElement("a");
      link.href = page.url;
      link.className = "search-hit";
      const group = document.createElement("small");
      group.textContent = page.group;
      const title = document.createElement("strong");
      highlight(title, page.title, words);
      const text = String(page.text || ""),
        at = normalize(text).indexOf(normalize(words[0])),
        start = Math.max(0, at - 45);
      const preview = document.createElement("p");
      highlight(
        preview,
        (start ? "…" : "") + text.slice(start, start + 180),
        words,
      );
      link.append(group, title, preview);
      results.append(link);
    });
    choose(0);
  }
  async function openSearch() {
    if (modal.open) return;
    previousFocus = document.activeElement;
    closeNav();
    modal.showModal();
    input.focus();
    message("Загружаем справочник…");
    await load();
    if (modal.open) render();
  }
  trigger.addEventListener("click", openSearch);
  $("searchClose").addEventListener("click", () => modal.close());
  modal.addEventListener("close", () => {
    if (
      previousFocus &&
      previousFocus !== document.body &&
      previousFocus.isConnected &&
      !previousFocus.closest("[inert]")
    )
      previousFocus.focus();
    else trigger.focus();
  });
  modal.addEventListener("click", (event) => {
    const r = modal.getBoundingClientRect();
    if (
      event.target === modal &&
      (event.clientX < r.left ||
        event.clientX > r.right ||
        event.clientY < r.top ||
        event.clientY > r.bottom)
    )
      modal.close();
  });
  input.addEventListener("input", render);
  input.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      choose(selection + (event.key === "ArrowDown" ? 1 : -1));
    }
    if (event.key === "Enter" && hits[selection]) {
      event.preventDefault();
      location.assign(hits[selection].url);
    }
  });
  document.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      openSearch();
    }
    if (event.key === "Escape") {
      if (modal.open) {
        event.preventDefault();
        modal.close();
      } else closeNav();
    }
  });
})();
