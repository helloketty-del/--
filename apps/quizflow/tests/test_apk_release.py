import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('release', ROOT / 'scripts/verify_apk_release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseGuardTest(unittest.TestCase):
    def test_only_the_explicit_prod_variant_is_accepted(self):
        metadata = {'variantName': 'devDebug', 'applicationId': 'com.futureyyy.tijing.dev',
                    'elements': [{'outputFile': 'app-dev-debug.apk', 'versionName': '0.5.2', 'versionCode': 11}]}
        with self.assertRaisesRegex(ValueError, 'prodDebug'):
            release.check_metadata(metadata, '0.5.2', 11)

    def test_version_in_metadata_must_match_the_release(self):
        metadata = {'variantName': 'prodDebug', 'applicationId': 'com.futureyyy.tijing',
                    'elements': [{'outputFile': 'app-prod-debug.apk', 'versionName': '0.4.4', 'versionCode': 10}]}
        with self.assertRaisesRegex(ValueError, 'version'):
            release.check_metadata(metadata, '0.5.2', 11)

    def test_metadata_selects_the_prod_apk(self):
        metadata = {'variantName': 'prodDebug', 'applicationId': 'com.futureyyy.tijing',
                    'elements': [{'outputFile': 'app-prod-debug.apk', 'versionName': '0.5.2', 'versionCode': 11}]}
        self.assertEqual('app-prod-debug.apk', release.check_metadata(metadata, '0.5.2', 11))

    def test_real_misdelivered_apk_is_missing_the_second_prompt(self):
        old = ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk'
        if not old.exists():
            self.skipTest('historical APK not present')
        with self.assertRaisesRegex(ValueError, 'questionBankPrompt'):
            release.check_feature_markers(old)

    def test_september_baseline_contains_both_prompts(self):
        baseline = ROOT / 'outputs/TiJing-0.5.1-prod-debug.apk'
        if not baseline.exists():
            self.skipTest('baseline APK not present')
        release.check_feature_markers(baseline)

    def test_modified_or_extra_assets_fail_the_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dist = root / 'dist'
            dist.mkdir()
            (dist / 'index.html').write_text('new')
            apk = root / 'sample.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('assets/web/index.html', 'old')
            with self.assertRaisesRegex(ValueError, 'assets'):
                release.check_web_assets(apk, dist)
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('assets/web/index.html', 'new')
                z.writestr('assets/web/stale.js', 'old')
            with self.assertRaisesRegex(ValueError, 'assets'):
                release.check_web_assets(apk, dist)


if __name__ == '__main__':
    unittest.main()
