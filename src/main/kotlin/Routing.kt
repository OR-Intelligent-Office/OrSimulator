package com.agh

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Test(
    val id: Int,
    val name: String,
)

// Singleton symulatora środowiska
object SimulatorManager {
    private var simulator: EnvironmentSimulator? = null
    private var updateJob: Job? = null

    fun initialize(
        application: Application,
        startBackgroundUpdates: Boolean = true,
    ) {
        if (simulator == null) {
            simulator =
                EnvironmentSimulator(
                    rooms = defaultRooms(),
                    timeSpeedMultiplier = 1.0,
                    failureProbability = 0.01,
                )

            // Uruchomienie aktualizacji symulatora w tle (tylko jeśli nie jesteśmy w teście)
            if (startBackgroundUpdates) {
                updateJob =
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            while (isActive) {
                                simulator?.update(deltaMinutes = 1.0)
                                delay(1.seconds)
                            }
                        } catch (e: Exception) {
                            // Ignoruj błędy w tle
                        }
                    }
            }
        }
    }

    fun getSimulator(): EnvironmentSimulator? = simulator

    fun stop() {
        updateJob?.cancel()
        updateJob = null
    }
}

fun Application.configureRouting() {
    install(Resources)

    SimulatorManager.initialize(this)

    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        
        route("/api/environment") {
            get("/state") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                call.respond(simulator.getCurrentState())
            }

            get("/events") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                call.respond(simulator.getEvents())
            }

            get("/rooms") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                call.respond(simulator.getCurrentState().rooms)
            }

            get("/rooms/{roomId}") {
                val roomId = call.parameters["roomId"]
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val room = simulator.getCurrentState().rooms.find { it.id == roomId }
                if (room != null) {
                    call.respond(room)
                } else {
                    call.respond(mapOf("error" to "Room not found"))
                }
            }

            get("/temperature") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val state = simulator.getCurrentState()
                call.respond(
                    mapOf(
                        "externalTemperature" to state.externalTemperature,
                        "rooms" to
                            state.rooms.map { room ->
                                mapOf(
                                    "roomId" to room.id,
                                    "roomName" to room.name,
                                    "temperature" to room.temperatureSensor.temperature,
                                )
                            },
                    ),
                )
            }

            get("/motion") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val state = simulator.getCurrentState()
                call.respond(
                    state.rooms.map { room ->
                        mapOf(
                            "roomId" to room.id,
                            "roomName" to room.name,
                            "motionDetected" to room.motionSensor.motionDetected,
                            "peopleCount" to room.peopleCount,
                            "lastMotionTime" to room.motionSensor.lastMotionTime,
                        )
                    },
                )
            }

            get("/devices") {
                val simulator =
                    SimulatorManager.getSimulator()
                        ?: return@get call.respond(mapOf("error" to "Simulator not initialized"))
                val state = simulator.getCurrentState()
                val allDevices = mutableListOf<Map<String, Any>>()

                state.rooms.forEach { room ->
                    room.lights.forEach { light ->
                        allDevices.add(
                            mapOf(
                                "id" to light.id,
                                "type" to "light",
                                "roomId" to room.id,
                                "roomName" to room.name,
                                "state" to light.state.name,
                                "brightness" to light.brightness,
                            ),
                        )
                    }

                    room.printer?.let { printer ->
                        allDevices.add(
                            mapOf(
                                "id" to printer.id,
                                "type" to "printer",
                                "roomId" to room.id,
                                "roomName" to room.name,
                                "state" to printer.state.name,
                                "tonerLevel" to printer.tonerLevel,
                                "paperLevel" to printer.paperLevel,
                            ),
                        )
                    }

                    room.blinds?.let { blinds ->
                        allDevices.add(
                            mapOf(
                                "id" to blinds.id,
                                "type" to "blinds",
                                "roomId" to room.id,
                                "roomName" to room.name,
                                "state" to blinds.state.name,
                            ),
                        )
                    }
                }

                call.respond(allDevices)
            }
        }
    }
}
