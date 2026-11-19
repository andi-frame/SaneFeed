package app.sanefeed.data

import android.content.Context
import app.sanefeed.core.DailyCounter
import app.sanefeed.core.Platform
import java.time.LocalDate
import java.time.format.DateTimeParseException

class PrefsCounterStore(context: Context) : DailyCounter.Store {
    private val prefs = context.getSharedPreferences("daily_counter", Context.MODE_PRIVATE)

    override fun load(platform: Platform): DailyCounter.Day? {
        val date = prefs.getString("${platform.id}.date", null) ?: return null
        val parsed = try {
            LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            return null
        }
        val keys = prefs.getString("${platform.id}.keys", null)?.split(SEPARATOR)?.filter(String::isNotEmpty)
        return DailyCounter.Day(parsed, prefs.getInt("${platform.id}.count", 0), keys.orEmpty())
    }

    override fun save(platform: Platform, day: DailyCounter.Day) {
        prefs.edit {
            putString("${platform.id}.date", day.date.toString())
            putInt("${platform.id}.count", day.count)
            putString("${platform.id}.keys", day.keys.joinToString(SEPARATOR))
        }
    }

    private companion object {
        // Post keys are "<handle>#<hex>", so a newline never occurs inside one.
        const val SEPARATOR = "\n"
    }
}
