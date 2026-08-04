// M4-S05: First-launch atomic extraction of the bootstrap-aarch64.zip APK
// asset into /data/data/dev.warp.mobile/files/usr.
//
// Design (per ralplan §6 M4 #1 + M4-S05 acceptance criteria):
//   1. Read version.json (sidecar; tells us the expected sha256 of the zip)
//      from APK assets/warp/bootstrap/.
//   2. If $PREFIX/.bootstrap-version.json already exists with a matching
//      sha256, return early — already installed.
//   3. Read assets/warp/bootstrap/bootstrap-aarch64.zip from APK.
//   4. Verify its SHA-256 matches version.json's expected value.
//   5. Extract to <data_dir>/files/usr.tmp/ (NOT directly into usr/ — we
//      want kill-mid-extract recovery: a partially-populated usr.tmp/ on
//      next launch is safe to wipe, but a partially-populated usr/ would
//      look "installed" and silently break).
//   6. Apply SYMLINKS.txt sidecar (the format the Termux app extractor uses
//      — entries are `target←linkname` per build-bootstrap.sh, where ← is
//      U+2190; we recreate the symlinks via `std::os::unix::fs::symlink`).
//   7. Atomically rename usr.tmp/ → usr/ (rename(2) is atomic on tmpfs/ext4
//      same-fs). If a previous run left a stale usr/, remove it first.
//      Both paths live under /data/data/<app>/files/ so they're guaranteed
//      to be on the same filesystem.
//   8. Write <data_dir>/files/.bootstrap-version.json as the install marker
//      (so step 2 can short-circuit on subsequent launches).
//
// JNI export: `Java_dev_warp_mobile_NativeBridge_bootstrapInstall` returns
// an integer status code:
//     0  = success (or already installed)
//     1  = invalid AAssetManager / JNI invariant
//     2  = APK asset not found
//     3  = sha256 mismatch (zip corrupted in transit)
//     4  = filesystem I/O error during extraction
//     5  = malformed SYMLINKS.txt
//     6  = atomic rename failed
//
// Codex M4-S03 round-4 carry-forward (M4-S05 AC #7): post-extraction we
// expect `$PREFIX/bin/zsh -c 'echo $0'` to launch with `LD_LIBRARY_PATH`
// unset because build-bootstrap.sh ran patchelf --set-rpath on every ELF.
// The atomic-extract step here only needs to preserve the file content
// byte-for-byte; the runpath fix-up was done at zip-creation time.

use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
#[cfg(unix)]
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::Path;
use std::sync::Mutex;

use sha2::{Digest, Sha256};
use zip::ZipArchive;

/// Process-wide single-flight guard for `install_bootstrap`. Codex M4-S05
/// round-1+2 findings 3+1: Activity recreation (rotation, config change,
/// etc.) can spawn multiple `GlobalScope.launch` coroutines targeting the
/// same `usr.tmp/` + `usr/` paths. Without this guard, two overlapping
/// calls can delete or rename each other's staging tree.
///
/// Round-2 fix: previous round used AtomicBool + fire-and-forget early
/// return on contention, which yielded a false-positive `Ok(())` (JNI
/// status 0) for callers that didn't actually run install. During rotation
/// on first install, recreated Activity could show "Termux runtime
/// installed (0 ms)" Toast before `usr/` actually existed. Now we use a
/// `Mutex<()>` so concurrent callers BLOCK on the lock. By the time they
/// acquire it, the in-flight install has finished; they re-enter the
/// install_bootstrap body, the sha-pin check short-circuits, and they
/// return Ok(()) only AFTER the install is genuinely complete.
///
/// Status 0 contract restored: success means bootstrap is ready when
/// this function returns.
static INSTALL_LOCK: Mutex<()> = Mutex::new(());

#[cfg(target_os = "android")]
use std::ffi::CString;
#[cfg(target_os = "android")]
use std::path::PathBuf;

// Android-only: paths into the APK asset tree that AAssetManager_open consumes.
// Host tests don't reference these (they synthesize zips in-memory).
#[cfg(target_os = "android")]
const BOOTSTRAP_ZIP_ASSET: &str = "warp/bootstrap/bootstrap-aarch64.zip";
#[cfg(target_os = "android")]
const VERSION_JSON_ASSET: &str = "warp/bootstrap/version.json";
#[cfg(target_os = "android")]
const VERSION_PIN_FILENAME: &str = ".bootstrap-version.json";

