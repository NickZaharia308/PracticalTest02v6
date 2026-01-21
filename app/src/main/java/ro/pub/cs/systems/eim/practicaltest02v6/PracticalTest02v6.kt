package ro.pub.cs.systems.eim.practicaltest02v6

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

class PracticalTest02v6MainActivity : AppCompatActivity() {
    private var serverThread: ServerThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.`activity_practical_test02v6_main`)

        // 1. Pornire Server
        val connectButton = findViewById<Button>(R.id.connect_button)
        val serverPortEditText = findViewById<EditText>(R.id.server_port_edit_text)

        connectButton.setOnClickListener {
            val port = serverPortEditText.text.toString()
            if (port.isEmpty()) {
                Toast.makeText(this, "Portul trebuie completat!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            serverThread = ServerThread(port.toInt())
            serverThread?.start()
            Toast.makeText(this, "Server started on port $port", Toast.LENGTH_SHORT).show()
        }

        // 2. Cerere Client
        val getCurrencyButton = findViewById<Button>(R.id.get_currency_button)
        val clientAddressEditText = findViewById<EditText>(R.id.client_address_edit_text)
        val clientPortEditText = findViewById<EditText>(R.id.client_port_edit_text)
        val currencyEditText = findViewById<EditText>(R.id.currency_text)
        val resultTextView = findViewById<TextView>(R.id.info_text_view)

        getCurrencyButton.setOnClickListener {
            val address = clientAddressEditText.text.toString()
            val port = clientPortEditText.text.toString()
            val currency = currencyEditText.text.toString()

            if (address.isEmpty() || port.isEmpty() || currency.isEmpty()) {
                Toast.makeText(this, "Toate câmpurile clientului sunt obligatorii!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Resetare text și pornire client
            resultTextView.text = ""
            val clientThread = ClientThread(address, port.toInt(), currency, resultTextView)
            clientThread.start()
        }
    }
}

data class CurrencyModel(
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)
class ServerThread(private val port: Int) : Thread() {
    private var serverSocket: ServerSocket? = null
    // 3a: Gestiunea obiectului local (Cache)
    private val data = mutableMapOf<String, CurrencyModel>()

    @Synchronized
    fun setData(currency: String, currencyModel: CurrencyModel) {
        this.data[currency] = currencyModel
    }

    @Synchronized
    fun getData(currency: String): CurrencyModel? = data[currency]

    override fun run() {
        try {
            serverSocket = ServerSocket(port)
            Log.i("ServerThread", "Server started successfully on port $port") // Adaugă acest log
            while (!isInterrupted) {
                val socket = serverSocket?.accept()
                Log.i("ServerThread", "New client connected!")
                CommunicationThread(this, socket!!).start()
            }
        } catch (e: Exception) {
            Log.e("ServerThread", "CRITICAL ERROR: Could not start server: ${e.message}")
            e.printStackTrace()
        }

    }

    fun stopServer() {
        interrupt()
        serverSocket?.close()
    }
}

class CommunicationThread(
    private val serverThread: ro.pub.cs.systems.eim.practicaltest02v6.ServerThread,
    private val socket: Socket
) : Thread() {

    override fun run() {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = java.io.PrintWriter(s.getOutputStream(), true)

            val currency = reader.readLine()

            if (currency == null) return

            // 3a: Verificare cache local
            var currencyInfo = serverThread.getData(currency)

            if (currencyInfo == null) {
                val currentTime = System.currentTimeMillis()
                val timestamp = currencyInfo?.timestamp ?: currentTime

                val timeDiffMillis = currentTime - timestamp
                val timeDiffSeconds = timeDiffMillis / 1000

                currencyInfo = fetchCurrencyfromApi(currency)

                if (timeDiffSeconds > 10) {
                    Log.i("ServerThread", "Datele sunt prea vechi. Se face refresh de la API.")
                    currencyInfo = null
                }

            }

            if (currencyInfo != null) {
                serverThread.setData(currency, currencyInfo)
            }

            // 3d: Transmiterea răspunsului către client
            val response = when (currency) {
                "USD" -> currencyInfo?.toString()
                "EUR" -> currencyInfo?.toString()
                else -> "Informație necunoscută!"
            }

            writer.println(response ?: "Eroare: Orașul nu a fost găsit.")
        }
    }

    private fun fetchCurrencyfromApi(currency: String): CurrencyModel? {
        val urlString = "https://data-api.coindesk.com/index/cc/v1/latest/tick?market=cadli&instruments=BTC-$currency"
        val dynamicKey = "BTC-$currency"
        return try {
            val response = URL(urlString).readText()
            val jsonResponse = JSONObject(response)

            val dataObject = jsonResponse.getJSONObject("Data")

            val currencyObject = dataObject.getJSONObject(dynamicKey)

            val valueString = currencyObject.getString("VALUE")

            CurrencyModel(
                value = valueString
            )
        } catch (e: Exception) {

            Log.e("CommThread", "Eroare API la preluarea $dynamicKey: ${e.message}")
            null
        }


    }
}


class ClientThread(
    private val address: String,
    private val port: Int,
    private val currency: String,
    private val currencyForecastTextView: TextView
) : Thread() {

    override fun run() {
        try {
            // 4a: Accesarea funcționalității oferite de server prin Socket
            val socket = Socket(address, port)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Trimiterea datelor către server (fiecare pe o linie nouă conform cerinței)
            writer.println(currency)

            // Citirea răspunsului (poate fi pe mai multe linii)
            val responseBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                responseBuilder.append(line).append("\n")
            }

            // Actualizarea interfeței grafice pe firul de execuție principal (UI Thread)
            val finalizedResult = responseBuilder.toString()
            currencyForecastTextView.post {
                currencyForecastTextView.text = finalizedResult
            }

            socket.close()

        } catch (e: Exception) {
            Log.e("ClientThread", "Eroare la conectarea cu serverul: ${e.message}")
            currencyForecastTextView.post {
                currencyForecastTextView.text = "Error connecting to server!"
            }
        }
    }
}

