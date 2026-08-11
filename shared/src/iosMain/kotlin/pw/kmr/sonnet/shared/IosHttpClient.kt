package pw.kmr.sonnet.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

fun createDarwinEngine(): HttpClientEngine = Darwin.create()