/// Status codes returned to Java side via the JNI export.
#[repr(i32)]
#[derive(Debug, Clone, Copy)]
pub enum InstallStatus {
    Success = 0,
    InvalidAssetManager = 1,
    AssetNotFound = 2,
    Sha256Mismatch = 3,
    IoError = 4,
    MalformedSymlinks = 5,
    AtomicRenameFailed = 6,
}

/// Read a single APK asset by path into a Vec<u8> via Android's AAssetManager.
///
/// Safety: `asset_mgr` MUST be a valid AAssetManager pointer obtained from
/// the JNI side (typically `AAssetManager_fromJava(env, javaAssetManager)`).
#[cfg(target_os = "android")]
unsafe fn read_apk_asset(
    asset_mgr: *mut ndk_sys::AAssetManager,
    path: &str,
) -> io::Result<Vec<u8>> {
    let c_path = CString::new(path).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, "asset path contains NUL byte")
    })?;
    let asset = ndk_sys::AAssetManager_open(
        asset_mgr,
        c_path.as_ptr(),
        ndk_sys::AASSET_MODE_BUFFER as i32,
    );
    if asset.is_null() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            format!("AAssetManager_open returned NULL for: {}", path),
        ));
    }
    let len = ndk_sys::AAsset_getLength64(asset) as usize;
    let buf_ptr = ndk_sys::AAsset_getBuffer(asset);
    if buf_ptr.is_null() {
        ndk_sys::AAsset_close(asset);
        return Err(io::Error::new(
            io::ErrorKind::Other,
            format!("AAsset_getBuffer returned NULL for: {} (len={})", path, len),
        ));
    }
    let buf = std::slice::from_raw_parts(buf_ptr as *const u8, len).to_vec();
    ndk_sys::AAsset_close(asset);
    Ok(buf)
}

fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<String>()
}

/// Parse the `sha256` field out of version.json without pulling in serde_derive.
/// version.json schema (from M4-S04 generateBootstrapVersion): it's a flat
/// JSON object with at least a `sha256` key. We use serde_json for robustness
/// (a serde_json::Value lookup) — the dep is already in this crate.
fn extract_sha256_from_version_json(bytes: &[u8]) -> io::Result<String> {
    let v: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        io::Error::new(io::ErrorKind::InvalidData, format!("version.json parse: {}", e))
    })?;
    v.get("sha256")
        .and_then(|s| s.as_str())
        .map(String::from)
        .ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "version.json missing string field `sha256`",
            )
        })
}

/// Returns Some(sha) if the install marker matches the expected sha; None
/// otherwise (file missing, parse error, or sha mismatch — all mean
/// "re-install").
fn read_pinned_sha(version_pin_path: &Path) -> Option<String> {
    let bytes = fs::read(version_pin_path).ok()?;
    extract_sha256_from_version_json(&bytes).ok()
}

/// Recursively wipe a directory tree if it exists. The Rust stdlib's
/// `remove_dir_all` is NOT atomic and won't follow symlinks — both
/// behaviors we want here.
fn wipe_dir(path: &Path) -> io::Result<()> {
    if path.exists() {
        fs::remove_dir_all(path)
    } else {
        Ok(())
    }
}

/// Apply the SYMLINKS.txt sidecar that ships inside the bootstrap zip.
/// Each line is `<target>←<linkname>` where the separator is U+2190
/// (LEFTWARDS ARROW, 3 bytes in UTF-8: e2 86 90). Lines with no separator
/// are silently skipped (defensive). `linkname` is RELATIVE to root.
fn apply_symlinks_txt(root: &Path, data: &[u8]) -> io::Result<()> {
    let text = std::str::from_utf8(data).map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "SYMLINKS.txt is not valid UTF-8",
        )
    })?;
    for line in text.lines() {
        // U+2190 LEFTWARDS ARROW = '\u{2190}' = "←"
        if let Some(idx) = line.find('\u{2190}') {
            let target = &line[..idx];
            let linkname = &line[idx + '\u{2190}'.len_utf8()..];
            if linkname.is_empty() {
                continue;
            }
            // Codex M4-S05 round-1 finding 1: path-confinement check on
            // `linkname` BEFORE any filesystem op. Without this, a malformed
            // `target←../escape` or `target←/abs/path` line could let
            // remove_file/symlink operate outside the staging tree.
            // (The zip-entry path-traversal check above handles regular
            // files; the symlink sidecar gets its own validation here.)
            validate_symlink_linkname(linkname)?;
            let link_path = root.join(linkname);
            if let Some(parent) = link_path.parent() {
                fs::create_dir_all(parent)?;
            }
            // If something landed at link_path during extraction (zip
            // shouldn't include both a regular file AND a symlink for the
            // same name, but be defensive), remove it.
            let _ = fs::remove_file(&link_path);
            // symlink(target, linkname) creates `linkname → target`.
            //
            // Note: `target` is intentionally NOT path-validated. Symlink
            // targets are resolved lazily by the kernel at follow time;
            // forbidding absolute or `..`-containing targets would also
            // forbid valid Termux usage (e.g. /system/bin/sh fallback).
            // The tighter check belongs in build-bootstrap.sh, not here.
            #[cfg(unix)]
            symlink(target, &link_path).map_err(|e| {
                io::Error::new(
                    e.kind(),
                    format!("symlink({} → {}): {}", linkname, target, e),
                )
            })?;
            #[cfg(not(unix))]
            {
                let _ = (&link_path, target);
            }
        }
    }
    Ok(())
}

