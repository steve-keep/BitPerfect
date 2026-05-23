package com.bitperfect.app.output

import java.net.URL

@android.annotation.SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
@SuppressWarnings("kotlin:S4830")
internal fun openTrustAllConnection(url: String): java.net.HttpURLConnection {
    val conn = URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 2_000
    conn.readTimeout = 2_000
    conn.instanceFollowRedirects = true

    // Accept self-signed certs for HTTPS endpoints
    if (conn is javax.net.ssl.HttpsURLConnection) {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(
            @android.annotation.SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {} // lgtm[java/insecure-trustmanager] lgtm[kt/insecure-trustmanager]
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {} // lgtm[java/insecure-trustmanager] lgtm[kt/insecure-trustmanager]
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf() // lgtm[java/insecure-trustmanager] lgtm[kt/insecure-trustmanager]
            }
        )
        val sc = javax.net.ssl.SSLContext.getInstance("TLS")
        sc.init(null, trustAll, java.security.SecureRandom()) // lgtm[java/insecure-trustmanager] lgtm[kt/insecure-trustmanager]
        conn.sslSocketFactory = sc.socketFactory // lgtm[java/insecure-trustmanager] lgtm[kt/insecure-trustmanager]
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }

    return conn
}
