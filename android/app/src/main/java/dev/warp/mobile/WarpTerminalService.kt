package dev.warp.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class WarpTerminalService : Service() {

    companion object {
        init {
            try {
                System.loadLibrary("warp_mobile_android_host")
            } catch (e: Throwable) {
                // Ignored on host JVM desktop test environments where native .so/.dll is absent
            }
        }
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "warp-terminal"
        private const val LOG_TAG = "WarpTerminal"
        private const val PTY_OUTPUT_TAG = "WarpTerminal:PtyOutput"

        const val ACTION_SPAWN  = "dev.warp.mobile.PTY_SPAWN"
        const val ACTION_WRITE  = "dev.warp.mobile.PTY_WRITE"
        const val ACTION_RESIZE = "dev.warp.mobile.PTY_RESIZE"
        const val ACTION_KILL   = "dev.warp.mobile.PTY_KILL"
        const val ACTION_OUTPUT = "dev.warp.mobile.PTY_OUTPUT"
        private const val MAX_FAST_DEATH_RETRIES = 3
        private const val FAST_DEATH_THRESHOLD_MS = 1500L
    }

    private val ptyManager = PtyManager()
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val readJobs = mutableMapOf<String, Job>()
    // V1-prep: remember which spawns we have already auto-fallen-back so a
    // bad fallback (e.g. /system/bin/sh somehow also dying) does not loop
    // forever. Keyed by cmd_id.
    private val fallbackAttempted = mutableSetOf<String>()
    private val fastDeathCounts = mutableMapOf<String, Int>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val intentCopy = Intent(intent)
            scope.launch(Dispatchers.IO) {
                when (intentCopy.action) {
                    ACTION_SPAWN  -> handleSpawn(intentCopy)
                    ACTION_WRITE  -> handleWrite(intentCopy)
                    ACTION_RESIZE -> handleResize(intentCopy)
                    ACTION_KILL   -> handleKill(intentCopy)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ANR protection: move asset extraction and symlink installation to background IO thread
        scope.launch(Dispatchers.IO) {
            extractWarpAssets()
            writeWarpZshenv()
            writeAptConfig()
            installTermuxBinSymlinks()
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_SPAWN)
            addAction(ACTION_WRITE)
            addAction(ACTION_RESIZE)
            addAction(ACTION_KILL)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        Log.i(LOG_TAG, "WarpTerminalService created, receivers registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        // Dispatch off main thread to avoid ANR on blocking JNI calls
        val action = intent?.action
        val intentCopy = intent
        if (intentCopy != null) scope.launch {
            when (action) {
                ACTION_SPAWN  -> handleSpawn(intentCopy)
                ACTION_WRITE  -> handleWrite(intentCopy)
                ACTION_RESIZE -> handleResize(intentCopy)
                ACTION_KILL   -> handleKill(intentCopy)
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Warp Terminal", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Warp terminal")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    // ── M3-S06: asset extraction ─────────────────────────────────────────────

    /**
     * Extract APK-bundled warp assets to the app's internal files dir.
     *
     * Currently extracts:
     *   assets/warp/zsh_body.sh → filesDir/warp/zsh_body.sh
     *
     * The file is skipped if it already exists (idempotent). Extraction happens
     * at service creation so the path is available before any PTY session
     * spawns. A PTY shell can `cat` the file at:
     *   /data/data/dev.warp.mobile/files/warp/zsh_body.sh
     *
     * Hook execution is DEFERRED to M5 Termux: the S24 Ultra ships only mksh;
     * zsh_body.sh's precmd/preexec hooks require zsh which ships in M5.
     *
     * Refs:
     *   https://developer.android.com/reference/android/content/res/AssetManager
     *   https://wiki.termux.com/wiki/Zsh (zsh availability in Termux; M5 target)
     *   AGPL-3.0 §5: source-form script shipped verbatim in APK satisfies §5
     *     (corresponding source = the script itself; no additional obligation).
     */
    /**
     * V1-prep iteration 20: relocate each $PREFIX/bin/<name> ELF binary so it
     * lives at ${nativeLibraryDir}/lib<name>.so (sanitized name in the
     * manifest), and install a symlink at $PREFIX/bin/<name> pointing at the
     * lib variant. The Gradle task `extractTermuxBinariesAsLibs` produces
     * the lib*.so files at build time + writes the original→lib_name mapping
     * to assets/warp/termux-bin-manifest.json.
     *
     * Why: Android since API 29 enforces SELinux
     * `neverallow untrusted_app app_data_file:file execute`. Files in
     * /data/data/<pkg>/files/ are app_data_file-labelled, so $PREFIX/bin/zsh
     * etc cannot be execve'd. Files in ${nativeLibraryDir} (which is
     * /data/app/.../lib/<abi>/) are apk_data_file-labelled, which the
     * untrusted_app domain HAS execute permission on.
     *
     * Idempotent: if a symlink at the target path already points at the
     * correct nativeLibraryDir target, no-op. If it points elsewhere or is
     * not a symlink, it gets replaced. If the lib*.so for a manifest entry
     * does not exist on this device (older APK), that entry is skipped.
     *
     * Gated on bootstrap presence: if $PREFIX/bin doesn't exist yet (first
     * launch racing with bootstrap_install), this method runs but creates no
     * symlinks. WarpTerminalService.handleSpawn calls writeWarpZshenv +
     * writeAptConfig at every spawn anyway; we'll call this from there too
     * so the symlinks get installed on the next spawn after extraction.
     */
    private fun installTermuxBinSymlinks() {
        val prefixDir = File(applicationInfo.dataDir, "files/usr")
        if (!prefixDir.isDirectory) {
            Log.i(LOG_TAG, "installTermuxBinSymlinks: $prefixDir not present yet (bootstrap not extracted); will retry at next spawn")
            return
        }
        val nativeLibDir = applicationInfo.nativeLibraryDir
        // Read manifest from APK assets.
        val manifestJson = try {
            assets.open("warp/termux-bin-manifest.json").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            Log.w(LOG_TAG, "installTermuxBinSymlinks: termux-bin-manifest.json missing from APK assets — extractTermuxBinariesAsLibs gradle task may not have run. Skipping.")
            return
        }
        val parsed = try {
            org.json.JSONObject(manifestJson)
        } catch (e: org.json.JSONException) {
            Log.e(LOG_TAG, "installTermuxBinSymlinks: manifest parse error: ${e.message}")
            return
        }
        val schemaVersion = parsed.optInt("version", 1)
        val bins = parsed.optJSONArray("bins") ?: run {
            Log.w(LOG_TAG, "installTermuxBinSymlinks: manifest has no 'bins' array")
            return
        }

        var installed = 0
        var skippedAlreadyOk = 0
        var skippedMissingLib = 0
        for (i in 0 until bins.length()) {
            val entry = bins.getJSONObject(i)
            val original = entry.optString("original")
            val libName = entry.optString("lib_name")
            if (original.isEmpty() || libName.isEmpty()) continue

            val libPath = File(nativeLibDir, libName)
            if (!libPath.exists()) {
                skippedMissingLib++
                continue
            }
            // Schema v1 stored just the basename relative to $PREFIX/bin/. v2
            // stores the full relative path from $PREFIX (e.g. "bin/ls",
            // "libexec/git-core/git-remote-http") so we can relocate the
            // libexec subtree too. Treat schema v1 entries as "bin/<original>"
            // for backwards compatibility.
            val rel = if (schemaVersion >= 2) original else "bin/$original"
            val target = File(prefixDir, rel)
            val targetPath = target.toPath()

            // mkdirs the parent — libexec/git-core/ may not exist yet on a
            // partial bootstrap_install (defensive; bootstrap_install
            // normally creates these dirs from SYMLINKS.txt).
            target.parentFile?.mkdirs()

            // Check if symlink already points at the right place.
            try {
                if (java.nio.file.Files.isSymbolicLink(targetPath)) {
                    val current = java.nio.file.Files.readSymbolicLink(targetPath).toString()
                    if (current == libPath.absolutePath) {
                        skippedAlreadyOk++
                        continue
                    }
                }
            } catch (e: Exception) {
                // Fall through to the replace path.
            }

            // Replace the existing entry (regular file from bootstrap unzip,
            // or stale symlink) with a fresh symlink to nativeLibraryDir.
            try {
                if (target.exists() || java.nio.file.Files.isSymbolicLink(targetPath)) {
                    java.nio.file.Files.delete(targetPath)
                }
                java.nio.file.Files.createSymbolicLink(targetPath, libPath.toPath())
                installed++
            } catch (e: Exception) {
                Log.w(LOG_TAG, "installTermuxBinSymlinks: failed to symlink $rel -> $libPath: ${e.message}")
            }
        }
        Log.i(
            LOG_TAG,
            "installTermuxBinSymlinks: schema=$schemaVersion installed=$installed already_ok=$skippedAlreadyOk missing_lib=$skippedMissingLib total_manifest=${bins.length()}"
        )
    }

    private fun extractWarpAssets() {
        val warpDir = File(filesDir, "warp")
        val target = File(warpDir, "zsh_body.sh")
        val temp = File(warpDir, "zsh_body.sh.tmp")
        // Read canonical bytes from the asset stream. `openFd` would let us
        // skip the buffer but it only works for uncompressed assets; AGP
        // compresses .sh files by default. The file is 66KB so buffering
        // is cheap, and reading once gives us the size for the integrity check.
        val canonicalBytes = try {
            assets.open("warp/zsh_body.sh").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "failed to read zsh_body.sh from APK assets: ${e.message}", e)
            return
        }
        val expectedSize = canonicalBytes.size.toLong()
        // Codex M3-S06 round-1 finding #1: validate existing file by size
        // before treating as already-extracted. A partial copy from a prior
        // launch (process killed mid-write) leaves a truncated file that
        // would otherwise be skipped forever.
        if (target.exists() && target.length() == expectedSize) {
            Log.i(LOG_TAG, "zsh_body.sh already extracted at ${target.absolutePath} (${target.length()} bytes); skipping")
            return
        }
        if (target.exists()) {
            Log.w(LOG_TAG, "zsh_body.sh size mismatch (target=${target.length()} expected=$expectedSize); re-extracting")
        }
        // Atomic-replace pattern: write to a same-dir temp file, verify size,
        // then rename. If any step fails the temp is deleted and target stays
        // either absent (first launch) or untouched (corrupt-detect re-extract).
        warpDir.mkdirs()
        if (temp.exists()) temp.delete()
        try {
            temp.writeBytes(canonicalBytes)
            if (temp.length() != expectedSize) {
                throw java.io.IOException("size mismatch after write: temp=${temp.length()} expected=$expectedSize")
            }
            if (target.exists() && !target.delete()) {
                throw java.io.IOException("could not remove stale target ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                throw java.io.IOException("rename ${temp.absolutePath} → ${target.absolutePath} failed")
            }
            Log.i(LOG_TAG, "extracted zsh_body.sh to ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            temp.delete()
            Log.e(LOG_TAG, "failed to extract zsh_body.sh: ${e.message}", e)
        }
    }

    /**
     * M4-S06: write our canonical $ZDOTDIR/.zshenv.
     *
     * Why this file exists:
     *   - zsh 5.9 IGNORES inherited MODULE_PATH env var (verified by codex M4-S03
     *     round-7+8) — it reinitializes module_path from the COMPILE-TIME default
     *     which still points at /data/data/com.termux/lib/zsh/5.9 because Termux
     *     pre-built debs were compiled against the upstream prefix.
     *   - FPATH IS honored as env var (imported into fpath shell array), but
     *     zsh's compile-time default ALSO seeds fpath with stale com.termux/...
     *     entries that survive the env-var preset.
     *   - The canonical fix is shell-array assignment in $ZDOTDIR/.zshenv:
     *       module_path=(...)              # full replace
     *       fpath=(...new... ${fpath:#/data/data/com.termux/asterisk}) # filter stale
     *
     * Idempotency: only writes if usr/ has been extracted (M4-S05 done) AND
     * the file is missing or has different content. Safe to call on every
     * service startup.
     *
     * Format chosen: ZDOTDIR=$PREFIX/etc → zsh sources $PREFIX/etc/.zshenv
     * automatically when ZDOTDIR is set in spawn env. We control that env
     * (handleSpawn below) so the path is reliable.
     */
    private fun writeWarpZshenv() {
        val prefix = "${applicationInfo.dataDir}/files/usr"
        val zsh = File("$prefix/bin/zsh")
        if (!zsh.exists()) {
            Log.i(LOG_TAG, "writeWarpZshenv: $prefix/bin/zsh not present (M4-S05 not run yet); skipping")
            return
        }
        // Codex round-1 finding 3: $PREFIX/tmp creation must be GATED on usr/
        // already existing — buildPrefixEnv used to mkdirs $PREFIX/tmp on every
        // spawn including the mksh fallback path, which could race the M4-S05
        // atomic rename `usr.tmp/ → usr/`. Now we create it here, AFTER the
        // usr/bin/zsh exists check, so it can only happen post-extraction.
        File("$prefix/tmp").mkdirs()
        val zshenvPath = File("$prefix/etc/.zshenv")
        // Canonical content (per M4-S06 AC #6/#7 from prd.json round-7).
        // The HEREDOC-style multiline string is a single Kotlin string with
        // explicit \n; embedded $ are escaped for Kotlin (\$).
        val canonical = """
            |# Warp Mobile zsh env override (M4-S06).
            |# Generated by WarpTerminalService.writeWarpZshenv on app launch.
            |# DO NOT edit by hand — content is reproducible at every service start.
            |#
            |# Codex M4-S03 round-7+8 finding: zsh 5.9 ignores inherited MODULE_PATH
            |# env var (reinitializes module_path from compile-time default which
            |# points at /data/data/com.termux/lib/zsh/5.9). Canonical fix: shell-
            |# array assignment here. fpath gets the same treatment plus a glob
            |# filter to strip any stale com.termux/* entries that survived from
            |# the compile-time default + env var preset.
            |
            |# 1. module_path: full replace with dev.warp.mobile-rooted path.
            |module_path=(/data/data/dev.warp.mobile/files/usr/lib/zsh/5.9)
            |
            |# 2. fpath: prepend dev.warp.mobile entries; strip ANY stale
            |#    com.termux/* entries from whatever zsh seeded the array with.
            |fpath=(
            |    /data/data/dev.warp.mobile/files/usr/share/zsh/5.9/functions
            |    /data/data/dev.warp.mobile/files/usr/share/zsh/site-functions
            |    ${"$"}{fpath:#/data/data/com.termux/*}
            |)
            |
            |# 3. TMPPREFIX redirects zsh heredoc/here-string temp files
            |#    away from /tmp (unwritable on Android) into our app-private
            |#    ${"$"}PREFIX/tmp. Without this, warp_escape_json (used by the
            |#    DCS hook script) emits "can't create temp file for here
            |#    document: permission denied" warnings every command.
            |TMPPREFIX="${"$"}{TMPDIR:-/data/data/dev.warp.mobile/files/usr/tmp}/zsh"
            |
            |# 4. Sanity-check sentinel for M4-S06 acceptance verification:
            |#    `print -rl -- ${"$"}WARP_ZSHENV_LOADED` returns "1" iff this
            |#    file was sourced. M4-S10 acceptance test asserts this.
            |export WARP_ZSHENV_LOADED=1
            |
            |# 5. Source the warp DCS-hook script extracted by M3-S06.
            |#    (Codex M4-S06 round-1 finding 1: AC #3 says hooks must fire,
            |#    but until that round zsh never sourced the script.)
            |#    (Codex M4-S06 round-2 finding 1: zsh_body.sh internally
            |#    sources ${"$"}{ZDOTDIR}/.zshenv before setting WARP_BOOTSTRAPPED,
            |#    causing infinite recursion via this .zshenv. Guard with a
            |#    SOURCING sentinel that's set BEFORE source and unset after.)
            |#    The script registers preexec/precmd functions that emit DCS
            |#    sequences (ESC P ${"$"} d <hex> 0x9c) consumed by the M3-S05
            |#    DCS parser to populate the Block model.
            |if [[ -r /data/data/dev.warp.mobile/files/warp/zsh_body.sh && -z ${"$"}{WARP_ZSH_BODY_SOURCING:-} ]]; then
            |    # zsh_body.sh expects WARP_SESSION_ID; default to PID if unset.
            |    : ${"$"}{WARP_SESSION_ID:=${"$"}${"$"}}
            |    export WARP_SESSION_ID
            |    WARP_ZSH_BODY_SOURCING=1
            |    source /data/data/dev.warp.mobile/files/warp/zsh_body.sh
            |    unset WARP_ZSH_BODY_SOURCING
            |fi
            |
            |# 6. V1-prep iteration 28 (2026-05-03): override the Warp Desktop
            |#    prompt with a mobile-friendly version. zsh_body.sh's
            |#    `warp_update_prompt_vars` (called on every prompt redraw)
            |#    decorates PROMPT with cursor-marker machinery that emits
            |#    CSI 44 C (move cursor right 44 cols) for RPROMPT positioning
            |#    + complex ZLE redraw escapes — overflows our 45-column
            |#    mobile grid and corrupts cursor state, so typed chars
            |#    overwrite the prompt at column 0 instead of appending.
            |#
            |#    We override `warp_update_prompt_vars` AFTER sourcing
            |#    zsh_body.sh so the decorator becomes a no-op, then set a
            |#    simple PROMPT with %{...%} zero-width markers around the
            |#    OSC 133;A/B hooks. RPROMPT empty, PS2 simple, no PROMPT_SP.
            |#
            |#    Block model preexec/precmd hooks (warp_preexec /
            |#    warp_precmd) remain registered, so command/output/
            |#    exit_code capture continues working.
            |if typeset -f warp_update_prompt_vars >/dev/null 2>&1; then
            |    warp_update_prompt_vars() { :; }
            |fi
            |unset RPROMPT
            |# V1-prep iteration 31 (2026-05-03): show last cwd component before
            |# the prompt char so the user knows where they are. %1~ gives the
            |# last 1 path segment with HOME substituted as ~. The trailing
            |# space + %# (= '#' for root, '%' otherwise) plus the OSC 133 A/B
            |# wrapping for Block aggregator are unchanged.
            |PROMPT=${"$"}'%{\e]133;A\a%}%1~ %# %{\e]133;B\a%}'
            |PS2='> '
            |setopt no_prompt_sp 2>/dev/null || true
            |
            |# V1-prep iteration 31: persist zsh history across app restarts
            |# under ZDOTDIR. Without this, HISTFILE defaults to HOME/.zsh_history
            |# but Android's app sandbox HOME may differ between cold launches.
            |# 1000-entry rolling buffer is plenty for mobile use.
            |HISTFILE=${"$"}{ZDOTDIR:-${"$"}HOME}/.zsh_history
            |HISTSIZE=1000
            |SAVEHIST=1000
            |setopt SHARE_HISTORY EXTENDED_HISTORY HIST_IGNORE_DUPS HIST_IGNORE_SPACE 2>/dev/null || true
            |
            |# 7. V1-prep iteration 30 (2026-05-03): enable Tab completion.
            |#    Termux's zsh ships the stock Completion/ tree under
            |#    ${"$"}PREFIX/share/zsh/${"$"}{ZSH_VERSION}/functions/Completion which is
            |#    auto-added to ${"$"}fpath. compinit scans it once and caches to
            |#    ${"$"}ZDOTDIR/.zcompdump. The -u flag tolerates "insecure"
            |#    ownership (Android sandbox UID owns everything anyway).
            |#    -C skips the security audit on subsequent loads (cache hit).
            |if (( ${"$"}+functions[compinit] == 0 )); then
            |    autoload -Uz compinit
            |fi
            |compinit -u -d ${"$"}{ZDOTDIR:-${"$"}HOME}/.zcompdump 2>/dev/null
            |
            |# 8. V1-prep iteration 33 (2026-05-03): conventional aliases +
            |#    colorised ls / grep / diff. M4 bundles GNU coreutils 9.10
            |#    so ls --color=auto is supported (toybox ls-from-stock
            |#    Android does not support --color, but our PATH puts
            |#    ${"$"}PREFIX/bin first so the GNU one wins). LS_COLORS is a
            |#    dark-bg-friendly palette: di (dirs) bold blue, ln (symlinks)
            |#    cyan, ex (executables) green, .tar/.gz/.zip red.
            |alias ls='ls --color=auto'
            |alias ll='ls -lah --color=auto'
            |alias la='ls -lAh --color=auto'
            |alias l='ls -CF --color=auto'
            |alias grep='grep --color=auto'
            |alias egrep='grep -E --color=auto'
            |alias fgrep='grep -F --color=auto'
            |alias diff='diff --color=auto'
            |alias ..='cd ..'
            |alias ...='cd ../..'
            |alias ....='cd ../../..'
            |export LS_COLORS='di=1;34:ln=36:so=35:pi=33:ex=32:bd=34;46:cd=34;43:su=30;41:sg=30;46:tw=30;42:ow=30;43:*.tar=31:*.tgz=31:*.zip=31:*.gz=31:*.bz2=31:*.xz=31:*.7z=31:*.jpg=35:*.jpeg=35:*.png=35:*.gif=35:*.mp4=35:*.mp3=35'
            |
            |# 9. V1-prep iteration 34 (2026-05-03): once-per-session welcome
            |#    banner. .zshenv gets sourced twice in our setup (zsh's normal
            |#    init + a re-source somewhere in WARP bootstrap; observed by
            |#    `[pid=N]` showing same N twice in iter-34b debug screenshot).
            |#    iter-34c (same day): WARP_BANNER_PRINTED guard makes this
            |#    idempotent so the banner only prints once per session.
            |#    iter-34a (same day): banner is ASCII-only. The original CJK
            |#    hint string ('傳送/Enter runs') triggered cosmic_text font
            |#    fallback into NotoColorEmoji's cmap-format-12 enumeration on
            |#    the first shape pass, which iterates >25k codepoints on the
            |#    main thread and exceeds the 30s ANR budget. Tracked in
            |#    .omc/v1-prep-regression-checks.json as a latent issue
            |#    (real CJK content in PTY output will still hit it; proper
            |#    fix is to bound fallback or cache codepoint lookup).
            |if [[ -z ${"$"}WARP_BANNER_PRINTED ]]; then
            |    print -P -- "%F{cyan}%BWarp Mobile%b%f - zsh ${"$"}{ZSH_VERSION}"
            |    # V1-prep iteration 42 (2026-05-03): banner shortened to
            |    # fit a typical 36-44 col mobile viewport (was 49 chars,
            |    # truncated as "...En" with Ellipsis on Galaxy S24 Ultra
            |    # 45-col grid).
            |    print -P -- "%F{8}Tab=complete  Up=history  Enter=run%f"
            |    print
            |    export WARP_BANNER_PRINTED=1
            |fi
            |
            |# 10. V1-prep iteration 37 (2026-05-03): start in HOME instead of
            |#     whatever cwd the parent FGS inherited from system (observed
            |#     as "/" via TERM_BLOCKS_DUMP — `pwd` returned "/" so `ls` hit
            |#     Permission denied scoped-storage on root). Once-per-session
            |#     guard mirrors WARP_BANNER_PRINTED so manual `cd` survives a
            |#     re-source of .zshenv (zsh's normal init double-source).
            |if [[ -z ${"$"}WARP_CWD_SET && -d ${"$"}HOME ]]; then
            |    cd -- "${"$"}HOME"
            |    export WARP_CWD_SET=1
            |fi
        """.trimMargin().trimStart() + "\n"

        // Idempotent write: only update if content differs.
        val needsWrite = !zshenvPath.exists() || zshenvPath.readText() != canonical
        if (!needsWrite) {
            Log.i(LOG_TAG, "writeWarpZshenv: ${zshenvPath.absolutePath} already current; skipping")
            return
        }
        try {
            zshenvPath.parentFile?.mkdirs()
            zshenvPath.writeText(canonical)
            Log.i(LOG_TAG, "writeWarpZshenv: wrote ${zshenvPath.absolutePath} (${canonical.length} bytes)")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "writeWarpZshenv: failed: ${e.message}", e)
        }
    }

    /**
     * M4-S07: write apt runtime-config override to $PREFIX/etc/apt/apt.conf.
     *
     * Termux's apt binary has /data/data/com.termux/files/usr/etc/apt/apt.conf.d/
     * baked in as its compile-time default. Without an override file apt fails
     * with "Unable to determine a suitable packaging system type" because none
     * of its Dir::* lookups resolve. The fix is to write an apt.conf that
     * points every directory at the dev.warp.mobile prefix; the spawn-time
     * env var APT_CONFIG points apt at this file before it consults the
     * compile-time default.
     *
     * Idempotency: only writes if $PREFIX/etc/apt exists (M4-S05 extracted)
     * AND the file is missing or has stale content.
     */
    private fun writeAptConfig() {
        val prefix = "${applicationInfo.dataDir}/files/usr"
        val aptDir = File("$prefix/etc/apt")
        if (!aptDir.exists()) {
            Log.i(LOG_TAG, "writeAptConfig: $prefix/etc/apt not present (M4-S05 not run yet); skipping")
            return
        }
        // Ensure apt.conf.d exists too — apt reads parts/ files even when
        // the main apt.conf is fully populated.
        File("$prefix/etc/apt/apt.conf.d").mkdirs()
        File("$prefix/var/log/apt").mkdirs()
        File("$prefix/var/cache/apt/archives/partial").mkdirs()
        File("$prefix/var/lib/apt/lists/partial").mkdirs()

        val aptConfPath = File("$prefix/etc/apt/apt.conf")
        // Canonical apt.conf overriding every Dir:: that apt's compile-time
        // default would otherwise resolve to /data/data/com.termux/...
        val canonical = """
            |# Warp Mobile apt.conf override (M4-S07).
            |# Generated by WarpTerminalService.writeAptConfig on app launch.
            |# DO NOT edit by hand — content is reproducible at every service start.
            |#
            |# Termux's apt binary has /data/data/com.termux/files/usr/etc/apt/
            |# baked in as compile-time default; without these overrides apt
            |# fails with "Unable to determine a suitable packaging system type".
            |
            |Dir "$prefix";
            |Dir::Etc "$prefix/etc/apt";
            |Dir::Etc::main "apt.conf";
            |Dir::Etc::parts "apt.conf.d";
            |Dir::Etc::sourcelist "sources.list";
            |Dir::Etc::sourceparts "sources.list.d";
            |Dir::Etc::trusted "trusted.gpg";
            |Dir::Etc::trustedparts "trusted.gpg.d";
            |Dir::Etc::preferences "preferences";
            |Dir::Etc::preferencesparts "preferences.d";
            |Dir::Cache "$prefix/var/cache/apt";
            |Dir::Cache::archives "archives";
            |Dir::Cache::pkgcache "pkgcache.bin";
            |Dir::Cache::srcpkgcache "srcpkgcache.bin";
            |Dir::State "$prefix/var/lib/apt";
            |Dir::State::lists "lists";
            |Dir::State::status "$prefix/var/lib/dpkg/status";
            |Dir::Bin::dpkg "$prefix/bin/dpkg";
            |Dir::Bin::gzip "$prefix/bin/gzip";
            |Dir::Bin::xz "$prefix/bin/xz";
            |Dir::Bin::bzip2 "$prefix/bin/bzip2";
            |Dir::Bin::lzma "$prefix/bin/xz";
            |Dir::Bin::zstd "$prefix/bin/zstd";
            |Dir::Bin::tar "$prefix/bin/tar";
            |Dir::Bin::lz4 "$prefix/bin/lz4";
            |Dir::Bin::methods "$prefix/lib/apt/methods";
            |Dir::Bin::solvers "$prefix/lib/apt/solvers";
            |Dir::Bin::planners "$prefix/lib/apt/planners";
            |# M5-S07 attempted #clear Dir::Bin::solvers::/planners:: but apt's
            |# compile-time LIST defaults are written deeper than apt.conf can
            |# override. The SCALAR forms above are what apt actually uses at
            |# runtime (verified end-to-end); the LIST forms with com.termux
            |# entries are cosmetic dump-output noise only. M5-S07 deferred to
            |# v1-release: clean fix would be a recompile of apt with our
            |# prefix as compile-time default, which lives in the Option C
            |# from-source build path (M4-S03 strategy doc).
            |Dir::Log "$prefix/var/log/apt";
            |DPkg::Path "$prefix/bin:$prefix/sbin:/system/bin";
            |DPkg::Options:: "--admindir=$prefix/var/lib/dpkg";
            |DPkg::Options:: "--instdir=$prefix";
            |Acquire::https::CaInfo "$prefix/etc/tls/cert.pem";
        """.trimMargin().trimStart() + "\n"

        val needsWrite = !aptConfPath.exists() || aptConfPath.readText() != canonical
        if (!needsWrite) {
            Log.i(LOG_TAG, "writeAptConfig: ${aptConfPath.absolutePath} already current; skipping")
            return
        }
        try {
            aptConfPath.writeText(canonical)
            Log.i(LOG_TAG, "writeAptConfig: wrote ${aptConfPath.absolutePath} (${canonical.length} bytes)")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "writeAptConfig: failed: ${e.message}", e)
        }
    }

    /**
     * M4-S06: build the spawn-time environment for the PTY child.
     *
     * Rust's pty.rs uses execve, NOT execvpe — so the env we pass IS the
     * complete env of the child process. We must include EVERYTHING the
     * child needs: PATH (for command lookup), HOME (for shell + git config
     * defaults), TMPDIR (for everything that writes /tmp).
     *
     * Per `.omc/prd.json` M4-S06 round-7 ACs:
     *   - HOME, ZDOTDIR (M4-S06 AC #2): standard env, work as zsh inherited
     *   - GIT_EXEC_PATH (round-7 AC #7): override git's compile-time
     *     /data/data/com.termux/files/usr/libexec/git-core default
     *   - TERMINFO, LOCPATH (round-7 AC #8): broader compile-time default
     *     coverage (terminal database + locale data)
     *   - SSL_CERT_FILE, SSL_CERT_DIR (M4-S07 round-7 AC): TLS CA path
     *     override (libcurl looks here before falling back to compile-time
     *     com.termux defaults)
     *
     * Note: MODULE_PATH/FPATH env vars NOT set — zsh ignores MODULE_PATH
     * and the compile-time fpath default carries stale com.termux entries
     * regardless. We rely on the $ZDOTDIR/.zshenv shell-array fix above.
     */
    private fun buildPrefixEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val prefix = "${applicationInfo.dataDir}/files/usr"
        val home = "${applicationInfo.dataDir}/files/home"
        // home/ is in the app's writable area regardless of M4-S05 state, so
        // mkdirs here is safe (no race with bootstrap atomic rename).
        // Codex round-1 finding 3: $PREFIX/tmp mkdirs MOVED OUT of this
        // function and into writeWarpZshenv (gated on zsh existence) to
        // avoid racing the M4-S05 `usr.tmp/ → usr/` atomic rename.
        File(home).mkdirs()

        // Codex round-1 finding 4: AC #2 says PATH=$PREFIX/bin:$PATH but
        // the round-1 implementation hardcoded $PREFIX/bin:/system/bin,
        // dropping all other entries from the parent's PATH (e.g.
        // /apex/com.android.runtime/bin which has linker shims). Honor
        // the inherited PATH per AC text.
        val parentPath = System.getenv("PATH") ?: "/system/bin"

        // V1-prep iteration 20 (2026-05-02): with the nativeLibraryDir
        // refactor + installTermuxBinSymlinks turning each $PREFIX/bin/<name>
        // into a symlink → ${nativeLibraryDir}/lib<name>.so, the
        // `untrusted_app` domain's `neverallow ... app_data_file:file
        // execute` rule no longer matters (the symlink target is
        // apk_data_file, exec-allowed). PATH can put $PREFIX/bin FIRST
        // again so GNU coreutils / zsh / curl / etc shadow toybox.
        return buildMap {
            // PATH: $PREFIX/bin first (Termux convention) so users get the
            // featureful GNU/Termux flavor of every tool. parentPath
            // (/system/bin + /apex/.../bin) is appended so Android-specific
            // tools (am, pm, settings, getprop, dumpsys) plus apex linker
            // shims remain reachable.
            put("PATH", "$prefix/bin:$parentPath")
            put("HOME", home)
            put("PREFIX", prefix)
            put("TERMUX_PREFIX", prefix) // termux-tools scripts read this
            put("TMPDIR", "$prefix/tmp")
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
            put("LC_ALL", "en_US.UTF-8")
            put("SHELL", "$prefix/bin/zsh")

            // M4-S06 AC #6: ZDOTDIR points at $PREFIX/etc so zsh sources our
            // canonical .zshenv (written above by writeWarpZshenv).
            put("ZDOTDIR", "$prefix/etc")

            // M4-S06 AC #7: git compile-time exec-path default override.
            put("GIT_EXEC_PATH", "$prefix/libexec/git-core")
            put("GIT_TEMPLATE_DIR", "$prefix/share/git-core/templates")

            // M4-S06 AC #8: terminfo + locale.
            put("TERMINFO", "$prefix/share/terminfo")
            put("LOCPATH", "$prefix/share/locale")

            // M4-S07 round-7 AC #7: TLS CA path override (libcurl looks
            // here first; without this it falls back to the compile-time
            // com.termux default and certificate validation breaks).
            put("SSL_CERT_FILE", "$prefix/etc/tls/cert.pem")
            put("SSL_CERT_DIR", "$prefix/etc/tls/certs")

            // M4-S07: APT_CONFIG points apt at our override file (written
            // by writeAptConfig on service onCreate). Without this, apt
            // tries the compile-time default /data/data/com.termux/files/usr
            // /etc/apt/apt.conf.d/ which is unreachable from our app
            // sandbox.
            put("APT_CONFIG", "$prefix/etc/apt/apt.conf")

            // V1-prep iteration 24 (2026-05-02): Termux's git is compiled
            // with --with-pager=pager (Debian convention — `pager` is a
            // wrapper that resolves to less). The wrapper isn't shipped in
            // the bootstrap zip, so `git log` fails with
            //   error: cannot run pager: No such file or directory
            //   fatal: unable to execute pager 'pager'
            // GIT_PAGER=less makes git use the actual less binary (which IS
            // in $PREFIX/bin) without the user needing to set core.pager.
            put("GIT_PAGER", "less")
            // PAGER also serves man, less itself, and other tools that
            // honor it. Defaulting to less matches every common shell rc.
            put("PAGER", "less")

            // Caller-supplied overrides win (e.g., test scripts wanting a
            // specific TERM or TMPDIR for isolation).
            putAll(extra)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel coroutines FIRST so read loops stop before killAll closes fds (Fix #1)
        serviceJob.cancel()
        unregisterReceiver(receiver)
        ptyManager.killAll()
        Log.i(LOG_TAG, "WarpTerminalService destroyed, all PTY sessions killed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Intent handlers ──────────────────────────────────────────────────────

    private fun handleSpawn(intent: Intent) {
        val cmdId = intent.getStringExtra("cmd_id") ?: "default"
        // M4-S06: default program changes from /system/bin/sh to $PREFIX/bin/zsh
        // (closes M3-S06 hook execution deferral — zsh now available post-M4-S05
        // extraction). Falls back to /system/bin/sh if zsh isn't extracted yet
        // (M4-S05 not run, e.g., race during first-launch coroutine startup);
        // the M3 PTY pipeline still works on mksh for that fallback.
        //
        // Codex round-1 finding 2: writeWarpZshenv() called here AS WELL as
        // in onCreate so a race where service starts before bootstrap finishes
        // gets retried at every spawn. Idempotent + cheap (~ms after first
        // write). Without this, .zshenv could remain unwritten forever if
        // service onCreate happened to win the race against M4-S05.
        // Same logic for writeAptConfig (M4-S07): both files live INSIDE usr/
        // and get wiped by bootstrap_install's atomic rename if onCreate's
        // call ran before the bootstrap coroutine finished. Spawn-time retry
        // ensures both are present by the time a real shell launches.
        writeWarpZshenv()
        writeAptConfig()
        // Iteration 20 retry: same reason — bootstrap may have completed
        // between onCreate and this spawn. Symlink installation is idempotent
        // and cheap (~1ms when no-op).
        installTermuxBinSymlinks()
        // V1-prep iteration 20 (2026-05-02): the nativeLibraryDir-shipped
        // libzsh.so PoC proved that binaries packaged as APK lib/<abi>/lib*.so
        // are execve-able from `untrusted_app` domain (label is
        // `apk_data_file` which has execute permission, unlike `app_data_file`
        // which has `neverallow ... execute`). Default-spawn libzsh.so
        // when present so the launcher path produces a real zsh prompt
        // instead of the iteration-18 mksh fallback. Falls through to
        // the legacy $PREFIX/bin/zsh if the lib variant isn't shipped (e.g.
        // running an older APK against a newer service binary), then to
        // /system/bin/sh as last resort.
        val nativeZsh = "${applicationInfo.nativeLibraryDir}/libzsh.so"
        val prefixZsh = "${applicationInfo.dataDir}/files/usr/bin/zsh"
        val defaultProgram = when {
            File(nativeZsh).exists() -> nativeZsh
            File(prefixZsh).exists() -> prefixZsh
            else -> "/system/bin/sh"
        }
        val program = intent.getStringExtra("program") ?: defaultProgram
        val args    = intent.getStringArrayExtra("args") ?: emptyArray()
        val cmd     = intent.getStringExtra("cmd")

        val (resolvedProgram, resolvedArgs) = if (cmd != null) {
            // Convenience: --es cmd "bash" maps to /system/bin/bash with no args
            val bin = if (cmd.startsWith("/")) cmd else "/system/bin/$cmd"
            Pair(bin, emptyArray<String>())
        } else {
            Pair(program, args)
        }

        val cwdExtra = intent.getStringExtra("cwd")
        val homeDir = "${applicationInfo.dataDir}/files/home"
        val resolvedCwd = if (cwdExtra != null && File(cwdExtra).isDirectory) cwdExtra else homeDir

        val env = buildPrefixEnv(mapOf("PWD" to resolvedCwd))

        Log.i(LOG_TAG, "PTY_SPAWN cmdId=$cmdId program=$resolvedProgram args=${resolvedArgs.toList()} cwd=$resolvedCwd env_keys=${env.keys.sorted()}")
        // Fix #2: PtyManager.spawn() kills existing session before replacing
        val ok = ptyManager.spawn(cmdId, resolvedProgram, resolvedArgs, env)
        if (ok) {
            // V1-prep iteration 27 (2026-05-02): apply initial winsize from
            // spawn extras BEFORE startReadLoop fires the first read. Without
            // this, zsh inherits the kernel default 80×24 which doesn't
            // match the renderer's dynamic_grid (computed from actual
            // SurfaceView dims) — line wraps misalign with visible grid,
            // cursor lands outside visible area, prompts overwrite output.
            val initRows = intent.getIntExtra("rows", 0).toShort()
            val initCols = intent.getIntExtra("cols", 0).toShort()
            if (initRows > 0 && initCols > 0) {
                Log.i(LOG_TAG, "PTY_SPAWN initial winsize cmdId=$cmdId rows=$initRows cols=$initCols")
                ptyManager.resize(cmdId, initRows, initCols)
            }
            startReadLoop(cmdId, resolvedProgram, env)
        }
    }

    private fun handleWrite(intent: Intent) {
        val cmdId = intent.getStringExtra("cmd_id") ?: "default"
        // Decoder precedence:
        //   1. `data` byte-array extra (rare via adb; intra-process broadcasts).
        //   2. `data_b64` base64-encoded string extra (M3-S08 — sidesteps the
        //      `am broadcast` argument parser that treats any value containing
        //      a `-l` / `-a`-shaped token as a flag).
        //   3. `data` plain string extra (legacy / simple ASCII).
        val data: ByteArray = intent.getByteArrayExtra("data")
            ?: intent.getStringExtra("data_b64")?.let {
                try {
                    android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    Log.e(LOG_TAG, "PTY_WRITE: invalid data_b64 ${e.message}")
                    return
                }
            }
            ?: intent.getStringExtra("data")?.let {
                val s = it.replace("\\n", "\n").replace("\\r", "\r")
                val bytes = s.toByteArray()
                if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) bytes + "\n".toByteArray() else bytes
            }
            ?: return
        Log.d(LOG_TAG, "PTY_WRITE cmdId=$cmdId bytes=${data.size}")
        ptyManager.write(cmdId, data)
    }

    private fun handleResize(intent: Intent) {
        val cmdId = intent.getStringExtra("cmd_id") ?: "default"
        val rows  = intent.getIntExtra("rows", 24).toShort()
        val cols  = intent.getIntExtra("cols", 80).toShort()
        Log.i(LOG_TAG, "PTY_RESIZE cmdId=$cmdId rows=$rows cols=$cols")
        ptyManager.resize(cmdId, rows, cols)
    }

    private fun handleKill(intent: Intent) {
        val cmdId = intent.getStringExtra("cmd_id") ?: "default"
        Log.i(LOG_TAG, "PTY_KILL cmdId=$cmdId")
        readJobs.remove(cmdId)?.cancel()
        fastDeathCounts.remove(cmdId)
        fallbackAttempted.remove(cmdId)
        ptyManager.kill(cmdId)
    }

    // ── PTY read loop ────────────────────────────────────────────────────────

    private fun startReadLoop(cmdId: String, program: String, env: Map<String, String>) {
        // Fix #2: cancel existing read job before replacing to avoid competing loops
        readJobs.remove(cmdId)?.cancel()
        val spawnedAtMs = System.currentTimeMillis()
        var bytesRead = 0L
        val job = scope.launch {
            val buf = ByteArray(4096)
            while (isActive) {
                // Fix #1: use readDirect (non-locking) to avoid deadlock with kill()
                val chunk = ptyManager.readDirect(cmdId, buf.size) ?: break
                if (chunk.isEmpty()) {
                    kotlinx.coroutines.delay(20)
                    continue
                }
                bytesRead += chunk.size
                // M3-S04: forward each PTY chunk to the Rust terminal model.
                val ingested = NativeBridge.terminalInputBytes(cmdId, chunk)
                if (ingested < 0) {
                    Log.w(LOG_TAG, "terminalInputBytes failed cmdId=$cmdId chunk_size=${chunk.size}")
                }
                val isAlt = try { NativeBridge.terminalIsAltScreen() } catch (e: Throwable) { false }
                SessionManager.getInstance(applicationContext).onToggleRawMode(isAlt)

                val text = chunk.toString(Charsets.UTF_8)
                // Log each line tagged WarpTerminal:PtyOutput as expected by test drivers
                for (line in text.lines()) {
                    if (line.isNotEmpty()) {
                        Log.i(PTY_OUTPUT_TAG, line)
                    }
                }
                // Fix #4: restrict PTY_OUTPUT to our own package (no data leak)
                val out = Intent(ACTION_OUTPUT).apply {
                    setPackage(packageName)
                    putExtra("cmd_id", cmdId)
                    putExtra("data", chunk)
                }
                sendBroadcast(out)
            }
            val aliveForMs = System.currentTimeMillis() - spawnedAtMs
            val exitCode = ptyManager.getExitStatus(cmdId) ?: -1
            Log.i(LOG_TAG, "read loop ended cmdId=$cmdId alive_ms=$aliveForMs bytes_read=$bytesRead program=$program exitCode=$exitCode")
            SessionManager.getInstance(applicationContext).onToggleRawMode(false)

            val fastDeath = aliveForMs in 0..FAST_DEATH_THRESHOLD_MS
            if (fastDeath) {
                val currentCount = (fastDeathCounts[cmdId] ?: 0) + 1
                fastDeathCounts[cmdId] = currentCount

                if (currentCount <= MAX_FAST_DEATH_RETRIES) {
                    val backoffMs = minOf(500L * (1L shl (currentCount - 1)), 5000L)
                    Log.w(
                        LOG_TAG,
                        "Fast death detected for $program (cmdId=$cmdId, attempt $currentCount/$MAX_FAST_DEATH_RETRIES, alive ${aliveForMs}ms, exitCode $exitCode); retrying in ${backoffMs}ms with fallback /system/bin/sh"
                    )
                    kotlinx.coroutines.delay(backoffMs)

                    // Clear the terminal grid so the failed shell's stderr diagnostic doesn't bleed into the UI
                    NativeBridge.terminalInputBytes(cmdId, "\u001b[2J\u001b[H".toByteArray())
                    val fallbackProg = "/system/bin/sh"
                    ptyManager.kill(cmdId)
                    val ok = ptyManager.spawn(cmdId, fallbackProg, emptyArray(), env)
                    if (ok) {
                        startReadLoop(cmdId, fallbackProg, env)
                    } else {
                        val errCode = if (exitCode != -1) exitCode else 127
                        val errBanner = "\r\n\u001b[31;1m[Warp Terminal Error] Failed to spawn fallback shell (exit code: $errCode).\u001b[0m\r\n"
                        NativeBridge.terminalInputBytes(cmdId, errBanner.toByteArray())
                        try {
                            SessionManager.getInstance(applicationContext).updateProcessState(cmdId, ProcessState.ERROR, errCode)
                        } catch (e: Throwable) {}
                        ptyManager.kill(cmdId)
                    }
                } else {
                    Log.e(LOG_TAG, "Fast death auto-recovery capped out after $MAX_FAST_DEATH_RETRIES retries for cmdId=$cmdId")
                    val errCode = if (exitCode != -1) exitCode else 127
                    val errBanner = "\r\n\u001b[31;1m[Warp Terminal Error] Shell exited immediately (exit code: $errCode). Auto-recovery capped out after $MAX_FAST_DEATH_RETRIES retries.\u001b[0m\r\n"
                    NativeBridge.terminalInputBytes(cmdId, errBanner.toByteArray())
                    try {
                        SessionManager.getInstance(applicationContext).updateProcessState(cmdId, ProcessState.ERROR, errCode)
                    } catch (e: Throwable) {}
                    ptyManager.kill(cmdId)
                }
            } else {
                fastDeathCounts.remove(cmdId)
                Log.i(LOG_TAG, "Shell process cmdId=$cmdId exited after ${aliveForMs}ms with exit code $exitCode")
                val finalState = if (exitCode == 0) ProcessState.EXITED else ProcessState.ERROR
                try {
                    SessionManager.getInstance(applicationContext).updateProcessState(cmdId, finalState, exitCode)
                } catch (e: Throwable) {}
                ptyManager.kill(cmdId)
            }
        }
        readJobs[cmdId] = job
    }
}