/// Validate that a `linkname` from SYMLINKS.txt is path-confined within the
/// staging root — i.e., relative AND no `..` segments that would let the
/// resulting symlink path escape. Round-1 finding 1.
fn validate_symlink_linkname(linkname: &str) -> io::Result<()> {
    if linkname.starts_with('/') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "SYMLINKS.txt linkname is absolute (rejected): {}",
                linkname
            ),
        ));
    }
    // Reject any `..` component. Split on both '/' and '\\' belt-and-
    // suspenders — Path::components has platform path-separator semantics.
    for component in linkname.split(|c| c == '/' || c == '\\') {
        if component == ".." {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!(
                    "SYMLINKS.txt linkname contains `..` segment (rejected): {}",
                    linkname
                ),
            ));
        }
    }
    Ok(())
}

/// Extract every entry from the zip archive into `dest_root`. Skips
/// `SYMLINKS.txt` from extraction — the caller handles that sidecar
/// separately via `apply_symlinks_txt`.
fn extract_zip_to(dest_root: &Path, zip_bytes: &[u8]) -> io::Result<Option<Vec<u8>>> {
    let cursor = io::Cursor::new(zip_bytes);
    let mut archive = ZipArchive::new(cursor).map_err(|e| {
        io::Error::new(io::ErrorKind::InvalidData, format!("zip open: {}", e))
    })?;
    fs::create_dir_all(dest_root)?;

    let mut symlinks_data: Option<Vec<u8>> = None;

    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| {
            io::Error::new(io::ErrorKind::InvalidData, format!("zip entry {}: {}", i, e))
        })?;

        // Defensive against zip-slip: the build-bootstrap.sh script we trust
        // doesn't generate `..` paths, but reject any suspicious entry.
        let entry_name = entry.name().to_string();
        if entry_name.contains("..") || entry_name.starts_with('/') {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("zip entry rejects zip-slip: {}", entry_name),
            ));
        }

        // SYMLINKS.txt sidecar is captured for separate handling.
        if entry_name == "SYMLINKS.txt" {
            let mut buf = Vec::new();
            entry.read_to_end(&mut buf)?;
            symlinks_data = Some(buf);
            continue;
        }

        let outpath = dest_root.join(&entry_name);
        if entry.is_dir() {
            fs::create_dir_all(&outpath)?;
        } else {
            if let Some(parent) = outpath.parent() {
                fs::create_dir_all(parent)?;
            }
            // OpenOptions write+create+truncate so re-extracting overwrites.
            let mut outfile = OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .open(&outpath)?;
            io::copy(&mut entry, &mut outfile)?;
            // Apply Unix permissions if present in zip metadata.
            #[cfg(unix)]
            if let Some(mode) = entry.unix_mode() {
                fs::set_permissions(&outpath, fs::Permissions::from_mode(mode))?;
            }
        }
    }

    Ok(symlinks_data)
}

