package gov.cdc.dex.mrr

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import gov.cdc.dex.azure.RedisProxy
import gov.cdc.dex.util.StringUtils.Companion.normalize
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class MmgatClient {
    private val GUIDANCE_STATUS_UAT = "useracceptancetesting"
    private val GUIDANCE_STATUS_FINAL = "final"
    private val MMG_AT_ROOT_URL = "https://mmgat.services.cdc.gov/api/guide/"
    private val MMG_NAMESPACE = "mmgv2:"

    private fun trustAllHosts() {
        try {
            /* Start of certificates fix */
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })

            val sc = SSLContext.getInstance("TLSv1.2")
            sc.init(null, trustAllCerts , SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            // Create all-trusting host name verifier
            val allHostsValid = HostnameVerifier { _, _ -> true }

            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid)
            /* End of certificates fix*/
        } catch (e: Exception) {
            throw Exception("Error in trustAllHosts method: ${e.printStackTrace()}")
        }
    }

    private fun getGuideAll(): String {
        try {
            trustAllHosts()
            val url = URL("${MMG_AT_ROOT_URL}all?type=0")
            return getContent(url)
        } catch (e: Exception) {
            println("exception:${e.printStackTrace()}")
            throw Exception("Error in getGuideAll method: ${e.message}")

        }
    }

    private fun getGuideById(id: String): String {
        try {
            val url = URL("${MMG_AT_ROOT_URL}$id?includeGenV2=false")
            return getContent(url)
        } catch (e: Exception) {
            throw Exception("Error in getGuideById method: ${e.message}")
        }
    }

    private fun getContent(url: URL): String {
        val sb = StringBuilder()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        var br : BufferedReader? = null
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("Failed : HTTP error code : " + conn.responseCode)
            }
            br = BufferedReader(InputStreamReader((conn.inputStream)))
            var line: String?
            while ((br.readLine().also { line = it }) != null) {
                sb.append(line)
            }
        }catch(e:Exception){
            throw Exception("Error reading MMGAT input stream: ${e.message}")
        }
        finally{
            br?.close()
        }
        return sb.toString()
    }

    fun loadLegacyMmgat(redisProxy: RedisProxy) {
        val legacyMMGFolder = Thread.currentThread().contextClassLoader.getResource("legacy_mmgs")
            ?: throw Exception("Directory legacy_mmgs not found.")
        val dir = File(legacyMMGFolder.file)
        dir.walk().filter{ it.isFile && it.extension.lowercase() == "json"}.forEach {
            val content = it.readText()
            val fileOutputJson = JsonParser.parseString(content)
            val filename = "$MMG_NAMESPACE${it.name.substring(0, it.name.lastIndexOf(".")).normalize()}"
            val jedis = redisProxy.getJedisClient()
            jedis.set(filename, fileOutputJson.toString())
        }
    }


    fun loadMMGAT(redisProxy: RedisProxy) {
        val mmgaGuide = this.getGuideAll()
        val elem: JsonElement = JsonParser.parseString(mmgaGuide)
        val mmgatJArray = elem.asJsonObject.getAsJsonArray("result")
        println("Json Array size:" + mmgatJArray.size())
        val gson = GsonBuilder().create()
        for (mmgatjson in mmgatJArray) {
            val mj = mmgatjson.asJsonObject
            //if (mj.get("guideStatus").asString.toLowerCase() in listOf(mmgaClient.GUIDANCE_STATUS_UAT, mmgaClient.GUIDANCE_STATUS_FINAL) )
            if (mj.get("guideStatus").asString
                    .equals(this.GUIDANCE_STATUS_UAT,true) || mj.get("guideStatus")
                    .asString.equals(this.GUIDANCE_STATUS_FINAL,true)
            ) {
                val id = (mj.get("id").asString)
                // context.logger.info("MMGAT id:$id")
                val mGuide = this.getGuideById(id)
                val melement = JsonParser.parseString(mGuide)
                val mresult = melement.asJsonObject.get("result")

                mresult.asJsonObject.remove("testScenarios")
                mresult.asJsonObject.remove("testCaseScenarioWorksheetColumns")
                mresult.asJsonObject.remove("columns")
                mresult.asJsonObject.remove("templates")
                mresult.asJsonObject.remove("valueSets")

                val key = "$MMG_NAMESPACE${mj.get("name").asString.normalize()}"
                println("MMGAT name: $key")
                if (redisProxy.getJedisClient().exists(key))
                    redisProxy.getJedisClient().del(key)

                    val jedis = redisProxy.getJedisClient()
                    jedis.set(key, gson.toJson(mresult))
                    println("...Done!")

            }
        }
    }


}