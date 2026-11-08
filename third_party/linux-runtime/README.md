# Embedded Linux components

W Agent launches unmodified PRoot as a separate process; the Java app does not link to its libraries. PRoot and its loader are installed by Android from APK `jniLibs`, not executed from writable app data. Alpine runs inside PRoot with W Agent's UID. No external Termux installation is used.

Build inputs are pinned by SHA-256 in `packages.json`. Run `node tools/prepare-linux.cjs` before Gradle. The script downloads official Termux PRoot 5.1.107.102, libtalloc 2.5.0, libandroid-shmem 0.7, and official Alpine 3.22.8 minirootfs for arm64/x86_64. Termux binaries are relocated through PRoot loader/environment settings and a talloc SONAME symlink; their contents are unchanged. The wrapper `pkg` delegates to Alpine's actual `apk`, with Alpine package names. This is not the Termux app or its package repository.

- PRoot: STMicroelectronics and contributors, GPL-2.0-or-later; `proot-COPYING`.
- talloc: Samba contributors, LGPL-3.0-or-later; `talloc-LICENSE` (upstream library license; Termux's recipe label is broader).
- libandroid-shmem: Termux contributors, BSD-3-Clause; `shmem-LICENSE`.
- Alpine: independently licensed components, versions/licenses recorded in `/lib/apk/db/installed`. Includes BusyBox, apk-tools, musl, OpenSSL, zlib, scanelf, base configuration, keys and CA certificates. Exact source-recipe commits are in `alpine-origins.json`.

The app's MIT license covers W Agent code, not these independently licensed components. Source preparation and redistribution instructions are provided with the runtime source bundle. Packages the user later installs retain their own licenses.

`node tools/prepare-linux-sources.cjs` creates `W-Agent-Linux-Sources.tar.gz`, published alongside APKs as the **W-Agent-Linux-Sources** CI artifact and GitHub Release asset. It includes upstream source archives, all pinned Alpine recipes, configuration and patches, and the Termux package build environment at commit `69d4af0de4b6f2b2e738a9618ab957b48bc6de0d`. Downloads are checked against upstream SHA-512 or pinned SHA-256/Git blob hashes. Follow the included Termux build scripts for Android/NDK compilation and Alpine APKBUILD recipes for guest packages; the Java app does not modify those binaries.