/// Main install function. Idempotent — returns Success early if already
/// installed (sha-pin match). Otherwise extracts bootstrap-aarch64.zip
/// atomically into <data_dir>/files/usr.
///
/// `data_dir` is typically `/data/data/dev.warp.mobile`. The function
/// derives `<data_dir>/files/` paths internally.
///
/// Concurrency: protected by a process-wide single-flight guard
/// (INSTALL_IN_FLIGHT). Subsequent overlapping calls return Ok early
/// without doing any I/O — the in-flight install will complete and the
/// next sha-pin check will short-circuit. Round-1 finding 3.
#[cfg(target_os = "android")]
pub fn install_bootstrap(
    asset_mgr: *mut ndk_sys::AAssetManager,
    data_dir: &Path,
) -> io::Result<()> {
    // Round-1+2 findings 3+1: single-flight guard via Mutex. Concurrent
    // callers BLOCK on the lock; once unblocked, they re-enter the body
    // and the sha-pin check below short-circuits. Status 0 contract
    // preserved: success means bootstrap is ready when this returns.
    //
    // PoisonError handling: if a previous caller panicked while holding
    // the lock, the data is `()` so there's nothing to lose; recover
    // with `into_inner()`. The next caller proceeds normally; whatever
    // partial state was left in usr.tmp/ gets wiped by step "kill-mid-
    // extract recovery" below.
    let _guard = INSTALL_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());

    let files_dir = data_dir.join("files");
    let usr_dir = files_dir.join("usr");
    let usr_tmp = files_dir.join("usr.tmp");
    let version_pin_path = files_dir.join(VERSION_PIN_FILENAME);

    fs::create_dir_all(&files_dir)?;

    // Step 1: read version.json from APK asset.
    let version_bytes = unsafe { read_apk_asset(asset_mgr, VERSION_JSON_ASSET)? };
    let expected_sha = extract_sha256_from_version_json(&version_bytes)?;

    // Step 2: short-circuit if already installed (sha-pin match).
    if usr_dir.exists() {
        if let Some(pinned_sha) = read_pinned_sha(&version_pin_path) {
            if pinned_sha == expected_sha {
                log::info!(
                    target: "android-host",
                    "bootstrap_install: sha-pin match ({}) — usr/ already current, skipping extract",
                    &expected_sha[..16]
                );
                return Ok(());
            }
            log::info!(
                target: "android-host",
                "bootstrap_install: sha mismatch (usr pinned={}, asset expects={}) — re-extracting",
                &pinned_sha[..16],
                &expected_sha[..16]
            );
        } else {
            log::info!(
                target: "android-host",
                "bootstrap_install: usr/ exists but no pin file — re-extracting"
            );
        }
    }

    // Step 3+4: read zip, verify sha.
    let zip_bytes = unsafe { read_apk_asset(asset_mgr, BOOTSTRAP_ZIP_ASSET)? };
    let actual_sha = sha256_hex(&zip_bytes);
    if actual_sha != expected_sha {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "bootstrap zip sha256 mismatch: expected {}, got {}",
                expected_sha, actual_sha
            ),
        ));
    }

    // Kill-mid-extract recovery: if usr.tmp/ is leftover from a previous
    // failed run, wipe it and start fresh.
    wipe_dir(&usr_tmp)?;

    // Step 5: extract into usr.tmp/.
    let symlinks_data = extract_zip_to(&usr_tmp, &zip_bytes)?;

    // Step 6: apply SYMLINKS.txt sidecar if present.
    if let Some(data) = symlinks_data {
        apply_symlinks_txt(&usr_tmp, &data)?;
    }

    // Step 7: atomic rename usr.tmp/ → usr/. Wipe stale usr/ first; rename(2)
    // requires the destination to be empty (or the same type).
    wipe_dir(&usr_dir)?;
    fs::rename(&usr_tmp, &usr_dir).map_err(|e| {
        io::Error::new(
            e.kind(),
            format!(
                "atomic rename {} → {} failed: {}",
                usr_tmp.display(),
                usr_dir.display(),
                e
            ),
        )
    })?;

    // Step 8: write the version-pin file. Doing it AFTER the rename means a
    // crash between step 7 and step 8 leaves usr/ correct but no pin file —
    // step 2 of the next launch will detect "no pin file" and re-extract,
    // which is safe (idempotent overwrite). Better to err toward redoing
    // work than skipping a needed re-extract.
    let mut pin_file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .open(&version_pin_path)?;
    pin_file.write_all(&version_bytes)?;
    pin_file.sync_all()?;

    log::info!(
        target: "android-host",
        "bootstrap_install: completed; usr/ at {} sha={}",
        usr_dir.display(),
        &expected_sha[..16]
    );
    Ok(())
}

