const { defineConfig } = require("@playwright/test");
module.exports = defineConfig({
  testDir: "./browser",
  timeout: 60000,
  workers: 2,
  reporter: process.env.CI ? [["list"], ["github"]] : "list",
  use: {
    baseURL: "http://127.0.0.1:4173",
    browserName: "chromium",
    launchOptions: process.env.WIKI_CHROMIUM
      ? {
          executablePath: process.env.WIKI_CHROMIUM,
          args: ["--no-sandbox", "--disable-dev-shm-usage"],
        }
      : {},
  },
  projects: [
    { name: "desktop", use: { viewport: { width: 1440, height: 1000 } } },
    {
      name: "mobile",
      use: {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
  webServer: {
    command:
      "python3 -m http.server 4173 --bind 0.0.0.0 --directory ../../site",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: !process.env.CI,
  },
});
