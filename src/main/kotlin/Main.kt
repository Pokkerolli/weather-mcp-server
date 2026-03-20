import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val httpClient = HttpClient(ClientCIO)

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

fun main() {
    val host = System.getenv("MCP_HOST")?.trim()?.ifBlank { null } ?: "127.0.0.1"
    val port = System.getenv("MCP_PORT")
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: 3001

    println("Starting Weather MCP Server on http://$host:$port/mcp")

    embeddedServer(CIO, host = host, port = port) {
        install(ContentNegotiation) { json(McpJson) }
        mcpStreamableHttp { createServer() }
    }.start(wait = true)
}

private fun createServer(): Server {
    val server = Server(
        Implementation(
            name = "weather-mcp-server",
            version = "2.0.0",
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    addSearchLocationTool(server)
    addGetForecastTool(server)
    addGetCityForecastTool(server)

    return server
}

private fun addSearchLocationTool(server: Server) {
    server.addTool(
        name = "search_location",
        description = "Search for a city by name and return structured location candidates with coordinates. " +
            "Use this if you need to resolve a city before requesting a forecast.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "City name to search for")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Maximum number of results (1-20, default 5)")
                }
            },
            required = listOf("name"),
        ),
    ) { request ->
        try {
            val name = request.arguments?.get("name")?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
                ?: return@addTool errorResult("Error: 'name' parameter is required")
            val count = request.arguments?.get("count")?.jsonPrimitive?.intOrNull ?: 5
            val locations = searchLocations(name = name, count = count)
            successResult(
                json.encodeToString(
                    SearchLocationResult(
                        status = "ok",
                        locations = locations,
                    )
                )
            )
        } catch (e: Exception) {
            errorResult("Error searching location: ${e.message}")
        }
    }
}

private fun addGetForecastTool(server: Server) {
    server.addTool(
        name = "get_forecast",
        description = "Get structured weather forecast data by coordinates. " +
            "Supports up to 35 days and returns JSON suitable for summarization tools. " +
            "For forecasts longer than 16 days, the server automatically switches to the ensemble endpoint. " +
            "If the user also asked for a summary or file export, pass this raw JSON to the next specialized tool.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("latitude") {
                    put("type", "number")
                    put("description", "Latitude of the location")
                }
                putJsonObject("longitude") {
                    put("type", "number")
                    put("description", "Longitude of the location")
                }
                putJsonObject("forecast_days") {
                    put("type", "integer")
                    put("description", "Number of forecast days (1-35, default 7)")
                }
                putJsonObject("timezone") {
                    put("type", "string")
                    put("description", "Timezone for the response. Use auto or a concrete timezone id.")
                }
                putJsonObject("location_name") {
                    put("type", "string")
                    put("description", "Optional location name to include in the response payload")
                }
                putJsonObject("country") {
                    put("type", "string")
                    put("description", "Optional country name to include in the response payload")
                }
                putJsonObject("admin1") {
                    put("type", "string")
                    put("description", "Optional admin region to include in the response payload")
                }
            },
            required = listOf("latitude", "longitude"),
        ),
    ) { request ->
        try {
            val latitude = request.arguments?.get("latitude")?.jsonPrimitive?.doubleOrNull
                ?: return@addTool errorResult("Error: 'latitude' parameter is required")
            val longitude = request.arguments?.get("longitude")?.jsonPrimitive?.doubleOrNull
                ?: return@addTool errorResult("Error: 'longitude' parameter is required")
            val forecastDays = request.arguments?.get("forecast_days")?.jsonPrimitive?.intOrNull ?: 7
            val timezone = request.arguments?.get("timezone")?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
            val location = ResolvedLocation(
                name = request.arguments?.get("location_name")?.jsonPrimitive?.contentOrNull,
                country = request.arguments?.get("country")?.jsonPrimitive?.contentOrNull,
                admin1 = request.arguments?.get("admin1")?.jsonPrimitive?.contentOrNull,
                latitude = latitude,
                longitude = longitude,
                timezone = timezone,
            )
            val payload = fetchForecastPayload(
                location = location,
                forecastDays = forecastDays,
                requestedTimezone = timezone,
            )
            successResult(json.encodeToString(payload))
        } catch (e: Exception) {
            errorResult("Error fetching forecast: ${e.message}")
        }
    }
}