// ── JNI bindings ────────────────────────────────────────────────────────────
//
// Java side calls:
//   int status = NativeBridge.bootstrapInstall(getAssets(), getApplicationInfo().dataDir);
//
// The AssetManager Java object → AAssetManager native pointer conversion uses
// android.content.res.AssetManager.AAssetManager_fromJava under the hood.

#[cfg(target_os = "android")]
fn map_io_to_status(err: &io::Error) -> InstallStatus {
    use io::ErrorKind;
    match err.kind() {
        ErrorKind::NotFound => {
            // NotFound from AAssetManager_open means asset missing.
            // Could also come from rename if usr.tmp got deleted concurrently;
            // both map to "filesystem state unexpected" → IoError.
            if err.to_string().contains("AAssetManager_open") {
                InstallStatus::AssetNotFound
            } else {
                InstallStatus::IoError
            }
        }
        ErrorKind::InvalidData => {
            if err.to_string().contains("sha256 mismatch") {
                InstallStatus::Sha256Mismatch
            } else if err.to_string().contains("SYMLINKS.txt") {
                InstallStatus::MalformedSymlinks
            } else {
                InstallStatus::IoError
            }
        }
        _ => {
            if err.to_string().contains("atomic rename") {
                InstallStatus::AtomicRenameFailed
            } else {
                InstallStatus::IoError
            }
        }
    }
}

#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_bootstrapInstall(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    asset_manager_obj: jni::objects::JObject,
    data_dir_jstr: jni::objects::JString,
) -> jni::sys::jint {
    crate::init_logger();

    // Convert Java AssetManager → AAssetManager*.
    let asset_mgr = unsafe {
        ndk_sys::AAssetManager_fromJava(env.get_raw() as *mut _, asset_manager_obj.as_raw() as *mut _)
    };
    if asset_mgr.is_null() {
        log::error!(target: "android-host", "bootstrapInstall: AAssetManager_fromJava returned NULL");
        return InstallStatus::InvalidAssetManager as jni::sys::jint;
    }

    let data_dir_str = match env.get_string(&data_dir_jstr) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(e) => {
            log::error!(target: "android-host", "bootstrapInstall: data_dir JString extract: {:?}", e);
            return InstallStatus::IoError as jni::sys::jint;
        }
    };
    let data_dir = PathBuf::from(&data_dir_str);

    match install_bootstrap(asset_mgr, &data_dir) {
        Ok(()) => {
            log::info!(target: "android-host", "bootstrapInstall: SUCCESS");
            InstallStatus::Success as jni::sys::jint
        }
        Err(e) => {
            log::error!(
                target: "android-host",
                "bootstrapInstall: FAILED: {} (kind={:?})",
                e,
                e.kind()
            );
            map_io_to_status(&e) as jni::sys::jint
        }
    }
}

