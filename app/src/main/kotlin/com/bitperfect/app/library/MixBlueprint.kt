package com.bitperfect.app.library

import android.content.Context
import org.json.JSONArray
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class MixBlueprint(
    val id: String,
    val name: String,
    val description: String,
    val moods: List<String>,
    val genres: List<String>,
    val bpmMin: Int,
    val bpmMax: Int,
    val energyMin: Float,
    val energyMax: Float,
    val verifiedOnly: Boolean = false,
    val decade: String? = null
)

object BlueprintLoader {
    private var cachedBlueprints: List<MixBlueprint>? = null

    fun load(context: Context): List<MixBlueprint> {
        cachedBlueprints?.let { return it }

        val jsonString = try {
            context.assets.open("mix_blueprints.json").use { inputStream ->
                InputStreamReader(inputStream, StandardCharsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        val blueprints = mutableListOf<MixBlueprint>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                val moods = mutableListOf<String>()
                val moodsArray = obj.optJSONArray("moods")
                if (moodsArray != null) {
                    for (j in 0 until moodsArray.length()) {
                        moods.add(moodsArray.getString(j))
                    }
                }

                val genres = mutableListOf<String>()
                val genresArray = obj.optJSONArray("genres")
                if (genresArray != null) {
                    for (j in 0 until genresArray.length()) {
                        genres.add(genresArray.getString(j))
                    }
                }

                blueprints.add(
                    MixBlueprint(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.getString("description"),
                        moods = moods,
                        genres = genres,
                        bpmMin = obj.getInt("bpmMin"),
                        bpmMax = obj.getInt("bpmMax"),
                        energyMin = obj.getDouble("energyMin").toFloat(),
                        energyMax = obj.getDouble("energyMax").toFloat(),
                        verifiedOnly = obj.optBoolean("verified_only", false),
                        decade = obj.optString("decade", null).takeIf { it != "null" }
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cachedBlueprints = blueprints
        return blueprints
    }
}
