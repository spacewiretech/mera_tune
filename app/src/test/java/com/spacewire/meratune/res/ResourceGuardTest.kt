package com.spacewire.meratune.res

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Mechanical guards for the UI refresh's resource rules (plan P1):
 * - layouts use Manrope, never the system `sans-serif*` families (the incoming-call overlay and
 *   notification keep their own look);
 * - the app theme is light-only (no DayNight parent in any `values*` folder);
 * - the incoming-call overlay, notification and their drawables never use the retuned brand tokens;
 * - text weights never ride on a `Text.MeraTune*` / `TextAppearance.MeraTune*` textAppearance,
 *   because the theme's fontFamily overrides the font inside a textAppearance (use `style=`);
 * - no theme sets the app-namespace `fontFamily`: AppCompat prefers it over `android:fontFamily`
 *   whenever it resolves (theme values included), so it would turn every layout's
 *   `android:fontFamily="@font/manrope_bold"` into the theme's Regular.
 */
class ResourceGuardTest {

    /** Excluded from the Manrope rule for good: overlay, RemoteViews notification, dead template. */
    private val systemFontLayouts = setOf(
        "activity_incoming_call_theme.xml",
        "notification_incoming_call.xml",
        "activity_main.xml",
    )

    /** Incoming-call overlay and notifier resources, which must stay visually unchanged. */
    private val incomingCallLayouts = listOf("activity_incoming_call_theme.xml", "notification_incoming_call.xml")
    private val incomingCallDrawablePrefixes = listOf("bg_call_", "bg_incoming_")
    private val incomingCallDrawables = setOf(
        "ic_incoming_chip.xml",
        "ic_call_accept.xml",
        "ic_call_decline.xml",
        "ic_expand_more.xml",
        "ic_music_note.xml",
    )
    private val retunedToken = Regex("@color/(navy|gradient_[a-z0-9_]+|text_[a-z0-9_]+)\\b")

    private val meraTuneTextAppearance = Regex("^@style/(Text|TextAppearance)\\.MeraTune(\\.|$)")

    private val resDir: File by lazy {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File(System.getProperty("user.dir"), "src/main/res"),
            File(System.getProperty("user.dir"), "app/src/main/res"),
        )
        candidates.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("Could not locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    private fun layouts(): List<File> {
        val files = File(resDir, "layout").listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }
        assertTrue("no layouts found in $resDir/layout", files.isNotEmpty())
        return files
    }

    private fun valuesFiles(): List<File> =
        resDir.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
            .flatMap { folder -> folder.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
            .sortedBy { it.path }

    private fun parse(file: File): org.w3c.dom.Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

    private fun elements(file: File, tag: String = "*"): List<Element> {
        val nodes = parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun relative(file: File): String = file.relativeTo(resDir).path

    @Test
    fun layoutsDoNotUseSystemSansSerif() {
        val failures = layouts()
            .filter { it.name !in systemFontLayouts }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if ("sans-serif" in line) "${relative(file)}:${index + 1}: ${line.trim()}" else null
                }
            }
        if (failures.isNotEmpty()) {
            fail(
                "Layouts must use @font/manrope_* (sans-serif-medium + bold -> manrope_bold, " +
                    "sans-serif-medium -> manrope_medium):\n" + failures.joinToString("\n"),
            )
        }
    }

    /** Covers values-night/themes.xml (the old DayNight override) and every other values folder. */
    @Test
    fun themeIsNeverDayNight() {
        val valuesFiles = valuesFiles()
        assertTrue("no values files found in $resDir", valuesFiles.any { it.path.endsWith("values/themes.xml") })
        val failures = valuesFiles.flatMap { file ->
            elements(file, "style")
                .filter { "DayNight" in it.getAttribute("parent") }
                .map { "${relative(file)}: style ${it.getAttribute("name")} has parent ${it.getAttribute("parent")}" }
        }
        if (failures.isNotEmpty()) {
            fail(
                "The app is light-only (MODE_NIGHT_NO + Theme.Material3.Light); no DayNight themes:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun themesDoNotSetAppFontFamily() {
        val failures = valuesFiles().flatMap { file ->
            elements(file, "style")
                .filter { "Theme" in it.getAttribute("name") || "Theme" in it.getAttribute("parent") }
                .flatMap { style ->
                    val items = style.getElementsByTagName("item")
                    (0 until items.length).map { items.item(it) as Element }
                        .filter { it.getAttribute("name") == "fontFamily" }
                        .map { "${relative(file)}: style ${style.getAttribute("name")} sets fontFamily=${it.textContent.trim()}" }
                }
        }
        if (failures.isNotEmpty()) {
            fail(
                "Themes may set only android:fontFamily. AppCompat prefers the app-namespace fontFamily, " +
                    "so a theme value overrides every view's android:fontFamily and drops its weight:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun incomingCallResourcesDoNotUseRetunedTokens() {
        val drawables = File(resDir, "drawable").listFiles { file ->
            file.extension == "xml" &&
                (file.name in incomingCallDrawables || incomingCallDrawablePrefixes.any { file.name.startsWith(it) })
        }.orEmpty().toList()
        val files = incomingCallLayouts.map { File(resDir, "layout/$it") } + drawables
        files.forEach { assertTrue("missing ${relative(it)}", it.isFile) }

        val failures = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                retunedToken.find(line)?.let { "${relative(file)}:${index + 1}: ${it.value}" }
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "The incoming-call overlay and notification must stay unchanged; use call_* colours only:\n" +
                    failures.joinToString("\n"),
            )
        }
    }

    @Test
    fun meraTuneTextStylesAreNotUsedAsTextAppearance() {
        val layoutFailures = layouts().flatMap { file ->
            elements(file).flatMap { element ->
                val attributes = element.attributes
                (0 until attributes.length).mapNotNull { index ->
                    val attribute = attributes.item(index)
                    val name = attribute.localName ?: attribute.nodeName
                    if (name == "textAppearance" && meraTuneTextAppearance.containsMatchIn(attribute.nodeValue)) {
                        "${relative(file)}: ${element.tagName} ${attribute.nodeName}=\"${attribute.nodeValue}\""
                    } else {
                        null
                    }
                }
            }
        }
        val styleFailures = valuesFiles().flatMap { file ->
            elements(file, "item")
                .filter { it.getAttribute("name").endsWith("textAppearance") }
                .filter { meraTuneTextAppearance.containsMatchIn(it.textContent.trim()) }
                .map { "${relative(file)}: <item name=\"${it.getAttribute("name")}\">${it.textContent.trim()}</item>" }
        }
        val failures = layoutFailures + styleFailures
        if (failures.isNotEmpty()) {
            fail(
                "A theme-level fontFamily overrides the font inside a textAppearance, so the weight is lost. " +
                    "Apply Text.MeraTune.* with style=\"...\" or set android:fontFamily explicitly:\n" +
                    failures.joinToString("\n"),
            )
        }
    }
}
