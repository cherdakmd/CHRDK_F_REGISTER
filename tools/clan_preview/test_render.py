import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('preview',Path(__file__).with_name('render.py'))
preview=importlib.util.module_from_spec(spec);spec.loader.exec_module(preview)

class PreviewTest(unittest.TestCase):
    def test_source_hash_and_rgb_fixture(self):
        self.assertEqual(5,len(preview.load()['frames']))
    def test_screenshot_is_explicitly_preview_not_game_capture(self):
        image=preview.scene(preview.load())
        self.assertEqual((1440,1050),image.size)
        self.assertEqual('RGB',image.mode)
    def test_all_gif_frames_render(self):
        data=preview.load()
        for tick in range(48):
            self.assertEqual((1440,1050),preview.scene(data,tick,True).size)
