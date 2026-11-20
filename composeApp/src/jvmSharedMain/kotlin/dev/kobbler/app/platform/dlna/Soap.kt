package dev.kobbler.app.platform.dlna

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

internal class UpnpFault(action: String, status: Int, val code: String?) :
    Exception("$action failed: HTTP $status${code?.let { " (UPnP $it)" }.orEmpty()}")

internal fun soapEnvelope(serviceType: String, action: String, args: List<Pair<String, String>>): String =
    """<?xml version="1.0" encoding="utf-8"?>""" +
        """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
        """<s:Body><u:$action xmlns:u="$serviceType">""" +
        args.joinToString("") { (k, v) -> "<$k>${v.xmlEscaped()}</$k>" } +
        "</u:$action></s:Body></s:Envelope>"

/** The out-arguments of an action's response, by name. */
internal fun parseSoapResponse(xml: String, action: String): Map<String, String> {
    val root = parseXml(xml) ?: return emptyMap()
    val response = root.descendants("${action}Response").firstOrNull() ?: return emptyMap()
    return response.children().associate { it.name to it.textContent.orEmpty() }
}

private fun faultCode(xml: String): String? = parseXml(xml)?.descendants("errorCode")?.firstOrNull()?.textContent?.trim()

internal suspend fun HttpClient.soap(
    endpoint: ServiceEndpoint,
    action: String,
    args: List<Pair<String, String>> = emptyList(),
): Map<String, String> {
    val response = post(endpoint.controlUrl) {
        header("SOAPACTION", "\"${endpoint.type}#$action\"")
        contentType(ContentType.parse("text/xml; charset=\"utf-8\""))
        setBody(soapEnvelope(endpoint.type, action, args))
    }
    val body = response.bodyAsText()
    if (!response.status.isSuccess()) throw UpnpFault(action, response.status.value, faultCode(body))
    return parseSoapResponse(body, action)
}