// ── Host-side unit tests ────────────────────────────────────────────────────
//
// Tests exercise everything except the AAssetManager read path (which is
// Android-only). The zip extract + symlink apply + rename + sha-pin logic
// are all OS-agnostic Unix ops.

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::io::Cursor;
    use tempfile::TempDir;
    use zip::write::SimpleFileOptions;
    use zip::CompressionMethod;

    /// Create a synthetic bootstrap-aarch64.zip in memory:
    ///   bin/zsh         (executable; mode 0o755)
    ///   etc/zshrc       (regular file)
    ///   share/.empty    (empty file inside subdir)
    ///   SYMLINKS.txt    (1 symlink: bin/sh → /data/data/dev.warp.mobile/files/usr/bin/zsh)
    fn make_synthetic_zip() -> Vec<u8> {
        let mut buf = Vec::new();
        {
            let cursor = Cursor::new(&mut buf);
            let mut writer = zip::ZipWriter::new(cursor);
            let opts = SimpleFileOptions::default()
                .compression_method(CompressionMethod::Deflated)
                .unix_permissions(0o755);

            writer.start_file("bin/zsh", opts).unwrap();
            writer.write_all(b"#!/bin/sh\necho zsh-stub\n").unwrap();

            let opts644 = SimpleFileOptions::default()
                .compression_method(CompressionMethod::Deflated)
                .unix_permissions(0o644);

            writer.start_file("etc/zshrc", opts644).unwrap();
            writer.write_all(b"# zshrc stub\n").unwrap();

            writer.start_file("share/.empty", opts644).unwrap();

            writer.start_file("SYMLINKS.txt", opts644).unwrap();
            // Note: '\u{2190}' = "←" in UTF-8.
            writer
                .write_all(b"/data/data/dev.warp.mobile/files/usr/bin/zsh\xe2\x86\x90bin/sh\n")
                .unwrap();

            writer.finish().unwrap();
        }
        buf
    }

    fn synthetic_version_json(zip_sha: &str) -> Vec<u8> {
        format!(
            r#"{{"sha256":"{}","size_bytes":0,"size_mb":0,"build_date":"2026-05-01T00:00:00Z","package_count":3,"package_list":["zsh"],"prefix":"/data/data/dev.warp.mobile/files/usr","warp_app_id":"dev.warp.mobile","arch":"aarch64"}}"#,
            zip_sha
        )
        .into_bytes()
    }

    #[test]
    fn sha256_hex_lowercase_padded() {
        let h = sha256_hex(b"");
        assert_eq!(
            h,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(h.len(), 64);
    }

    #[test]
    fn extract_sha256_from_version_json_happy() {
        let bytes = b"{\"sha256\":\"abcd\",\"foo\":\"bar\"}";
        assert_eq!(extract_sha256_from_version_json(bytes).unwrap(), "abcd");
    }

    #[test]
    fn extract_sha256_from_version_json_missing_field() {
        let bytes = b"{\"foo\":\"bar\"}";
        assert!(extract_sha256_from_version_json(bytes).is_err());
    }

    #[test]
    fn apply_symlinks_txt_creates_links() {
        let tmp = TempDir::new().unwrap();
        // U+2190 = "\xe2\x86\x90"
        let data = b"target/file\xe2\x86\x90link/inner.lnk\n";
        apply_symlinks_txt(tmp.path(), data).unwrap();
        let link = tmp.path().join("link/inner.lnk");
        assert!(link.is_symlink());
        assert_eq!(fs::read_link(&link).unwrap().to_str().unwrap(), "target/file");
    }

    #[test]
    fn apply_symlinks_txt_rejects_absolute_linkname() {
        // Round-1 finding 1: linkname starting with `/` would let symlink
        // operate outside the staging root. Must reject.
        let tmp = TempDir::new().unwrap();
        let data = b"target/file\xe2\x86\x90/etc/passwd\n";
        let result = apply_symlinks_txt(tmp.path(), data);
        assert!(result.is_err(), "expected absolute-path rejection");
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("absolute"),
            "expected 'absolute' in error message, got: {}",
            msg
        );
        // No symlinks should have been created.
        assert_eq!(fs::read_dir(tmp.path()).unwrap().count(), 0);
    }

    #[test]
    fn apply_symlinks_txt_rejects_dotdot_linkname() {
        // Round-1 finding 1: linkname containing `..` segment could
        // escape the staging root.
        let tmp = TempDir::new().unwrap();
        let data = b"target/file\xe2\x86\x90../escape.lnk\n";
        let result = apply_symlinks_txt(tmp.path(), data);
        assert!(result.is_err(), "expected `..`-segment rejection");
        let msg = result.unwrap_err().to_string();
        assert!(msg.contains(".."), "expected '..' in error message, got: {}", msg);
        assert_eq!(fs::read_dir(tmp.path()).unwrap().count(), 0);
    }

    #[test]
    fn apply_symlinks_txt_rejects_dotdot_in_subpath() {
        // The `..` rejection must also catch nested cases like
        // `bin/../../../escape`, not just leading `..`.
        let tmp = TempDir::new().unwrap();
        let data = b"target\xe2\x86\x90bin/../../../escape\n";
        let result = apply_symlinks_txt(tmp.path(), data);
        assert!(result.is_err(), "expected nested `..`-segment rejection");
    }

    #[test]
    fn apply_symlinks_txt_rejects_dotdot_with_backslash() {
        // Belt-and-suspenders: split treats both '/' and '\\' as separators
        // so a Windows-style path with backslash also gets caught.
        let tmp = TempDir::new().unwrap();
        let data = b"target\xe2\x86\x90sub\\..\\escape\n";
        let result = apply_symlinks_txt(tmp.path(), data);
        assert!(result.is_err(), "expected backslash `..` rejection");
    }

    #[test]
    fn apply_symlinks_txt_skips_malformed_lines() {
        let tmp = TempDir::new().unwrap();
        // Lines without ← are silently skipped; lines with ← but empty
        // linkname are skipped too.
        let data = b"this has no arrow\n\
                     target\xe2\x86\x90\n\
                     real\xe2\x86\x90real-link\n";
        apply_symlinks_txt(tmp.path(), data).unwrap();
        assert!(tmp.path().join("real-link").is_symlink());
        // No "arrow" file or empty-linkname garbage created.
        let entries: Vec<_> = fs::read_dir(tmp.path())
            .unwrap()
            .map(|e| e.unwrap().file_name().into_string().unwrap())
            .collect();
        assert_eq!(entries, vec!["real-link"]);
    }

    #[test]
    fn extract_zip_to_writes_files_and_captures_symlinks() {
        let zip_bytes = make_synthetic_zip();
        let tmp = TempDir::new().unwrap();
        let symlinks = extract_zip_to(tmp.path(), &zip_bytes).unwrap();

        assert!(tmp.path().join("bin/zsh").exists());
        assert!(tmp.path().join("etc/zshrc").exists());
        assert!(tmp.path().join("share/.empty").exists());
        // SYMLINKS.txt is captured, NOT extracted as a regular file.
        assert!(!tmp.path().join("SYMLINKS.txt").exists());
        assert!(symlinks.is_some());

        // bin/zsh must have its 0o755 perms preserved.
        let perms = fs::metadata(tmp.path().join("bin/zsh")).unwrap().permissions();
        assert_eq!(perms.mode() & 0o777, 0o755);
    }

    #[test]
    fn extract_zip_rejects_zip_slip() {
        // Hand-craft a zip with a path containing `..`.
        let mut buf = Vec::new();
        {
            let cursor = Cursor::new(&mut buf);
            let mut writer = zip::ZipWriter::new(cursor);
            let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            writer.start_file("../escape", opts).unwrap();
            writer.write_all(b"evil").unwrap();
            writer.finish().unwrap();
        }
        let tmp = TempDir::new().unwrap();
        let result = extract_zip_to(tmp.path(), &buf);
        assert!(result.is_err());
        let msg = result.unwrap_err().to_string();
        assert!(msg.contains("zip-slip"), "expected zip-slip rejection, got: {}", msg);
    }

    #[test]
    fn read_pinned_sha_handles_missing_file() {
        let tmp = TempDir::new().unwrap();
        assert!(read_pinned_sha(&tmp.path().join("nope.json")).is_none());
    }

    #[test]
    fn read_pinned_sha_returns_value() {
        let tmp = TempDir::new().unwrap();
        let path = tmp.path().join("pin.json");
        fs::write(&path, br#"{"sha256":"deadbeef"}"#).unwrap();
        assert_eq!(read_pinned_sha(&path).unwrap(), "deadbeef");
    }

    #[test]
    fn wipe_dir_is_idempotent_on_missing() {
        let tmp = TempDir::new().unwrap();
        wipe_dir(&tmp.path().join("does-not-exist")).unwrap();
    }

    #[test]
    fn synthetic_zip_round_trip_via_extract_then_pin() {
        // Simulate the full extract path WITHOUT the AAssetManager read
        // (we feed the zip+version bytes directly).
        let zip_bytes = make_synthetic_zip();
        let zip_sha = sha256_hex(&zip_bytes);
        let version_bytes = synthetic_version_json(&zip_sha);

        let tmp = TempDir::new().unwrap();
        let usr = tmp.path().join("files/usr");
        let usr_tmp = tmp.path().join("files/usr.tmp");
        let pin = tmp.path().join("files/.bootstrap-version.json");
        fs::create_dir_all(tmp.path().join("files")).unwrap();

        // Mimic install_bootstrap steps 4-8 directly.
        let extracted_sha = sha256_hex(&zip_bytes);
        assert_eq!(extracted_sha, zip_sha);
        let symlinks_data = extract_zip_to(&usr_tmp, &zip_bytes).unwrap();
        if let Some(data) = symlinks_data {
            apply_symlinks_txt(&usr_tmp, &data).unwrap();
        }
        wipe_dir(&usr).unwrap();
        fs::rename(&usr_tmp, &usr).unwrap();
        fs::write(&pin, &version_bytes).unwrap();

        // Verify final state.
        assert!(usr.join("bin/zsh").exists());
        assert!(usr.join("bin/sh").is_symlink());
        assert_eq!(read_pinned_sha(&pin).unwrap(), zip_sha);
    }
}
