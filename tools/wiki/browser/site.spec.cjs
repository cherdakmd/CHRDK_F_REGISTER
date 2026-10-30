const { test, expect } = require("@playwright/test");
const { default: AxeBuilder } = require("@axe-core/playwright");
const fs = require("node:fs");
const path = require("node:path");
const site = path.resolve(__dirname, "../../../site");

test("все страницы открываются без ошибок и горизонтального сдвига", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (err) => errors.push(err.message));
  page.on("response", (r) => {
    if (r.status() >= 400) errors.push(r.url() + ": " + r.status());
  });
  for (const name of fs.readdirSync(site).filter((p) => p.endsWith(".html"))) {
    await page.goto("/" + name);
    await expect(page.locator("h1")).toHaveCount(1);
    await expect(page.locator("main")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      name,
    ).toBeTruthy();
  }
  expect(errors).toEqual([]);
});

test("темы сохраняются, основные страницы проходят WCAG AA", async ({
  page,
}) => {
  for (const name of ["index", "shop", "start"]) {
    await page.goto("/" + name + ".html");
    for (const theme of ["dark", "light"]) {
      if ((await page.locator("html").getAttribute("data-theme")) !== theme)
        await page.locator("#themeBtn").click();
      const result = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();
      expect(
        result.violations.map((v) => ({
          id: v.id,
          nodes: v.nodes.map((n) => n.target),
        })),
      ).toEqual([]);
    }
  }
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});

test("поиск, клавиатура, обработка пустого и небезопасного запроса", async ({
  page,
}) => {
  await page.goto("/index.html");
  await page.locator("#searchTrigger").click();
  await expect(page.locator("#searchInput")).toBeFocused();
  await page.locator("#searchInput").fill("purple_concrete");
  await expect(page.locator(".search-hit").first()).toHaveAttribute(
    "href",
    "shop.html",
  );
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/shop.html$/);
  await page.keyboard.press("Control+k");
  await page
    .locator("#searchInput")
    .fill('<img src=x onerror="window.injected=1">');
  await expect(page.locator("#searchResults")).toContainText(
    "Ничего не найдено",
  );
  expect(await page.evaluate(() => window.injected)).toBeUndefined();
  await page.locator("#searchInput").fill("аренда");
  await expect(page.locator(".search-hit").first()).toBeVisible();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Escape");
  await expect(page.locator("#searchModal")).not.toBeVisible();
  await expect(page.locator("#searchTrigger")).toBeFocused();
});

test("ошибка загрузки поиска даёт понятный выход", async ({ page }) => {
  await page.route("**/search-index.json*", (route) => route.abort());
  await page.goto("/index.html");
  await page.locator("#searchTrigger").click();
  await expect(page.locator("#searchResults")).toContainText(
    "Поиск не загрузился",
  );
  await page.locator("#searchClose").click();
  await expect(page.locator("#searchTrigger")).toBeFocused();
});

test("навигация на телефоне не оставляет скрытые ссылки в фокусе", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/index.html");
  expect(await page.locator("#sidebar").evaluate((n) => n.inert)).toBeTruthy();
  await page.locator("#menuBtn").click();
  await expect(page.locator("#menuBtn")).toHaveAttribute(
    "aria-expanded",
    "true",
  );
  expect(await page.locator("#sidebar").evaluate((n) => n.inert)).toBeFalsy();
  await page.keyboard.press("Escape");
  await expect(page.locator("#menuBtn")).toHaveAttribute(
    "aria-expanded",
    "false",
  );
  await page.locator("#menuBtn").click();
  await page.locator('#sidebar a[href="claims.html"]').click();
  await expect(page).toHaveURL(/claims.html$/);
  expect(await page.locator("#sidebar").evaluate((n) => n.inert)).toBeTruthy();
});

test("копирование команды доступно и с клавиатуры", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      value: {
        writeText: async (text) => {
          window.copied = text;
        },
      },
    });
  });
  await page.goto("/start.html");
  const command = page
    .locator(".page code")
    .filter({ hasText: "/register" })
    .first();
  await command.focus();
  await command.press("Enter");
  expect(await page.evaluate(() => window.copied)).toContain("/register");
  await expect(page.locator("#toast")).toContainText("скопирована");
});

test("содержание и ссылки работают без JavaScript", async ({ browser }) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 390, height: 844 },
  });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:4173/index.html");
  await expect(page.locator('#sidebar a[href="start.html"]')).toBeVisible();
  await page.locator('#sidebar a[href="start.html"]').click();
  await expect(page.locator("h1")).toHaveText("Первый вход");
  await context.close();
});

// Минимальная ширина reflow проверяется отдельно от основного мобильного проекта.
test("узкий экран 320 px не растягивает страницу", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await page.goto("/index.html");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
});

test("повреждённый поисковый индекс не ломает диалог", async ({ page }) => {
  await page.route("**/search-index.json*", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: '[{"url":"javascript:alert(1)","title":"bad"}]',
    }),
  );
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/index.html");
  await page.locator("#searchTrigger").click();
  await expect(page.locator("#searchResults")).toContainText(
    "Поиск не загрузился",
  );
  expect(errors).toEqual([]);
});

// Каталог с тарифом покупки шире экрана: прокрутка должна работать без мыши.
test("широкий каталог прокручивается с клавиатуры", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/shop.html");
  const wide = page.locator(".table-wrap").last();
  await expect(wide).toHaveAttribute("tabindex", "0");
  await expect(wide).toHaveAttribute("role", "group");
  await wide.focus();
  await expect(wide).toBeFocused();
  expect(await wide.evaluate(n => n.scrollWidth > n.clientWidth)).toBeTruthy();
  await page.keyboard.press("ArrowRight");
  await expect.poll(() => wide.evaluate(n => n.scrollLeft)).toBeGreaterThan(0);
});