private fun addGetCityForecastTool(server: Server) {
    server.addTool(
        name = "get_city_forecast",
        description = "Get structured weather forecast data directly by city name. " +
            "Use this for requests like 'weather in Paris for a month'. " +
            "Returns JSON suitable for summarization and file-saving workflows. " +
            "If the user also asked for a summary or file export, pass this raw JSON to the next specialized tool.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city_name") {
                    put("type", "string")
                    put("description", "City name, for example Paris")
                }
                putJsonObject("forecast_days") {
                    put("type", "integer")
                    put("description", "Number of forecast days (1-35, default 7)")
                }
                putJsonObject("timezone") {
                    put("type", "string")
                    put("description", "Optional timezone override. If omitted, the city's timezone is used when available.")
                }
            },
            required = listOf("city_name"),
        ),
    ) { request ->
        try {
            val cityName = request.arguments?.get("city_name")?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
                ?: return@addTool errorResult("Error: 'city_name' parameter is required")
            val forecastDays = request.arguments?.get("forecast_days")?.jsonPrimitive?.intOrNull ?: 7
            val timezone = request.arguments?.get("timezone")?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
            val location = searchLocations(name = cityName, count = 1).firstOrNull()
                ?: return@addTool errorResult("No locations found for '$cityName'.")
            val payload = fetchForecastPayload(
                location = location,
                forecastDays = forecastDays,
                requestedTimezone = timezone,
            )
            successResult(json.encodeToString(payload))
        } catch (e: Exception) {
            errorResult("Error fetching city forecast: ${e.message}")
        }
    }
}

