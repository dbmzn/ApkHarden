#!/usr/bin/env python3
"""Build standalone macOS image tools. Downloads are pinned and SHA-256 checked.

Requires Xcode command-line tools, Python 3 and CMake (or the pinned build-local
cmake Python package). Runtime compression never downloads tools or images.
"""
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path.home() / '.gradle/caches/apkharden/image-tools'
WORK = ROOT / 'build/image-tools-build'
OUT = ROOT / 'build/app-resources/macos/image-tools'
SOURCES = {
    'webp': ('https://codeload.github.com/webmproject/libwebp/tar.gz/refs/tags/v1.6.0', '93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136'),
    'zlib': ('https://codeload.github.com/madler/zlib/tar.gz/refs/tags/v1.3.1', '17e88863f3600672ab49182f217281b6fc4d3c762bde361935e436a95214d05c'),
    'pngquant': ('https://codeload.github.com/kornelski/pngquant/tar.gz/refs/tags/2.18.0', '424ff432e51dfc3cf5ff8001ad1b64198850686c5e3c26ecd477e4b69ef4fade'),
    'imagequant': ('https://codeload.github.com/ImageOptim/libimagequant/tar.gz/refs/tags/2.17.0', '9f6cc50182be4d2ece75118aa0b0fd3e9bbad06e94fd6b9eb3a4c08129c2dd26'),
    'png': ('https://codeload.github.com/pnggroup/libpng/tar.gz/refs/tags/v1.6.55', '71a2c5b1218f60c4c6d2f1954c7eb20132156cae90bdb90b566c24db002782a6'),
    'mozjpeg': ('https://codeload.github.com/mozilla/mozjpeg/tar.gz/refs/tags/v4.1.1', '66b1b8d6b55d263f35f27f55acaaa3234df2a401232de99b6d099e2bb0a9d196'),
    'oxipng-arm': ('https://github.com/oxipng/oxipng/releases/download/v10.2.1/oxipng-10.2.1-aarch64-apple-darwin.tar.gz', '7039fcfc78e8aa1ed2b57d848057a0296f082e92b3e1807ac65402d10d926764'),
    'oxipng-x64': ('https://github.com/oxipng/oxipng/releases/download/v10.2.1/oxipng-10.2.1-x86_64-apple-darwin.tar.gz', '111883bbe42b25e01cb1ca41f39f8094e4830b173f0e42be342ecc4ca48f3131'),
}

def run(*args, cwd=WORK):
    subprocess.run([str(x) for x in args], cwd=cwd, check=True)

def fetch(name):
    url, digest = SOURCES[name]
    path = CACHE / (name + '.tar.gz')
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError('SHA-256 mismatch: ' + name)
        path.write_bytes(data)
    return path

def unpack(name):
    target = WORK / name
    if target.exists():
        shutil.rmtree(target)
    target.mkdir()
    with tarfile.open(fetch(name)) as archive:
        for member in archive.getmembers():
            parts = Path(member.name).parts[1:]
            if not parts:
                continue
            if '..' in parts or member.issym() or member.islnk():
                raise RuntimeError('Unsafe archive entry: ' + member.name)
            member.name = str(Path(*parts))
            archive.extract(member, target)
    return target

