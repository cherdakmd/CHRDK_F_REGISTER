"""The release guard must reject missing, duplicate, broken and mixed-version JARs."""
from pathlib import Path
import tempfile
import unittest
import zipfile
from verify_release import expected_plugins, standalone_plugins, verify, verify_standalone_jar


class ReleaseValidationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        (self.directory / "ОБНОВЛЕНИЕ.txt").write_text("Порядок обновления", encoding="utf-8")

    def jar(self, filename, name="Core", version="1.4.189", descriptor=True):
        with zipfile.ZipFile(self.directory / filename, "w") as archive:
            if descriptor:
                archive.writestr("plugin.yml", f"name: '{name}'\nversion: {version}\n")
            archive.writestr("dummy.class", b"test fixture")

    def test_complete_release(self):
        self.jar("core.jar")
        self.jar("addon.jar", "Addon")
        self.assertEqual({"Core", "Addon"}, set(verify(self.directory, "1.4.189", {"Core", "Addon"})))

    def test_missing_or_unexpected_module(self):
        self.jar("core.jar")
        for expected in ({"Core", "Addon"}, {"Addon"}):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                verify(self.directory, "1.4.189", expected)

    def test_mixed_versions(self):
        self.jar("core.jar", version="1.4.188")
        with self.assertRaisesRegex(ValueError, "version"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_duplicate_plugin(self):
        self.jar("core.jar")
        self.jar("copy.jar")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_descriptor_required(self):
        self.jar("core.jar", descriptor=False)
        with self.assertRaisesRegex(ValueError, "plugin.yml"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_broken_jar(self):
        (self.directory / "core.jar").write_bytes(b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            verify(self.directory, "1.4.189", {"Core"})

    def test_memo_required(self):
        self.jar("core.jar")
        (self.directory / "ОБНОВЛЕНИЕ.txt").unlink()
        with self.assertRaisesRegex(ValueError, "ОБНОВЛЕНИЕ"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_snapshot_is_not_a_stable_release(self):
        self.jar("core.jar")
        with self.assertRaises(ValueError):
            verify(self.directory, "1.4.189-SNAPSHOT", {"Core"})

    def test_standalone_jar_validation(self):
        self.jar("chrdk-vk-1.4.268.jar", name="CHRDK_VK", version="1.4.268")
        self.assertEqual(
            "chrdk-vk-1.4.268.jar",
            verify_standalone_jar(self.directory / "chrdk-vk-1.4.268.jar", "CHRDK_VK", "1.4.268"),
        )
        with self.assertRaisesRegex(ValueError, "name"):
            verify_standalone_jar(self.directory / "chrdk-vk-1.4.268.jar", "CHRDK_Core", "1.4.268")
        with self.assertRaisesRegex(ValueError, "version"):
            verify_standalone_jar(self.directory / "chrdk-vk-1.4.268.jar", "CHRDK_VK", "1.4.267")

    def test_current_update_memo_matches_version_and_distribution(self):
        import re
        root = Path(__file__).resolve().parents[2]
        version = re.search(r"^plugin_version=(.+)$", (root / "gradle.properties").read_text(), re.M).group(1)
        memo = (root / "docs/releases/update-memo.txt").read_text(encoding="utf-8")
        current = memo.split("Ниже — предыдущие изменения", 1)[0]
        self.assertIn(f"Комплект {version}", current)
        # v1.5.2-rc.2 уже опубликован: его ZIP физически содержит 22 JAR.
        # Модуль disasters добавлен в ветку после этого пререлиза и войдёт
        # только в следующий комплект, поэтому историческую памятку не трогаем.
        # После bump plugin_version эта оговорка перестаёт действовать и тест
        # снова требует полный набор модулей из settings.gradle.
        module_prereleases = {"2.0.2-rc.1": "chrdk-diamond-shop", "2.0.2-rc.2": "chrdk-diamond-shop"}
        if version in module_prereleases:
            self.assertIn(f"только {module_prereleases[version]}-{version}.jar", current)
            self.assertIn("Общего ZIP нет", current)
            self.assertIn("остальные плагины остаются 2.0.1", current)
        else:
            published_kits = {"1.5.2-rc.2": 22}
            jars = published_kits.get(version, len(expected_plugins(root)))
            self.assertIn(f"{jars} JAR одной версии", current)
            self.assertIn("CHRDK_Queue", current)
            self.assertIn("plugins/update/", current)
            self.assertIn("вручную", current)
            self.assertIn(f"chrdk-vk-{version}.jar", current)

    def test_numbered_rc_archive_is_valid_but_arbitrary_suffixes_are_not(self):
        self.jar("core.jar", version="1.4.281-rc.1")
        self.assertEqual({"Core"}, set(verify(self.directory, "1.4.281-rc.1", {"Core"})))
        (self.directory / "core.jar").unlink()
        self.jar("fix.jar", version="1.5.2-fix")
        self.assertEqual({"Core"}, set(verify(self.directory, "1.5.2-fix", {"Core"})))
        for version in ("1.4.281-rc.0", "1.4.281-beta", "1.4.281-rc.x", "1.4.281-rc.-1"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                verify(self.directory, version, {"Core"})

    def test_rc_workflow_never_publishes_as_stable(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/release.yml").read_text()
        self.assertIn("RC can only be published as a GitHub prerelease", workflow)
        updater = (root / "src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java").read_text()
        self.assertIn("prerelease", updater)

    def test_source_modules_have_unique_names(self):
        root = Path(__file__).resolve().parents[2]
        names = expected_plugins(root)
        self.assertIn("CHRDK_Core", names)
        self.assertIn("CHRDK_BattlePass", names)
        self.assertNotIn("CHRDK_Professions", names)
        self.assertNotIn("CHRDK_Hunt", names)
        self.assertEqual(19, len(names))
        self.assertNotIn("CHRDK_VK", names, "CHRDK_VK публикуется отдельно, а не внутри общего ZIP")
        self.assertNotIn("CHRDK_Disasters", names, "CHRDK_Disasters публикуется отдельно, а не внутри общего ZIP")
        self.assertEqual({"CHRDK_VK": "vk", "CHRDK_Disasters": "disasters"}, standalone_plugins(root))
        self.assertNotIn("CHRDK_Bosses", names)
        self.assertNotIn("CHRDK_Treasures", names)
        self.assertNotIn("CHRDK_Streams", names)
        for retired in ("CHRDK_Events", "CHRDK_Lottery", "CHRDK_Party", "CHRDK_Referrals"):
            self.assertNotIn(retired, names)
        self.assertNotIn("CHRDK_Events", names)
        self.assertNotIn("CHRDK_Queue", names)


class ReleaseWorkflowContractTest(unittest.TestCase):
    def test_sixty_roadmap_criteria_are_separately_accounted_for(self):
        import re
        root = Path(__file__).resolve().parents[2]
        source = (root / "docs/roadmaps/mobs-enchants-60.md").read_text()
        for prefix, expected in (("I", 25), ("N", 35)):
            rows = re.findall(r"^\| (" + prefix + r"\d{2}) \|.*\| Реализовано \d+ \|$", source, re.M)
            self.assertEqual(len(set(rows)), expected)
            self.assertEqual(len(rows), expected)

    def test_release_and_prerelease_triggers_are_not_limited_to_major_one(self):
        root = Path(__file__).resolve().parents[2]
        release = (root / ".github/workflows/release.yml").read_text()
        prerelease = (root / ".github/workflows/prerelease-announce.yml").read_text()
        self.assertIn("startsWith(github.event.release.tag_name, 'v')", release)
        self.assertNotIn("startsWith(github.event.release.tag_name, 'v1.')", release)
        self.assertIn("tags: ['v*-rc.*']", prerelease)

    def test_diamondshop_rc_release_cannot_attach_full_bundle(self):
        root = Path(__file__).resolve().parents[2]
        release = (root / ".github/workflows/release.yml").read_text()
        self.assertIn("Pack DiamondShop-only prerelease", release)
        self.assertIn("chrdk-diamond-shop-${version}.jar", release)
        self.assertIn("if: !startsWith(steps.verify-version.outputs.version, '2.0.2-rc.')", release)
        attach = release.split("name: Attach verified release assets", 1)[1]
        self.assertIn('if [[ "${{ steps.verify-version.outputs.version }}" == 2.0.2-rc.* ]]', attach)
        self.assertIn('"${{ env.dshop_jar }}" "${{ env.dshop_checksums }}"', attach)

    def test_memo_is_external_so_run_step_stays_below_github_limit(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / ".github/workflows/release.yml").read_text()
        self.assertIn("cp docs/releases/update-memo.txt", source)
        import re
        notes = re.search(r"1\.4\.\{200,201,\{220\.\.(\d+)\}\}\.md", source)
        self.assertIsNotNone(notes, "Release должен включать подробные инструкции перехода")
        version = re.search(r"^plugin_version=(\d+)\.(\d+)(?:\.(\d+))?(?:-rc\.[1-9]\d*|-fix)?$", (root / "gradle.properties").read_text(), re.M)
        self.assertIsNotNone(version)
        major, minor = int(version.group(1)), int(version.group(2))
        patch = int(version.group(3) or 0)
        if (major, minor) == (1, 4):
            self.assertGreaterEqual(int(notes.group(1)), patch,
                                    "Инструкция текущего релиза должна войти в ZIP")
        else:
            # Минорный выпуск лежит вне жёсткого списка 1.4.200..289: workflow
            # обязан дописать его заметки в ОБНОВЛЕНИЕ.txt отдельно.
            self.assertIn('docs/releases/${version}.md', source,
                          "Заметки минорного выпуска должны входить в ZIP")
        self.assertIn("! -name 'chrdk-vk-*.jar'", source)
        self.assertIn("! -name 'chrdk-disasters-*.jar'", source)
        self.assertIn('unzip -t "chrdk-plugins-${version}.zip"', source)
        self.assertIn('python3 .github/scripts/pack_release.py release "$version" "chrdk-plugins-${version}.zip"', source)
        self.assertIn('--standalone "chrdk-vk-${version}.jar" CHRDK_VK "$version"', source)
        self.assertIn('--standalone "chrdk-disasters-${version}.jar" CHRDK_Disasters "$version"', source)
        self.assertNotIn("zip -j -9", source)
        self.assertNotIn("--zip", source)
        self.assertIn('"${{ env.zip }}" "${{ env.vk_jar }}" "${{ env.disasters_jar }}" "${{ env.datapack }}" "${{ env.checksums }}" --clobber', source)
        # GitHub limits each run script to 21000 characters. Avoid parsing YAML/dependencies.
        for section in source.split("run: |\n")[1:]:
            script = section.split("\n      - name:", 1)[0]
            self.assertLess(len(script), 21000)
        self.assertTrue((root / "docs/releases/update-memo.txt").read_text().startswith("CHRDK"))

    def test_release_has_dry_run_checksums_and_publish_gate(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / ".github/workflows/release.yml").read_text()
        self.assertIn("workflow_dispatch:", source)
        self.assertIn("CONFIRM_VERSION: ${{ inputs.version }}", source)
        self.assertIn('if [[ "$CONFIRM_VERSION" != "$plugin_version" ]]', source)
        self.assertIn("Dry-run only:", source)
        self.assertIn("if: github.event_name == 'workflow_dispatch'", source)
        self.assertIn("if: github.event_name == 'release'", source)
        self.assertIn('sha256sum -c "$checksums"', source)
        self.assertIn("chrdk-${version}-SHA256SUMS.txt", source)

    def test_all_archive_tasks_are_reproducible(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "build.gradle").read_text()
        self.assertIn("tasks.withType(AbstractArchiveTask).configureEach", source)
        self.assertIn("preserveFileTimestamps = false", source)
        self.assertIn("reproducibleFileOrder = true", source)


if __name__ == "__main__":
    unittest.main()