private suspend fun searchLocations(
    name: String,
    count: Int
): List<ResolvedLocation> {
    val normalizedCount = count.coerceIn(1, 20)
    val response = httpClient.get(GEOCODING_URL) {
        parameter("name", name)
        parameter("count", normalizedCount)
        parameter("language", "en")
        parameter("format", "json")
    }
    val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
    val results = body["results"]?.jsonArray.orEmpty()
    return results.mapNotNull { item ->
        val obj = item.jsonObject
        val latitude = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val longitude = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        ResolvedLocation(
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
            country = obj["country"]?.jsonPrimitive?.contentOrNull,
            admin1 = obj["admin1"]?.jsonPrimitive?.contentOrNull,
            latitude = latitude,
            longitude = longitude,
            timezone = obj["timezone"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

private suspend fun fetchForecastPayload(
    location: ResolvedLocation,
    forecastDays: Int,
    requestedTimezone: String?
): WeatherForecastPayload {
    val normalizedDays = forecastDays.coerceIn(1, MAX_FORECAST_DAYS)
    val timezone = requestedTimezone ?: location.timezone ?: "auto"
    val useEnsembleEndpoint = normalizedDays > STANDARD_FORECAST_MAX_DAYS
    val endpoint = if (useEnsembleEndpoint) ENSEMBLE_URL else FORECAST_URL

    val response = httpClient.get(endpoint) {
        parameter("latitude", location.latitude)
        parameter("longitude", location.longitude)
        parameter(
            "daily",
            "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code"
        )
        parameter("forecast_days", normalizedDays)
        parameter("timezone", timezone)
        if (!useEnsembleEndpoint) {
            parameter(
                "current",
                "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
            )
        }
    }

    val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
    val daily = buildDailyForecast(body)
    val current = if (useEnsembleEndpoint) {
        null
    } else {
        buildCurrentWeather(body)
    }

    return WeatherForecastPayload(
        status = "ok",
        sourceEndpoint = if (useEnsembleEndpoint) "ensemble" else "forecast",
        forecastDays = normalizedDays,
        timezone = body["timezone"]?.jsonPrimitive?.contentOrNull ?: timezone,
        location = WeatherLocationPayload(
            name = location.name,
            country = location.country,
            admin1 = location.admin1,
            latitude = location.latitude,
            longitude = location.longitude,
            timezone = location.timezone ?: body["timezone"]?.jsonPrimitive?.contentOrNull,
        ),
        current = current,
        daily = daily,
    )
}

private fun buildCurrentWeather(body: kotlinx.serialization.json.JsonObject): CurrentWeatherPayload? {
    val current = body["current"]?.jsonObject ?: return null
    return CurrentWeatherPayload(
        time = current["time"]?.jsonPrimitive?.contentOrNull,
        temperature = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull,
        relativeHumidity = current["relative_humidity_2m"]?.jsonPrimitive?.intOrNull,
        windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull,
        weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull,
        weatherDescription = current["weather_code"]?.jsonPrimitive?.intOrNull
            ?.let(::weatherCodeToDescription),
    )
}

private fun buildDailyForecast(body: kotlinx.serialization.json.JsonObject): List<DailyForecastPayload> {
    val daily = body["daily"]?.jsonObject
        ?: throw IllegalStateException("Forecast response does not contain 'daily' block")
    val dates = daily["time"]?.jsonArray.orEmpty()
    val maxTemps = daily["temperature_2m_max"]?.jsonArray.orEmpty()
    val minTemps = daily["temperature_2m_min"]?.jsonArray.orEmpty()
    val precipitation = daily["precipitation_sum"]?.jsonArray.orEmpty()
    val weatherCodes = daily["weather_code"]?.jsonArray.orEmpty()

    return dates.indices.mapNotNull { index ->
        val date = dates.getOrNull(index)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val weatherCode = weatherCodes.getOrNull(index)?.jsonPrimitive?.intOrNull ?: 0
        DailyForecastPayload(
            date = date,
            weatherCode = weatherCode,
            weatherDescription = weatherCodeToDescription(weatherCode),
            temperatureMin = minTemps.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: 0.0,
            temperatureMax = maxTemps.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: 0.0,
            precipitationSum = precipitation.getOrNull(index)?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
}

private fun weatherCodeToDescription(code: Int): String = when (code) {
    0 -> "Clear sky"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45 -> "Fog"
    48 -> "Depositing rime fog"
    51 -> "Light drizzle"
    53 -> "Moderate drizzle"
    55 -> "Dense drizzle"
    56 -> "Light freezing drizzle"
    57 -> "Dense freezing drizzle"
    61 -> "Slight rain"
    63 -> "Moderate rain"
    65 -> "Heavy rain"
    66 -> "Light freezing rain"
    67 -> "Heavy freezing rain"
    71 -> "Slight snowfall"
    73 -> "Moderate snowfall"
    75 -> "Heavy snowfall"
    77 -> "Snow grains"
    80 -> "Slight rain showers"
    81 -> "Moderate rain showers"
    82 -> "Violent rain showers"
    85 -> "Slight snow showers"
    86 -> "Heavy snow showers"
    95 -> "Thunderstorm"
    96 -> "Thunderstorm with slight hail"
    99 -> "Thunderstorm with heavy hail"
    else -> "Unknown ($code)"
}

private fun successResult(payload: String): CallToolResult {
    return CallToolResult(content = listOf(TextContent(payload)))
}

private fun errorResult(message: String): CallToolResult {
    return CallToolResult(
        content = listOf(TextContent(message)),
        isError = true,
    )
}

@Serializable
private data class SearchLocationResult(
    val status: String,
    val locations: List<ResolvedLocation>,
)

@Serializable
private data class ResolvedLocation(
    val name: String? = null,
    val country: String? = null,
    val admin1: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
)

@Serializable
private data class WeatherForecastPayload(
    val status: String,
    val sourceEndpoint: String,
    val forecastDays: Int,
    val timezone: String,
    val location: WeatherLocationPayload,
    val current: CurrentWeatherPayload? = null,
    val daily: List<DailyForecastPayload>,
)

@Serializable
private data class WeatherLocationPayload(
    val name: String? = null,
    val country: String? = null,
    val admin1: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
)

@Serializable
private data class CurrentWeatherPayload(
    val time: String? = null,
    val temperature: Double? = null,
    val relativeHumidity: Int? = null,
    val windSpeed: Double? = null,
    val weatherCode: Int? = null,
    val weatherDescription: String? = null,
)

@Serializable
private data class DailyForecastPayload(
    val date: String,
    val weatherCode: Int,
    val weatherDescription: String,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val precipitationSum: Double,
)

private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
private const val ENSEMBLE_URL = "https://api.open-meteo.com/v1/ensemble"
private const val STANDARD_FORECAST_MAX_DAYS = 16
private const val MAX_FORECAST_DAYS = 35
