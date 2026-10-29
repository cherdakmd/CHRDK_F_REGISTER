const { test } = require("node:test");
const assert = require("node:assert/strict");
const { normalize, safePageUrl, rankPages } = require("./assets/app.js");
const pages = [
  {
    title: "Магазин",
    url: "shop.html",
    group: "Алмазы",
    headings: ["Блоки"],
    text: "Начало. " + "слово ".repeat(1000) + "purple_concrete и терракота",
  },
  {
    title: "Территории",
    url: "claims.html",
    group: "Мир",
    headings: ["Аренда"],
    text: "Аренда чанка и ежедневная оплата",
  },
  {
    title: "Чужой сайт",
    url: "javascript:alert(1)",
    group: "",
    headings: [],
    text: "аренда",
  },
];
test("поиск работает с ё и несколькими словами", () => {
  assert.equal(normalize("ЁЖ"), "еж");
  assert.equal(rankPages(pages, "чанка аренда")[0].page.url, "claims.html");
  assert.equal(rankPages(pages, "чанка железо").length, 0);
});
test("индекс не обрезается до первого абзаца", () =>
  assert.equal(rankPages(pages, "purple_concrete")[0].page.url, "shop.html"));
test("URL результатов ограничены страницами сайта", () => {
  for (const url of [
    "javascript:alert(1)",
    "https://example.com",
    "//example.com",
    "../index.html",
    "shop.html?x=y",
    'shop.html" onclick="x',
  ])
    assert.equal(safePageUrl(url), false);
  assert.equal(safePageUrl("shop.html#catalog"), true);
  assert.equal(rankPages(pages, "аренда").length, 1);
});
test("пустой запрос не выдаёт весь индекс", () =>
  assert.deepEqual(rankPages(pages, "   "), []));
test("пользовательский ввод не становится регулярным выражением ранжирования", () =>
  assert.deepEqual(rankPages(pages, "[<script>.*"), []));
