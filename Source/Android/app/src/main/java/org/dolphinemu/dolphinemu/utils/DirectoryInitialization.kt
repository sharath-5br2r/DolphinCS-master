/*
 * Copyright 2014 Dolphin Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.dolphinemu.dolphinemu.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.IntSetting
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A class that spawns its own thread in order perform initialization.
 *
 * The initialization steps include:
 * - Extracting the Sys directory from the APK so it can be accessed using regular file APIs
 * - Letting the native code know where on external storage it should place the User directory
 * - Running the native code's init steps (which include things like populating the User directory)
 */
object DirectoryInitialization {
    const val PREF_USER_DIR_MODE = "userDirectoryMode"
    const val USER_DIR_MODE_SCOPED = 0
    const val USER_DIR_MODE_INTERNAL = 1
    const val USER_DIR_MODE_SDCARD = 2
    const val USER_DIR_MODE_CUSTOM = 3

    private const val PREF_MIGRATION_OFFERED = "userDataMigrationOffered"
    private const val PREF_PREVIOUS_USER_DIR_MODE = "previousUserDirMode"
    private const val PREF_CUSTOM_USER_DIR_PATH = "customUserDirPath"

    // The exact real path we were using right before switching, captured at the moment of
    // switching (while userPath still reflects the old location). This is what migration
    // actually reads from — NOT reconstructed from the mode afterward, since two different
    // custom folders share the same mode number (CUSTOM) and the mode alone can't tell them
    // apart once the new path has overwritten the old one in prefs.
    private const val PREF_MIGRATION_SOURCE_PATH = "migrationSourcePath"

    private val directoryState = MutableLiveData(DirectoryInitializationState.NOT_YET_INITIALIZED)

    @Volatile
    private var areDirectoriesAvailable = false

    private lateinit var userPath: String
    private lateinit var driverPath: String
    private var usingLegacyUserDirectory = false
    private var userDirectoryWasPreExisting = false

    enum class DirectoryInitializationState {
        NOT_YET_INITIALIZED, INITIALIZING, DOLPHIN_DIRECTORIES_INITIALIZED
    }

    @JvmStatic
    fun start(context: Context) {
        if (directoryState.value != DirectoryInitializationState.NOT_YET_INITIALIZED) {
            return
        }

        directoryState.value = DirectoryInitializationState.INITIALIZING

        // Can take a few seconds to run, so don't block UI thread.
        thread { init(context) }
    }

    private fun init(context: Context) {
        if (directoryState.value == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED) {
            return
        }

        if (!setDolphinUserDirectory(context)) {
            ContextCompat.getMainExecutor(context).execute {
                Toast.makeText(context, R.string.external_storage_not_mounted, Toast.LENGTH_LONG)
                    .show()
                exitProcess(1)
            }
            return
        }

        // Record before Initialize() creates default folders, so migration can distinguish
        // a pre-existing user directory from one Dolphin just scaffolded fresh
        userDirectoryWasPreExisting = File(userPath).let { it.exists() && it.listFiles()?.isNotEmpty() == true }
        Log.info("[CustomLocation] init: userPath=$userPath, mode=${getStorageMode(context)}, preExisting=$userDirectoryWasPreExisting")

        // A storage location change restarts into this directory before the user has decided
        // (or before the copy has run), so handle both the "Keep Existing" conflict case and the
        // fresh/empty clean-migration case before anything else reads this directory's config.
        run {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean(PREF_MIGRATION_OFFERED, false)) {
                val sourcePath = prefs.getString(PREF_MIGRATION_SOURCE_PATH, null)
                if (sourcePath != null) {
                    val source = File(sourcePath)
                    if (source.absolutePath != File(userPath).absolutePath) {
                        if (userDirectoryWasPreExisting) {
                            // Patch ahead of GameFileCache's own startup scan, which
                            // silently strips unresolvable ISOPaths and saves — otherwise
                            // that runs before the user even sees the migration dialog.
                            Log.info("[CustomLocation] init: pre-existing dest — patching stale paths ahead of scan")
                            patchStalePaths(context, source)
                        } else {
                            // Destination is empty until the copy runs, so first-run flags
                            // like the analytics prompt would otherwise reset and re-fire
                            // on every storage location change.
                            Log.info("[CustomLocation] init: empty dest — seeding first-run flags from source")
                            seedFirstRunFlagsFromSource(source)
                        }
                    }
                }
            }
        }

