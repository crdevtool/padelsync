import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * The two apps published on Google Play under one app ID.
 *
 * Play keeps a single list of version codes per app ID, across phones and
 * watches, so the two apps must never use the same one. [slot] is the last
 * digit of each app's version code and is what keeps them apart.
 */
enum class FormFactor(val slot: Int) {
    PHONE(1),

    /** Higher than the phone's, so a watch offered both is given the watch app. */
    WATCH(2),
}

/** The key that signs what is uploaded to Google Play. */
class UploadKey(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

/**
 * Version numbers and release signing for the Android apps.
 *
 * Version code = release number x 10 + the app's [FormFactor.slot]:
 *
 *     release 7  ->  phone 71, watch 72
 *     release 8  ->  phone 81, watch 82
 *
 * The release number comes from the environment variable
 * `PADELSYNC_RELEASE_NUMBER`, which the release workflow sets to its run
 * number, so every release is higher than the one before. Without it the
 * number is 0 (phone 1, watch 2), which is what debug builds carry.
 */
object PlayRelease {
    private const val RELEASE_NUMBER = "PADELSYNC_RELEASE_NUMBER"
    private const val KEYSTORE_FILE = "PLAY_KEYSTORE_FILE"
    private const val KEYSTORE_PASSWORD = "PLAY_KEYSTORE_PASSWORD"
    private const val KEY_ALIAS = "PLAY_KEY_ALIAS"
    private const val KEY_PASSWORD = "PLAY_KEY_PASSWORD"

    /** Play refuses version codes above 2,100,000,000. */
    private const val MAX_RELEASE_NUMBER = 200_000_000

    /** The version players see, from `padelsync.versionName` in gradle.properties. */
    fun versionName(project: Project): String =
        project.providers.gradleProperty("padelsync.versionName").orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("padelsync.versionName is missing from gradle.properties.")

    fun versionCode(project: Project, formFactor: FormFactor): Int =
        releaseNumber(project) * 10 + formFactor.slot

    private fun releaseNumber(project: Project): Int {
        val text = env(project, RELEASE_NUMBER)?.trim() ?: return 0
        val number = text.toIntOrNull()
        if (number == null || number < 0 || number > MAX_RELEASE_NUMBER) {
            throw GradleException("$RELEASE_NUMBER must be a whole number from 0 to $MAX_RELEASE_NUMBER, but is '$text'.")
        }
        return number
    }

    /**
     * The upload key described by the environment, or null if none of its
     * four variables is set, in which case release builds are left unsigned.
     *
     * Setting only some of them is an error: a release that silently came
     * out unsigned, or signed with the wrong key, would be worse than a
     * build that stops.
     */
    fun uploadKey(project: Project): UploadKey? {
        val names = listOf(KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
        val values = names.associateWith { env(project, it) }
        if (values.values.all { it == null }) return null
        val missing = values.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The Google Play upload key is only partly configured. Missing: ${missing.joinToString()}. " +
                    "Set all four of ${names.joinToString()}, or none for an unsigned release build.",
            )
        }
        val storeFile = File(values.getValue(KEYSTORE_FILE)!!)
        if (!storeFile.isFile) {
            throw GradleException("$KEYSTORE_FILE points to '$storeFile', which is not a file.")
        }
        return UploadKey(
            storeFile = storeFile,
            storePassword = values.getValue(KEYSTORE_PASSWORD)!!,
            keyAlias = values.getValue(KEY_ALIAS)!!,
            keyPassword = values.getValue(KEY_PASSWORD)!!,
        )
    }

    /** An environment variable, with "set but empty" treated as not set. */
    private fun env(project: Project, name: String): String? =
        project.providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
}
