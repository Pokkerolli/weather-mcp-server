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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

val httpClient = HttpClient(ClientCIO)

val json = Json { ignoreUnknownKeys = true }

fun weatherCodeToDescription(code: Int): String = when (code) {
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

fun createServer(): Server {
    val server = Server(
        Implementation(
            name = "weather-mcp-server",
            version = "1.0.0",
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    // Tool: search_location
    server.addTool(
        name = "search_location",
        description = "Search for a city by name and get its coordinates (latitude, longitude). " +
            "Use this to find coordinates before requesting a weather forecast.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "City name to search for")
                }
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Maximum number of results (1-100, default 5)")
                }
            },
            required = listOf("name"),
        ),
    ) { request ->
        try {
            val name = request.arguments?.get("name")?.jsonPrimitive?.content
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Error: 'name' parameter is required")),
                    isError = true,
                )
            val count = request.arguments?.get("count")?.jsonPrimitive?.int ?: 5

            val response = httpClient.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", name)
                parameter("count", count)
                parameter("language", "en")
                parameter("format", "json")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val results = body["results"]?.jsonArray

            if (results == null || results.isEmpty()) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("No locations found for '$name'.")),
                )
            }

            val sb = StringBuilder("Found ${results.size} location(s) for '$name':\n\n")
            for ((i, item) in results.withIndex()) {
                val obj = item.jsonObject
                val cityName = obj["name"]?.jsonPrimitive?.content ?: "Unknown"
                val country = obj["country"]?.jsonPrimitive?.content ?: ""
                val admin1 = obj["admin1"]?.jsonPrimitive?.content
                val lat = obj["latitude"]?.jsonPrimitive?.double
                val lon = obj["longitude"]?.jsonPrimitive?.double
                val elevation = obj["elevation"]?.jsonPrimitive?.double
                val population = obj["population"]?.jsonPrimitive?.int
                val timezone = obj["timezone"]?.jsonPrimitive?.content

                sb.append("${i + 1}. $cityName")
                if (admin1 != null) sb.append(", $admin1")
                sb.append(", $country\n")
                sb.append("   Coordinates: $lat, $lon\n")
                if (elevation != null) sb.append("   Elevation: ${elevation}m\n")
                if (population != null) sb.append("   Population: $population\n")
                if (timezone != null) sb.append("   Timezone: $timezone\n")
                sb.append("\n")
            }

            CallToolResult(content = listOf(TextContent(sb.toString().trimEnd())))
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error searching location: ${e.message}")),
                isError = true,
            )
        }
    }

    // Tool: get_forecast
    server.addTool(
        name = "get_forecast",
        description = "Get current weather and daily forecast for a location by its coordinates. " +
            "Use search_location first to find coordinates by city name.",
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
                    put("description", "Number of forecast days (1-16, default 7)")
                }
            },
            required = listOf("latitude", "longitude"),
        ),
    ) { request ->
        try {
            val latitude = request.arguments?.get("latitude")?.jsonPrimitive?.double
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Error: 'latitude' parameter is required")),
                    isError = true,
                )
            val longitude = request.arguments?.get("longitude")?.jsonPrimitive?.double
                ?: return@addTool CallToolResult(
                    content = listOf(TextContent("Error: 'longitude' parameter is required")),
                    isError = true,
                )
            val forecastDays = request.arguments?.get("forecast_days")?.jsonPrimitive?.int ?: 7

            val response = httpClient.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("current", "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code")
                parameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code")
                parameter("forecast_days", forecastDays)
                parameter("timezone", "auto")
            }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val sb = StringBuilder()

            // Current weather
            val current = body["current"]?.jsonObject
            val currentUnits = body["current_units"]?.jsonObject
            if (current != null) {
                val temp = current["temperature_2m"]?.jsonPrimitive?.double
                val tempUnit = currentUnits?.get("temperature_2m")?.jsonPrimitive?.content ?: "°C"
                val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.int
                val humidityUnit = currentUnits?.get("relative_humidity_2m")?.jsonPrimitive?.content ?: "%"
                val windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.double
                val windUnit = currentUnits?.get("wind_speed_10m")?.jsonPrimitive?.content ?: "km/h"
                val weatherCode = current["weather_code"]?.jsonPrimitive?.int ?: 0
                val time = current["time"]?.jsonPrimitive?.content

                sb.append("=== Current Weather ===\n")
                if (time != null) sb.append("Time: $time\n")
                sb.append("Condition: ${weatherCodeToDescription(weatherCode)}\n")
                sb.append("Temperature: $temp $tempUnit\n")
                sb.append("Humidity: $humidity $humidityUnit\n")
                sb.append("Wind Speed: $windSpeed $windUnit\n")
                sb.append("\n")
            }

            // Daily forecast
            val daily = body["daily"]?.jsonObject
            val dailyUnits = body["daily_units"]?.jsonObject
            if (daily != null) {
                val times = daily["time"]?.jsonArray
                val maxTemps = daily["temperature_2m_max"]?.jsonArray
                val minTemps = daily["temperature_2m_min"]?.jsonArray
                val precipitations = daily["precipitation_sum"]?.jsonArray
                val weatherCodes = daily["weather_code"]?.jsonArray
                val tempUnit = dailyUnits?.get("temperature_2m_max")?.jsonPrimitive?.content ?: "°C"
                val precipUnit = dailyUnits?.get("precipitation_sum")?.jsonPrimitive?.content ?: "mm"

                if (times != null) {
                    sb.append("=== ${times.size}-Day Forecast ===\n")
                    for (i in times.indices) {
                        val date = times[i].jsonPrimitive.content
                        val maxTemp = maxTemps?.get(i)?.jsonPrimitive?.double
                        val minTemp = minTemps?.get(i)?.jsonPrimitive?.double
                        val precip = precipitations?.get(i)?.jsonPrimitive?.double
                        val code = weatherCodes?.get(i)?.jsonPrimitive?.int ?: 0

                        sb.append("\n$date — ${weatherCodeToDescription(code)}\n")
                        sb.append("  Temperature: $minTemp / $maxTemp $tempUnit\n")
                        sb.append("  Precipitation: $precip $precipUnit\n")
                    }
                }
            }

            CallToolResult(content = listOf(TextContent(sb.toString().trimEnd())))
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error fetching forecast: ${e.message}")),
                isError = true,
            )
        }
    }

    return server
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
