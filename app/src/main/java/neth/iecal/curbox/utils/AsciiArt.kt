package neth.iecal.curbox.utils

import android.content.Context
import android.widget.TextView

/**
 * Art lives in assets/ascii as plain text. Assets are compressed in the APK,
 * while string resources are stored as is.
 */
object AsciiArt {
    private const val DIR = "ascii"

    fun names(context: Context): List<String> =
        context.assets.list(DIR).orEmpty().map { it.removeSuffix(".txt") }.sorted()

    fun load(context: Context, name: String): String = try {
        context.assets.open("$DIR/$name.txt").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }
}

fun TextView.setAsciiArt(name: String) {
    text = AsciiArt.load(context, name)
}
