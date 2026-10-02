#!/usr/bin/env python3
"""Run the Android-independent presentation tests using an existing Gradle JDK toolchain.

This does not compile Compose, package an APK, or claim device coverage.
Usage: python scripts/check_android_presentation.py --gradle-lib /path/to/gradle/lib --out /tmp/check
"""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--gradle-lib', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
lib = args.gradle_lib.resolve()
args.out.mkdir(parents=True, exist_ok=True)
main = ROOT / 'android-app/app/src/main/java/com/koreainv/dashboard/ui/screens'
test = ROOT / 'android-app/app/src/test/java/com/koreainv/dashboard/ui/screens/ScreenRequestOwnerTest.kt'
classpath = ':'.join(str(p) for p in lib.glob('*.jar'))
subprocess.run(['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', classpath, '-d', str(args.out),
                str(main / 'ScreenRequestOwner.kt'), str(main / 'DashboardPresentationPolicy.kt'), str(test)], check=True)
subprocess.run(['java', '-cp', str(args.out) + ':' + classpath, 'org.junit.runner.JUnitCore',
                'com.koreainv.dashboard.ui.screens.ScreenRequestOwnerTest'], check=True)