def main():
    if platform.system() != 'Darwin':
        raise SystemExit('This build script targets macOS; see docs/image-tools.md for other hosts.')
    CACHE.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    signature = hashlib.sha256(Path(__file__).read_bytes()).hexdigest() + platform.machine()
    stamp = OUT / '.build-id'
    tools = ['pngquant', 'oxipng', 'cjpeg', 'djpeg', 'jpegtran', 'cwebp', 'dwebp']
    if stamp.exists() and stamp.read_text() == signature and all((OUT / t).is_file() for t in tools):
        print('Image tools already built')
        return
    cmake = shutil.which('cmake')
    if not cmake:
        local = ROOT / 'build/image-build-python'
        candidate = local / 'cmake/data/bin/cmake'
        if not candidate.exists():
            run('python3', '-m', 'pip', 'install', '--target', local, 'cmake==3.31.6')
        cmake = str(candidate)
    png = unpack('png')
    run(cmake, '-S', png, '-B', png / 'out', '-DPNG_SHARED=OFF', '-DPNG_TESTS=OFF', '-DPNG_TOOLS=OFF', '-DCMAKE_BUILD_TYPE=Release')
    run(cmake, '--build', png / 'out', '--parallel', '4')
    quant = unpack('pngquant')
    zlib = unpack('zlib')
    run(cmake, '-S', zlib, '-B', zlib / 'out', '-DCMAKE_BUILD_TYPE=Release')
    run(cmake, '--build', zlib / 'out', '--target', 'zlibstatic', '--parallel', '4')
    (quant / 'zlib').mkdir()
    for source in [zlib / 'out/libz.a', zlib / 'zlib.h', zlib / 'out/zconf.h']:
        shutil.copy2(source, quant / 'zlib' / source.name)
    for header in ['png.h', 'pngconf.h']:
        shutil.copy2(png / header, png / 'out' / header)
    iq = unpack('imagequant')
    shutil.copytree(iq, quant / 'lib', dirs_exist_ok=True)
    run('./configure', '--with-libpng=' + str(png / 'out'), '--disable-openmp', '--without-lcms2', cwd=quant)
    run('make', '-j4', cwd=quant)
    jpeg = unpack('mozjpeg')
    run(cmake, '-S', jpeg, '-B', jpeg / 'out', '-DENABLE_SHARED=OFF', '-DWITH_TURBOJPEG=OFF', '-DPNG_SUPPORTED=OFF', '-DWITH_SIMD=OFF', '-DCMAKE_BUILD_TYPE=Release')
    run(cmake, '--build', jpeg / 'out', '--parallel', '4')
    oxi = unpack('oxipng-arm' if platform.machine() == 'arm64' else 'oxipng-x64')
    shutil.copy2(quant / 'pngquant', OUT / 'pngquant')
    shutil.copy2(oxi / 'oxipng', OUT / 'oxipng')
    for name in ['cjpeg', 'djpeg', 'jpegtran']:
        shutil.copy2(jpeg / 'out' / (name + '-static'), OUT / name)
    webp = unpack('webp')
    run(cmake, '-S', webp, '-B', webp / 'out', '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF',
        '-DPNG_LIBRARY=' + str(png / 'out/libpng16.a'), '-DPNG_PNG_INCLUDE_DIR=' + str(png / 'out'),
        '-DCMAKE_DISABLE_FIND_PACKAGE_JPEG=TRUE', '-DCMAKE_DISABLE_FIND_PACKAGE_TIFF=TRUE',
        '-DWEBP_BUILD_ANIM_UTILS=OFF', '-DWEBP_BUILD_GIF2WEBP=OFF', '-DWEBP_BUILD_IMG2WEBP=OFF',
        '-DWEBP_BUILD_VWEBP=OFF', '-DWEBP_BUILD_WEBPMUX=OFF', '-DWEBP_BUILD_EXTRAS=OFF')
    run(cmake, '--build', webp / 'out', '--target', 'cwebp', 'dwebp', '--parallel', '4')
    for name in ['cwebp', 'dwebp']:
        shutil.copy2(webp / 'out' / name, OUT / name)
    # Standalone GPL tools retain their own license; ship exact corresponding sources.
    notices = OUT / 'licenses'
    notices.mkdir(exist_ok=True)
    for name, source in [('pngquant', quant), ('imagequant', iq), ('png', png), ('mozjpeg', jpeg), ('oxipng', oxi), ('zlib', zlib), ('webp', webp)]:
        for file in source.iterdir():
            if file.is_file() and any(s in file.name.lower() for s in ['license', 'copying', 'copyright', 'patents', 'authors']):
                shutil.copy2(file, notices / (name + '-' + file.name))
    for name in ['pngquant', 'imagequant', 'png', 'mozjpeg', 'zlib', 'webp']:
        shutil.copy2(fetch(name), notices / (name + '-source.tar.gz'))
    shutil.copy2(__file__, notices / 'prepare-image-tools.py')
    for name in tools:
        (OUT / name).chmod(0o755)
        run('codesign', '--force', '--sign', '-', OUT / name)
    stamp.write_text(signature)
    print('Prepared local image tools:', OUT)

if __name__ == '__main__':
    main()
