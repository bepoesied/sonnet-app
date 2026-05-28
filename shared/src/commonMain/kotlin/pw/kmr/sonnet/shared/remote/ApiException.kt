package pw.kmr.sonnet.shared.remote

class ApiException(
    val statusCode: Int,
    responseBody: String
) : Exception(responseBody)