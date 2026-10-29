"""Verify the actual prod APK before publishing; never select by filename date."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ('questionBankPrompt', 'question_bank', '视觉解题提示词', '题库匹配提示词',
            '保存提示词', '测试连接', '自动识别当前题目页', 'retryCount', 'vision_fallback')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def check_metadata(metadata, version, code):
    if metadata.get('variantName') != 'prodDebug' or metadata.get('applicationId') != 'com.futureyyy.tijing':
        raise ValueError('Expected prodDebug / com.futureyyy.tijing')
    elements = metadata.get('elements', [])
    if len(elements) != 1:
        raise ValueError('Expected one APK output')
    element = elements[0]
    if element.get('versionName') != version or element.get('versionCode') != code:
        raise ValueError('APK metadata version does not match the source release version')
    if element.get('outputFile') != 'app-prod-debug.apk':
        raise ValueError('Unexpected prodDebug output filename')
    return element['outputFile']


def check_feature_markers(apk):
    with zipfile.ZipFile(apk) as z:
        bundle = '\n'.join(z.read(name).decode('utf-8') for name in z.namelist()
                           if name.startswith('assets/web/') and name.endswith('.js'))
        missing = [marker for marker in FEATURES if marker not in bundle]
        if missing:
            raise ValueError('Missing frontend features: ' + ', '.join(missing))
        if 'assets/question-bank.json' not in z.namelist():
            raise ValueError('Missing bundled question bank')


def check_web_assets(apk, dist):
    expected = {str(f.relative_to(dist)).replace(os.sep, '/'): sha256(f.read_bytes())
                for f in dist.rglob('*') if f.is_file()}
    with zipfile.ZipFile(apk) as z:
        actual = {name[len('assets/web/'):]: sha256(z.read(name)) for name in z.namelist()
                  if name.startswith('assets/web/') and not name.endswith('/')}
    if not expected or actual != expected:
        raise ValueError('APK web assets differ from the current dist assets')
    return actual


def run_tool(tool, *args):
    env = dict(os.environ)
    java = ROOT / '.tooling/jdk/usr/lib/jvm/java-21-openjdk-amd64'
    if java.exists():
        env['JAVA_HOME'] = str(java)
        env['PATH'] = str(java / 'bin') + os.pathsep + env.get('PATH', '')
    return subprocess.check_output([str(tool), *map(str, args)], env=env, text=True,
                                   stderr=subprocess.STDOUT, timeout=90)


def identity(apk, tools):
    data = run_tool(tools / 'aapt', 'dump', 'badging', apk)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", data)
    if not match:
        raise ValueError('APK manifest identity was not found')
    cert = run_tool(tools / 'apksigner', 'verify', '--print-certs', apk)
    digest = re.search(r'certificate SHA-256 digest: ([a-fA-F0-9]+)', cert)
    if not digest:
        raise ValueError('APK signer was not found')
    return {'applicationId': match[1], 'versionCode': int(match[2]), 'versionName': match[3],
            'certificateSha256': digest[1].lower()}


def app_classes(apk):
    with zipfile.ZipFile(apk) as z:
        return {s.decode('ascii') for name in z.namelist() if re.fullmatch(r'classes\d*\.dex', name)
                for s in re.findall(rb'Lcom/futureyyy/tijing/[A-Za-z0-9_$/]+;', z.read(name))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, help='Validate an explicit APK instead of the prod metadata selection')
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    version = json.loads((ROOT / 'package.json').read_text())['version']
    gradle = (ROOT / 'android/app/build.gradle.kts').read_text()
    code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle)[1])
    gradle_version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
    if gradle_version != version:
        raise ValueError('package.json / Gradle version mismatch')
    output_dir = ROOT / 'android/app/build/outputs/apk/prod/debug'
    metadata = json.loads((output_dir / 'output-metadata.json').read_text())
    candidate = args.candidate or output_dir / check_metadata(metadata, version, code)
    candidate = candidate.resolve()
    baseline = ROOT / 'outputs/TiJing-0.5.1-prod-debug.apk'
    tools = ROOT / '.tooling/android-sdk/build-tools/35.0.0'
    current = identity(candidate, tools)
    previous = identity(baseline, tools)
    if (current['applicationId'] != 'com.futureyyy.tijing' or current['versionName'] != version
            or current['versionCode'] != code):
        raise ValueError(f'Actual APK manifest version/identity mismatch: {current}')
    if code <= previous['versionCode']:
        raise ValueError('versionCode must increase above the verified baseline')
    if current['certificateSha256'] != previous['certificateSha256']:
        raise ValueError('Signing certificate differs from baseline; do not publish')
    check_feature_markers(candidate)
    assets = check_web_assets(candidate, ROOT / 'dist')
    with zipfile.ZipFile(candidate) as new, zipfile.ZipFile(baseline) as old:
        bank_hash = sha256(new.read('assets/question-bank.json'))
        if bank_hash != sha256(old.read('assets/question-bank.json')):
            raise ValueError('Question bank changed from baseline; review required')
        old_assets = {n: sha256(old.read(n)) for n in old.namelist()
                      if n.startswith('assets/web/') and not n.endswith('/')}
        new_assets = {n: sha256(new.read(n)) for n in new.namelist()
                      if n.startswith('assets/web/') and not n.endswith('/')}
    missing_classes = app_classes(baseline) - app_classes(candidate)
    if missing_classes:
        raise ValueError('Baseline application classes missing: ' + ', '.join(sorted(missing_classes)))
    sources = [ROOT / 'android/app/build.gradle.kts', ROOT / 'package.json']
    for directory in ('android/app/src', 'src', 'dist'):
        sources.extend(f for f in (ROOT / directory).rglob('*') if f.is_file())
    if candidate.stat().st_mtime < max(f.stat().st_mtime for f in sources):
        raise ValueError('APK predates a source or asset modification; rebuild first')
    digest = sha256(candidate.read_bytes())
    report = {'verifiedAt': datetime.now(timezone.utc).isoformat(), 'sourceApk': str(candidate),
              **current, 'sha256': digest, 'bytes': candidate.stat().st_size,
              'baselineApk': str(baseline), 'baselineSha256': sha256(baseline.read_bytes()),
              'baselineFrontendByteIdentical': old_assets == new_assets,
              'baselineApplicationClassesPreserved': True, 'questionBankSha256': bank_hash,
              'verifiedFeatures': list(FEATURES), 'distAssetHashes': assets,
              'deviceInstallTest': 'not_run'}
    if not args.check_only:
        target = ROOT / 'outputs' / f'TiJing-{version}-prod-debug.apk'
        if target.exists():
            raise ValueError(f'Output already exists; preserve it and choose a new version: {target}')
        shutil.copy2(candidate, target)
        if sha256(target.read_bytes()) != digest:
            raise ValueError('Published APK hash mismatch')
        report['publishedApk'] = str(target)
        target.with_suffix('.verification.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
        target.with_suffix('.apk.sha256').write_text(f'{digest}  {target.name}\n')
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
