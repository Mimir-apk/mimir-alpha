"""Architectural guards for shared UI ownership; no Android runtime is required."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

class SharedUiBoundaryTest(unittest.TestCase):
    def test_library_does_not_depend_on_settings(self):
        script = (ROOT / 'feature-library/build.gradle.kts').read_text(encoding='utf-8')
        self.assertNotRegex(script, r'project\(\s*":feature-settings"\s*\)')
        offenders = [str(p.relative_to(ROOT)) for p in (ROOT / 'feature-library/src').rglob('*.kt')
                     if 'com.gruning.mimir.rewrite.feature.settings' in p.read_text(encoding='utf-8')]
        self.assertEqual([], offenders)

    def test_core_ui_has_no_feature_dependencies(self):
        self.assertNotRegex((ROOT / 'core-ui/build.gradle.kts').read_text(encoding='utf-8'),
                            r'project\(\s*":feature-')
        offenders = [str(p.relative_to(ROOT)) for p in (ROOT / 'core-ui/src/main').rglob('*.kt')
                     if re.search(r'^import .*\.feature\.', p.read_text(encoding='utf-8'), re.MULTILINE)]
        self.assertEqual([], offenders)

if __name__ == '__main__':
    unittest.main()