        extractSysDirectory(context)
        NativeLibrary.Initialize()

        areDirectoriesAvailable = true

        checkThemeSettings(context)

        directoryState.postValue(DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED)
    }

    private fun getLegacyUserDirectoryPath(): File? {
        val externalPath = Environment.getExternalStorageDirectory() ?: return null
        return File(externalPath, "dolphin-emu")
    }

    @JvmStatic
    fun hasSdCard(context: Context): Boolean = getSdCardRoot(context) != null

    @JvmStatic
    fun getStorageMode(context: Context): Int {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_USER_DIR_MODE, USER_DIR_MODE_SCOPED)
    }

    @JvmStatic
    fun setStorageMode(context: Context, mode: Int) {
        val current = getStorageMode(context)
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_USER_DIR_MODE, mode)
            .putInt(PREF_PREVIOUS_USER_DIR_MODE, current)
            .remove(PREF_MIGRATION_OFFERED)
        if (areDirectoriesAvailable) {
            Log.info("[CustomLocation] setStorageMode: capturing migration source=$userPath (mode $current -> $mode)")
            editor.putString(PREF_MIGRATION_SOURCE_PATH, userPath)
        }
        editor.apply()
    }

    @JvmStatic
    fun getCustomUserDirPath(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_CUSTOM_USER_DIR_PATH, null)

    /**
     * Records a user-chosen custom location and switches to custom mode. [parentRealPath] is the
     * real absolute path of the folder the user picked; we always place a "dolphin-emu" folder
     * inside it so we only ever own (and, on migration, delete) our own subfolder — never the
     * user's chosen folder itself. Mirrors [setStorageMode]'s bookkeeping so migration works.
     */
    @JvmStatic
    fun setCustomUserDir(context: Context, parentRealPath: String) {
        val current = getStorageMode(context)
        val userDir = File(parentRealPath, "dolphin-emu").absolutePath
        Log.info("[CustomLocation] setCustomUserDir: parent=$parentRealPath")
        Log.info("[CustomLocation]   userDir (dolphin-emu appended)=$userDir")
        Log.info("[CustomLocation]   previousMode=$current -> mode=CUSTOM($USER_DIR_MODE_CUSTOM)")
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_CUSTOM_USER_DIR_PATH, userDir)
            .putInt(PREF_USER_DIR_MODE, USER_DIR_MODE_CUSTOM)
            .putInt(PREF_PREVIOUS_USER_DIR_MODE, current)
            .remove(PREF_MIGRATION_OFFERED)
        // Captured here (not derived from mode later) specifically so custom->custom works: two
        // different custom folders are both mode CUSTOM, so once userDir above overwrites
        // PREF_CUSTOM_USER_DIR_PATH, the old path would otherwise be unrecoverable.
        if (areDirectoriesAvailable) {
            Log.info("[CustomLocation]   capturing migration source=$userPath")
            editor.putString(PREF_MIGRATION_SOURCE_PATH, userPath)
        }
        editor.apply()
    }

    /**
     * Converts an ACTION_OPEN_DOCUMENT_TREE tree URI into a real absolute filesystem path, or
     * null if it can't be resolved to one. Only real on-device storage exposed by the Android
     * external-storage provider (primary internal storage and physical SD cards) can be decoded;
     * cloud providers (Drive, etc.) have no real path and return null. Since we hold all-files
     * access, the returned path is then used directly for fast native file I/O — SAF is only ever
     * touched here, for the one-time folder selection.
     */
    @JvmStatic
    fun treeUriToRealPath(context: Context, treeUri: Uri): String? {
        Log.info("[CustomLocation] Decoding tree URI: $treeUri")
        Log.info("[CustomLocation]   authority=${treeUri.authority}")

        if (treeUri.authority != "com.android.externalstorage.documents") {
            Log.warning("[CustomLocation] Rejected: not the external-storage provider (likely cloud/other)")
            return null
        }

        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.error("[CustomLocation] Rejected: getTreeDocumentId threw: ${e.message}")
            return null
        }
        Log.info("[CustomLocation]   documentId=$documentId")

        val colon = documentId.indexOf(':')
        if (colon == -1) {
            Log.warning("[CustomLocation] Rejected: documentId has no ':' separator")
            return null
        }
        val volumeId = documentId.substring(0, colon)
        val relativePath = documentId.substring(colon + 1)
        Log.info("[CustomLocation]   volumeId=$volumeId, relativePath=$relativePath")

        val volumeRoot: File = if (volumeId.equals("primary", ignoreCase = true)) {
            val primary = Environment.getExternalStorageDirectory()
            if (primary == null) {
                Log.error("[CustomLocation] Rejected: getExternalStorageDirectory() returned null")
                return null
            }
            Log.info("[CustomLocation]   primary volume root=${primary.absolutePath}")
            primary
        } else {
            // Physical SD card / USB storage is mounted at /storage/<volumeId>. Prefer the
            // StorageManager's own directory when the volume UUID matches, in case an OEM mounts
            // it somewhere non-standard.
            val sdRoot = getSdCardRoot(context)
            Log.info("[CustomLocation]   sdRoot=${sdRoot?.absolutePath} (name=${sdRoot?.name})")
            if (sdRoot != null && sdRoot.name == volumeId) {
                Log.info("[CustomLocation]   using StorageManager SD root (UUID matched)")
                sdRoot
            } else {
                val fallback = File("/storage/$volumeId")
                Log.info("[CustomLocation]   using fallback mount point=${fallback.absolutePath}")
                fallback
            }
        }

        val resolved = if (relativePath.isEmpty()) volumeRoot.absolutePath
        else File(volumeRoot, relativePath).absolutePath
        Log.info("[CustomLocation] Decoded real path=$resolved")
        Log.info("[CustomLocation]   exists=${File(resolved).exists()}, isDir=${File(resolved).isDirectory}, canWrite=${File(resolved).canWrite()}")
        return resolved
    }

    enum class MigrationState { NONE, CLEAN, CONFLICT }

    @JvmStatic
    fun getMigrationState(context: Context): MigrationState {
        if (!areDirectoriesAvailable) {
            Log.info("[CustomLocation] getMigrationState=NONE (directories not available yet)")
            return MigrationState.NONE
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_MIGRATION_OFFERED, false)) {
            Log.info("[CustomLocation] getMigrationState=NONE (already offered)")
            return MigrationState.NONE
        }

        val sourcePath = prefs.getString(PREF_MIGRATION_SOURCE_PATH, null)
        if (sourcePath == null) {
            Log.info("[CustomLocation] getMigrationState=NONE (no migration source recorded)")
            return MigrationState.NONE
        }

        Log.info("[CustomLocation] getMigrationState: prevMode=${prefs.getInt(PREF_PREVIOUS_USER_DIR_MODE, -1)}, currentMode=${getStorageMode(context)}")
        val sourceDir = File(sourcePath)
        Log.info("[CustomLocation] getMigrationState: source=${sourceDir.absolutePath}, dest=$userPath")
        if (!sourceDir.exists() || sourceDir.listFiles()?.isEmpty() != false) {
            Log.info("[CustomLocation] getMigrationState=NONE (source missing or empty)")
            return MigrationState.NONE
        }

        val destDir = File(userPath)
        // Same resolved path means either the app fell back to scoped due to missing permission,
        // or (for custom mode) the user picked the exact same folder again — neither is a real
        // migration.
        if (sourceDir.canonicalPath == destDir.canonicalPath) {
            Log.info("[CustomLocation] getMigrationState=NONE (source==dest — permission fallback or same folder re-picked)")
            return MigrationState.NONE
        }

        // Use the pre-init snapshot so Dolphin's own folder scaffolding doesn't look like a conflict
        val state = if (userDirectoryWasPreExisting) MigrationState.CONFLICT else MigrationState.CLEAN
        Log.info("[CustomLocation] getMigrationState=$state (destPreExisting=$userDirectoryWasPreExisting)")
        return state
    }

    @JvmStatic
    fun markMigrationOffered(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_MIGRATION_OFFERED, true).apply()
    }

    enum class MigrationResult { SUCCESS, NOT_ENOUGH_SPACE, SD_CARD_UNAVAILABLE, FAILED }

    // Require some headroom on top of the exact byte count copied, since filesystem block
    // overhead means a copy can run out of space slightly before an exact total would suggest.
    private const val MIGRATION_SPACE_MARGIN_BYTES = 50L * 1024 * 1024

    @JvmStatic
    fun copyUserDataToNewLocation(
        context: Context,
        onProgress: (copied: Int, total: Int) -> Unit,
        onComplete: (MigrationResult) -> Unit
    ) {
        if (!areDirectoriesAvailable) { onComplete(MigrationResult.FAILED); return }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prevMode = prefs.getInt(PREF_PREVIOUS_USER_DIR_MODE, -1)
        val sourcePath = prefs.getString(PREF_MIGRATION_SOURCE_PATH, null)
            ?: run { onComplete(MigrationResult.FAILED); return }
        val source = File(sourcePath)
        val dest = File(userPath)
        Log.info("[CustomLocation] Migration START: prevMode=$prevMode, source=${source.absolutePath}, dest=${dest.absolutePath}")
        if (source.absolutePath == dest.absolutePath) {
            Log.info("[CustomLocation] Migration: source==dest, nothing to do")
            onComplete(MigrationResult.SUCCESS); return
        }

        thread {
            try {
                // The SD card can be removed in the window between the dialog appearing and the
                // user confirming — check it's still there rather than surfacing a raw IOException.
                val currentMode = getStorageMode(context)
                if (prevMode == USER_DIR_MODE_SDCARD || currentMode == USER_DIR_MODE_SDCARD) {
                    val sdRoot = getSdCardRoot(context)
                    if (sdRoot == null || !isVolumeMounted(sdRoot)) {
                        Log.error("[DirectoryInitialization] Migration aborted — SD card not available")
                        onComplete(MigrationResult.SD_CARD_UNAVAILABLE)
                        return@thread
                    }
                }

                // Source could have been emptied or removed in that same window
                if (!source.exists() || source.listFiles()?.isEmpty() != false) {
                    Log.error("[DirectoryInitialization] Migration aborted — source directory missing or empty")
                    onComplete(MigrationResult.FAILED)
                    return@thread
                }

                val files = source.walk().filter { it.isFile }.toList()
                val requiredBytes = files.sumOf { it.length() }
                Log.info("[CustomLocation] Migration: ${files.size} files, ${requiredBytes / (1024 * 1024)}MB to copy")

                // dest may not exist yet (clean migration) — walk up to the nearest existing
                // ancestor to find its writability and how much space is available on that volume.
                var spaceProbe = dest
                while (!spaceProbe.exists() && spaceProbe.parentFile != null) {
                    spaceProbe = spaceProbe.parentFile!!
                }
                Log.info("[CustomLocation] Migration: spaceProbe=${spaceProbe.absolutePath}, canWrite=${spaceProbe.canWrite()}, usable=${spaceProbe.usableSpace / (1024 * 1024)}MB")

                if (!spaceProbe.canWrite()) {
                    Log.error("[DirectoryInitialization] Migration aborted — destination is not writable")
                    onComplete(MigrationResult.FAILED)
                    return@thread
                }

                val availableBytes = spaceProbe.usableSpace
                if (availableBytes < requiredBytes + MIGRATION_SPACE_MARGIN_BYTES) {
                    Log.error(
                        "[DirectoryInitialization] Migration aborted — not enough free space " +
                            "(need ~${requiredBytes / (1024 * 1024)}MB + margin, " +
                            "have ~${availableBytes / (1024 * 1024)}MB available)"
                    )
                    onComplete(MigrationResult.NOT_ENOUGH_SPACE)
                    return@thread
                }

                // Clear destination first so no stale files remain after copy
                val clearFailed = dest.listFiles()?.any { !it.deleteRecursively() } == true
                if (clearFailed) {
                    Log.error("[DirectoryInitialization] Migration aborted — could not clear destination")
                    onComplete(MigrationResult.FAILED)
                    return@thread
                }

                val total = files.size
                onProgress(0, total)

                files.forEachIndexed { index, srcFile ->
                    val destFile = dest.resolve(srcFile.relativeTo(source))
                    destFile.parentFile?.mkdirs()
                    srcFile.copyTo(destFile, overwrite = true)
                    onProgress(index + 1, total)
                }

                Log.info("[CustomLocation] Migration: copy done, verifying...")
                if (verifyMigration(source, dest)) {
                    Log.info("[CustomLocation] Migration: verify OK, patching stale paths + deleting source")
                    // The copied Dolphin.ini still has path/URI values pointing at the old
                    // location — rewrite anything that resolves under the new user directory.
                    patchStalePaths(context, source)

                    // For Internal/SD Card/Custom we own the dolphin-emu folder entirely — delete
                    // it. For Scoped, Android manages the directory itself so only empty it.
                    if (prevMode == USER_DIR_MODE_INTERNAL ||
                        prevMode == USER_DIR_MODE_SDCARD ||
                        prevMode == USER_DIR_MODE_CUSTOM
                    ) {
                        source.deleteRecursively()
                    } else {
                        source.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    Log.info("[CustomLocation] Migration SUCCESS")
                    onComplete(MigrationResult.SUCCESS)
                } else {
                    Log.error("[DirectoryInitialization] Migration verification failed — source kept")
                    onComplete(MigrationResult.FAILED)
                }
            } catch (e: Exception) {
                Log.error("[DirectoryInitialization] Migration failed: ${e.message}")
                onComplete(MigrationResult.FAILED)
            }
        }
    }

    private fun verifyMigration(source: File, dest: File): Boolean {
        source.walk().filter { it.isFile }.forEach { srcFile ->
            val destFile = dest.resolve(srcFile.relativeTo(source))
            if (!destFile.exists() || destFile.length() != srcFile.length()) return false
        }
        return true
    }

    // Dolphin.ini keys that store filesystem paths and can end up stale after a storage
    // location change — either copied verbatim (clean migration) or already present in a
    // pre-existing folder the user chose to keep. ISOPath0, ISOPath1, ... are handled separately
    // since they're a numbered array rather than a fixed key.
    private val TRACKED_STRING_PATHS = mapOf(
        "Core" to setOf("DefaultISO"),
        "General" to setOf(
            "DumpPath", "LoadPath", "ResourcePackPath", "NANDRootPath",
            "WiiSDCardPath", "WiiSDCardSyncFolder", "WFSPath"
        ),
        "GBA" to setOf("BIOS", "GBPlayerRom", "SavesPath")
    )
    private val ISO_PATH_KEY = Regex("ISOPath\\d+")

    /**
     * Copies the analytics prompt's answer (and choice) from the previous user directory's
     * Dolphin.ini into the new, still-empty one, so the prompt doesn't reappear on every
     * storage location change while waiting for the real migration copy to run.
     */
    private fun seedFirstRunFlagsFromSource(source: File) {
        val sourceIni = File(source, "Config" + File.separator + "Dolphin.ini")
        if (!sourceIni.exists()) return

        var permissionAsked = false
        var analyticsEnabled = "False"
        var inAnalyticsSection = false

        try {
            sourceIni.forEachLine { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inAnalyticsSection = trimmed == "[Analytics]"
                    return@forEachLine
                }
                if (!inAnalyticsSection) return@forEachLine

                val eq = rawLine.indexOf('=')
                if (eq == -1) return@forEachLine
                when (rawLine.substring(0, eq).trim()) {
                    "PermissionAsked" -> permissionAsked = rawLine.substring(eq + 1).trim() == "True"
                    "Enabled" -> analyticsEnabled = rawLine.substring(eq + 1).trim()
                }
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to read source Dolphin.ini for seeding: ${e.message}")
            return
        }

        if (!permissionAsked) return

        val destIni = File(userPath, "Config" + File.separator + "Dolphin.ini")
        if (destIni.exists()) return // Don't clobber anything already scaffolded here

        try {
            destIni.parentFile?.mkdirs()
            destIni.writeText("[Analytics]\nPermissionAsked = True\nEnabled = $analyticsEnabled\n")
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to seed first-run flags: ${e.message}")
        }
    }

    /**
     * Rewrites path-like settings in Dolphin.ini that still point at [sourceRoot] (the previous
     * user directory) so they resolve under the current [userPath] instead. Values that don't
     * fall under [sourceRoot] are left untouched, since they were deliberately pointed elsewhere.
     *
     * content:// values are handled separately (see [decodeContentUriIfStillReal]) and don't
     * need to match [sourceRoot] at all — game folders, GBA BIOS, etc. are picked independently
     * of the user directory (usually pointing at wherever the user's ROMs actually live), so the
     * only thing that matters for those is whether the location they decode to still exists,
     * regardless of how many storage-mode switches or app reinstalls have happened since.
     */
    private fun patchStalePaths(context: Context, sourceRoot: File) {
        val iniFile = File(userPath, "Config" + File.separator + "Dolphin.ini")
        if (!iniFile.exists()) return

        val sourceRootAbs = sourceRoot.absolutePath

        val lines = try {
            iniFile.readLines()
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to read Dolphin.ini for path fixup: ${e.message}")
            return
        }

        var currentSection = ""
        var changed = false

        val newLines = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1)
                return@map line
            }

            val eq = line.indexOf('=')
            if (eq == -1) return@map line

            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty()) return@map line

            val isTracked = (currentSection == "General" && ISO_PATH_KEY.matches(key)) ||
                TRACKED_STRING_PATHS[currentSection]?.contains(key) == true
            if (!isTracked) return@map line

            val rewritten = rewriteStalePath(context, value, sourceRootAbs) ?: return@map line
            Log.info("[CustomLocation] patchStalePaths: [$currentSection] $key: $value -> $rewritten")
            changed = true
            "$key = $rewritten"
        }

        if (changed) {
            try {
                iniFile.writeText(newLines.joinToString("\n") + "\n")
            } catch (e: IOException) {
                Log.error("[DirectoryInitialization] Failed to rewrite stale paths: ${e.message}")
            }
        }
    }

    private fun rewriteStalePath(context: Context, value: String, sourceRootAbs: String): String? {
        if (ContentHandler.isContentUri(value)) {
            return decodeContentUriIfStillReal(context, value)
        }

        val suffix = when {
            value == sourceRootAbs -> ""
            value.startsWith(sourceRootAbs + File.separator) ->
                value.removePrefix(sourceRootAbs + File.separator)
            else -> return null
        }

        val newFile = if (suffix.isEmpty()) File(userPath) else File(userPath, suffix)
        return if (newFile.exists()) newFile.absolutePath else null
    }

    /**
     * Decodes a stored content:// URI (game folder, GBA BIOS, etc.) to its real absolute path and
     * returns it only if that path still exists on disk right now. Unlike [rewriteStalePath]'s
     * plain-path handling, this doesn't need to know any migration history — game folders and
     * similar are picked independently of the user directory, so "does the real thing still
     * exist" is the only question that matters, however far back or disconnected the entry's
     * origin is (a different app install, an old SD card path, etc.).
     */
    private fun decodeContentUriIfStillReal(context: Context, value: String): String? {
        val uri = try {
            Uri.parse(value)
        } catch (e: Exception) {
            return null
        }
        if (uri.authority != "com.android.externalstorage.documents") return null

        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e2: Exception) {
                return null
            }
        }

        val colon = documentId.indexOf(':')
        if (colon == -1) return null
        val volumeId = documentId.substring(0, colon)
        val relativePath = documentId.substring(colon + 1)

        val volumeRoot = volumeIdToRoot(context, volumeId) ?: return null
        val resolved = if (relativePath.isEmpty()) volumeRoot else File(volumeRoot, relativePath)
        return if (resolved.exists()) resolved.absolutePath else null
    }

    /** Resolves a SAF document ID's volume component to its real filesystem root. */
    private fun volumeIdToRoot(context: Context, volumeId: String): File? {
        return if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            val sdRoot = getSdCardRoot(context)
            if (sdRoot != null && sdRoot.name == volumeId) sdRoot else File("/storage/$volumeId")
        }
    }

    private fun hasLegacyStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            PermissionsHandler.hasWriteAccess(context)
        }
    }

    private fun getSdCardRoot(context: Context): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            return sm.storageVolumes
                .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                ?.directory
        }
        // Pre-R: strip Android/data/<pkg>/files (4 levels) from the scoped SD card path
        val dirs = ContextCompat.getExternalFilesDirs(context, null)
        var sdScoped = dirs.getOrNull(1) ?: return null
        repeat(4) { sdScoped = sdScoped.parentFile ?: return null }
        return sdScoped
    }

    private fun isVolumeMounted(root: File): Boolean {
        return try {
            Environment.getExternalStorageState(root) == Environment.MEDIA_MOUNTED
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    fun getUserDirectoryPath(context: Context?): File? {
        if (context == null) return null
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null

        return when (getStorageMode(context)) {
            USER_DIR_MODE_INTERNAL -> {
                if (hasLegacyStorageAccess(context)) {
                    usingLegacyUserDirectory = true
                    getLegacyUserDirectoryPath()
                } else {
                    usingLegacyUserDirectory = false
                    context.getExternalFilesDir(null)
                }
            }
            USER_DIR_MODE_SDCARD -> {
                val sdRoot = getSdCardRoot(context)
                if (sdRoot != null && hasLegacyStorageAccess(context)) {
                    usingLegacyUserDirectory = true
                    File(sdRoot, "dolphin-emu")
                } else {
                    usingLegacyUserDirectory = false
                    context.getExternalFilesDir(null)
                }
            }
            USER_DIR_MODE_CUSTOM -> {
                val customPath = getCustomUserDirPath(context)
                val hasAccess = hasLegacyStorageAccess(context)
                Log.info("[CustomLocation] getUserDirectoryPath CUSTOM: storedPath=$customPath, hasAllFilesAccess=$hasAccess")
                if (customPath != null && hasAccess) {
                    Log.info("[CustomLocation]   using custom path=$customPath")
                    usingLegacyUserDirectory = true
                    File(customPath)
                } else {
                    // No stored path yet, or All Files Access not granted — fall back to scoped
                    // so the app still boots. The permission prompt handles the rest on restart.
                    Log.warning("[CustomLocation]   falling back to scoped (path null or no access)")
                    usingLegacyUserDirectory = false
                    context.getExternalFilesDir(null)
                }
            }
            else -> { // USER_DIR_MODE_SCOPED
                usingLegacyUserDirectory = false
                context.getExternalFilesDir(null)
            }
        }
    }

    private fun setDolphinUserDirectory(context: Context): Boolean {
        val path = getUserDirectoryPath(context) ?: return false

        userPath = path.absolutePath

        Log.debug("[DirectoryInitialization] User Dir: $userPath")
        NativeLibrary.SetUserDirectory(userPath)

        var cacheDir = context.externalCacheDir
        if (cacheDir == null) {
            // In some custom ROMs getExternalCacheDir might return null for some reason. If that
            // is the case, fallback to getCacheDir which seems to work just fine.
            cacheDir = context.cacheDir ?: return false
        }

        Log.debug("[DirectoryInitialization] Cache Dir: ${cacheDir.path}")
        NativeLibrary.SetCacheDirectory(cacheDir.path)

        return true
    }

    private fun extractSysDirectory(context: Context) {
        val sysDirectory = File(context.filesDir, "Sys")

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val revision = NativeLibrary.GetGitRevision()
        if (preferences.getString("sysDirectoryVersion", "") != revision) {
            // There is no extracted Sys directory, or there is a Sys directory from another
            // version of Dolphin that might contain outdated files. Let's (re-)extract Sys.
            deleteDirectoryRecursively(sysDirectory)
            copyAssetFolder("Sys", sysDirectory, context)

            preferences.edit {
                putString("sysDirectoryVersion", revision)
            }
        }

        // Let the native code know where the Sys directory is.
        SetSysDirectory(sysDirectory.path)

        val driverDirectory = File(context.filesDir, "GPUDrivers")
        driverDirectory.mkdirs()
        val driverExtractedDir = File(driverDirectory, "Extracted")
        driverExtractedDir.mkdirs()
        val driverTmpDir = File(driverDirectory, "Tmp")
        driverTmpDir.mkdirs()
        val driverFileRedirectDir = File(driverDirectory, "FileRedirect")
        driverFileRedirectDir.mkdirs()

        SetGpuDriverDirectories(driverDirectory.path, context.applicationInfo.nativeLibraryDir)
        driverPath = driverExtractedDir.absolutePath
    }

    private fun deleteDirectoryRecursively(file: File) {
        if (file.isDirectory) {
            val files = file.listFiles() ?: return
            for (child in files) {
                deleteDirectoryRecursively(child)
            }
        }

        if (!file.delete()) {
            Log.error("[DirectoryInitialization] Failed to delete ${file.absolutePath}")
        }
    }

    @JvmStatic
    fun shouldStart(context: Context): Boolean {
        return getDolphinDirectoriesState().value == DirectoryInitializationState.NOT_YET_INITIALIZED && !isWaitingForWriteAccess(
            context
        )
    }

    @JvmStatic
    fun areDolphinDirectoriesReady(): Boolean {
        return directoryState.value == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED
    }

    @JvmStatic
    fun getDolphinDirectoriesState(): LiveData<DirectoryInitializationState> {
        return directoryState
    }

    @JvmStatic
    fun getUserDirectory(): String {
        if (!areDirectoriesAvailable) {
            throw IllegalStateException(
                "DirectoryInitialization must run before accessing the user directory!"
            )
        }

        return userPath
    }

    @JvmStatic
    fun getExtractedDriverDirectory(): String {
        if (!areDirectoriesAvailable) {
            throw IllegalStateException(
                "DirectoryInitialization must run before accessing the driver directory!"
            )
        }

        return driverPath
    }

    @JvmStatic
    fun getGameListCache(): File {
        return File(NativeLibrary.GetCacheDirectory(), "gamelist.cache")
    }

    private fun copyAsset(asset: String, output: File, context: Context) {
        Log.verbose("[DirectoryInitialization] Copying File $asset to $output")

        try {
            context.assets.open(asset).use { input ->
                FileOutputStream(output).use { outputStream ->
                    copyFile(input, outputStream)
                }
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to copy asset file: $asset${e.message}")
        }
    }

    private fun copyAssetFolder(assetFolder: String, outputFolder: File, context: Context) {
        Log.verbose("[DirectoryInitialization] Copying Folder $assetFolder to $outputFolder")

        try {
            val assetList = context.assets.list(assetFolder) ?: return

            var createdFolder = false
            for (file in assetList) {
                if (!createdFolder) {
                    if (!outputFolder.mkdir()) {
                        Log.error(
                            "[DirectoryInitialization] Failed to create folder " + outputFolder.absolutePath
                        )
                    }
                    createdFolder = true
                }

                val childAsset = assetFolder + File.separator + file
                val childOutput = File(outputFolder, file)
                copyAssetFolder(childAsset, childOutput, context)
                copyAsset(childAsset, childOutput, context)
            }
        } catch (e: IOException) {
            Log.error(
                "[DirectoryInitialization] Failed to copy asset folder: $assetFolder${e.message}"
            )
        }
    }

    @Throws(IOException::class)
    private fun copyFile(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int

        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
    }

    @JvmStatic
    fun preferOldFolderPicker(context: Context): Boolean {
        // As of January 2021, ACTION_OPEN_DOCUMENT_TREE seems to be broken on the Nvidia Shield TV
        // (the activity can't be navigated correctly with a gamepad). We can use the old folder
        // picker for the time being - Android 11 hasn't been released for this device. We have an
        // explicit check for Android 11 below in hopes that Nvidia will fix this before releasing
        // Android 11.
        //
        // No Android TV device other than the Nvidia Shield TV is known to have an implementation
        // of ACTION_OPEN_DOCUMENT or ACTION_OPEN_DOCUMENT_TREE that even launches, but
        // "fortunately", no Android TV device other than the Shield TV is known to be able to run
        // Dolphin (either due to the 64-bit requirement or due to the GLES 3.0 requirement), so
        // we can ignore this problem.
        //
        // All phones which are running a compatible version of Android support ACTION_OPEN_DOCUMENT
        // and ACTION_OPEN_DOCUMENT_TREE, as this is required by the mobile Android CTS (unlike
        // Android TV).

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R && PermissionsHandler.isExternalStorageLegacy() && TvUtil.isLeanback(
            context
        )
    }

    @JvmStatic
    fun isUsingLegacyUserDirectory(): Boolean {
        return usingLegacyUserDirectory
    }

    @JvmStatic
    fun isWaitingForWriteAccess(context: Context): Boolean {
        // This first check is only for performance, not correctness
        if (directoryState.value != DirectoryInitializationState.NOT_YET_INITIALIZED) {
            return false
        }

        val mode = getStorageMode(context)
        if (mode == USER_DIR_MODE_SCOPED) return false

        // On Android 11+, MANAGE_EXTERNAL_STORAGE is resolved at launch; we don't block here.
        // On older Android, block until WRITE_EXTERNAL_STORAGE is granted.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !PermissionsHandler.hasWriteAccess(context)
    }

    private fun checkThemeSettings(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (IntSetting.MAIN_INTERFACE_THEME.int != preferences.getInt(
                ThemeHelper.CURRENT_THEME, ThemeHelper.DEFAULT
            )
        ) {
            preferences.edit {
                putInt(ThemeHelper.CURRENT_THEME, IntSetting.MAIN_INTERFACE_THEME.int)
            }
        }

        if (IntSetting.MAIN_INTERFACE_THEME_MODE.int != preferences.getInt(
                ThemeHelper.CURRENT_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        ) {
            preferences.edit {
                putInt(ThemeHelper.CURRENT_THEME_MODE, IntSetting.MAIN_INTERFACE_THEME_MODE.int)
            }
        }

        if (BooleanSetting.MAIN_USE_BLACK_BACKGROUNDS.boolean != preferences.getBoolean(
                ThemeHelper.USE_BLACK_BACKGROUNDS, false
            )
        ) {
            preferences.edit {
                putBoolean(
                    ThemeHelper.USE_BLACK_BACKGROUNDS,
                    BooleanSetting.MAIN_USE_BLACK_BACKGROUNDS.boolean
                )
            }
        }
    }

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetSysDirectory(path: String)

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetGpuDriverDirectories(path: String, libPath: String)
}
