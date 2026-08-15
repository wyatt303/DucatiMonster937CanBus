package pl.linuch.ducatitelemetry

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var telemetryText: TextView
    private var ble: DucatiBleClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 18f
            text = "Disconnected"
        }

        telemetryText = TextView(this).apply {
            textSize = 20f
            text = "No telemetry"
        }

        val scanButton = Button(this).apply {
            text = "Connect to Ducati"
            setOnClickListener {
                if (hasBluetoothPermission()) {
                    ble?.startScan()
                } else {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ),
                        100
                    )
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(scanButton)
            addView(telemetryText)
        }

        setContentView(layout)

        ble = DucatiBleClient(
            this,
            onTelemetry = { t ->
                runOnUiThread {
                    telemetryText.text = """
                        RPM: ${t.rpm}
                        Gear: ${t.gear}
                        Speed: %.2f km/h
                        Throttle: %.2f %%
                        Front brake: %.2f %%
                        Engine: ${t.engineTempC} °C
                        Ambient: ${t.ambientTempC} °C
                        Sequence: ${t.sequence}
                    """.trimIndent().format(
                        t.speedKmh,
                        t.throttlePercent,
                        t.frontBrakePercent
                    )
                }
            },
            onConnectionChanged = { connected ->
                runOnUiThread {
                    status.text =
                        if (connected) "BLE Connected"
                        else "BLE Disconnected"
                }
            }
        )
    }

    private fun hasBluetoothPermission(): Boolean {
        return checkSelfPermission(
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        ble?.disconnect()
        super.onDestroy()
    }
}
